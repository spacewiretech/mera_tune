/**
 * One run of the failed-charge retry job. Storage and Cashfree are passed in, so the flow is tested
 * with fakes (supabase/functions/tests/charge_retry_job_test.ts). In `dry_run` nothing is written
 * and nothing is POSTed: the run reads the DB and Cashfree and reports what it would do.
 *
 * 1. Open chains (a failed charge with pending / requested attempts): the outcome of each requested
 *    retry (from the webhook's records, else Cashfree once its day is over), then the next due
 *    attempt (one send per chain per run).
 * 2. New failures cashfree-webhook recorded in charge_failures: the fast path, within the hour.
 * 3. Sweep: mandates whose charge came due and was not recorded as paid, for failures the webhook
 *    did not deliver and for the backlog.
 * 2 and 3 fetch the mandate and its payments; a latest charge that failed for insufficient funds
 * gets a chain of three attempts, and its first due attempt is sent in the same run.
 */
import {
  type ResolvedVia,
  retryEventKey,
  retryFailedProps,
  retryRequestedProps,
  retrySucceededProps,
  type RetryTracker,
} from "./analytics.ts";
import type { CashfreeClient, Limiter } from "./cashfree.ts";
import {
  type AttemptRow,
  type AttemptStatus,
  type CfMandate,
  type CfPayment,
  checkRetryResponse,
  classifySendError,
  discover,
  gateAfterFetch,
  gateBeforeFetch,
  addDays,
  idempotencyKey,
  istDay,
  istMidnightMs,
  latestCharge,
  nextCheckMs,
  outcomeDue,
  type RetryConfig,
  retryOutcome,
  type RetryOutcome,
  upper,
} from "./policy.ts";

const DAY_MS = 86_400_000;
/** Failed charges loaded per run from each of the two actionable sets (to send, to resolve). */
const OPEN_CHAINS_PER_KIND = 600;
const NEW_FAILURES_PER_RUN = 500;
/** A requested retry with no outcome this long after its day is closed as `ended` / no_outcome. */
const NO_OUTCOME_AFTER_MS = 7 * DAY_MS;

export type Candidate = {
  subscription_row_id: number;
  user_id: number;
  cf_subscription_id: string;
  merchant_subscription_id: string | null;
  account: string | null;
  due_on: string;
};

/** A charge_failures row (written by cashfree-webhook). */
export type ChargeFailure = {
  payment_id: string;
  merchant_subscription_id: string;
  cf_subscription_id: string | null;
  subscription_row_id: number | null;
  user_id: number | null;
  payment_status: string;
  failure_reason: string | null;
  retry_attempts: number;
  scheduled_on: string | null;
};

export type AttemptRecord = AttemptRow & {
  subscription_row_id: number;
  user_id: number;
  account: string;
  merchant_subscription_id: string;
  cf_subscription_id: string | null;
  failed_cf_payment_id: string | null;
  failed_amount: number | null;
  failed_on: string;
  idempotency_key: string;
  status_reason?: string | null;
  retry_of_payment_id?: string | null;
  retry_cf_payment_id?: string | null;
  retry_amount?: number | null;
  retry_scheduled_for?: string | null;
  last_error?: string | null;
  send_errors?: number;
};

export type NewAttempt = Omit<AttemptRecord, "id">;

export type CheckRecord = {
  subscription_row_id: number;
  merchant_subscription_id: string | null;
  account: string | null;
  cashfree_status: string | null;
  next_charge_on: string | null;
  latest_charge_payment_id: string | null;
  latest_charge_status: string | null;
  outcome: string;
  checked_at: string;
  next_check_at: string;
};

export type AttemptPatch = Partial<Omit<AttemptRecord, "id" | "attempt" | "failed_payment_id">> & {
  resolved_at?: string;
};

export interface RetryStore {
  /** `checkAfterHours`: a charge is looked at from this time (IST) on its due day. */
  candidates(since: string, limit: number, only: string[], checkAfterHours: number): Promise<Candidate[]>;
  /**
   * Every attempt of the failed charges that need action now, earliest first: a pending attempt
   * scheduled up to `pendingThrough` (sendable, or late and to be skipped), or a requested one
   * whose debit day is up to `requestedThrough` (its outcome may be in). At most `limit` each.
   */
  openChains(q: { limit: number; pendingThrough: string; requestedThrough: string }): Promise<AttemptRecord[]>;
  /** Unprocessed FAILED regular charges (retry_attempts 0) from `since` (IST day), newest first. */
  newFailures(since: string, limit: number): Promise<ChargeFailure[]>;
  failuresFor(paymentIds: string[]): Promise<ChargeFailure[]>;
  markFailureProcessed(paymentId: string, outcome: string): Promise<void>;
  recurringPayments(
    rowIds: number[],
    sinceIso: string,
  ): Promise<Array<{ subscription_row_id: number; cf_payment_id: string; paid_at: string }>>;
  saveCheck(check: CheckRecord): Promise<void>;
  /** Inserts a chain; returns the inserted rows (none when the chain already exists). */
  insertChain(rows: NewAttempt[]): Promise<AttemptRecord[]>;
  /** Compare-and-set on the current status; false when the row had moved on. */
  updateAttempt(id: number, from: AttemptStatus[], patch: AttemptPatch): Promise<boolean>;
}

export type JobDeps = {
  store: RetryStore;
  cashfree: CashfreeClient;
  limiter: Limiter;
  lookupLimiter: Limiter;
  cfg: RetryConfig;
  now?: () => number;
  /** Mixpanel, live runs only; must not throw. */
  track?: RetryTracker;
};

type Counts = Record<string, number>;

export type JobReport = {
  mode: RetryConfig["mode"];
  failures_since: string | null;
  only: string[];
  stopped: "done" | "deadline" | "throttled" | "response_mismatch" | "send_cap";
  lookup_throttled: boolean;
  requests: number;
  lookup_requests: number;
  elapsed_ms: number;
  resolved: Counts;
  checks: { examined: number; by_source: Counts; by_outcome: Counts; by_account: Counts };
  planned: { chains: number; attempts_by_day: Counts; skipped: Counts };
  sends: { requested: number; would_send: number; by_day: Counts; skipped: Counts; ended: Counts; rejected: Counts; transient: number };
  mismatches: Array<{ attempt_id: number; mismatches: string[] }>;
  errors: Counts;
};

function bump(counts: Counts, key: string, by = 1) {
  counts[key] = (counts[key] ?? 0) + by;
}

async function pool<T>(items: T[], size: number, keepGoing: () => boolean, fn: (item: T) => Promise<void>) {
  let next = 0;
  const worker = async () => {
    while (next < items.length && keepGoing()) await fn(items[next++]);
  };
  await Promise.all(Array.from({ length: Math.max(1, size) }, worker));
}

/** A charge_failures row read as a Cashfree payment, for retryOutcome. */
function failureAsPayment(f: ChargeFailure): CfPayment {
  return { payment_status: f.payment_status, failure_details: { failure_reason: f.failure_reason } };
}

export async function runRetryJob(deps: JobDeps): Promise<JobReport> {
  const { store, cashfree, limiter, lookupLimiter, cfg } = deps;
  const now = deps.now ?? Date.now;
  const startMs = now();
  const live = cfg.mode === "live";
  const report: JobReport = {
    mode: cfg.mode,
    failures_since: cfg.failuresSince,
    only: cfg.only,
    stopped: "done",
    lookup_throttled: false,
    requests: 0,
    lookup_requests: 0,
    elapsed_ms: 0,
    resolved: {},
    checks: { examined: 0, by_source: {}, by_outcome: {}, by_account: {} },
    planned: { chains: 0, attempts_by_day: {}, skipped: {} },
    sends: { requested: 0, would_send: 0, by_day: {}, skipped: {}, ended: {}, rejected: {}, transient: 0 },
    mismatches: [],
    errors: {},
  };
  if (cfg.mode === "off") return report;

  let halted = false; // a RETRY response that is not what was asked for stops all sends
  let sends = 0;
  const running = () => limiter.open && !halted;
  const onlySet = new Set(cfg.only);
  const inScope = (merchantId: string | null | undefined, cfId: string | null | undefined) =>
    !onlySet.size || onlySet.has(merchantId ?? "") || onlySet.has(cfId ?? "");
  const iso = (ms: number) => new Date(ms).toISOString();

  /** True when this run moved the row (live only): the once-guard for its Mixpanel event. */
  const update = async (row: AttemptRecord, status: AttemptStatus, patch: AttemptPatch = {}): Promise<boolean> => {
    const from = row.status;
    row.status = status;
    Object.assign(row, patch);
    if (!live) return false;
    const terminal = status !== "pending" && status !== "requested";
    const ok = await store.updateAttempt(row.id, [from], { status, ...patch, ...(terminal ? { resolved_at: iso(now()) } : {}) });
    if (!ok) bump(report.errors, "attempt_moved_on");
    return ok;
  };
  const track = (event: Parameters<RetryTracker>[0], a: AttemptRecord, props: Parameters<RetryTracker>[2]) => {
    try {
      deps.track?.(event, a.user_id, props, retryEventKey(event, a));
    } catch {
      bump(report.errors, "analytics");
    }
  };

  const endFrom = async (chain: AttemptRecord[], fromAttempt: number, reason: string) => {
    for (const a of chain) {
      if (a.attempt >= fromAttempt && a.status === "pending") {
        await update(a, "ended", { status_reason: reason });
        bump(report.sends.ended, reason);
      }
    }
  };

  // ---- 1. open chains ----
  const today = istDay(startMs);
  const openRows = (await store.openChains({
    limit: OPEN_CHAINS_PER_KIND,
    pendingThrough: addDays(today, 1),
    requestedThrough: today,
  })).filter((r) => inScope(r.merchant_subscription_id, r.cf_subscription_id));
  const byFailure = new Map<string, AttemptRecord[]>();
  for (const r of openRows) {
    const chain = byFailure.get(r.failed_payment_id) ?? [];
    chain.push(r);
    byFailure.set(r.failed_payment_id, chain);
  }
  const chains = [...byFailure.values()].map((c) => c.sort((a, b) => a.attempt - b.attempt));

  // Payments the webhook recorded since each chain's failure, and its records of failed retries.
  const paidByRow = new Map<number, Array<{ cf_payment_id: string; paid_at: string }>>();
  const retryFailures = new Map<string, ChargeFailure>();
  if (chains.length) {
    const rowIds = [...new Set(chains.map((c) => c[0].subscription_row_id))];
    const earliest = chains.reduce((min, c) => (c[0].failed_on < min ? c[0].failed_on : min), chains[0][0].failed_on);
    for (const id of rowIds) paidByRow.set(id, []);
    for (const p of await store.recurringPayments(rowIds, iso(istMidnightMs(earliest)))) {
      paidByRow.get(Number(p.subscription_row_id))?.push(p);
    }
    const retryIds = openRows.filter((a) => a.status === "requested" && a.retry_payment_id).map((a) => a.retry_payment_id!);
    for (const f of retryIds.length ? await store.failuresFor(retryIds) : []) retryFailures.set(f.payment_id, f);
  }
  const paidSince = (chain: AttemptRecord[]) => {
    const since = istMidnightMs(chain[0].failed_on);
    return (paidByRow.get(chain[0].subscription_row_id) ?? []).filter((p) => Date.parse(p.paid_at) >= since);
  };

  const applyOutcome = async (chain: AttemptRecord[], a: AttemptRecord, outcome: RetryOutcome, via: ResolvedVia) => {
    if (outcome.status === "succeeded") {
      if (await update(a, "succeeded", { status_reason: via === "cashfree" ? "charge_success" : via })) {
        track("payment_retry_succeeded", a, retrySucceededProps(a, via));
      }
      bump(report.resolved, "succeeded");
    } else if (outcome.status === "failed") {
      const willRetry = outcome.chainContinues && chain.some((x) => x.attempt > a.attempt && x.status === "pending");
      if (await update(a, "failed", { status_reason: outcome.reason })) {
        track("payment_retry_failed", a, retryFailedProps(a, outcome.reason, willRetry, via));
      }
      bump(report.resolved, `failed_${outcome.reason}`);
      if (!outcome.chainContinues) await endFrom(chain, a.attempt + 1, `retry_${outcome.reason}`);
    }
  };

  const resolveChain = async (chain: AttemptRecord[]) => {
    const paid = paidSince(chain);
    for (const a of chain) {
      if (a.status !== "requested") continue;
      if (a.retry_cf_payment_id && paid.some((p) => p.cf_payment_id === a.retry_cf_payment_id)) {
        await applyOutcome(chain, a, { status: "succeeded" }, "payment_recorded");
        continue;
      }
      const recorded = a.retry_payment_id ? retryFailures.get(a.retry_payment_id) : undefined;
      if (recorded) {
        await applyOutcome(chain, a, retryOutcome(failureAsPayment(recorded)), "webhook");
        continue;
      }
      // No webhook outcome: ask Cashfree once the retry's day should be settled.
      const day = a.retry_scheduled_for || a.scheduled_for;
      if (!outcomeDue(day, now(), cfg.timing.outcomeDelayMs) || !a.retry_payment_id) continue;
      const account = cashfree.account(a.account);
      if (!account) {
        bump(report.errors, "unknown_account");
        continue;
      }
      const res = await cashfree.payment(account, a.merchant_subscription_id, a.retry_payment_id);
      if (!res.ok) {
        if (!res.stopped) bump(report.errors, `resolve_${res.code}`);
        return;
      }
      const outcome = retryOutcome(res.body);
      if (outcome.status !== "pending") await applyOutcome(chain, a, outcome, "cashfree");
      else if (now() > istMidnightMs(day) + NO_OUTCOME_AFTER_MS) {
        await update(a, "ended", { status_reason: "no_outcome" });
        bump(report.resolved, "no_outcome");
      } else bump(report.resolved, "still_pending");
    }
  };

  /** `fresh`: the mandate fetched moments ago, used for this chain's first send. */
  const advanceChain = async (chain: AttemptRecord[], fresh?: CfMandate) => {
    const paid = paidSince(chain).length > 0;
    const pending = chain.filter((a) => a.status === "pending").sort((a, b) => a.attempt - b.attempt);
    for (const a of pending) {
      if (!running()) return;
      const gate = gateBeforeFetch(a, chain, paid, now(), cfg.timing.sendLeadMs);
      if (gate.action === "wait") return;
      if (gate.action === "skip") {
        await update(a, "skipped", { status_reason: gate.reason });
        bump(report.sends.skipped, gate.reason);
        continue;
      }
      if (gate.action === "end") {
        await endFrom(chain, a.attempt, gate.reason);
        return;
      }
      if (sends >= cfg.maxSendsPerRun) {
        report.stopped = "send_cap";
        return;
      }
      const account = cashfree.account(a.account);
      if (!account) {
        bump(report.errors, "unknown_account");
        return;
      }
      let mandate = fresh;
      fresh = undefined;
      if (!mandate) {
        const fetched = await cashfree.mandate(account, a.merchant_subscription_id);
        if (!fetched.ok) {
          if (!fetched.stopped) bump(report.errors, `mandate_${fetched.code}`);
          return;
        }
        mandate = fetched.body;
      }
      const after = gateAfterFetch(a, mandate);
      if (after?.action === "skip") {
        await update(a, "skipped", { status_reason: after.reason });
        bump(report.sends.skipped, after.reason);
        continue;
      }
      if (after?.action === "end") {
        await endFrom(chain, a.attempt, after.reason);
        return;
      }
      sends++;
      if (!live) {
        report.sends.would_send++;
        bump(report.sends.by_day, a.scheduled_for);
        return;
      }
      const res = await cashfree.retry(account, a.merchant_subscription_id, gate.retryOf, a.scheduled_for, a.idempotency_key);
      if (res.ok) {
        const check = checkRetryResponse(res.body ?? {}, {
          retriedPaymentId: gate.retryOf,
          day: a.scheduled_for,
          amount: mandate.recurringAmount ?? a.failed_amount,
        });
        const moved = await update(a, "requested", {
          retry_of_payment_id: gate.retryOf,
          retry_payment_id: check.paymentId || null,
          retry_cf_payment_id: check.cfPaymentId || null,
          retry_amount: check.amount,
          retry_scheduled_for: check.scheduledFor,
          requested_at: iso(now()),
          last_error: check.mismatches.length ? `response_mismatch:${check.mismatches.join(",")}` : null,
        });
        report.sends.requested++;
        bump(report.sends.by_day, a.scheduled_for);
        if (moved) track("payment_retry_requested", a, retryRequestedProps(a, check.retryNumber));
        if (check.mismatches.length) {
          halted = true;
          report.stopped = "response_mismatch";
          report.mismatches.push({ attempt_id: a.id, mismatches: check.mismatches });
          console.error("retry-failed-charges: RETRY response mismatch, sends halted:", a.id, check.mismatches.join(","));
        }
        return;
      }
      if (res.stopped) return;
      const kind = classifySendError(res.status);
      if (kind === "throttled") return; // not a failure: the attempt stays pending for the next run
      if (kind === "transient") {
        await update(a, "pending", { last_error: res.code, send_errors: (a.send_errors ?? 0) + 1 });
        report.sends.transient++;
        return;
      }
      await update(a, "rejected", { status_reason: res.code, last_error: `http_${res.status}` });
      bump(report.sends.rejected, res.code);
      return;
    }
  };

  await pool(chains, cfg.concurrency, running, async (chain) => {
    await resolveChain(chain);
    await advanceChain(chain);
  });

  const since = cfg.failuresSince;

  /**
   * Payments, the plan and the check row for a mandate just fetched; sends the chain's first due
   * attempt. Returns the outcome, or null when a Cashfree call did not complete (try again later).
   */
  const examine = async (
    target: { subscription_row_id: number; user_id: number; cf_subscription_id: string | null },
    account: { name: string },
    mandate: CfMandate,
    source: string,
  ): Promise<string | null> => {
    const merchantId = mandate.merchantSubscriptionId;
    let payments: CfPayment[] = [];
    if (mandate.status === "ACTIVE") {
      const res = await cashfree.payments(cashfree.account(account.name)!, merchantId);
      if (!res.ok) {
        if (!res.stopped) bump(report.errors, `payments_${res.code}`);
        return null;
      }
      payments = res.body;
    }
    const found = discover(mandate, payments, since!, now(), cfg.timing);
    const outcome = found.kind === "plan" ? "planned" : found.outcome;
    const latest = latestCharge(payments);
    report.checks.examined++;
    bump(report.checks.by_source, source);
    bump(report.checks.by_outcome, outcome);
    bump(report.checks.by_account, account.name);
    const check: CheckRecord = {
      subscription_row_id: target.subscription_row_id,
      merchant_subscription_id: merchantId,
      account: account.name,
      cashfree_status: mandate.status || null,
      next_charge_on: mandate.nextChargeOn,
      latest_charge_payment_id: latest ? String(latest.payment_id ?? "") || null : null,
      latest_charge_status: latest ? upper(latest.payment_status) || null : null,
      outcome,
      checked_at: iso(now()),
      next_check_at: iso(nextCheckMs(outcome, mandate.nextChargeOn, now(), cfg.timing.outcomeDelayMs)),
    };
    if (found.kind !== "plan") {
      if (live) await store.saveCheck(check);
      return outcome;
    }

    report.planned.chains++;
    const rows: NewAttempt[] = [];
    for (const p of found.attempts) {
      if (p.status === "pending") bump(report.planned.attempts_by_day, p.scheduledFor);
      else bump(report.planned.skipped, p.reason ?? "skipped");
      rows.push({
        subscription_row_id: target.subscription_row_id,
        user_id: target.user_id,
        account: account.name,
        merchant_subscription_id: merchantId,
        cf_subscription_id: target.cf_subscription_id,
        failed_payment_id: found.paymentId,
        failed_cf_payment_id: found.cfPaymentId || null,
        failed_amount: found.amount,
        failed_on: found.failedOn,
        attempt: p.attempt,
        scheduled_for: p.scheduledFor,
        status: p.status,
        status_reason: p.reason ?? null,
        idempotency_key: await idempotencyKey(found.paymentId, p.attempt),
      });
    }
    let chain: AttemptRecord[];
    if (live) {
      chain = await store.insertChain(rows);
      await store.saveCheck(check);
      if (!chain.length) return outcome; // planned by an earlier run: sent from its open chain
    } else {
      chain = rows.map((r, i) => ({ ...r, id: -(i + 1) }));
    }
    chain.sort((a, b) => a.attempt - b.attempt);
    paidByRow.set(target.subscription_row_id, []);
    await advanceChain(chain, mandate);
    return outcome;
  };

  // ---- 2. failures the webhook recorded ----
  if (since && running()) {
    const failures = (await store.newFailures(since, NEW_FAILURES_PER_RUN))
      .filter((f) => inScope(f.merchant_subscription_id, f.cf_subscription_id));
    const seenRows = new Set<number>();
    await pool(failures, cfg.concurrency, running, async (f) => {
      const done = async (outcome: string) => {
        if (live) await store.markFailureProcessed(f.payment_id, outcome);
      };
      if (f.subscription_row_id == null || f.user_id == null) return await done("no_subscription");
      // Several failures of one mandate in a run: the first examination covers them all.
      if (seenRows.has(f.subscription_row_id)) return await done("same_mandate");
      seenRows.add(f.subscription_row_id);
      const found = await cashfree.mandateInAccounts(f.merchant_subscription_id);
      if ("error" in found) {
        if (!found.error.stopped) bump(report.errors, `mandate_${found.error.code}`);
        return;
      }
      if ("notFound" in found) {
        bump(report.checks.by_outcome, "not_found_in_accounts");
        return await done("not_found_in_accounts");
      }
      const outcome = await examine(
        { subscription_row_id: f.subscription_row_id, user_id: f.user_id, cf_subscription_id: f.cf_subscription_id },
        found.account,
        found.mandate,
        "webhook",
      );
      if (outcome) await done(outcome);
    });
  }

  // ---- 3. sweep ----
  if (since && running()) {
    const candidates = await store.candidates(since, cfg.maxChecksPerRun, cfg.only, cfg.timing.outcomeDelayMs / 3_600_000);
    await pool(candidates, cfg.concurrency, running, async (c) => {
      let account = c.merchant_subscription_id ? cashfree.account(c.account) : undefined;
      let mandate: CfMandate;
      if (account) {
        const res = await cashfree.mandate(account, c.merchant_subscription_id!);
        if (!res.ok) {
          if (!res.stopped) bump(report.errors, `mandate_${res.code}`);
          return;
        }
        mandate = res.body;
      } else {
        if (!lookupLimiter.open) return;
        const found = await cashfree.lookupByCfId(c.cf_subscription_id, c.account);
        if ("error" in found) {
          if (!found.error.stopped) bump(report.errors, `lookup_${found.error.code}`);
          return;
        }
        if ("notFound" in found) {
          report.checks.examined++;
          bump(report.checks.by_source, "sweep");
          bump(report.checks.by_outcome, "not_found_in_accounts");
          if (live) {
            await store.saveCheck({
              subscription_row_id: c.subscription_row_id,
              merchant_subscription_id: null,
              account: null,
              cashfree_status: null,
              next_charge_on: null,
              latest_charge_payment_id: null,
              latest_charge_status: null,
              outcome: "not_found_in_accounts",
              checked_at: iso(now()),
              next_check_at: iso(now() + 30 * DAY_MS),
            });
          }
          return;
        }
        account = found.account;
        mandate = found.mandate;
      }
      // A merchant id in `only` also matches the user's other mandates in SQL: keep the named one.
      if (!inScope(mandate.merchantSubscriptionId, c.cf_subscription_id)) return;
      await examine(c, account, mandate, "sweep");
    });
  }

  report.lookup_throttled = lookupLimiter.throttled;
  if (report.stopped === "done") {
    if (limiter.throttled) report.stopped = "throttled";
    else if (now() >= limiter.deadlineMs) report.stopped = "deadline";
  }
  report.requests = limiter.requests;
  report.lookup_requests = lookupLimiter.requests;
  report.elapsed_ms = now() - startMs;
  return report;
}
