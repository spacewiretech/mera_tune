/**
 * The retry job's server events (Mixpanel, `platform` = server, `distinct_id` = the user). Pure prop
 * builders, tested in supabase/functions/tests/charge_retry_job_test.ts; the allowlists are in
 * _shared/subscription-analytics.ts SERVER_EVENT_PROPS. Sent in `live` mode only, once per failed
 * charge and attempt (`$insert_id` from `retryEventKey`).
 *
 * - payment_retry_requested: Cashfree accepted a RETRY (a new ₹299 charge for the retry day).
 * - payment_retry_succeeded / payment_retry_failed: the outcome of that charge.
 * Revenue stays on the webhook's subscription_paid (`is_retry_recovery` = true).
 */
import type { MixpanelProps } from "../_shared/mixpanel.ts";
import type { AttemptRecord } from "./job.ts";
import { addDays, istMidnightMs } from "./policy.ts";

export type RetryEvent = "payment_retry_requested" | "payment_retry_succeeded" | "payment_retry_failed";

/** How the job learned the outcome: the webhook's payment row, the webhook's failure row, or Cashfree. */
export type ResolvedVia = "payment_recorded" | "webhook" | "cashfree";

export type RetryTracker = (event: RetryEvent, userId: number, props: MixpanelProps, key: string) => void;

const DAY_MS = 86_400_000;

function retryDay(a: AttemptRecord): string {
  return a.retry_scheduled_for || a.scheduled_for;
}

/** Whole IST days from the failed charge to the retry's debit day. */
export function daysSinceFailure(failedOn: string, day: string): number | undefined {
  if (!failedOn || !day) return undefined;
  const days = Math.round((istMidnightMs(day) - istMidnightMs(failedOn)) / DAY_MS);
  return days >= 0 && addDays(failedOn, days) === day ? days : undefined;
}

/** Semantic dedupe key: one event per failed charge, attempt and kind. */
export function retryEventKey(event: RetryEvent, a: Pick<AttemptRecord, "failed_payment_id" | "attempt">): string {
  return `${event}:${a.failed_payment_id}:${a.attempt}`.slice(0, 200);
}

function base(a: AttemptRecord): MixpanelProps {
  const day = retryDay(a);
  return {
    subscription_id: a.merchant_subscription_id,
    attempt: a.attempt,
    retry_date: day,
    failed_date: a.failed_on,
    days_since_failure: daysSinceFailure(a.failed_on, day),
  };
}

export function retryRequestedProps(a: AttemptRecord, retryNumber: number | null): MixpanelProps {
  return {
    ...base(a),
    retry_number: retryNumber ?? undefined,
    amount: a.retry_amount ?? undefined,
    currency: "INR",
    cf_payment_id: a.retry_cf_payment_id ?? undefined,
    failed_cf_payment_id: a.failed_cf_payment_id ?? undefined,
  };
}

export function retrySucceededProps(a: AttemptRecord, via: ResolvedVia): MixpanelProps {
  return {
    ...base(a),
    amount: a.retry_amount ?? undefined,
    currency: "INR",
    cf_payment_id: a.retry_cf_payment_id ?? undefined,
    resolved_via: via,
  };
}

export function retryFailedProps(
  a: AttemptRecord,
  failureReason: string,
  willRetry: boolean,
  via: ResolvedVia,
): MixpanelProps {
  return {
    ...base(a),
    failure_reason: failureReason,
    will_retry: willRetry,
    cf_payment_id: a.retry_cf_payment_id ?? undefined,
    resolved_via: via,
  };
}
