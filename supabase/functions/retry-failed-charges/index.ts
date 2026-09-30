import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createServiceClient, type ServiceClient } from "../_shared/supabase-client.ts";
import { CashfreeClient, cashfreeAccounts, Limiter } from "./cashfree.ts";
import { runRetryJob } from "./job.ts";
import { retryConfig } from "./policy.ts";
import { supabaseRetryStore } from "./store.ts";

/**
 * Retries ₹299 autopay charges that failed for insufficient funds (see policy.ts for the rules).
 * Called hourly by pg_cron (`charge-retry-hourly`, migration 20261001120000) with the
 * `x-job-secret` header; `verify_jwt = false` in config.toml. Ships off: app_config
 * `charge_retry_mode` = off | dry_run | live. The body may only make a run safer:
 * `{ "dry_run": true, "only": ["mt_…"] }`.
 */

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

async function getConfig(supabase: ServiceClient) {
  const { data, error } = await supabase.from("app_config").select("key, value");
  if (error) throw new Error(`Failed to load app_config: ${error.message}`);
  const config: Record<string, string> = {};
  for (const row of data ?? []) config[row.key] = row.value ?? "";
  return config;
}

/** Constant-time compare of the job secret; an unset secret never matches. */
function secretMatches(given: string, expected: string): boolean {
  const a = new TextEncoder().encode(given);
  const b = new TextEncoder().encode(expected);
  if (!b.length || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return jsonResponse({ error: "POST only" }, 405);
  try {
    const supabase = createServiceClient();
    const config = await getConfig(supabase);
    if (!secretMatches(req.headers.get("x-job-secret") ?? "", config.charge_retry_job_secret ?? "")) {
      return jsonResponse({ error: "Unauthorized" }, 401);
    }

    let body: Record<string, unknown> = {};
    try {
      const parsed = await req.json();
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) body = parsed as Record<string, unknown>;
    } catch {
      // empty body: cron sends {}
    }

    const cfg = retryConfig(config, body);
    if (cfg.mode === "off") return jsonResponse({ mode: "off" });

    const accounts = cashfreeAccounts(config);
    if (!accounts.length) return jsonResponse({ error: "Payment gateway is not configured" }, 503);

    const deadlineMs = Date.now() + cfg.timeBudgetMs;
    const limiter = new Limiter(cfg.concurrency, cfg.minIntervalMs, deadlineMs);
    const lookupLimiter = new Limiter(1, cfg.lookupIntervalMs, deadlineMs);
    const cashfree = new CashfreeClient({
      environment: config.cashfree_environment || "sandbox",
      apiVersion: config.cashfree_api_version || "2025-01-01",
      accounts,
      limiter,
      lookupLimiter,
    });

    const report = await runRetryJob({ store: supabaseRetryStore(supabase), cashfree, limiter, lookupLimiter, cfg });
    console.log(JSON.stringify({
      fn: "retry-failed-charges",
      mode: report.mode,
      stopped: report.stopped,
      requests: report.requests,
      lookup_requests: report.lookup_requests,
      lookup_throttled: report.lookup_throttled,
      examined: report.checks.examined,
      planned: report.planned.chains,
      requested: report.sends.requested,
      would_send: report.sends.would_send,
      rejected: report.sends.rejected,
      resolved: report.resolved,
      mismatches: report.mismatches.length,
      errors: report.errors,
      elapsed_ms: report.elapsed_ms,
    }));
    return jsonResponse(report);
  } catch (err) {
    console.error("retry-failed-charges error:", err instanceof Error ? err.message : err);
    return jsonResponse({ error: "Retry job failed" }, 500);
  }
});
