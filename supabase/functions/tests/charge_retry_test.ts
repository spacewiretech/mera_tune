// deno test --allow-read supabase/functions/tests
//
// Retry rules for failed ₹299 charges (retry-failed-charges/policy.ts): the schedule in IST, the
// failure-reason filter, what the sweep plans, the send gates, the RETRY response checks and config.
import { assert, assertEquals, assertFalse, assertMatch } from "jsr:@std/assert@1";
import { chargeFailureRow } from "../_shared/charge-failures.ts";
import { chargeSkipReason, paidProps, parsePayment } from "../_shared/subscription-analytics.ts";
import {
  addDays,
  type AttemptRow,
  canStillSend,
  cashfreeDay,
  type CfMandate,
  type CfPayment,
  checkRetryResponse,
  classifySendError,
  discover,
  fifthAfter,
  gateAfterFetch,
  gateBeforeFetch,
  idempotencyKey,
  isInsufficientFunds,
  istDay,
  istMidnightMs,
  latestCharge,
  nextCheckMs,
  outcomeDue,
  planAttempts,
  retryBody,
  retryConfig,
  retryDays,
  retryOutcome,
  sendTiming,
} from "../retry-failed-charges/policy.ts";

const HOUR_MS = 3_600_000;
/** An IST wall-clock time as epoch ms. */
const ist = (s: string) => Date.parse(`${s}+05:30`);

// ---- schedule ------------------------------------------------------------------------------

Deno.test("retryDays: +2, +4, then the first 5th after the second attempt", () => {
  assertEquals(retryDays("2026-09-27"), ["2026-09-29", "2026-10-01", "2026-10-05"]);
  assertEquals(retryDays("2026-09-30"), ["2026-10-02", "2026-10-04", "2026-10-05"]);
  // The second attempt on the 5th itself: the third is the next month's 5th.
  assertEquals(retryDays("2026-10-01"), ["2026-10-03", "2026-10-05", "2026-11-05"]);
  assertEquals(retryDays("2026-10-02"), ["2026-10-04", "2026-10-06", "2026-11-05"]);
  assertEquals(retryDays("2026-12-30"), ["2027-01-01", "2027-01-03", "2027-01-05"]);
  assertEquals(retryDays("2026-12-03"), ["2026-12-05", "2026-12-07", "2027-01-05"]);
  assertEquals(retryDays("2027-02-27"), ["2027-03-01", "2027-03-03", "2027-03-05"]);
  assertEquals(retryDays("2028-02-27"), ["2028-02-29", "2028-03-02", "2028-03-05"]);
});

Deno.test("fifthAfter is strictly after the day", () => {
  assertEquals(fifthAfter("2026-10-04"), "2026-10-05");
  assertEquals(fifthAfter("2026-10-05"), "2026-11-05");
  assertEquals(fifthAfter("2026-10-31"), "2026-11-05");
  assertEquals(fifthAfter("2026-12-06"), "2027-01-05");
});

Deno.test("IST days: the day turns at 00:00 IST, not UTC", () => {
  assertEquals(istDay(Date.parse("2026-09-26T18:29:59Z")), "2026-09-26");
  assertEquals(istDay(Date.parse("2026-09-26T18:30:00Z")), "2026-09-27");
  assertEquals(istMidnightMs("2026-09-27"), Date.parse("2026-09-26T18:30:00Z"));
  assertEquals(addDays("2026-02-27", 2), "2026-03-01");
  // Cashfree times: ISO with offset, naive IST (legacy API), bare date.
  assertEquals(cashfreeDay("2026-09-27T00:10:00+05:30"), "2026-09-27");
  assertEquals(cashfreeDay("2026-10-27 00:10:00"), "2026-10-27");
  assertEquals(cashfreeDay("2026-10-27"), "2026-10-27");
  assertEquals(cashfreeDay("2026-09-26T20:00:00Z"), "2026-09-27");
  assertEquals(cashfreeDay(""), null);
  assertEquals(cashfreeDay(null), null);
  assertEquals(cashfreeDay("soon"), null);
});

Deno.test("sendTiming: Cashfree debits 24 h after the request, so a retry for D is sent on D-1 (IST)", () => {
  // Measured 2026-09-30: requested 14:49 IST for 5 Oct, scheduled 1 Oct 14:49 IST.
  const day = "2026-10-01";
  assertEquals(sendTiming(day, ist("2026-09-29T23:59:00")), "early");
  assertEquals(sendTiming(day, ist("2026-09-30T00:00:00")), "now");
  assertEquals(sendTiming(day, ist("2026-09-30T14:49:13")), "now");
  assertEquals(sendTiming(day, ist("2026-09-30T23:49:00")), "now");
  // The debit would land in the last 10 minutes of the day, or after it.
  assertEquals(sendTiming(day, ist("2026-09-30T23:51:00")), "late");
  assertEquals(sendTiming(day, ist("2026-10-01T00:00:00")), "late");
  assert(canStillSend(day, ist("2026-09-29T08:00:00")));
  assertFalse(canStillSend(day, ist("2026-10-01T09:00:00")));
  // Another delay, if Cashfree's changes.
  assertEquals(sendTiming(day, ist("2026-09-30T17:00:00"), 6 * HOUR_MS), "early");
  assertEquals(sendTiming(day, ist("2026-09-30T20:00:00"), 6 * HOUR_MS), "now");
  assertEquals(istMidnightMs(day), ist("2026-10-01T00:00:00"));
});

Deno.test("planAttempts: skips days on or after the regular next charge, and days too close", () => {
  const failedOn = "2026-09-27";
  // Discovered the morning of the failure: all three go.
  assertEquals(planAttempts(failedOn, "2026-10-27", ist("2026-09-27T07:00:00")).map((a) => a.status), [
    "pending",
    "pending",
    "pending",
  ]);
  // The 5th is on the next regular charge: skipped (that charge is the next attempt anyway).
  const onNext = planAttempts(failedOn, "2026-10-05", ist("2026-09-27T07:00:00"));
  assertEquals(onNext[2], { attempt: 3, scheduledFor: "2026-10-05", status: "skipped", reason: "after_next_charge" });
  const afterNext = planAttempts(failedOn, "2026-10-03", ist("2026-09-27T07:00:00"));
  assertEquals(afterNext.map((a) => a.status), ["pending", "pending", "skipped"]);
  // A backlog failure seen three days later: 29 Sep is gone, 1 Oct can still be hit (sent today).
  const late = planAttempts(failedOn, "2026-10-27", ist("2026-09-30T12:00:00"));
  assertEquals(late.map((a) => [a.status, a.reason]), [
    ["skipped", "too_late"],
    ["pending", undefined],
    ["pending", undefined],
  ]);
  // Seen on 4 Oct: only the 5th is left.
  assertEquals(planAttempts(failedOn, "2026-10-27", ist("2026-10-04T12:00:00")).map((a) => a.status), [
    "skipped",
    "skipped",
    "pending",
  ]);
  // Unknown next charge: nothing is skipped for it.
  assertEquals(planAttempts(failedOn, null, ist("2026-09-27T07:00:00")).filter((a) => a.status === "pending").length, 3);
});

// ---- failure reasons -----------------------------------------------------------------------

Deno.test("isInsufficientFunds: only insufficient funds, never a dead or blocked mandate", () => {
  for (
    const yes of [
      "DEBIT FAILED | Insufficient Funds In Customer (Remitter) Account",
      "Insufficient funds",
      "insufficient balance in account",
      "Balance not sufficient",
      "LOW BALANCE",
    ]
  ) assert(isInsufficientFunds(yes), yes);
  for (
    const no of [
      "Notification couldn't be sent as the mandate is already cancelled",
      "Notification couldn't be sent, insufficient funds",
      "Mandate revoked by customer",
      "Mandate is not active | Insufficient funds",
      "Subscription paused by customer",
      "Mandate inactive",
      "Insufficient funds; mandate suspended",
      "Account closed",
      "DEBIT FAILED | Transaction declined by bank",
      "DEBIT FAILED | Remitter bank not available",
      "Mandate expired",
      "",
      "   ",
    ]
  ) assertFalse(isInsufficientFunds(no), no);
  assertFalse(isInsufficientFunds(null));
  assertFalse(isInsufficientFunds(undefined));
  assertFalse(isInsufficientFunds(42));
});

// ---- discovery -----------------------------------------------------------------------------

const ACTIVE: CfMandate = {
  merchantSubscriptionId: "mt_877432_1790408546604",
  status: "ACTIVE",
  nextChargeOn: "2026-10-27",
  recurringAmount: 299,
};

/** The probed production mandate (2026-09-30), PII removed: ₹3 auth, then a failed ₹299 charge. */
const PROBED: CfPayment[] = [
  {
    payment_id: "CH_7509517731840503813",
    cf_payment_id: "1117136245",
    payment_type: "AUTH",
    payment_status: "SUCCESS",
    payment_amount: 3,
    payment_initiated_date: "2026-09-26T13:12:27+05:30",
    payment_schedule_date: null,
    retry_attempts: null,
    failure_details: { failure_reason: "" },
  },
  {
    payment_id: "1353204_355_1790408562302",
    cf_payment_id: "1117138074",
    payment_type: "CHARGE",
    payment_status: "FAILED",
    payment_amount: 299,
    payment_initiated_date: "2026-09-26T13:12:42+05:30",
    payment_schedule_date: "2026-09-27T00:10:00+05:30",
    retry_attempts: null,
    failure_details: { failure_reason: "DEBIT FAILED | Insufficient Funds In Customer (Remitter) Account" },
  },
];

function charge(overrides: Partial<CfPayment>): CfPayment {
  return { ...PROBED[1], ...overrides };
}

Deno.test("latestCharge: newest CHARGE, never the auth payment", () => {
  assertEquals(latestCharge(PROBED)?.payment_id, "1353204_355_1790408562302");
  assertEquals(latestCharge([PROBED[0]]), null);
  const later = charge({ payment_id: "later", payment_initiated_date: "2026-10-26T13:00:00+05:30", payment_status: "SUCCESS" });
  assertEquals(latestCharge([later, ...PROBED])?.payment_id, "later");
  const sameTime = charge({ payment_id: "higher_cf_id", cf_payment_id: "1117138999" });
  assertEquals(latestCharge([...PROBED, sameTime])?.payment_id, "higher_cf_id");
});

Deno.test("discover: plans the probed failure; dates in IST from the charge's schedule date", () => {
  const found = discover(ACTIVE, PROBED, "2026-09-26", ist("2026-09-27T07:00:00"));
  assertEquals(found.kind, "plan");
  if (found.kind !== "plan") return;
  assertEquals(found.failedOn, "2026-09-27");
  assertEquals(found.paymentId, "1353204_355_1790408562302");
  assertEquals(found.cfPaymentId, "1117138074");
  assertEquals(found.amount, 299);
  assertEquals(found.attempts.map((a) => a.scheduledFor), ["2026-09-29", "2026-10-01", "2026-10-05"]);
});

Deno.test("discover: everything that is not a fresh insufficient-funds failure", () => {
  const now = ist("2026-09-27T07:00:00");
  const outcome = (m: CfMandate, p: CfPayment[], since = "2026-09-01") => {
    const d = discover(m, p, since, now);
    return d.kind === "plan" ? "plan" : d.outcome;
  };
  assertEquals(outcome({ ...ACTIVE, status: "CUSTOMER_PAUSED" }, PROBED), "mandate_customer_paused");
  assertEquals(outcome({ ...ACTIVE, status: "ON_HOLD" }, PROBED), "mandate_on_hold");
  assertEquals(outcome({ ...ACTIVE, status: "CUSTOMER_CANCELLED" }, PROBED), "mandate_customer_cancelled");
  assertEquals(outcome(ACTIVE, [PROBED[0]]), "no_charge");
  assertEquals(outcome(ACTIVE, [charge({ payment_status: "SUCCESS" })]), "latest_paid");
  assertEquals(outcome(ACTIVE, [charge({ payment_status: "INITIALIZED" })]), "charge_pending");
  assertEquals(outcome(ACTIVE, [charge({ payment_status: "PENDING" })]), "charge_pending");
  // Cashfree creates the next regular charge about 1.5 days ahead (seen 2026-09-29 11:15 IST for
  // 2026-10-01): that charge is the next attempt, the failure before it is not retried.
  const upcoming = charge({
    payment_id: "next",
    payment_status: "INITIALIZED",
    payment_initiated_date: "2026-09-27T06:00:00+05:30",
    payment_schedule_date: "2026-09-28T00:10:00+05:30",
    failure_details: { failure_reason: "" },
  });
  assertEquals(outcome(ACTIVE, [...PROBED, upcoming]), "next_charge_upcoming");
  assertEquals(outcome(ACTIVE, [charge({ payment_status: "CANCELLED" })]), "charge_cancelled");
  assertEquals(
    outcome(ACTIVE, [charge({ failure_details: { failure_reason: "Notification couldn't be sent, mandate already cancelled" } })]),
    "not_insufficient_funds",
  );
  assertEquals(outcome(ACTIVE, [charge({ retry_attempts: 1 })]), "foreign_retry");
  assertEquals(outcome(ACTIVE, [charge({ payment_id: "" })]), "missing_payment_id");
  assertEquals(outcome(ACTIVE, [charge({ payment_schedule_date: null, payment_initiated_date: null })]), "missing_failure_date");
  // Before the start date: backlog is opt-in.
  assertEquals(outcome(ACTIVE, PROBED, "2026-09-28"), "before_start_date");
  assertEquals(outcome(ACTIVE, PROBED, "2026-09-27"), "plan");
  // A failure older than the paid charge after it is not the latest charge.
  const paidLater = charge({ payment_id: "paid", payment_status: "SUCCESS", payment_initiated_date: "2026-09-28T00:10:00+05:30" });
  assertEquals(outcome(ACTIVE, [...PROBED, paidLater]), "latest_paid");
  // Every attempt already impossible: nothing to plan.
  assertEquals(discover(ACTIVE, PROBED, "2026-09-01", ist("2026-10-05T00:00:00")).kind, "none");
});

Deno.test("nextCheckMs: pending in 3 h, paused in 3 days, else 22:00 IST on the next regular charge day", () => {
  const now = ist("2026-09-27T07:00:00");
  assertEquals(nextCheckMs("charge_pending", "2026-10-27", now), now + 3 * HOUR_MS);
  assertEquals(nextCheckMs("mandate_customer_paused", "2026-10-27", now), now + 72 * HOUR_MS);
  assertEquals(nextCheckMs("latest_paid", "2026-10-27", now), ist("2026-10-27T22:00:00"));
  assertEquals(nextCheckMs("planned", "2026-10-27", now), ist("2026-10-27T22:00:00"));
  assertEquals(nextCheckMs("next_charge_upcoming", "2026-09-28", now), ist("2026-09-28T22:00:00"));
  assertEquals(nextCheckMs("mandate_customer_cancelled", "2026-10-27", now), ist("2026-10-27T22:00:00"));
  assertEquals(nextCheckMs("latest_paid", null, now), now + 7 * 24 * HOUR_MS);
  assertEquals(nextCheckMs("latest_paid", "2026-10-27", now, 6 * HOUR_MS), ist("2026-10-27T06:00:00"));
});

// ---- sending -------------------------------------------------------------------------------

function row(attempt: number, status: AttemptRow["status"], extra: Partial<AttemptRow> = {}): AttemptRow {
  const days = retryDays("2026-09-27");
  return { id: attempt, attempt, scheduled_for: days[attempt - 1], status, failed_payment_id: "root_1", ...extra };
}

Deno.test("gateBeforeFetch: the chain goes one attempt at a time, the day before each, retrying the latest failed charge", () => {
  const now = ist("2026-09-28T07:00:00"); // the day before attempt 1 (29 Sep)
  const chain = [row(1, "pending"), row(2, "pending"), row(3, "pending")];
  assertEquals(gateBeforeFetch(chain[0], chain, false, now), { action: "send", retryOf: "root_1" });
  assertEquals(gateBeforeFetch(chain[1], chain, false, now), { action: "wait" });
  // Planned on the failure day: attempt 1 waits for 28 Sep.
  assertEquals(gateBeforeFetch(chain[0], chain, false, ist("2026-09-27T23:00:00")), { action: "wait" });

  const afterFail = [row(1, "failed", { retry_payment_id: "retry_1" }), row(2, "pending"), row(3, "pending")];
  // Attempt 1 failed on 29 Sep: attempt 2 (1 Oct) goes on 30 Sep.
  assertEquals(gateBeforeFetch(afterFail[1], afterFail, false, ist("2026-09-29T22:30:00")), { action: "wait" });
  assertEquals(gateBeforeFetch(afterFail[1], afterFail, false, ist("2026-09-30T08:00:00")), { action: "send", retryOf: "retry_1" });

  const day2 = ist("2026-09-30T08:00:00");
  const afterSkip = [row(1, "skipped"), row(2, "pending"), row(3, "pending")];
  assertEquals(gateBeforeFetch(afterSkip[1], afterSkip, false, day2), { action: "send", retryOf: "root_1" });
  const afterReject = [row(1, "rejected"), row(2, "pending"), row(3, "pending")];
  assertEquals(gateBeforeFetch(afterReject[1], afterReject, false, day2), { action: "send", retryOf: "root_1" });

  const afterRequested = [row(1, "requested", { retry_payment_id: "retry_1" }), row(2, "pending")];
  assertEquals(gateBeforeFetch(afterRequested[1], afterRequested, false, now), { action: "wait" });
  const afterSuccess = [row(1, "succeeded"), row(2, "pending")];
  assertEquals(gateBeforeFetch(afterSuccess[1], afterSuccess, false, now), { action: "end", reason: "recovered" });
  const afterEnded = [row(1, "ended"), row(2, "pending")];
  assertEquals(gateBeforeFetch(afterEnded[1], afterEnded, false, now), { action: "end", reason: "chain_ended" });

  // Paid since the failure (a regular charge or a resubscribe): the chain ends.
  assertEquals(gateBeforeFetch(chain[0], chain, true, now), { action: "end", reason: "paid_since_failure" });
  // Its day can no longer be hit, even while the previous attempt is still open.
  assertEquals(gateBeforeFetch(afterRequested[1], afterRequested, false, ist("2026-10-01T00:30:00")), {
    action: "skip",
    reason: "too_late",
  });
});

Deno.test("gateAfterFetch: the mandate must be ACTIVE, and the day before its next regular charge", () => {
  const a = row(3, "pending"); // 2026-10-05
  assertEquals(gateAfterFetch(a, ACTIVE), null);
  assertEquals(gateAfterFetch(a, { ...ACTIVE, status: "CUSTOMER_PAUSED" }), { action: "end", reason: "mandate_customer_paused" });
  assertEquals(gateAfterFetch(a, { ...ACTIVE, status: "CANCELLED" }), { action: "end", reason: "mandate_cancelled" });
  assertEquals(gateAfterFetch(a, { ...ACTIVE, nextChargeOn: "2026-10-05" }), { action: "skip", reason: "after_next_charge" });
  assertEquals(gateAfterFetch(a, { ...ACTIVE, nextChargeOn: "2026-10-06" }), null);
});

Deno.test("retryBody: RETRY with only the date, no amount", () => {
  assertEquals(retryBody("mt_1_2", "1353204_355_1790408562302", "2026-10-05"), {
    subscription_id: "mt_1_2",
    payment_id: "1353204_355_1790408562302",
    action: "RETRY",
    action_details: { next_scheduled_time: "2026-10-05T00:00:00+05:30" },
  });
});

Deno.test("idempotencyKey: per failed charge and attempt, stable, UUID-shaped", async () => {
  const k1 = await idempotencyKey("1353204_355_1790408562302", 1);
  assertMatch(k1, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  assertEquals(k1, await idempotencyKey("1353204_355_1790408562302", 1));
  assert(k1 !== await idempotencyKey("1353204_355_1790408562302", 2));
  assert(k1 !== await idempotencyKey("1353204_355_1790408562303", 1));
});

Deno.test("checkRetryResponse: a new charge, for the plan amount, on the day asked for", () => {
  const expected = { retriedPaymentId: "1353204_355_1790408562302", day: "2026-10-05", amount: 299 };
  const good: CfPayment = {
    payment_id: "1353204_355_1790999999999",
    cf_payment_id: "1200000001",
    payment_status: "INITIALIZED",
    payment_amount: 299,
    payment_schedule_date: "2026-10-05T00:00:00+05:30",
    retry_attempts: 1,
  };
  assertEquals(checkRetryResponse(good, expected), {
    paymentId: "1353204_355_1790999999999",
    cfPaymentId: "1200000001",
    amount: 299,
    scheduledFor: "2026-10-05",
    retryNumber: 1,
    mismatches: [],
  });
  assertEquals(checkRetryResponse({ ...good, payment_amount: 3 }, expected).mismatches, ["amount"]);
  assertEquals(checkRetryResponse({ ...good, payment_amount: "299.00" }, expected).mismatches, []);
  assertEquals(checkRetryResponse({ ...good, payment_id: expected.retriedPaymentId }, expected).mismatches, ["same_payment_id"]);
  assertEquals(checkRetryResponse({ ...good, payment_id: "" }, expected).mismatches, ["missing_payment_id"]);
  assertEquals(checkRetryResponse({ ...good, payment_schedule_date: "2026-10-06T00:00:00+05:30" }, expected).mismatches, [
    "scheduled_date",
  ]);
  assertEquals(checkRetryResponse(good, { ...expected, amount: null }).mismatches, ["amount"]);
  assertEquals(checkRetryResponse({}, expected).mismatches, ["missing_payment_id", "amount", "scheduled_date"]);
});

Deno.test("classifySendError: 429 stops the run, 5xx / 409 / network are resent, other 4xx are rejections", () => {
  assertEquals(classifySendError(429), "throttled");
  assertEquals(classifySendError(500), "transient");
  assertEquals(classifySendError(503), "transient");
  assertEquals(classifySendError(409), "transient");
  assertEquals(classifySendError(0), "transient");
  assertEquals(classifySendError(400), "rejected");
  assertEquals(classifySendError(404), "rejected");
  assertEquals(classifySendError(422), "rejected");
});

Deno.test("retryOutcome and outcomeDue", () => {
  assertEquals(retryOutcome(charge({ payment_status: "SUCCESS" })), { status: "succeeded" });
  assertEquals(retryOutcome(charge({})), { status: "failed", reason: "insufficient_funds", chainContinues: true });
  assertEquals(retryOutcome(charge({ failure_details: { failure_reason: "Mandate revoked" } })), {
    status: "failed",
    reason: "not_insufficient_funds",
    chainContinues: false,
  });
  assertEquals(retryOutcome(charge({ payment_status: "CANCELLED" })), { status: "failed", reason: "charge_cancelled", chainContinues: false });
  assertEquals(retryOutcome(charge({ payment_status: "INITIALIZED" })), { status: "pending" });
  // Failures settle late in the day: read from 22:00 IST by default.
  assertFalse(outcomeDue("2026-10-05", ist("2026-10-05T21:59:59")));
  assert(outcomeDue("2026-10-05", ist("2026-10-05T22:00:00")));
  assert(outcomeDue("2026-10-05", ist("2026-10-05T06:00:00"), 6 * HOUR_MS));
});

// ---- config --------------------------------------------------------------------------------

Deno.test("retryConfig: ships off; the body can only make a run safer", () => {
  assertEquals(retryConfig({}).mode, "off");
  assertEquals(retryConfig({ charge_retry_mode: "LIVE" }).mode, "live");
  assertEquals(retryConfig({ charge_retry_mode: "on" }).mode, "off");
  assertEquals(retryConfig({ charge_retry_mode: "dry_run" }).mode, "dry_run");
  assertEquals(retryConfig({ charge_retry_mode: "live" }, { dry_run: true }).mode, "dry_run");
  assertEquals(retryConfig({ charge_retry_mode: "off" }, { dry_run: false, mode: "live" }).mode, "off");
  assertEquals(retryConfig({ charge_retry_mode: "dry_run" }, { dry_run: false }).mode, "dry_run");

  assertEquals(retryConfig({ charge_retry_only: "mt_877432_1790408546604, 359414437 ,junk,mt_x" }).only, [
    "mt_877432_1790408546604",
    "359414437",
  ]);
  // A body list narrows the configured one and never widens it.
  const cfg = { charge_retry_mode: "live", charge_retry_only: "mt_1_1,mt_2_2" };
  assertEquals(retryConfig(cfg, { only: ["mt_2_2", "mt_3_3"] }).only, ["mt_2_2"]);
  assertEquals(retryConfig(cfg, { only: ["mt_3_3"] }).mode, "off");
  assertEquals(retryConfig({ charge_retry_mode: "live" }, { only: ["mt_3_3"] }).only, ["mt_3_3"]);

  assertEquals(retryConfig({ charge_retry_failures_since: "2026-10-01" }).failuresSince, "2026-10-01");
  assertEquals(retryConfig({ charge_retry_failures_since: "" }).failuresSince, null);
  assertEquals(retryConfig({ charge_retry_failures_since: "2026-13-40" }).failuresSince, null);
  assertEquals(retryConfig({ charge_retry_failures_since: "1 Oct" }).failuresSince, null);

  const d = retryConfig({});
  assertEquals([d.concurrency, d.maxChecksPerRun, d.maxSendsPerRun, d.minIntervalMs, d.lookupIntervalMs], [3, 150, 300, 250, 1100]);
  assertEquals(d.timing, { sendLeadMs: 24 * HOUR_MS, outcomeDelayMs: 22 * HOUR_MS });
  assertEquals(retryConfig({ charge_retry_send_lead_hours: "25", charge_retry_check_after_hours: "20" }).timing, {
    sendLeadMs: 25 * HOUR_MS,
    outcomeDelayMs: 20 * HOUR_MS,
  });
  assertEquals(retryConfig({ charge_retry_send_lead_hours: "0" }).timing.sendLeadMs, 24 * HOUR_MS);
  assert(d.timeBudgetMs < 150_000);
  assertEquals(retryConfig({ charge_retry_max_sends_per_run: "-5" }).maxSendsPerRun, 300);
  assertEquals(retryConfig({ charge_retry_max_sends_per_run: "0" }).maxSendsPerRun, 0);
});

// ---- webhook: failures recorded for the job, and a successful retry recorded like any charge ----

async function fixture(name: string): Promise<{ type: string; event_time: string; data: Record<string, unknown> }> {
  return JSON.parse(await Deno.readTextFile(new URL(`./fixtures/cashfree/${name}.json`, import.meta.url)));
}

Deno.test("chargeFailureRow: the ids the RETRY call needs, scrubbed text, IST day", async () => {
  const failed = await fixture("payment_failed");
  const eventMs = Date.parse(failed.event_time);
  const row = chargeFailureRow(failed.data, eventMs, { id: 7, user_id: 42 });
  assertEquals(row, {
    payment_id: "ab-SUBV2ODRIeYlFEhMHfS0M-1",
    cf_payment_id: "49585655",
    merchant_subscription_id: "mozh4iRHSsjre7GkDNz",
    cf_subscription_id: "22393526",
    subscription_row_id: 7,
    user_id: 42,
    payment_status: "FAILED",
    failure_reason: "DEBIT FAILED | Insufficient Funds In Customer (Remitter) Account",
    failure_bucket: "insufficient_funds",
    retry_attempts: 0,
    amount: 399,
    scheduled_on: "2025-08-07",
    failed_at: new Date(eventMs).toISOString(),
  });
  assert(isInsufficientFunds(row!.failure_reason));
  // No VPA or phone ever lands in the row.
  assertFalse(JSON.stringify(row).includes("@"));
  assertFalse(JSON.stringify(row).includes("9910000000"));

  const cancelled = await fixture("payment_cancelled");
  const c = chargeFailureRow(cancelled.data, Date.parse(cancelled.event_time), { id: 7, user_id: 42 });
  assertEquals(c?.payment_status, "CANCELLED");
  assertEquals(c?.failure_bucket, "payment_cancelled");

  // Auth payments and payloads without the merchant id are not recorded.
  assertEquals(chargeFailureRow({ ...failed.data, payment_type: "AUTH" }, eventMs, { id: 7, user_id: 42 }), null);
  assertEquals(chargeFailureRow({ ...failed.data, subscription_id: "" }, eventMs, { id: 7, user_id: 42 }), null);
  assertEquals(chargeFailureRow({ ...failed.data, subscription_id: "22393526" }, eventMs, { id: 7, user_id: 42 }), null);
  assertEquals(chargeFailureRow({ ...failed.data, payment_id: "" }, eventMs, { id: 7, user_id: 42 }), null);
});

Deno.test("a successful retry arrives as an ordinary charge: recorded, subscription_paid as a recovery", async () => {
  // The RETRY response is a new charge with its own ids and retry_attempts 1; its success webhook
  // takes the same path as a regular ₹299 debit (subscription_payments row, users.status active).
  const success = await fixture("payment_success_charge");
  const retrySuccess = {
    ...success.data,
    payment_id: "1353204_355_1790999999999",
    cf_payment_id: "1200000001",
    payment_amount: 299,
    retry_attempts: 1,
  };
  const p = parsePayment(retrySuccess);
  assertEquals(chargeSkipReason(p), null);
  assertEquals(p.cfPaymentId, "1200000001");
  const props = paidProps(p, { renewalNumber: 1, subscriptionRenewalNumber: 1, billingMonth: "2026-10", expectedAmount: 299 });
  assertEquals(props.amount, 299);
  assertEquals(props.is_retry_recovery, true);
  assertEquals(props.retry_attempts, 1);
  assertEquals(props.amount_mismatch, false);
});
