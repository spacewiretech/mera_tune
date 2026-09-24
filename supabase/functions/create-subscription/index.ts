import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createServiceClient, type ServiceClient } from "../_shared/supabase-client.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

async function getConfig(supabase: ServiceClient) {
  const { data, error } = await supabase.from("app_config").select("key, value");
  if (error) throw new Error(`Failed to load app_config: ${error.message}`);
  const config: Record<string, string> = {};
  for (const row of data ?? []) config[row.key] = row.value ?? "";
  return config;
}

function cashfreeBaseUrl(environment: string) {
  return environment === "production"
    ? "https://api.cashfree.com/pg"
    : "https://sandbox.cashfree.com/pg";
}

function cashfreeErrorMessage(result: Record<string, unknown>): string {
  const message = result.message ?? result.error_description ?? result.error;
  if (typeof message === "string" && message.trim()) return message.trim();
  const code = result.code ?? result.error_code;
  if (typeof code === "string" && code.trim()) return code.trim();
  return "Could not create subscription";
}

const FALLBACK_CUSTOMER_NAME = "MeraTune User";

/** Cashfree rejects Indic/Unicode names with customer_name_invalid. */
function cashfreeCustomerName(name: string | null | undefined): string {
  const trimmed = (name ?? "").trim().replace(/\s+/g, " ");
  if (/^[A-Za-z][A-Za-z .'-]{2,99}$/.test(trimmed)) {
    return trimmed.slice(0, 100);
  }
  return FALLBACK_CUSTOMER_NAME;
}

function buildPlanDetails(config: Record<string, string>, recurringAmount: number, intervalMonths: number) {
  const usePlanId = config.cashfree_use_plan_id?.trim().toLowerCase() === "true";
  const planId = config.cashfree_plan_id?.trim();
  if (usePlanId && planId) {
    return { plan_id: planId };
  }
  return {
    plan_name: "MeraTune Premium",
    plan_type: "PERIODIC",
    plan_amount: recurringAmount,
    plan_max_amount: recurringAmount,
    plan_max_cycles: 100,
    plan_intervals: intervalMonths,
    plan_currency: "INR",
    plan_interval_type: "MONTH",
    plan_note: "MeraTune ringtone subscription",
  };
}

function isAuthenticationError(result: Record<string, unknown>): boolean {
  const type = String(result.type ?? "").toLowerCase();
  const message = String(result.message ?? "").toLowerCase();
  return type === "authentication_error" || message.includes("authentication failed");
}

async function createCashfreeSubscription(
  environment: string,
  clientId: string,
  clientSecret: string,
  apiVersion: string,
  body: Record<string, unknown>,
) {
  const response = await fetch(`${cashfreeBaseUrl(environment)}/subscriptions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-client-id": clientId,
      "x-client-secret": clientSecret,
      "x-api-version": apiVersion,
    },
    body: JSON.stringify(body),
  });
  const result = await response.json().catch(() => ({})) as Record<string, unknown>;
  return { response, result };
}

/** Next calendar day at 10:00 IST. Edge runs in UTC; must not use local getDate(). */
function tomorrowMorningIst(): string {
  const istToday = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date()); // YYYY-MM-DD in IST
  const [y, m, d] = istToday.split("-").map(Number);
  // Noon UTC on the IST calendar day avoids DST edge cases (IST has none).
  const tomorrow = new Date(Date.UTC(y, m - 1, d + 1, 12));
  const ty = tomorrow.getUTCFullYear();
  const tm = String(tomorrow.getUTCMonth() + 1).padStart(2, "0");
  const td = String(tomorrow.getUTCDate()).padStart(2, "0");
  return `${ty}-${tm}-${td}T10:00:00+05:30`;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const { user_id: userId } = await req.json();
    if (!userId) return jsonResponse({ error: "user_id is required" }, 400);

    const supabase = createServiceClient();

    const config = await getConfig(supabase);
    const clientId = config.cashfree_client_id?.trim();
    const clientSecret = config.cashfree_client_secret?.trim();
    if (!clientId || !clientSecret) {
      return jsonResponse({ error: "Payment gateway is not configured" }, 503);
    }

    const { data: user, error: userError } = await supabase
      .from("users")
      .select("id, phone, name, status")
      .eq("id", userId)
      .single();

    if (userError || !user) return jsonResponse({ error: "User not found" }, 404);

    const { data: existingSub } = await supabase
      .from("subscriptions")
      .select("id, status, cashfree_subscription_id")
      .eq("user_id", userId)
      .in("status", ["pending", "active"])
      .maybeSingle();

    if (existingSub?.status === "active") {
      return jsonResponse({ error: "Subscription already active" }, 409);
    }

    const authAmount = parseFloat(config.subscription_auth_amount || "3");
    const recurringAmount = parseFloat(config.subscription_recurring_amount || "299");
    const intervalMonths = parseInt(config.subscription_interval_months || "1", 10);
    const apiVersion = config.cashfree_api_version || "2025-01-01";
    const environment = config.cashfree_environment || "sandbox";
    const subscriptionId = `mt_${user.id}_${Date.now()}`;

    const cashfreeBody = {
      subscription_id: subscriptionId,
      customer_details: {
        customer_name: cashfreeCustomerName(user.name),
        customer_email: `${user.phone}@meratune.app`,
        customer_phone: user.phone,
      },
      plan_details: buildPlanDetails(config, recurringAmount, intervalMonths),
      authorization_details: {
        authorization_amount: authAmount,
        authorization_amount_refund: false,
        payment_methods: ["upi", "card", "enach"],
      },
      subscription_meta: {
        return_url: config.cashfree_return_url || "https://meratune.app/subscription/return",
      },
      subscription_first_charge_time: tomorrowMorningIst(),
      subscription_expiry_time: "2100-01-01T23:59:59+05:30",
      subscription_tags: {
        user_id: String(user.id),
        phone: user.phone,
      },
    };

    let { response: cfResponse, result: cfResult } = await createCashfreeSubscription(
      environment,
      clientId,
      clientSecret,
      apiVersion,
      cashfreeBody,
    );

    const usePlanId = config.cashfree_use_plan_id?.trim().toLowerCase() === "true";
    if (!cfResponse.ok && usePlanId && !isAuthenticationError(cfResult)) {
      console.warn("Cashfree plan_id subscription failed, retrying inline plan:", cfResult);
      const inlineBody = {
        ...cashfreeBody,
        plan_details: buildPlanDetails({ ...config, cashfree_use_plan_id: "false" }, recurringAmount, intervalMonths),
      };
      ({ response: cfResponse, result: cfResult } = await createCashfreeSubscription(
        environment,
        clientId,
        clientSecret,
        apiVersion,
        inlineBody,
      ));
    }

    if (!cfResponse.ok) {
      console.error("Cashfree create subscription error:", cfResponse.status, cfResult);
      if (isAuthenticationError(cfResult)) {
        return jsonResponse({
          error: "Cashfree authentication failed. Verify App ID and Secret Key match the same environment (production/sandbox) in app_config.",
        }, 502);
      }
      return jsonResponse({ error: cashfreeErrorMessage(cfResult) }, 502);
    }

    const sessionId = cfResult.subscription_session_id as string;
    const cfSubId = cfResult.cf_subscription_id as string;
    if (!sessionId) {
      return jsonResponse({ error: "Invalid response from payment gateway" }, 502);
    }

    if (existingSub) {
      await supabase
        .from("subscriptions")
        .update({
          plan_type: "trial",
          status: "pending",
          amount: authAmount,
          cashfree_subscription_id: cfSubId,
          autopay_enabled: true,
          // The abandoned mandate's status must not become the new one's previous_status.
          cashfree_status: null,
          cashfree_status_at: null,
          updated_at: new Date().toISOString(),
        })
        .eq("id", existingSub.id);
    } else {
      await supabase.from("subscriptions").insert({
        user_id: user.id,
        phone: user.phone,
        plan_type: "trial",
        status: "pending",
        amount: authAmount,
        autopay_enabled: true,
        cashfree_subscription_id: cfSubId,
      });
    }

    return jsonResponse({
      subscription_id: subscriptionId,
      subscription_session_id: sessionId,
      cf_subscription_id: cfSubId,
      environment,
      auth_amount: authAmount,
      recurring_amount: recurringAmount,
    });
  } catch (err) {
    console.error("create-subscription error:", err);
    return jsonResponse({ error: "Internal server error" }, 500);
  }
});
