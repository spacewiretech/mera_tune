/**
 * Pure mapping from Cashfree subscription webhooks (and verify-subscription) onto the server
 * Mixpanel events: per-event prop allowlists, payload readers, failure buckets, status transitions
 * and `$insert_id` keys. No I/O, so it is unit tested in supabase/functions/tests/cashfree_webhook_test.ts.
 * Payload fields are read defensively: Cashfree sends null, "" or omits them depending on type and version.
 */
import {
  istDate,
  mixpanelDate,
  type MixpanelProps,
  parseCashfreeTimeMs,
  type PeopleOps,
} from "./mixpanel.ts";
import { planTypeOf } from "./subscription-offer.ts";

type Json = Record<string, unknown>;

const DAY_MS = 86_400_000;

/** The only keys each server event may carry (`platform`, `time`, `$insert_id` are added by mixpanel.ts). */
export const SERVER_EVENT_PROPS = {
  trial_payment_succeeded: [
    "subscription_id", "amount", "currency", "activated_via", "payment_group", "upi_handle",
    "payment_app", "cf_payment_id", "is_trial", "trial_days", "recurring_amount", "interval_months",
  ],
  trial_expired: ["reason", "ringtones_created", "days_since_trial_start", "subscription_id"],
  mandate_auth_failed: [
    "failure_reason", "payment_status", "payment_group", "upi_handle", "retry_attempts",
    "subscription_id",
  ],
  subscription_paid: [
    "amount", "currency", "payment_type", "renewal_number", "billing_month", "subscription_id",
    "cf_payment_id", "subscription_renewal_number", "is_first_charge", "retry_attempts",
    "is_retry_recovery", "payment_group", "upi_handle", "days_since_trial_start", "amount_mismatch",
  ],
  subscription_renewal_failed: [
    "amount", "currency", "payment_status", "failure_reason", "retry_attempts", "subscription_id",
    "cf_payment_id",
  ],
  subscription_renewal_notified: ["amount", "payment_schedule_date", "subscription_id", "cf_payment_id"],
  subscription_cancelled: [
    "cancellation_status", "subscription_id", "renewals_before_cancel", "cancelled_by",
    "previous_status", "cancelled_during_trial", "user_downgraded",
  ],
  subscription_status_changed: [
    "status", "previous_status", "transition", "is_reactivation", "next_schedule_date",
    "subscription_id",
  ],
  subscription_refund_processed: [
    "refund_status", "refund_amount", "currency", "refund_speed", "original_payment_type",
  ],
} as const satisfies Record<string, readonly string[]>;

export type ServerEvent = keyof typeof SERVER_EVENT_PROPS;

const warnedKeys = new Set<string>();

/** Keeps only allowlisted keys; warns once per dropped key (the name only, never the value). */
export function pickEventProps(event: ServerEvent, props: MixpanelProps): MixpanelProps {
  const allowed: readonly string[] = SERVER_EVENT_PROPS[event];
  const picked: MixpanelProps = {};
  for (const [key, value] of Object.entries(props)) {
    if (allowed.includes(key)) {
      picked[key] = value;
    } else if (!warnedKeys.has(`${event}.${key}`)) {
      warnedKeys.add(`${event}.${key}`);
      console.warn("Dropped non-allowlisted Mixpanel prop:", event, key);
    }
  }
  return picked;
}

function obj(value: unknown): Json {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Json : {};
}

function text(value: unknown): string {
  if (typeof value === "string") return value.trim();
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return "";
}

function num(value: unknown): number | undefined {
  const parsed = typeof value === "number"
    ? value
    : typeof value === "string" && value.trim()
    ? Number(value)
    : NaN;
  return Number.isFinite(parsed) ? parsed : undefined;
}

function currencyCode(value: unknown): string {
  const code = text(value).toUpperCase();
  return /^[A-Z]{3}$/.test(code) ? code : "INR";
}

/** Lower snake_case slug of a Cashfree enum, or `unknown`. */
export function statusSlug(value: string | null | undefined): string {
  const slug = (value ?? "").trim().toLowerCase();
  return /^[a-z_]{1,32}$/.test(slug) ? slug : "unknown";
}

export function authorizationDetails(source: Json): Json {
  return obj(source.authorization_details ?? source.authorisation_details);
}

const PAYMENT_GROUPS = new Set(["upi", "card", "enach", "pnach"]);

function normalizeGroup(value: string): string | undefined {
  const group = value.trim().toLowerCase();
  if (!group) return undefined;
  if (group.endsWith("card")) return "card";
  return PAYMENT_GROUPS.has(group) ? group : undefined;
}

export function paymentGroup(auth: Json): string | undefined {
  const raw = text(auth.payment_group);
  const fromGroup = normalizeGroup(raw);
  if (fromGroup) return fromGroup;
  const method = auth.payment_method;
  const methodKey = typeof method === "string" ? method : Object.keys(obj(method))[0] ?? "";
  const fromMethod = normalizeGroup(methodKey);
  if (fromMethod) return fromMethod;
  return raw || methodKey ? "other" : undefined;
}

/** PSP handle after `@` (`ybl`, `okaxis`). The part before `@` is usually the phone number: never kept. */
export function upiHandle(auth: Json): string | undefined {
  const upiId = text(obj(obj(auth.payment_method).upi).upi_id);
  const at = upiId.lastIndexOf("@");
  if (at < 0) return undefined;
  const handle = upiId.slice(at + 1).toLowerCase();
  return /^[a-z0-9.-]{2,32}$/.test(handle) ? handle : undefined;
}

export type PaymentApp = "phonepe" | "google_pay" | "paytm" | "bhim";

/** The UPI app behind a PSP handle, in the app's `payment_app` values; undefined for bank and unknown handles. */
export function paymentAppFromUpiHandle(handle: string | null | undefined): PaymentApp | undefined {
  const h = (handle ?? "").trim().toLowerCase();
  if (h === "ybl" || h === "ibl" || h === "axl") return "phonepe";
  if (h.startsWith("ok")) return "google_pay";
  if (h === "paytm" || h.startsWith("pt")) return "paytm";
  if (h === "upi") return "bhim";
  return undefined;
}

const MIXPANEL_INSERT_ID = /^[A-Za-z0-9-]{1,36}$/;

/** A Cashfree payment id that is already a valid `$insert_id`; undefined means hash the event key instead. */
export function paymentInsertId(cfPaymentId: string | null | undefined): string | undefined {
  const id = (cfPaymentId ?? "").trim();
  return MIXPANEL_INSERT_ID.test(id) ? id : undefined;
}

export const FAILURE_REASONS = [
  "insufficient_funds",
  "limit_exceeded",
  "account_issue",
  "mandate_revoked",
  "mandate_inactive",
  "bank_declined",
  "user_declined",
  "timeout",
  "bank_technical_error",
  "debit_failed",
  "other",
  "payment_cancelled",
  "user_cancelled",
  "unknown",
] as const;

export type FailureReason = typeof FAILURE_REASONS[number];

/** First match wins; applied to `failure_details.failure_reason`. */
const FAILURE_RULES: ReadonlyArray<readonly [FailureReason, RegExp]> = [
  ["insufficient_funds", /insufficient|not sufficient|low balance/i],
  ["limit_exceeded", /limit|exceed|frequency|too many/i],
  [
    "account_issue",
    /account.{0,20}(closed|blocked|frozen|dormant|invalid|inoperative|inactive|not found|does not exist)|invalid (vpa|upi id|account)|kyc|debit freeze|restricted/i,
  ],
  [
    "mandate_revoked",
    /revok|(mandate|subscription).{0,20}(cancel|paus|suspend)|(cancel|paus)\w* by (customer|user|payer|remitter)/i,
  ],
  ["mandate_inactive", /not active|inactive|(mandate|subscription).{0,20}(expired|not found|invalid|does not exist)/i],
  ["bank_declined", /(declin|reject)\w*.{0,25}(bank|issuer|remitter)|(bank|issuer|remitter).{0,25}(declin|reject)/i],
  ["user_declined", /declin|reject|denied|not approved|disapprov/i],
  ["timeout", /time ?out|timed out|expired|no response|not respond|deemed/i],
  [
    "bank_technical_error",
    /(bank|issuer|remitter|psp|npci|cbs|switch).{0,30}(not available|unavailable|down|offline|technical|error|failure|not reachable)|technical|system error|server error|service unavailable/i,
  ],
  ["debit_failed", /debit (has been )?failed|transaction failed|payment failed|failed/i],
];

export function failureReasonBucket(
  failureText: string | null | undefined,
  paymentStatus: string,
  context: "auth" | "charge",
): FailureReason {
  if (paymentStatus.trim().toUpperCase() === "CANCELLED") {
    return context === "charge" ? "payment_cancelled" : "user_cancelled";
  }
  const trimmed = (failureText ?? "").trim();
  if (!trimmed) return "unknown";
  for (const [bucket, pattern] of FAILURE_RULES) {
    if (pattern.test(trimmed)) return bucket;
  }
  return "other";
}

/** For logs only (never Mixpanel): masks VPAs and digit runs, collapses whitespace, max 120 chars. */
export function scrubFailureText(failureText: string | null | undefined): string {
  return (failureText ?? "")
    .replace(/[\w.-]+@[\w.-]+/g, "[vpa]")
    .replace(/\d{4,}/g, "#")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 120);
}

const STATUS_TRANSITIONS: Record<string, string> = {
  ON_HOLD: "on_hold",
  CUSTOMER_PAUSED: "paused",
  PAUSED: "paused",
  EXPIRED: "expired",
  LINK_EXPIRED: "checkout_expired",
  COMPLETED: "completed",
  CARD_EXPIRED: "card_expired",
  BANK_APPROVAL_PENDING: "bank_approval_pending",
  INITIALIZED: "initialized",
  CUSTOMER_CANCELLED: "cancelled",
  CANCELLED: "cancelled",
};

const RESUMABLE_STATUSES = new Set(["CUSTOMER_PAUSED", "PAUSED", "CARD_EXPIRED"]);

export function statusTransition(
  previous: string | null | undefined,
  next: string,
): { transition: string; is_reactivation: boolean } {
  const prev = (previous ?? "").trim().toUpperCase();
  const incoming = next.trim().toUpperCase();
  if (incoming === "ACTIVE") {
    if (prev === "ON_HOLD") return { transition: "recovered", is_reactivation: true };
    if (RESUMABLE_STATUSES.has(prev)) return { transition: "resumed", is_reactivation: true };
    return { transition: "activated", is_reactivation: false };
  }
  return { transition: STATUS_TRANSITIONS[incoming] ?? "other", is_reactivation: false };
}

/** Out-of-order and repeated STATUS_CHANGED deliveries must not re-emit or roll the stored status back. */
export function shouldApplyStatus(
  previousStatus: string | null | undefined,
  previousAtMs: number | null | undefined,
  next: string,
  eventMs: number,
): "apply" | "duplicate" | "stale" {
  if (previousAtMs != null && eventMs < previousAtMs) return "stale";
  if ((previousStatus ?? "").trim().toUpperCase() === next.trim().toUpperCase()) return "duplicate";
  return "apply";
}

export const CANCELLED_STATUSES = new Set(["CUSTOMER_CANCELLED", "CANCELLED"]);

export function cancelledBy(status: string): "customer" | "merchant" {
  return status.trim().toUpperCase() === "CUSTOMER_CANCELLED" ? "customer" : "merchant";
}

/**
 * `subscriptions.status` before the cancel, with an active trial row that was never charged counted
 * as trial. A paid `monthly` row paid its first month upfront, so it is `active` from activation.
 */
export function cancelPreviousStatus(
  rowStatus: string,
  subscriptionRenewals: number,
  planType?: unknown,
): "pending" | "trial" | "active" {
  if (rowStatus === "pending") return "pending";
  if (planTypeOf(planType) === "monthly") return "active";
  return subscriptionRenewals > 0 ? "active" : "trial";
}

export type TrialExpiredReason = "cancelled_in_trial" | "mandate_expired" | "mandate_completed" | "card_expired";

/**
 * Statuses that end an unconverted trial. ON_HOLD is not one: it can still recover to ACTIVE.
 * CARD_EXPIRED is kept per the tracking plan although a card update can resume it (`resumed`), so
 * a `card_expired` trial_expired can precede a subscription_paid. The app only opens UPI mandates.
 */
export function trialExpiredReason(status: string): TrialExpiredReason | null {
  const incoming = status.trim().toUpperCase();
  if (CANCELLED_STATUSES.has(incoming)) return "cancelled_in_trial";
  if (incoming === "EXPIRED") return "mandate_expired";
  if (incoming === "COMPLETED") return "mandate_completed";
  if (incoming === "CARD_EXPIRED") return "card_expired";
  return null;
}

export type TrialExpiryRow = {
  status?: unknown;
  plan_type?: unknown;
  start_date?: unknown;
  trial_expired_at?: unknown;
};

/**
 * A row that started a trial and has not sent trial_expired yet (the caller still checks it has no
 * recurring charge). The cancel owns a cancelled row, so a later EXPIRED/COMPLETED does not report it.
 * A paid `monthly` row never had a trial, so it never reports one.
 */
export function isTrialExpiryCandidate(row: TrialExpiryRow, reason: TrialExpiredReason): boolean {
  if (planTypeOf(row.plan_type) === "monthly") return false;
  if (row.start_date == null || row.start_date === "") return false;
  if (row.trial_expired_at != null && row.trial_expired_at !== "") return false;
  return reason === "cancelled_in_trial" || row.status !== "cancelled";
}

/** Whole days from `subscriptions.start_date` to the event; undefined when unknown or negative. */
export function daysSince(startIso: unknown, eventMs: number): number | undefined {
  const startMs = parseCashfreeTimeMs(startIso);
  if (startMs == null) return undefined;
  const days = Math.floor((eventMs - startMs) / DAY_MS);
  return days >= 0 ? days : undefined;
}

export type WebhookRoute =
  | "auth_status"
  | "payment_success"
  | "payment_failed"
  | "payment_notification"
  | "status_changed"
  | "refund_status"
  | "ignored";

/** CARD_EXPIRY_REMINDER and CONTROLLED_* are ignored: the app only creates UPI mandates. */
export function webhookRoute(type: string): WebhookRoute {
  switch (type) {
    case "SUBSCRIPTION_AUTH_STATUS":
      return "auth_status";
    case "SUBSCRIPTION_PAYMENT_SUCCESS":
      return "payment_success";
    case "SUBSCRIPTION_PAYMENT_FAILED":
    case "SUBSCRIPTION_PAYMENT_CANCELLED":
      return "payment_failed";
    case "SUBSCRIPTION_PAYMENT_NOTIFICATION_INITIATED":
      return "payment_notification";
    case "SUBSCRIPTION_STATUS_CHANGED":
      return "status_changed";
    case "SUBSCRIPTION_REFUND_STATUS":
      return "refund_status";
    default:
      return "ignored";
  }
}

export type PaymentFields = {
  cfPaymentId: string;
  cfSubId: string;
  /** Merchant id (`mt_<user>_<ts>`), falling back to the Cashfree id. */
  subscriptionId: string;
  paymentType: string;
  paymentStatus: string;
  amount: number;
  currency: string;
  retryAttempts: number;
  scheduleDate: string;
  failureText: string;
  nextScheduleDate: string;
  auth: Json;
};

/** Common fields of AUTH_STATUS / PAYMENT_* payloads (2025-01-01; 2026-01-01 adds subscription_details). */
export function parsePayment(data: Json): PaymentFields {
  const details = obj(data.subscription_details);
  const cfSubId = text(data.cf_subscription_id) || text(details.cf_subscription_id);
  return {
    cfPaymentId: text(data.cf_payment_id) || text(data.payment_id),
    cfSubId,
    subscriptionId: text(data.subscription_id) || text(details.subscription_id) || cfSubId,
    paymentType: text(data.payment_type).toUpperCase(),
    paymentStatus: text(data.payment_status).toUpperCase(),
    amount: num(data.payment_amount) ?? 0,
    currency: currencyCode(data.payment_currency),
    retryAttempts: Math.max(0, Math.trunc(num(data.retry_attempts) ?? 0)),
    scheduleDate: text(data.payment_schedule_date),
    failureText: text(obj(data.failure_details).failure_reason),
    nextScheduleDate: text(details.next_schedule_date),
    auth: authorizationDetails(data),
  };
}

/** Why a PAYMENT_* webhook is not a recurring charge we record; null when it is one. */
export function chargeSkipReason(p: PaymentFields): string | null {
  if (p.paymentType !== "CHARGE") return "auth_payment";
  if (!(p.amount > 0)) return "zero_amount";
  if (!p.cfPaymentId) return "missing_payment_id";
  return null;
}

export type StatusChange = {
  status: string;
  cfSubId: string;
  subscriptionId: string;
  nextScheduleDate: string;
};

export function parseStatusChange(data: Json): StatusChange {
  const details = obj(data.subscription_details ?? data);
  const cfSubId = text(details.cf_subscription_id);
  return {
    status: text(details.subscription_status).toUpperCase(),
    cfSubId,
    subscriptionId: text(details.subscription_id) || cfSubId,
    nextScheduleDate: text(details.next_schedule_date),
  };
}

export type RefundFields = {
  cfPaymentIds: string[];
  refundId: string;
  refundStatus: string;
  refundAmount?: number;
  refundSpeed: string;
};

export function parseRefund(data: Json): RefundFields {
  const ids = [text(data.cf_payment_id), text(data.payment_id)].filter(Boolean);
  return {
    cfPaymentIds: [...new Set(ids)],
    refundId: text(data.cf_refund_id) || text(data.refund_id),
    refundStatus: text(data.refund_status).toUpperCase(),
    refundAmount: num(data.refund_amount),
    refundSpeed: text(data.refund_speed),
  };
}

/** A mandate activation: a ₹3 trial, or (`isTrial` false) a returning user's paid first month. */
export type TrialActivation = {
  subscriptionId: string;
  auth: Json;
  /** The AUTH payment's Cashfree id; falls back to `auth.cf_payment_id`. */
  cfPaymentId?: string;
  /** The subscription row's authorisation amount, used when Cashfree's is missing. */
  authAmount: number;
  isTrial: boolean;
  trialDays: number;
  recurringAmount: number;
  intervalMonths: number;
  activatedVia: "webhook" | "app_verify";
};

export function trialPaymentId(t: TrialActivation): string {
  return (t.cfPaymentId ?? "").trim() || text(t.auth.cf_payment_id);
}

export function trialPaymentSucceededProps(t: TrialActivation): MixpanelProps {
  const handle = upiHandle(t.auth);
  return {
    subscription_id: t.subscriptionId,
    amount: num(t.auth.authorization_amount) ?? t.authAmount,
    currency: "INR",
    activated_via: t.activatedVia,
    payment_group: paymentGroup(t.auth),
    upi_handle: handle,
    payment_app: paymentAppFromUpiHandle(handle),
    cf_payment_id: trialPaymentId(t),
    is_trial: t.isTrial,
    trial_days: t.isTrial ? t.trialDays : 0,
    recurring_amount: t.recurringAmount,
    interval_months: t.intervalMonths,
  };
}

export type TrialExpiry = {
  reason: TrialExpiredReason;
  /** Undefined when the count could not be read: the prop is omitted rather than sent as 0. */
  ringtonesCreated?: number;
  daysSinceTrialStart?: number;
  subscriptionId: string;
};

export function trialExpiredProps(t: TrialExpiry): MixpanelProps {
  return {
    reason: t.reason,
    ringtones_created: t.ringtonesCreated,
    days_since_trial_start: t.daysSinceTrialStart,
    subscription_id: t.subscriptionId,
  };
}

export function trialPeopleOps(startMs: number, trialDays: number): PeopleOps {
  return {
    set: {
      subscription_status: "trial",
      trial_started_at: mixpanelDate(startMs),
      trial_ends_at: mixpanelDate(startMs + trialDays * DAY_MS),
      autopay_enabled: true,
    },
  };
}

/** Profile update at activation: the trial one, or for a paid first month no trial_* properties. */
export function activationPeopleOps(t: TrialActivation, startMs: number): PeopleOps {
  if (t.isTrial) return trialPeopleOps(startMs, t.trialDays);
  return { set: { subscription_status: "active", autopay_enabled: true } };
}

export function mandateAuthFailedProps(p: PaymentFields): MixpanelProps {
  return {
    failure_reason: failureReasonBucket(p.failureText, p.paymentStatus, "auth"),
    payment_status: statusSlug(p.paymentStatus),
    payment_group: paymentGroup(p.auth),
    upi_handle: upiHandle(p.auth),
    retry_attempts: p.retryAttempts,
    subscription_id: p.subscriptionId,
  };
}

export type PaidStats = {
  renewalNumber: number;
  subscriptionRenewalNumber: number;
  billingMonth: string;
  daysSinceTrialStart?: number;
  expectedAmount?: number;
};

export function paidProps(p: PaymentFields, s: PaidStats): MixpanelProps {
  const expected = s.expectedAmount ?? 0;
  return {
    amount: p.amount,
    currency: p.currency,
    payment_type: "recurring",
    renewal_number: s.renewalNumber,
    billing_month: s.billingMonth,
    subscription_id: p.subscriptionId,
    cf_payment_id: p.cfPaymentId,
    subscription_renewal_number: s.subscriptionRenewalNumber,
    is_first_charge: s.subscriptionRenewalNumber === 1,
    retry_attempts: p.retryAttempts,
    is_retry_recovery: p.retryAttempts > 0,
    payment_group: paymentGroup(p.auth),
    upi_handle: upiHandle(p.auth),
    days_since_trial_start: s.daysSinceTrialStart,
    amount_mismatch: expected > 0 ? Math.abs(p.amount - expected) > 0.005 : undefined,
  };
}

export function renewalFailedProps(p: PaymentFields): MixpanelProps {
  return {
    amount: p.amount,
    currency: p.currency,
    payment_status: statusSlug(p.paymentStatus),
    failure_reason: failureReasonBucket(p.failureText, p.paymentStatus, "charge"),
    retry_attempts: p.retryAttempts,
    subscription_id: p.subscriptionId,
    cf_payment_id: p.cfPaymentId,
  };
}

export function renewalNotifiedProps(p: PaymentFields): MixpanelProps {
  return {
    amount: p.amount,
    payment_schedule_date: istDate(parseCashfreeTimeMs(p.scheduleDate)),
    subscription_id: p.subscriptionId,
    cf_payment_id: p.cfPaymentId,
  };
}

export function statusChangedProps(change: StatusChange, previousStatus: string | null | undefined): MixpanelProps {
  const { transition, is_reactivation } = statusTransition(previousStatus, change.status);
  return {
    status: statusSlug(change.status),
    previous_status: previousStatus ? statusSlug(previousStatus) : undefined,
    transition,
    is_reactivation,
    next_schedule_date: istDate(parseCashfreeTimeMs(change.nextScheduleDate)),
    subscription_id: change.subscriptionId,
  };
}

export type CancelContext = {
  rowStatus: string;
  /** `subscriptions.plan_type`: a `monthly` row is never cancelled during a trial. */
  planType?: unknown;
  lifetimeRenewals: number;
  subscriptionRenewals: number;
  userDowngraded: boolean;
};

export function cancelledProps(change: StatusChange, c: CancelContext): MixpanelProps {
  const previous = cancelPreviousStatus(c.rowStatus, c.subscriptionRenewals, c.planType);
  return {
    cancellation_status: change.status.toLowerCase(),
    subscription_id: change.subscriptionId,
    renewals_before_cancel: c.lifetimeRenewals,
    cancelled_by: cancelledBy(change.status),
    previous_status: previous,
    cancelled_during_trial: previous === "trial",
    user_downgraded: c.userDowngraded,
  };
}

export function refundProps(r: RefundFields, originalPaymentType: string): MixpanelProps {
  return {
    refund_status: statusSlug(r.refundStatus),
    refund_amount: r.refundAmount,
    currency: "INR",
    refund_speed: r.refundSpeed ? statusSlug(r.refundSpeed) : undefined,
    original_payment_type: originalPaymentType === "auth" || originalPaymentType === "recurring"
      ? originalPaymentType
      : undefined,
  };
}

function key(...parts: Array<string | number>): string {
  return parts.map(String).join(":").slice(0, 200);
}

/**
 * Semantic dedupe keys; `$insert_id = mixpanelInsertId(key)`, so a Cashfree retry maps to the same id.
 * trial_payment_succeeded uses `paymentInsertId(cf_payment_id)` first and `trial` only as the fallback.
 */
export const eventKey = {
  trial: (subscriptionRowId: number | string) => key("trial", subscriptionRowId),
  trialExpired: (subscriptionRowId: number | string) => key("trial_expired", subscriptionRowId),
  mandateAuthFailed: (p: PaymentFields, eventMs: number) =>
    p.cfPaymentId
      ? key("auth_failed", p.cfPaymentId, p.paymentStatus, p.retryAttempts)
      : key("auth_failed", p.cfSubId, p.paymentStatus, eventMs),
  paid: (cfPaymentId: string) => key("paid", cfPaymentId),
  renewalFailed: (type: string, p: PaymentFields) => key(type, p.cfPaymentId, p.retryAttempts),
  renewalNotified: (p: PaymentFields) =>
    key("renewal_notified", p.cfPaymentId || p.cfSubId, p.scheduleDate, p.retryAttempts),
  cancel: (subscriptionRowId: number | string) => key("cancel", subscriptionRowId),
  statusChanged: (cfSubId: string, status: string, eventMs: number) => key("status", cfSubId, status, eventMs),
  refund: (r: RefundFields) => key("refund", r.refundId || r.cfPaymentIds.join(","), r.refundStatus),
};
