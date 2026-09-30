/**
 * The `charge_failures` row cashfree-webhook writes for a failed or cancelled ₹299 charge, which
 * retry-failed-charges reads: new failures to retry, and the outcome of its own retries. Pure, so
 * it is tested with the webhook fixtures (supabase/functions/tests/charge_retry_test.ts).
 * The Cashfree failure text is kept scrubbed (no VPAs or digit runs), for the retry filter only.
 */
import { istDate, parseCashfreeTimeMs } from "./mixpanel.ts";
import { failureReasonBucket, parsePayment, scrubFailureText } from "./subscription-analytics.ts";

type Json = Record<string, unknown>;

export type ChargeFailureRow = {
  payment_id: string;
  cf_payment_id: string | null;
  merchant_subscription_id: string;
  cf_subscription_id: string | null;
  subscription_row_id: number;
  user_id: number;
  payment_status: string;
  failure_reason: string | null;
  failure_bucket: string;
  retry_attempts: number;
  amount: number | null;
  scheduled_on: string | null;
  failed_at: string;
};

function text(value: unknown): string {
  if (typeof value === "string") return value.trim();
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return "";
}

/**
 * Null unless the payload is a CHARGE with Cashfree's string payment_id and the merchant
 * subscription id (the retry call needs both; the DB stores neither elsewhere).
 */
export function chargeFailureRow(
  data: Json,
  eventTimeMs: number,
  subscription: { id: number; user_id: number },
): ChargeFailureRow | null {
  const p = parsePayment(data);
  if (p.paymentType !== "CHARGE") return null;
  const details = (data.subscription_details && typeof data.subscription_details === "object"
    ? data.subscription_details
    : {}) as Json;
  const paymentId = text(data.payment_id);
  const merchantId = text(data.subscription_id) || text(details.subscription_id);
  if (!paymentId || !merchantId || merchantId === p.cfSubId) return null;
  return {
    payment_id: paymentId,
    cf_payment_id: text(data.cf_payment_id) || null,
    merchant_subscription_id: merchantId,
    cf_subscription_id: p.cfSubId || null,
    subscription_row_id: Number(subscription.id),
    user_id: Number(subscription.user_id),
    payment_status: p.paymentStatus || "FAILED",
    failure_reason: scrubFailureText(p.failureText) || null,
    failure_bucket: failureReasonBucket(p.failureText, p.paymentStatus, "charge"),
    retry_attempts: p.retryAttempts,
    amount: p.amount > 0 ? p.amount : null,
    scheduled_on: istDate(parseCashfreeTimeMs(p.scheduleDate)) || null,
    failed_at: new Date(eventTimeMs).toISOString(),
  };
}
