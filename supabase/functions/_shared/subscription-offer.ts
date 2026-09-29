/**
 * Which mandate a user is offered, and what its activation writes. Pure (no I/O), so every rule is
 * unit tested in supabase/functions/tests/subscription_offer_test.ts.
 *
 * - trial (never authorised a mandate, by user or phone): plan_type `trial`, ₹3 auth
 *   (`subscription_auth_amount`, not refunded), first ₹299 charge tomorrow 10:00 IST.
 * - paid (a returning user): plan_type `monthly`, the first month (`subscription_recurring_amount`)
 *   is the auth amount, next charge the same IST day `subscription_interval_months` later, 10:00 IST.
 */
import { IST_OFFSET_MS } from "./mixpanel.ts";

export type Offer = "trial" | "paid";
export type PlanType = "trial" | "monthly";

const DAY_MS = 86_400_000;

/** Last 10 digits of a phone in any format (`+91 98185 99939`, `919818599939`, …); null when shorter. */
export function phoneKey(phone: unknown): string | null {
  if (typeof phone !== "string" && typeof phone !== "number") return null;
  const digits = String(phone).replace(/\D/g, "");
  return digits.length >= 10 ? digits.slice(-10) : null;
}

/**
 * LIKE pattern matching any stored phone whose digits contain `key` in order (spaces, dashes and a
 * country code in between). A prefilter only: callers keep the rows whose `phoneKey` equals `key`.
 */
export function phoneLikePattern(key: string): string {
  return `%${key.split("").join("%")}%`;
}

/** `subscriptions.status` values only an activated row reaches. */
const AUTHORISED_ROW_STATUSES = new Set(["active", "cancelled", "expired"]);
/** Cashfree subscription statuses that exist only after the mandate was authorised. */
const AUTHORISED_CASHFREE_STATUSES = new Set([
  "ACTIVE",
  "CUSTOMER_CANCELLED",
  "CANCELLED",
  "EXPIRED",
  "COMPLETED",
  "CUSTOMER_PAUSED",
  "ON_HOLD",
  "CARD_EXPIRED",
]);
/** `users.status` values that follow an authorised mandate. */
const AUTHORISED_USER_STATUSES = new Set(["trial", "active", "cancelled", "expired"]);

function lower(value: unknown): string {
  return typeof value === "string" ? value.trim().toLowerCase() : "";
}

function present(value: unknown): boolean {
  return value != null && value !== "";
}

export type HistoryRow = {
  status?: unknown;
  start_date?: unknown;
  cashfree_status?: unknown;
};

/**
 * A subscriptions row that shows an authorised mandate. A pending row that was never authorised
 * (abandoned checkout, AUTH FAILED / CANCELLED, LINK_EXPIRED) does not.
 */
export function rowShowsAuthorisation(row: HistoryRow): boolean {
  if (present(row.start_date)) return true;
  if (AUTHORISED_ROW_STATUSES.has(lower(row.status))) return true;
  return AUTHORISED_CASHFREE_STATUSES.has(lower(row.cashfree_status).toUpperCase());
}

export type TrialHistory = {
  userStatus: unknown;
  /** The user's own rows and the rows of the same phone (any user id). */
  subscriptions: HistoryRow[];
  /** `subscription_payments` rows (auth or recurring) of the user. */
  paymentCount: number;
  /** A history query failed: fail closed, never a trial on a DB error. */
  lookupFailed?: boolean;
};

export type TrialEligibilityReason =
  | "no_history"
  | "lookup_failed"
  | "user_status"
  | "payment"
  | "subscription";

/** Trial only when neither the user nor the phone ever had an authorised mandate. */
export function trialEligibility(h: TrialHistory): { eligible: boolean; reason: TrialEligibilityReason } {
  if (h.lookupFailed) return { eligible: false, reason: "lookup_failed" };
  if (AUTHORISED_USER_STATUSES.has(lower(h.userStatus))) return { eligible: false, reason: "user_status" };
  if (h.paymentCount > 0) return { eligible: false, reason: "payment" };
  if (h.subscriptions.some(rowShowsAuthorisation)) return { eligible: false, reason: "subscription" };
  return { eligible: true, reason: "no_history" };
}

export type OfferConfig = {
  authAmount: number;
  recurringAmount: number;
  intervalMonths: number;
  trialDays: number;
};

function positiveNumber(value: string | undefined, fallback: number): number {
  const parsed = parseFloat(value ?? "");
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function wholeNumber(value: string | undefined, fallback: number, min: number): number {
  const parsed = parseInt(value ?? "", 10);
  return Number.isFinite(parsed) && parsed >= min ? parsed : fallback;
}

/** Prices from app_config, with the function defaults 3 / 299 / 1 month / 1 trial day. */
export function offerConfig(config: Record<string, string | undefined>): OfferConfig {
  return {
    authAmount: positiveNumber(config.subscription_auth_amount, 3),
    recurringAmount: positiveNumber(config.subscription_recurring_amount, 299),
    intervalMonths: wholeNumber(config.subscription_interval_months, 1, 1),
    trialDays: wholeNumber(config.subscription_trial_days, 1, 0),
  };
}

type IstDay = { y: number; m: number; d: number };

/** The IST calendar day of an instant (month 1–12). Edge runs in UTC: never local getDate(). */
export function istCalendarDay(ms: number): IstDay {
  const shifted = new Date(ms + IST_OFFSET_MS);
  return { y: shifted.getUTCFullYear(), m: shifted.getUTCMonth() + 1, d: shifted.getUTCDate() };
}

function istMorning({ y, m, d }: IstDay): string {
  return `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}T10:00:00+05:30`;
}

/** Next IST calendar day at 10:00 IST. */
export function trialFirstChargeTime(nowMs: number): string {
  const { y, m, d } = istCalendarDay(nowMs);
  const next = new Date(Date.UTC(y, m - 1, d + 1));
  return istMorning({ y: next.getUTCFullYear(), m: next.getUTCMonth() + 1, d: next.getUTCDate() });
}

/** The same IST calendar day `months` later at 10:00 IST, clamped to that month (31 Jan → 28/29 Feb). */
export function paidFirstChargeTime(nowMs: number, months: number): string {
  const { y, m, d } = istCalendarDay(nowMs);
  const monthIndex = m - 1 + Math.max(1, Math.trunc(months));
  const ty = y + Math.floor(monthIndex / 12);
  const tm = monthIndex % 12;
  const lastDay = new Date(Date.UTC(ty, tm + 1, 0)).getUTCDate();
  return istMorning({ y: ty, m: tm + 1, d: Math.min(d, lastDay) });
}

export type SubscriptionOffer = {
  offer: Offer;
  planType: PlanType;
  trialEligible: boolean;
  /** Charged when the mandate is authorised (not refunded). */
  authAmount: number;
  recurringAmount: number;
  intervalMonths: number;
  /** 0 for the paid offer. */
  trialDays: number;
  /** Cashfree `subscription_first_charge_time` (`YYYY-MM-DDT10:00:00+05:30`). */
  firstChargeAt: string;
};

export function subscriptionOffer(trialEligible: boolean, cfg: OfferConfig, nowMs: number): SubscriptionOffer {
  if (trialEligible) {
    return {
      offer: "trial",
      planType: "trial",
      trialEligible: true,
      authAmount: cfg.authAmount,
      recurringAmount: cfg.recurringAmount,
      intervalMonths: cfg.intervalMonths,
      trialDays: cfg.trialDays,
      firstChargeAt: trialFirstChargeTime(nowMs),
    };
  }
  return {
    offer: "paid",
    planType: "monthly",
    trialEligible: false,
    authAmount: cfg.recurringAmount,
    recurringAmount: cfg.recurringAmount,
    intervalMonths: cfg.intervalMonths,
    trialDays: 0,
    firstChargeAt: paidFirstChargeTime(nowMs, cfg.intervalMonths),
  };
}

/** `subscriptions.plan_type`; anything but `monthly` (including legacy blanks) is a trial row. */
export function planTypeOf(planType: unknown): PlanType {
  return lower(planType) === "monthly" ? "monthly" : "trial";
}

export type ActivationRow = {
  plan_type?: unknown;
  amount?: unknown;
  next_billing_date?: unknown;
};

export type Activation = {
  planType: PlanType;
  isTrial: boolean;
  userStatus: "trial" | "active";
  /** The row's authorisation amount: also the auth payment's amount. */
  authAmount: number;
  startDate: string;
  endDate: string;
  nextBillingDate: string;
  trialDays: number;
};

function positive(value: unknown): number | undefined {
  const parsed = typeof value === "number" ? value : typeof value === "string" ? parseFloat(value) : NaN;
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
}

/**
 * What activating a pending row writes, by its plan_type. The amount is the row's own (3 or 299,
 * written at create); Cashfree's authorised amount and then the offer price are only fallbacks for
 * a row without one. A monthly row runs to the first charge create-subscription stored as its
 * next_billing_date (recomputed when that is missing or past).
 */
export function activationFields(
  row: ActivationRow,
  cfg: OfferConfig,
  nowMs: number,
  /** Cashfree's `authorization_amount` as sent (number, numeric string or missing). */
  cashfreeAuthAmount?: unknown,
): Activation {
  const planType = planTypeOf(row.plan_type);
  const startDate = new Date(nowMs).toISOString();
  if (planType === "trial") {
    return {
      planType,
      isTrial: true,
      userStatus: "trial",
      authAmount: positive(row.amount) ?? positive(cashfreeAuthAmount) ?? cfg.authAmount,
      startDate,
      endDate: new Date(nowMs + cfg.trialDays * DAY_MS).toISOString(),
      nextBillingDate: new Date(nowMs + DAY_MS).toISOString(),
      trialDays: cfg.trialDays,
    };
  }
  const storedMs = typeof row.next_billing_date === "string" ? Date.parse(row.next_billing_date) : NaN;
  const firstChargeMs = Number.isFinite(storedMs) && storedMs > nowMs
    ? storedMs
    : Date.parse(paidFirstChargeTime(nowMs, cfg.intervalMonths));
  const firstCharge = new Date(firstChargeMs).toISOString();
  return {
    planType,
    isTrial: false,
    userStatus: "active",
    authAmount: positive(row.amount) ?? positive(cashfreeAuthAmount) ?? cfg.recurringAmount,
    startDate,
    endDate: firstCharge,
    nextBillingDate: firstCharge,
    trialDays: 0,
  };
}
