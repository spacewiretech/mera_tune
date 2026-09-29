// deno test --allow-read supabase/functions/tests
//
// Trial eligibility, the trial / paid offer, first charge times in IST and what activation writes
// (_shared/subscription-offer.ts), for create-subscription, verify-subscription and cashfree-webhook.
import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  activationFields,
  type HistoryRow,
  istCalendarDay,
  offerConfig,
  paidFirstChargeTime,
  phoneKey,
  phoneLikePattern,
  planTypeOf,
  rowShowsAuthorisation,
  subscriptionOffer,
  type TrialHistory,
  trialEligibility,
  trialFirstChargeTime,
} from "../_shared/subscription-offer.ts";

const DAY_MS = 86_400_000;
const CFG = offerConfig({});

/** SQL LIKE (`%`, `_`) as a regex, to prove the phone prefilter keeps every stored format. */
function likeMatches(pattern: string, value: string): boolean {
  const source = pattern
    .split("")
    .map((ch) => (ch === "%" ? ".*" : ch === "_" ? "." : ch.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")))
    .join("");
  return new RegExp(`^${source}$`, "s").test(value);
}

function history(overrides: Partial<TrialHistory> = {}): TrialHistory {
  return { userStatus: "none", subscriptions: [], paymentCount: 0, ...overrides };
}

const PHONE_FORMATS = [
  "9818599939",
  "+919818599939",
  "919818599939",
  "09818599939",
  "+91 9818599939",
  "+91 98185 99939",
  "+91-98185-99939",
  " 98185 99939 ",
  "(+91) 981 859 9939",
];

Deno.test("phoneKey: the last 10 digits of every stored format; shorter or empty is null", () => {
  for (const phone of PHONE_FORMATS) assertEquals(phoneKey(phone), "9818599939", phone);
  assertEquals(phoneKey(9818599939), "9818599939");
  assertEquals(phoneKey("00919818599939"), "9818599939");
  for (const phone of ["", "   ", "98185", "981859993", "abc", null, undefined, {}, []]) {
    assertEquals(phoneKey(phone), null, String(phone));
  }
  assert(phoneKey("+919818599938") !== phoneKey("+919818599939"));
});

Deno.test("phoneLikePattern: matches every format of the phone; the key filter drops look-alikes", () => {
  const key = "9818599939";
  const pattern = phoneLikePattern(key);
  assertEquals(pattern, "%9%8%1%8%5%9%9%9%3%9%");
  for (const phone of PHONE_FORMATS) assert(likeMatches(pattern, phone), phone);
  for (const phone of ["9818599938", "8818599939", "98185 9993", ""]) {
    assert(!likeMatches(pattern, phone), phone);
  }
  // A longer number that contains the digits in order passes the prefilter; the key check drops it.
  const lookAlike = "98185999391";
  assert(likeMatches(pattern, lookAlike));
  assert(phoneKey(lookAlike) !== key);
});

Deno.test("rowShowsAuthorisation: activated, cancelled, expired or Cashfree-authorised rows only", () => {
  const neverAuthorised: HistoryRow[] = [
    { status: "pending" },
    { status: "pending", start_date: null, cashfree_status: null },
    { status: "pending", start_date: "", cashfree_status: "" },
    { status: "pending", cashfree_status: "LINK_EXPIRED" },
    { status: "pending", cashfree_status: "INITIALIZED" },
    { status: "pending", cashfree_status: "BANK_APPROVAL_PENDING" },
    {},
  ];
  for (const row of neverAuthorised) assert(!rowShowsAuthorisation(row), JSON.stringify(row));

  const authorised: HistoryRow[] = [
    { status: "pending", start_date: "2026-09-01T05:00:00+00:00" },
    { status: "active" },
    { status: "cancelled" },
    { status: "expired" },
    { status: " Cancelled " },
    ...["ACTIVE", "CUSTOMER_CANCELLED", "CANCELLED", "EXPIRED", "COMPLETED", "CUSTOMER_PAUSED", "ON_HOLD", "CARD_EXPIRED"]
      .map((cashfree_status) => ({ status: "pending", cashfree_status })),
    { status: "pending", cashfree_status: "active" },
  ];
  for (const row of authorised) assert(rowShowsAuthorisation(row), JSON.stringify(row));
});

Deno.test("trialEligibility: new users and never-authorised attempts get the trial", () => {
  const eligible: Array<[string, TrialHistory]> = [
    ["new user", history()],
    ["no status", history({ userStatus: null })],
    ["abandoned checkout", history({ subscriptions: [{ status: "pending" }] })],
    ["auth failed / cancelled in the UPI app", history({ subscriptions: [{ status: "pending", cashfree_status: null }] })],
    ["checkout link expired", history({ subscriptions: [{ status: "pending", cashfree_status: "LINK_EXPIRED" }] })],
    ["several abandoned attempts", history({ subscriptions: [{ status: "pending" }, { status: "pending", cashfree_status: "INITIALIZED" }] })],
  ];
  for (const [name, h] of eligible) {
    assertEquals(trialEligibility(h), { eligible: true, reason: "no_history" }, name);
  }
});

Deno.test("trialEligibility: returning users (prod 907759 shape), same phone, payments and DB errors get the paid offer", () => {
  // Trial 587346 → CUSTOMER_CANCELLED: users.status cancelled, the cancelled row, the ₹3 auth row.
  const cancelledTrial = history({
    userStatus: "cancelled",
    subscriptions: [{
      status: "cancelled",
      cashfree_status: "CUSTOMER_CANCELLED",
      start_date: "2026-09-20T05:00:00+00:00",
    }],
    paymentCount: 1,
  });
  assertEquals(trialEligibility(cancelledTrial), { eligible: false, reason: "user_status" });

  for (const status of ["trial", "active", "cancelled", "expired", " CANCELLED "]) {
    assertEquals(trialEligibility(history({ userStatus: status })), { eligible: false, reason: "user_status" }, status);
  }
  assertEquals(trialEligibility(history({ paymentCount: 1 })), { eligible: false, reason: "payment" });
  const rowOnly: Array<[string, HistoryRow]> = [
    ["cancelled row, users.status out of sync", { status: "cancelled", start_date: "2026-09-20T05:00:00+00:00" }],
    ["expired row", { status: "expired" }],
    ["active row", { status: "active" }],
    ["authorised, activation missed", { status: "pending", cashfree_status: "ACTIVE" }],
    ["paused mandate", { status: "pending", cashfree_status: "CUSTOMER_PAUSED" }],
    // Rows found by phone belong to another user id with the same number (deleted and re-signed up).
    ["same phone, other user", { status: "cancelled", cashfree_status: "CUSTOMER_CANCELLED" }],
  ];
  for (const [name, row] of rowOnly) {
    assertEquals(
      trialEligibility(history({ subscriptions: [{ status: "pending" }, row] })),
      { eligible: false, reason: "subscription" },
      name,
    );
  }
  // Fail closed: a failed history query never hands out a trial.
  assertEquals(trialEligibility(history({ lookupFailed: true })), { eligible: false, reason: "lookup_failed" });
});

Deno.test("offerConfig: defaults 3 / 299 / 1 month / 1 day; garbage falls back", () => {
  assertEquals(CFG, { authAmount: 3, recurringAmount: 299, intervalMonths: 1, trialDays: 1 });
  assertEquals(
    offerConfig({
      subscription_auth_amount: "5",
      subscription_recurring_amount: "399.5",
      subscription_interval_months: "3",
      subscription_trial_days: "7",
    }),
    { authAmount: 5, recurringAmount: 399.5, intervalMonths: 3, trialDays: 7 },
  );
  assertEquals(
    offerConfig({
      subscription_auth_amount: "abc",
      subscription_recurring_amount: "0",
      subscription_interval_months: "0",
      subscription_trial_days: "-1",
    }),
    CFG,
  );
  assertEquals(offerConfig({ subscription_trial_days: "0" }).trialDays, 0);
});

Deno.test("istCalendarDay: the IST day, across the UTC day boundary", () => {
  assertEquals(istCalendarDay(Date.parse("2026-09-30T18:29:59Z")), { y: 2026, m: 9, d: 30 });
  assertEquals(istCalendarDay(Date.parse("2026-09-30T18:30:00Z")), { y: 2026, m: 10, d: 1 });
  assertEquals(istCalendarDay(Date.parse("2026-09-30T19:00:00Z")), { y: 2026, m: 10, d: 1 });
  assertEquals(istCalendarDay(Date.parse("2026-12-31T19:00:00Z")), { y: 2027, m: 1, d: 1 });
});

Deno.test("trialFirstChargeTime: the next IST day at 10:00 IST", () => {
  const rows: Array<[string, string]> = [
    ["2026-09-29T10:00:00Z", "2026-09-30T10:00:00+05:30"],
    ["2026-09-30T18:29:59Z", "2026-10-01T10:00:00+05:30"], // 30 Sep 23:59 IST
    ["2026-09-30T19:00:00Z", "2026-10-02T10:00:00+05:30"], // 1 Oct 00:30 IST
    ["2026-12-31T12:00:00Z", "2027-01-01T10:00:00+05:30"],
    ["2028-02-28T12:00:00Z", "2028-02-29T10:00:00+05:30"],
    ["2027-02-28T12:00:00Z", "2027-03-01T10:00:00+05:30"],
  ];
  for (const [now, expected] of rows) assertEquals(trialFirstChargeTime(Date.parse(now)), expected, now);
});

Deno.test("paidFirstChargeTime: the same IST day a month on at 10:00 IST, clamped to the month", () => {
  const rows: Array<[string, number, string]> = [
    ["2026-09-29T10:00:00Z", 1, "2026-10-29T10:00:00+05:30"],
    ["2026-01-31T06:00:00Z", 1, "2026-02-28T10:00:00+05:30"], // 31 Jan → 28 Feb
    ["2028-01-31T06:00:00Z", 1, "2028-02-29T10:00:00+05:30"], // leap year
    ["2028-02-29T06:00:00Z", 1, "2028-03-29T10:00:00+05:30"],
    ["2026-03-31T06:00:00Z", 1, "2026-04-30T10:00:00+05:30"],
    ["2026-12-31T06:00:00Z", 1, "2027-01-31T10:00:00+05:30"], // into next year
    ["2026-12-15T06:00:00Z", 1, "2027-01-15T10:00:00+05:30"],
    ["2026-11-30T06:00:00Z", 3, "2027-02-28T10:00:00+05:30"],
    ["2026-10-31T06:00:00Z", 12, "2027-10-31T10:00:00+05:30"],
    ["2026-09-30T19:00:00Z", 1, "2026-11-01T10:00:00+05:30"], // 1 Oct IST, not 30 Sep UTC
    ["2026-09-30T18:29:59Z", 1, "2026-10-30T10:00:00+05:30"], // still 30 Sep IST
    ["2026-01-30T19:00:00Z", 1, "2026-02-28T10:00:00+05:30"], // 31 Jan IST
    ["2026-09-29T10:00:00Z", 0, "2026-10-29T10:00:00+05:30"], // never less than a month
  ];
  for (const [now, months, expected] of rows) {
    const firstCharge = paidFirstChargeTime(Date.parse(now), months);
    assertEquals(firstCharge, expected, `${now} +${months}`);
    assert(Date.parse(firstCharge) - Date.parse(now) >= 27 * DAY_MS, `${now} is at least four weeks out`);
  }
});

Deno.test("subscriptionOffer: the ₹3 trial for eligible users, the ₹299 first month otherwise", () => {
  const now = Date.parse("2026-09-29T10:00:00Z");
  assertEquals(subscriptionOffer(true, CFG, now), {
    offer: "trial",
    planType: "trial",
    trialEligible: true,
    authAmount: 3,
    recurringAmount: 299,
    intervalMonths: 1,
    trialDays: 1,
    firstChargeAt: "2026-09-30T10:00:00+05:30",
  });
  assertEquals(subscriptionOffer(false, CFG, now), {
    offer: "paid",
    planType: "monthly",
    trialEligible: false,
    authAmount: 299,
    recurringAmount: 299,
    intervalMonths: 1,
    trialDays: 0,
    firstChargeAt: "2026-10-29T10:00:00+05:30",
  });
  const quarterly = subscriptionOffer(false, offerConfig({ subscription_interval_months: "3", subscription_recurring_amount: "799" }), now);
  assertEquals(quarterly.authAmount, 799);
  assertEquals(quarterly.firstChargeAt, "2026-12-29T10:00:00+05:30");
});

Deno.test("planTypeOf: only monthly is paid; legacy blanks are trial rows", () => {
  for (const value of ["monthly", "MONTHLY", " monthly "]) assertEquals(planTypeOf(value), "monthly", value);
  for (const value of ["trial", "", null, undefined, "yearly", 1]) assertEquals(planTypeOf(value), "trial", String(value));
});

Deno.test("activationFields: a trial row keeps today's activation, with the row's amount", () => {
  const now = Date.parse("2026-09-29T10:00:00Z");
  assertEquals(activationFields({ plan_type: "trial", amount: 3 }, CFG, now), {
    planType: "trial",
    isTrial: true,
    userStatus: "trial",
    authAmount: 3,
    startDate: "2026-09-29T10:00:00.000Z",
    endDate: "2026-09-30T10:00:00.000Z",
    nextBillingDate: "2026-09-30T10:00:00.000Z",
    trialDays: 1,
  });
  const week = activationFields({ plan_type: "trial", amount: 3 }, offerConfig({ subscription_trial_days: "7" }), now);
  assertEquals(week.endDate, "2026-10-06T10:00:00.000Z");
  assertEquals(week.nextBillingDate, "2026-09-30T10:00:00.000Z");
  // Legacy rows: no plan_type, numeric string amount.
  const legacy = activationFields({ amount: "3.00" }, CFG, now);
  assertEquals([legacy.planType, legacy.userStatus, legacy.authAmount], ["trial", "trial", 3]);
  // No row amount: Cashfree's authorised amount, then the trial price.
  assertEquals(activationFields({ plan_type: "trial" }, CFG, now, 2).authAmount, 2);
  assertEquals(activationFields({ plan_type: "trial" }, CFG, now, "2.0").authAmount, 2);
  assertEquals(activationFields({ plan_type: "trial", amount: null }, CFG, now).authAmount, 3);
});

Deno.test("activationFields: a monthly row is active until its first charge, at the row's ₹299", () => {
  const now = Date.parse("2026-09-29T10:02:00Z");
  const row = { plan_type: "monthly", amount: 299, next_billing_date: "2026-10-29T04:30:00+00:00" };
  assertEquals(activationFields(row, CFG, now), {
    planType: "monthly",
    isTrial: false,
    userStatus: "active",
    authAmount: 299,
    startDate: "2026-09-29T10:02:00.000Z",
    endDate: "2026-10-29T04:30:00.000Z",
    nextBillingDate: "2026-10-29T04:30:00.000Z",
    trialDays: 0,
  });
  // Never re-read from app_config: a later price change does not alter this mandate.
  const repriced = offerConfig({ subscription_auth_amount: "5", subscription_recurring_amount: "399" });
  assertEquals(activationFields(row, repriced, now).authAmount, 299);
  assertEquals(activationFields(row, repriced, now, 399).authAmount, 299);
  // Missing or past first charge: recomputed from the activation day.
  for (const next of [undefined, null, "", "garbage", "2026-09-01T04:30:00+00:00"]) {
    const activation = activationFields({ ...row, next_billing_date: next }, CFG, now);
    assertEquals(activation.nextBillingDate, "2026-10-29T04:30:00.000Z", String(next));
    assertEquals(activation.endDate, activation.nextBillingDate);
  }
  // No row amount: Cashfree's authorised amount, then the recurring price (never the ₹3 trial).
  assertEquals(activationFields({ plan_type: "monthly" }, CFG, now, 299).authAmount, 299);
  assertEquals(activationFields({ plan_type: "monthly" }, CFG, now).authAmount, 299);
});

Deno.test("create → activate round trip: the row create writes activates as its own offer", () => {
  const createdAt = Date.parse("2026-01-30T19:00:00Z"); // 31 Jan 00:30 IST
  const activatedAt = createdAt + 3 * 60_000;
  for (const eligible of [true, false]) {
    const offer = subscriptionOffer(eligible, CFG, createdAt);
    const row = {
      plan_type: offer.planType,
      amount: offer.authAmount,
      next_billing_date: new Date(Date.parse(offer.firstChargeAt)).toISOString(),
    };
    const activation = activationFields(row, CFG, activatedAt);
    assertEquals(activation.isTrial, eligible);
    assertEquals(activation.authAmount, eligible ? 3 : 299);
    assertEquals(activation.userStatus, eligible ? "trial" : "active");
    if (!eligible) {
      assertEquals(offer.firstChargeAt, "2026-02-28T10:00:00+05:30");
      assertEquals(activation.nextBillingDate, "2026-02-28T04:30:00.000Z");
      assertEquals(activation.endDate, activation.nextBillingDate);
    }
  }
});
