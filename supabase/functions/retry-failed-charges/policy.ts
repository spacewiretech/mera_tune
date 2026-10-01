/**
 * Rules for retrying failed ₹299 autopay charges. Pure (no I/O), so every rule is unit tested in
 * supabase/functions/tests/charge_retry_test.ts.
 *
 * - Only the mandate's latest recurring charge, only when it failed for insufficient funds, and only
 *   from `charge_retry_failures_since` (IST day) on.
 * - Three attempts, counted from the failure's IST day F: F+2, F+4, then the first 5th of a month
 *   after F+4. An attempt on or after the mandate's regular next charge is skipped (that charge is
 *   the next attempt anyway).
 * - Cashfree debits a retry 24 h after the request and ignores `next_scheduled_time` (measured on
 *   production 2026-09-30: asked for 5 Oct at 14:49 IST on 30 Sep, scheduled 1 Oct 14:49). So an
 *   attempt for day D is sent on the IST day before D, and one whose day can no longer be hit is skipped.
 * - Cashfree charges the mandate's own plan amount: no amount is sent, and the response is checked.
 */
import { IST_OFFSET_MS } from "../_shared/mixpanel.ts";
import { sha256Hex } from "../_shared/user-sessions.ts";

const HOUR_MS = 3_600_000;
const DAY_MS = 86_400_000;

export const MAX_ATTEMPTS = 3;
/** Cashfree debits a retry this long after the request. Config `charge_retry_send_lead_hours`. */
export const SEND_LEAD_MS = 24 * HOUR_MS;
/** A debit this close to the end of the retry day counts as missing it. */
const DAY_END_MARGIN_MS = 10 * 60_000;
/**
 * Default: a charge's outcome is read from 22:00 IST of its day. Debits start at 00:10 IST, but
 * failures settle late: on 2026-09-30 every unpaid charge of the day was still INITIALIZED or
 * PENDING at 14:00, and successes arrive in waves at 00-02, 13 and 21 h IST.
 * Config `charge_retry_check_after_hours`.
 */
export const OUTCOME_DELAY_MS = 22 * HOUR_MS;
/** A charge still in progress is looked at again this much later. */
export const PENDING_RECHECK_MS = 3 * HOUR_MS;

/** How early a retry must be requested, and when a charge's outcome is final. */
export type Timing = { sendLeadMs: number; outcomeDelayMs: number };
export const DEFAULT_TIMING: Timing = { sendLeadMs: SEND_LEAD_MS, outcomeDelayMs: OUTCOME_DELAY_MS };

export type AttemptStatus = "pending" | "requested" | "succeeded" | "failed" | "ended" | "rejected" | "skipped";
export const OPEN_STATUSES: ReadonlySet<AttemptStatus> = new Set(["pending", "requested"]);

export type RetryMode = "off" | "dry_run" | "live";

// ---------------------------------------------------------------------------------------------
// IST calendar days, as `YYYY-MM-DD` strings. Edge runs in UTC: never local dates.

const DAY_RE = /^\d{4}-\d{2}-\d{2}$/;

export function isDay(value: unknown): value is string {
  return typeof value === "string" && DAY_RE.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00Z`));
}

export function istDay(ms: number): string {
  return new Date(ms + IST_OFFSET_MS).toISOString().slice(0, 10);
}

/** Epoch ms of 00:00 IST on `day`. */
export function istMidnightMs(day: string): number {
  return Date.parse(`${day}T00:00:00Z`) - IST_OFFSET_MS;
}

export function addDays(day: string, days: number): string {
  return new Date(Date.parse(`${day}T00:00:00Z`) + days * DAY_MS).toISOString().slice(0, 10);
}

/** The first 5th of a month strictly after `day`. */
export function fifthAfter(day: string): string {
  const [y, m, d] = day.split("-").map(Number);
  const month = d < 5 ? m - 1 : m; // 0-based month index of the 5th we want
  return new Date(Date.UTC(y, month, 5)).toISOString().slice(0, 10);
}

/** Cashfree `next_scheduled_time`. Cashfree ignored it on 2026-09-30 (debit 24 h after the request); still sent. */
export function scheduledTime(day: string): string {
  return `${day}T00:00:00+05:30`;
}

/**
 * IST day of a Cashfree time: ISO with an offset, a naive IST time (`2026-10-27 00:10:00`) or a
 * bare date. Null when missing or unreadable.
 */
export function cashfreeDay(value: unknown): string | null {
  if (typeof value !== "string" || !value.trim()) return null;
  const v = value.trim();
  if (DAY_RE.test(v)) return isDay(v) ? v : null;
  const naive = /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/.test(v);
  const ms = Date.parse(naive ? `${v.replace(" ", "T")}+05:30` : v);
  return Number.isNaN(ms) ? null : istDay(ms);
}

// ---------------------------------------------------------------------------------------------
// Schedule

export function retryDays(failedOn: string): [string, string, string] {
  const second = addDays(failedOn, 4);
  return [addDays(failedOn, 2), second, fifthAfter(second)];
}

/**
 * Where `nowMs` stands for a retry on `day`: `early` (sent now, the debit would land before the
 * day), `now` (it lands on the day) or `late` (it would land after it).
 */
export function sendTiming(day: string, nowMs: number, leadMs = SEND_LEAD_MS): "early" | "now" | "late" {
  const debitMs = nowMs + leadMs;
  if (debitMs < istMidnightMs(day)) return "early";
  if (debitMs >= istMidnightMs(addDays(day, 1)) - DAY_END_MARGIN_MS) return "late";
  return "now";
}

/** An attempt on `day` can still be requested now or later. */
export function canStillSend(day: string, nowMs: number, leadMs = SEND_LEAD_MS): boolean {
  return sendTiming(day, nowMs, leadMs) !== "late";
}

export type PlannedAttempt = {
  attempt: number;
  scheduledFor: string;
  status: "pending" | "skipped";
  reason?: "after_next_charge" | "too_late";
};

export function planAttempts(
  failedOn: string,
  nextChargeOn: string | null,
  nowMs: number,
  leadMs = SEND_LEAD_MS,
): PlannedAttempt[] {
  return retryDays(failedOn).map((day, i) => {
    const attempt = i + 1;
    if (nextChargeOn && day >= nextChargeOn) {
      return { attempt, scheduledFor: day, status: "skipped", reason: "after_next_charge" };
    }
    if (!canStillSend(day, nowMs, leadMs)) return { attempt, scheduledFor: day, status: "skipped", reason: "too_late" };
    return { attempt, scheduledFor: day, status: "pending" };
  });
}

// ---------------------------------------------------------------------------------------------
// Failure reasons

const INSUFFICIENT = /insufficient|not sufficient|low balance/i;
/**
 * Texts that mention funds but are really a dead or blocked mandate, e.g. "notification couldn't be
 * sent ... already cancelled". Checked first: any of these means no retry.
 */
const NOT_RETRYABLE = /revok|cancel|not active|inactive|paus|suspend|notification|expired|closed|frozen|blocked|dormant/i;

export function isInsufficientFunds(failureText: unknown): boolean {
  if (typeof failureText !== "string") return false;
  const text = failureText.trim();
  if (!text || NOT_RETRYABLE.test(text)) return false;
  return INSUFFICIENT.test(text);
}

// ---------------------------------------------------------------------------------------------
// Cashfree entities (2025-01-01 /pg shapes; the legacy lookup is mapped onto CfMandate)

export type CfPayment = {
  payment_id?: unknown;
  cf_payment_id?: unknown;
  payment_type?: unknown;
  payment_status?: unknown;
  payment_amount?: unknown;
  payment_schedule_date?: unknown;
  payment_initiated_date?: unknown;
  retry_attempts?: unknown;
  failure_details?: { failure_reason?: unknown } | null;
};

export type CfMandate = {
  merchantSubscriptionId: string;
  status: string;
  /** IST day of Cashfree's next regular charge; null when unknown. */
  nextChargeOn: string | null;
  recurringAmount: number | null;
};

function text(value: unknown): string {
  if (typeof value === "string") return value.trim();
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return "";
}

function num(value: unknown): number | null {
  const n = typeof value === "number" ? value : typeof value === "string" && value.trim() ? Number(value) : NaN;
  return Number.isFinite(n) ? n : null;
}

export function upper(value: unknown): string {
  return text(value).toUpperCase();
}

export function failureText(p: CfPayment): string {
  return text(p.failure_details?.failure_reason);
}

/** The mandate's newest CHARGE (by initiated time, then cf_payment_id). Auth payments never count. */
export function latestCharge(payments: CfPayment[]): CfPayment | null {
  const charges = payments.filter((p) => upper(p.payment_type) === "CHARGE");
  if (!charges.length) return null;
  const at = (p: CfPayment) => Date.parse(text(p.payment_initiated_date) || text(p.payment_schedule_date)) || 0;
  return charges.reduce((best, p) => {
    const d = at(p) - at(best);
    if (d !== 0) return d > 0 ? p : best;
    return (num(p.cf_payment_id) ?? 0) > (num(best.cf_payment_id) ?? 0) ? p : best;
  });
}

/** Lower snake_case slug of a Cashfree value, for bounded `status_reason` / outcome strings. */
export function slug(value: unknown, fallback = "unknown"): string {
  const s = text(value).toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "").slice(0, 40);
  return s || fallback;
}

// ---------------------------------------------------------------------------------------------
// Discovery: does this mandate's latest charge get a retry chain?

export type Discovery =
  | {
    kind: "plan";
    failedOn: string;
    paymentId: string;
    cfPaymentId: string;
    amount: number | null;
    attempts: PlannedAttempt[];
  }
  | { kind: "none"; outcome: string };

export function discover(
  mandate: CfMandate,
  payments: CfPayment[],
  failuresSince: string,
  nowMs: number,
  timing: Timing = DEFAULT_TIMING,
): Discovery {
  if (mandate.status !== "ACTIVE") return { kind: "none", outcome: `mandate_${slug(mandate.status)}` };
  const latest = latestCharge(payments);
  if (!latest) return { kind: "none", outcome: "no_charge" };
  const status = upper(latest.payment_status);
  if (status === "SUCCESS") return { kind: "none", outcome: "latest_paid" };
  if (status === "CANCELLED") return { kind: "none", outcome: "charge_cancelled" };
  if (status !== "FAILED") {
    // Cashfree creates the next regular charge (INITIALIZED) about 1.5 days ahead: that charge is
    // the next attempt, nothing to retry.
    const day = cashfreeDay(latest.payment_schedule_date);
    return { kind: "none", outcome: day && day > istDay(nowMs) ? "next_charge_upcoming" : "charge_pending" };
  }
  if (!isInsufficientFunds(failureText(latest))) return { kind: "none", outcome: "not_insufficient_funds" };
  // A charge that is itself a retry belongs to a chain this job did not start.
  if ((num(latest.retry_attempts) ?? 0) > 0) return { kind: "none", outcome: "foreign_retry" };
  const paymentId = text(latest.payment_id);
  if (!paymentId) return { kind: "none", outcome: "missing_payment_id" };
  const failedOn = cashfreeDay(latest.payment_schedule_date) ?? cashfreeDay(latest.payment_initiated_date);
  if (!failedOn) return { kind: "none", outcome: "missing_failure_date" };
  if (failedOn < failuresSince) return { kind: "none", outcome: "before_start_date" };
  const attempts = planAttempts(failedOn, mandate.nextChargeOn, nowMs, timing.sendLeadMs);
  if (!attempts.some((a) => a.status === "pending")) return { kind: "none", outcome: "no_attempt_left" };
  return {
    kind: "plan",
    failedOn,
    paymentId,
    cfPaymentId: text(latest.cf_payment_id),
    amount: num(latest.payment_amount),
    attempts,
  };
}

/** When the sweep looks at this mandate again. */
export function nextCheckMs(
  outcome: string,
  nextChargeOn: string | null,
  nowMs: number,
  outcomeDelayMs = OUTCOME_DELAY_MS,
): number {
  if (outcome === "charge_pending") return nowMs + PENDING_RECHECK_MS;
  if (outcome.startsWith("mandate_") && outcome !== "mandate_cancelled" && outcome !== "mandate_customer_cancelled") {
    return nowMs + 3 * DAY_MS; // paused / on hold can come back
  }
  if (nextChargeOn) return Math.max(nowMs + HOUR_MS, istMidnightMs(nextChargeOn) + outcomeDelayMs);
  return nowMs + 7 * DAY_MS;
}

// ---------------------------------------------------------------------------------------------
// Sending an attempt

export type AttemptRow = {
  id: number;
  attempt: number;
  scheduled_for: string;
  status: AttemptStatus;
  failed_payment_id: string;
  retry_payment_id?: string | null;
  requested_at?: string | null;
};

export type SendGate =
  | { action: "send"; retryOf: string }
  | { action: "wait" }
  | { action: "skip"; reason: string }
  | { action: "end"; reason: string };

/**
 * DB-only checks, before any Cashfree call. `chain` is every attempt of the same failed charge.
 * The previous attempt must be over: a failed (insufficient funds), skipped or rejected one lets
 * this one go; a success or an ended chain ends it.
 */
export function gateBeforeFetch(
  row: AttemptRow,
  chain: AttemptRow[],
  paidSinceFailure: boolean,
  nowMs: number,
  leadMs = SEND_LEAD_MS,
): SendGate {
  const earlier = chain.filter((a) => a.attempt < row.attempt).sort((a, b) => b.attempt - a.attempt);
  if (earlier.some((a) => a.status === "succeeded")) return { action: "end", reason: "recovered" };
  if (paidSinceFailure) return { action: "end", reason: "paid_since_failure" };
  const timing = sendTiming(row.scheduled_for, nowMs, leadMs);
  if (timing === "late") return { action: "skip", reason: "too_late" };
  for (const prev of earlier) {
    if (prev.status === "pending" || prev.status === "requested") return { action: "wait" };
    if (prev.status === "ended") return { action: "end", reason: "chain_ended" };
  }
  // Sent the day before: Cashfree debits 24 h after the request.
  if (timing === "early") return { action: "wait" };
  // Cashfree retries the latest failed charge: the newest failed retry of this chain, else the original.
  const lastFailed = earlier.find((a) => a.status === "failed" && a.retry_payment_id);
  return { action: "send", retryOf: lastFailed?.retry_payment_id ?? row.failed_payment_id };
}

/** Checks on the mandate fetched right before the send (the DB can lag). */
export function gateAfterFetch(row: AttemptRow, mandate: CfMandate): SendGate | null {
  if (mandate.status !== "ACTIVE") return { action: "end", reason: `mandate_${slug(mandate.status)}` };
  if (mandate.nextChargeOn && row.scheduled_for >= mandate.nextChargeOn) return { action: "skip", reason: "after_next_charge" };
  return null;
}

export function retryBody(subscriptionId: string, paymentId: string, day: string) {
  return {
    subscription_id: subscriptionId,
    payment_id: paymentId,
    action: "RETRY",
    action_details: { next_scheduled_time: scheduledTime(day) },
  };
}

/** Per failed charge and attempt, formatted as a UUID; the same on every resend. */
export async function idempotencyKey(failedPaymentId: string, attempt: number): Promise<string> {
  const h = await sha256Hex(`charge-retry:${failedPaymentId}:${attempt}`);
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20, 32)}`;
}

export type RetryResponseCheck = {
  paymentId: string;
  cfPaymentId: string;
  amount: number | null;
  scheduledFor: string | null;
  /** Cashfree's count of retries on this cycle (1 for the first). */
  retryNumber: number | null;
  /** Empty when the response is what was asked for. */
  mismatches: string[];
};

/** The RETRY response is a new charge: its own id, the plan amount, the day asked for. */
export function checkRetryResponse(
  body: CfPayment,
  expected: { retriedPaymentId: string; day: string; amount: number | null },
): RetryResponseCheck {
  const paymentId = text(body.payment_id);
  const amount = num(body.payment_amount);
  const scheduledFor = cashfreeDay(body.payment_schedule_date);
  const mismatches: string[] = [];
  if (!paymentId) mismatches.push("missing_payment_id");
  else if (paymentId === expected.retriedPaymentId) mismatches.push("same_payment_id");
  if (expected.amount == null || amount == null || Math.abs(amount - expected.amount) > 0.005) mismatches.push("amount");
  if (scheduledFor !== expected.day) mismatches.push("scheduled_date");
  return { paymentId, cfPaymentId: text(body.cf_payment_id), amount, scheduledFor, retryNumber: num(body.retry_attempts), mismatches };
}

export type SendErrorKind = "throttled" | "transient" | "rejected";

/** 429 stops the run and is not a failure; 5xx, timeouts and 409 (in-flight duplicate) are resent later. */
export function classifySendError(httpStatus: number): SendErrorKind {
  if (httpStatus === 429) return "throttled";
  if (httpStatus === 0 || httpStatus === 409 || httpStatus >= 500) return "transient";
  return "rejected";
}

// ---------------------------------------------------------------------------------------------
// Outcome of a requested retry

/** A requested retry's outcome is read from the same time on its day as a regular charge's. */
export function outcomeDue(scheduledFor: string, nowMs: number, outcomeDelayMs = OUTCOME_DELAY_MS): boolean {
  return nowMs >= istMidnightMs(scheduledFor) + outcomeDelayMs;
}

export type RetryOutcome =
  | { status: "succeeded" }
  | { status: "failed"; reason: string; chainContinues: boolean }
  | { status: "pending" };

export function retryOutcome(p: CfPayment): RetryOutcome {
  const status = upper(p.payment_status);
  if (status === "SUCCESS") return { status: "succeeded" };
  if (status === "FAILED" || status === "CANCELLED") {
    const insufficient = status === "FAILED" && isInsufficientFunds(failureText(p));
    return {
      status: "failed",
      reason: insufficient ? "insufficient_funds" : status === "CANCELLED" ? "charge_cancelled" : "not_insufficient_funds",
      chainContinues: insufficient,
    };
  }
  return { status: "pending" };
}

// ---------------------------------------------------------------------------------------------
// Config

export type RetryConfig = {
  mode: RetryMode;
  /** IST day; failures before it are never retried. Null: nothing new is planned. */
  failuresSince: string | null;
  /** When non-empty, only these mandates (merchant `mt_…` or Cashfree numeric ids). */
  only: string[];
  maxChecksPerRun: number;
  maxSendsPerRun: number;
  concurrency: number;
  /** Spacing of 2025-01-01 API calls (reads and RETRY). */
  minIntervalMs: number;
  /** Spacing of legacy lookups: that API allows about 60 a minute per IP. */
  lookupIntervalMs: number;
  timing: Timing;
  /** Work stops here; the Edge request limit is 150 s. */
  timeBudgetMs: number;
};

function intIn(value: unknown, fallback: number, min: number, max: number): number {
  const n = parseInt(text(value), 10);
  return Number.isFinite(n) && n >= min && n <= max ? n : fallback;
}

function idList(value: unknown): string[] {
  const raw = Array.isArray(value) ? value.map(text) : text(value).split(/[\s,]+/);
  return [...new Set(raw.map((s) => s.trim()).filter((s) => /^(mt_\d+_\d+|\d{5,20})$/.test(s)))];
}

/**
 * app_config first; the request body can only make a run safer (dry_run instead of live, a
 * narrower mandate list), never turn retries on.
 */
export function retryConfig(config: Record<string, string | undefined>, body: Record<string, unknown> = {}): RetryConfig {
  const configured = text(config.charge_retry_mode).toLowerCase();
  let mode: RetryMode = configured === "live" ? "live" : configured === "dry_run" ? "dry_run" : "off";
  if (mode === "live" && body.dry_run === true) mode = "dry_run";

  const configuredOnly = idList(config.charge_retry_only);
  const bodyOnly = idList(body.only);
  // A body list narrows a configured list; it never widens it.
  const only = configuredOnly.length && bodyOnly.length
    ? bodyOnly.filter((id) => configuredOnly.includes(id))
    : configuredOnly.length ? configuredOnly : bodyOnly;
  const narrowedToNothing = configuredOnly.length > 0 && bodyOnly.length > 0 && only.length === 0;

  const since = text(config.charge_retry_failures_since);
  return {
    mode: narrowedToNothing ? "off" : mode,
    failuresSince: isDay(since) ? since : null,
    only,
    maxChecksPerRun: intIn(config.charge_retry_max_checks_per_run, 150, 0, 2000),
    maxSendsPerRun: intIn(config.charge_retry_max_sends_per_run, 300, 0, 1000),
    concurrency: 3,
    minIntervalMs: intIn(config.charge_retry_min_interval_ms, 250, 50, 5000),
    lookupIntervalMs: intIn(config.charge_retry_lookup_interval_ms, 1100, 200, 10_000),
    timing: {
      sendLeadMs: intIn(config.charge_retry_send_lead_hours, 24, 1, 72) * HOUR_MS,
      outcomeDelayMs: intIn(config.charge_retry_check_after_hours, 22, 1, 47) * HOUR_MS,
    },
    timeBudgetMs: 100_000,
  };
}
