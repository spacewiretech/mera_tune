// deno test --allow-read supabase/functions/tests
//
// Fixtures in fixtures/cashfree are the Cashfree 2025-01-01 subscription webhook samples from
// cashfree.com/docs (payments/subscription/webhooks and the API reference), PII included, so the
// tests can prove it never reaches Mixpanel. The docs have no sample for these, so they are the
// closest doc sample with the minimum fields changed: auth_status_success (auth_status_failed with
// SUCCESS / ACTIVE), payment_success_charge (payment_failed as a SUCCESS with 2026-01-01
// subscription_details and retry_attempts 2), status_changed_customer_cancelled,
// status_changed_on_hold and status_changed_expired (the status sample with create-subscription's
// user_id/phone tags).
import { assert, assertEquals, assertMatch } from "jsr:@std/assert@1";
import { cleanProps, mixpanelInsertId, type MixpanelProps } from "../_shared/mixpanel.ts";
import {
  activationPeopleOps,
  CANCELLED_STATUSES,
  cancelledProps,
  cancelPreviousStatus,
  chargeSkipReason,
  daysSince,
  eventKey,
  FAILURE_REASONS,
  failureReasonBucket,
  isTrialExpiryCandidate,
  mandateAuthFailedProps,
  paidProps,
  parsePayment,
  parseRefund,
  parseStatusChange,
  paymentAppFromUpiHandle,
  paymentGroup,
  paymentInsertId,
  pickEventProps,
  refundProps,
  renewalFailedProps,
  renewalNotifiedProps,
  scrubFailureText,
  SERVER_EVENT_PROPS,
  type ServerEvent,
  shouldApplyStatus,
  statusChangedProps,
  statusTransition,
  trialExpiredProps,
  trialExpiredReason,
  trialPaymentId,
  trialPaymentSucceededProps,
  trialPeopleOps,
  upiHandle,
  webhookRoute,
} from "../_shared/subscription-analytics.ts";
import {
  parseSignatureMode,
  type SignatureVerdict,
  signatureDecision,
  timestampSkewMs,
  verifyCashfreeSignature,
} from "../cashfree-webhook/signature.ts";

type Json = Record<string, unknown>;
type Payload = { type: string; event_time: string; data: Json };

const FIXTURES = [
  "auth_status_failed",
  "auth_status_failed_2026",
  "auth_status_success",
  "card_expiry_reminder",
  "controlled_notification_status",
  "payment_cancelled",
  "payment_failed",
  "payment_notification_initiated",
  "payment_success_auth",
  "payment_success_charge",
  "refund_status",
  "status_changed_bank_approval_pending",
  "status_changed_customer_cancelled",
  "status_changed_expired",
  "status_changed_on_hold",
] as const;

/** Values in the fixtures that must never appear in any Mixpanel event. */
const PII = [
  "9910000000",
  "8100000000",
  "9900000000",
  "9090909090",
  "9876500000",
  "john",
  "dummy.com",
  "cashfree.com",
  "tesg refund",
  "[at]",
  "@",
];

async function fixture(name: string): Promise<Payload> {
  return JSON.parse(
    await Deno.readTextFile(new URL(`./fixtures/cashfree/${name}.json`, import.meta.url)),
  ) as Payload;
}

type SentEvent = { event: ServerEvent; props: MixpanelProps };

/**
 * Mirrors the webhook's routing onto the pure prop builders (the handlers add only DB-derived
 * values). Status changes that end a trial also send trial_expired, as for a never-charged trial row.
 */
function eventsFor(payload: Payload): SentEvent[] {
  const data = payload.data;
  switch (webhookRoute(payload.type)) {
    case "auth_status": {
      const payment = parsePayment(data);
      if (payment.paymentStatus === "SUCCESS") {
        return [{
          event: "trial_payment_succeeded",
          props: trialPaymentSucceededProps({
            subscriptionId: payment.subscriptionId,
            auth: payment.auth,
            cfPaymentId: payment.cfPaymentId,
            authAmount: 3,
            isTrial: true,
            trialDays: 1,
            recurringAmount: 299,
            intervalMonths: 1,
            activatedVia: "webhook",
          }),
        }];
      }
      return [{ event: "mandate_auth_failed", props: mandateAuthFailedProps(payment) }];
    }
    case "payment_success": {
      const payment = parsePayment(data);
      if (chargeSkipReason(payment)) return [];
      return [{
        event: "subscription_paid",
        props: paidProps(payment, {
          renewalNumber: 3,
          subscriptionRenewalNumber: 2,
          billingMonth: "2025-08",
          daysSinceTrialStart: 31,
          expectedAmount: 299,
        }),
      }];
    }
    case "payment_failed":
      return [{ event: "subscription_renewal_failed", props: renewalFailedProps(parsePayment(data)) }];
    case "payment_notification":
      return [{ event: "subscription_renewal_notified", props: renewalNotifiedProps(parsePayment(data)) }];
    case "status_changed": {
      const change = parseStatusChange(data);
      const events: SentEvent[] = CANCELLED_STATUSES.has(change.status)
        ? [{
          event: "subscription_cancelled",
          props: cancelledProps(change, {
            rowStatus: "active",
            lifetimeRenewals: 0,
            subscriptionRenewals: 0,
            userDowngraded: true,
          }),
        }]
        : [{ event: "subscription_status_changed", props: statusChangedProps(change, "ACTIVE") }];
      const reason = trialExpiredReason(change.status);
      if (reason) {
        events.push({
          event: "trial_expired",
          props: trialExpiredProps({ reason, ringtonesCreated: 2, daysSinceTrialStart: 1, subscriptionId: change.subscriptionId }),
        });
      }
      return events;
    }
    case "refund_status":
      return [{ event: "subscription_refund_processed", props: refundProps(parseRefund(data), "recurring") }];
    default:
      return [];
  }
}

function sent(event: ServerEvent, props: MixpanelProps) {
  return cleanProps(pickEventProps(event, props));
}

async function sign(secret: string, timestamp: string, body: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(timestamp + body));
  return btoa(String.fromCharCode(...new Uint8Array(mac)));
}

Deno.test("verifyCashfreeSignature: valid, tampered, wrong secret, missing and malformed", async () => {
  const body = JSON.stringify(await fixture("payment_failed"));
  const ts = "1754542485000";
  const signature = await sign("test_secret", ts, body);

  assertEquals(await verifyCashfreeSignature(body, ts, signature, "test_secret"), "valid");
  assertEquals(await verifyCashfreeSignature(body.replace("49585655", "49585656"), ts, signature, "test_secret"), "invalid");
  assertEquals(await verifyCashfreeSignature(body, "1754542485001", signature, "test_secret"), "invalid");
  assertEquals(await verifyCashfreeSignature(body, ts, signature, "other_secret"), "invalid");
  assertEquals(await verifyCashfreeSignature(body, "", "", "test_secret"), "missing_header");
  assertEquals(await verifyCashfreeSignature(body, ts, "", "test_secret"), "missing_header");
  assertEquals(await verifyCashfreeSignature(body, "", signature, "test_secret"), "missing_header");
  assertEquals(await verifyCashfreeSignature(body, ts, signature, ""), "missing_secret");
  assertEquals(await verifyCashfreeSignature(body, "", "", ""), "missing_secret");
  assertEquals(await verifyCashfreeSignature(body, ts, "%%%", "test_secret"), "malformed");
  assertEquals(await verifyCashfreeSignature(body, ts, "abc=def", "test_secret"), "malformed");
  assertEquals(await verifyCashfreeSignature(body, ts, btoa("sixteen byte mac"), "test_secret"), "invalid");
});

Deno.test("signatureDecision: bad signatures are rejected in both modes, missing ones only when enforcing", () => {
  const expected: Record<SignatureVerdict, { enforce: boolean; log_only: boolean }> = {
    valid: { enforce: true, log_only: true },
    invalid: { enforce: false, log_only: false },
    malformed: { enforce: false, log_only: false },
    missing_header: { enforce: false, log_only: true },
    missing_secret: { enforce: false, log_only: true },
  };
  for (const [verdict, modes] of Object.entries(expected) as Array<[SignatureVerdict, typeof expected.valid]>) {
    assertEquals(signatureDecision(verdict, "enforce").accept, modes.enforce, `${verdict} enforce`);
    assertEquals(signatureDecision(verdict, "log_only").accept, modes.log_only, `${verdict} log_only`);
  }
});

Deno.test("parseSignatureMode: only log_only relaxes; missing or unknown enforces", () => {
  assertEquals(parseSignatureMode("log_only"), "log_only");
  assertEquals(parseSignatureMode(" LOG_ONLY "), "log_only");
  for (const value of [undefined, null, "", "enforce", "off", "logonly"]) {
    assertEquals(parseSignatureMode(value), "enforce", String(value));
  }
});

Deno.test("timestampSkewMs: epoch ms and seconds; garbage is null", () => {
  assertEquals(timestampSkewMs("1617695238078", 1617695238078 + 1500), 1500);
  assertEquals(timestampSkewMs("1617695238", 1617695238000 + 2000), 2000);
  assertEquals(timestampSkewMs("", 1), null);
  assertEquals(timestampSkewMs("abc", 1), null);
  assertEquals(timestampSkewMs("-5", 1), null);
});

Deno.test("failureReasonBucket: one row per bucket, first match wins", () => {
  const rows: Array<[string, string]> = [
    ["DEBIT FAILED | Insufficient Funds In Customer (Remitter) Account", "insufficient_funds"],
    ["Insufficient balance", "insufficient_funds"],
    ["Debit limit exceeded", "limit_exceeded"],
    ["Account is frozen", "account_issue"],
    ["Invalid VPA", "account_issue"],
    ["Mandate revoked by payer", "mandate_revoked"],
    ["Mandate paused by customer", "mandate_revoked"],
    ["Subscription is not active", "mandate_inactive"],
    ["Mandate expired", "mandate_inactive"],
    ["Declined by issuer bank", "bank_declined"],
    ["Transaction declined by customer", "user_declined"],
    ["Request expired", "timeout"],
    ["Transaction timed out", "timeout"],
    ["Remitter bank not available", "bank_technical_error"],
    ["Technical issue at NPCI", "bank_technical_error"],
    ["DEBIT HAS BEEN FAILED", "debit_failed"],
    ["xyz", "other"],
  ];
  for (const [failureText, bucket] of rows) {
    assertEquals(failureReasonBucket(failureText, "FAILED", "charge"), bucket, failureText);
  }
  assertEquals(failureReasonBucket(null, "FAILED", "charge"), "unknown");
  assertEquals(failureReasonBucket("  ", "FAILED", "auth"), "unknown");
  assertEquals(failureReasonBucket(null, "CANCELLED", "charge"), "payment_cancelled");
  assertEquals(failureReasonBucket("Insufficient balance", "cancelled", "auth"), "user_cancelled");
  for (const [failureText] of rows) {
    assert((FAILURE_REASONS as readonly string[]).includes(failureReasonBucket(failureText, "FAILED", "auth")));
  }
});

Deno.test("scrubFailureText: no VPA, no digit runs, bounded length", () => {
  const scrubbed = scrubFailureText(`Debit failed for 9910000000@ybl ref 49585655 ${"x ".repeat(100)}`);
  assert(!scrubbed.includes("@"));
  assert(!/\d{4,}/.test(scrubbed));
  assert(scrubbed.length <= 120);
  assertEquals(scrubFailureText(null), "");
});

Deno.test("statusTransition: every row of the transition table", () => {
  const rows: Array<[string | null, string, string, boolean]> = [
    ["ON_HOLD", "ACTIVE", "recovered", true],
    ["CUSTOMER_PAUSED", "ACTIVE", "resumed", true],
    ["PAUSED", "ACTIVE", "resumed", true],
    ["CARD_EXPIRED", "ACTIVE", "resumed", true],
    [null, "ACTIVE", "activated", false],
    ["INITIALIZED", "ACTIVE", "activated", false],
    ["BANK_APPROVAL_PENDING", "ACTIVE", "activated", false],
    ["ACTIVE", "ON_HOLD", "on_hold", false],
    ["ACTIVE", "CUSTOMER_PAUSED", "paused", false],
    ["ACTIVE", "PAUSED", "paused", false],
    ["ACTIVE", "EXPIRED", "expired", false],
    [null, "LINK_EXPIRED", "checkout_expired", false],
    ["ACTIVE", "COMPLETED", "completed", false],
    ["ACTIVE", "CARD_EXPIRED", "card_expired", false],
    [null, "BANK_APPROVAL_PENDING", "bank_approval_pending", false],
    [null, "INITIALIZED", "initialized", false],
    ["ACTIVE", "SOMETHING_NEW", "other", false],
  ];
  for (const [previous, next, transition, isReactivation] of rows) {
    assertEquals(statusTransition(previous, next), { transition, is_reactivation: isReactivation }, `${previous}->${next}`);
  }
});

Deno.test("shouldApplyStatus: stale, duplicate, and same-instant different status", () => {
  const at = Date.parse("2025-08-07T05:01:35Z");
  assertEquals(shouldApplyStatus("ACTIVE", at, "ON_HOLD", at - 1), "stale");
  assertEquals(shouldApplyStatus("ACTIVE", at, "ACTIVE", at + 1000), "duplicate");
  assertEquals(shouldApplyStatus("active", null, "ACTIVE", at), "duplicate");
  assertEquals(shouldApplyStatus("ACTIVE", at, "ON_HOLD", at), "apply");
  assertEquals(shouldApplyStatus(null, null, "ACTIVE", at), "apply");
});

Deno.test("webhookRoute: handled types, and card expiry / controlled / unknown are ignored", () => {
  assertEquals(webhookRoute("SUBSCRIPTION_AUTH_STATUS"), "auth_status");
  assertEquals(webhookRoute("SUBSCRIPTION_PAYMENT_SUCCESS"), "payment_success");
  assertEquals(webhookRoute("SUBSCRIPTION_PAYMENT_FAILED"), "payment_failed");
  assertEquals(webhookRoute("SUBSCRIPTION_PAYMENT_CANCELLED"), "payment_failed");
  assertEquals(webhookRoute("SUBSCRIPTION_PAYMENT_NOTIFICATION_INITIATED"), "payment_notification");
  assertEquals(webhookRoute("SUBSCRIPTION_STATUS_CHANGED"), "status_changed");
  assertEquals(webhookRoute("SUBSCRIPTION_REFUND_STATUS"), "refund_status");
  for (const type of [
    "SUBSCRIPTION_CARD_EXPIRY_REMINDER",
    "SUBSCRIPTION_CONTROLLED_NOTIFICATION_STATUS",
    "SUBSCRIPTION_CONTROLLED_EXECUTION_STATUS",
    "PAYMENT_SUCCESS_WEBHOOK",
    "",
  ]) {
    assertEquals(webhookRoute(type), "ignored", type);
  }
});

Deno.test("fixtures: every event carries allowlisted keys only and no PII", async () => {
  const produced = new Set<string>();
  for (const name of FIXTURES) {
    for (const result of eventsFor(await fixture(name))) {
      produced.add(result.event);
      const props = sent(result.event, result.props);
      const allowed: readonly string[] = SERVER_EVENT_PROPS[result.event];
      for (const key of Object.keys(props)) assert(allowed.includes(key), `${name}: ${key} not allowlisted`);
      const serialized = JSON.stringify(props).toLowerCase();
      for (const value of PII) assert(!serialized.includes(value), `${name} leaks ${value}`);
      if (result.props.upi_handle !== undefined) assertEquals(props.upi_handle, "ybl", name);
    }
  }
  assertEquals(produced, new Set<string>([
    "trial_payment_succeeded",
    "trial_expired",
    "mandate_auth_failed",
    "subscription_paid",
    "subscription_renewal_failed",
    "subscription_renewal_notified",
    "subscription_cancelled",
    "subscription_status_changed",
    "subscription_refund_processed",
  ]));
  assertEquals(produced, new Set(Object.keys(SERVER_EVENT_PROPS)));
});

Deno.test("fixtures: card expiry, controlled and auth-payment PAYMENT_SUCCESS produce no event", async () => {
  for (const name of ["card_expiry_reminder", "controlled_notification_status", "payment_success_auth"]) {
    assertEquals(eventsFor(await fixture(name)), [], name);
  }
  assertEquals(chargeSkipReason(parsePayment((await fixture("payment_success_auth")).data)), "auth_payment");
  assertEquals(chargeSkipReason(parsePayment((await fixture("payment_success_charge")).data)), null);
});

Deno.test("mandate_auth_failed from the docs' AUTH failure (2025-01-01 and 2026-01-01)", async () => {
  for (const name of ["auth_status_failed", "auth_status_failed_2026"]) {
    const payment = parsePayment((await fixture(name)).data);
    assertEquals(sent("mandate_auth_failed", mandateAuthFailedProps(payment)), {
      failure_reason: "debit_failed",
      payment_status: "failed",
      payment_group: "upi",
      upi_handle: "ybl",
      retry_attempts: 0,
      subscription_id: "mozth7smWGCCqPRaSv7",
    }, name);
  }
});

Deno.test("trial_payment_succeeded from AUTH success reads the mandate amount, PSP handle and app", async () => {
  const payment = parsePayment((await fixture("auth_status_success")).data);
  const trial = {
    subscriptionId: payment.subscriptionId,
    auth: payment.auth,
    cfPaymentId: payment.cfPaymentId,
    authAmount: 3,
    isTrial: true,
    trialDays: 1,
    recurringAmount: 299,
    intervalMonths: 1,
    activatedVia: "webhook" as const,
  };
  assertEquals(sent("trial_payment_succeeded", trialPaymentSucceededProps(trial)), {
    subscription_id: "mozth7smWGCCqPRaSv7",
    amount: 2,
    currency: "INR",
    activated_via: "webhook",
    payment_group: "upi",
    upi_handle: "ybl",
    payment_app: "phonepe",
    cf_payment_id: "49988825",
    is_trial: true,
    trial_days: 1,
    recurring_amount: 299,
    interval_months: 1,
  });
  assertEquals(paymentInsertId(trialPaymentId(trial)), "49988825");
  const start = Date.parse("2025-08-07T05:04:23Z");
  const trialPeople = {
    set: {
      subscription_status: "trial",
      trial_started_at: "2025-08-07T05:04:23",
      trial_ends_at: "2025-08-08T05:04:23",
      autopay_enabled: true,
    },
  };
  assertEquals(trialPeopleOps(start, 1), trialPeople);
  assertEquals(activationPeopleOps(trial, start), trialPeople);
});

Deno.test("trial_payment_succeeded for a returning user's paid month: is_trial false, no trial days or trial profile", async () => {
  const payment = parsePayment((await fixture("auth_status_success")).data);
  const paid = {
    subscriptionId: payment.subscriptionId,
    auth: { ...payment.auth, authorization_amount: 299 },
    cfPaymentId: payment.cfPaymentId,
    authAmount: 299,
    isTrial: false,
    // Even if a caller passes the configured trial length, a paid month has none.
    trialDays: 1,
    recurringAmount: 299,
    intervalMonths: 1,
    activatedVia: "webhook" as const,
  };
  const props = sent("trial_payment_succeeded", trialPaymentSucceededProps(paid));
  assertEquals(props.is_trial, false);
  assertEquals(props.trial_days, 0);
  assertEquals(props.amount, 299);
  assertEquals(props.recurring_amount, 299);
  // Without Cashfree's amount, the row's authorisation amount (never app_config's ₹3).
  const { authorization_amount: _dropped, ...authWithoutAmount } = paid.auth;
  assertEquals(trialPaymentSucceededProps({ ...paid, auth: authWithoutAmount }).amount, 299);

  const people = activationPeopleOps(paid, Date.parse("2025-08-07T05:04:23Z"));
  assertEquals(people, { set: { subscription_status: "active", autopay_enabled: true } });
  assert(!("trial_started_at" in (people.set ?? {})));
  assert(!("trial_ends_at" in (people.set ?? {})));
  assert(SERVER_EVENT_PROPS.trial_payment_succeeded.includes("is_trial"));
});

Deno.test("trial_payment_succeeded from app verify: no Cashfree payment id, so the key is hashed", () => {
  const trial = {
    subscriptionId: "mt_42_1754543685000",
    auth: {
      authorization_amount: 3,
      payment_id: "ab-SUBV2ODRFhdJuHlcQYyFw-1",
      payment_method: { upi: { upi_id: "9910000000@okicici" } },
    },
    authAmount: 3,
    isTrial: true,
    trialDays: 1,
    recurringAmount: 299,
    intervalMonths: 1,
    activatedVia: "app_verify" as const,
  };
  const props = sent("trial_payment_succeeded", trialPaymentSucceededProps(trial));
  assertEquals(props.payment_app, "google_pay");
  assertEquals(props.cf_payment_id, undefined);
  assertEquals(paymentInsertId(trialPaymentId(trial)), undefined);
  assertEquals(trialPaymentId({ ...trial, auth: { ...trial.auth, cf_payment_id: 49988825 } }), "49988825");
});

Deno.test("paymentAppFromUpiHandle: PhonePe, Google Pay, Paytm and BHIM handles; banks omitted", () => {
  const rows: Array<[string | undefined, string | undefined]> = [
    ["ybl", "phonepe"],
    ["ibl", "phonepe"],
    ["axl", "phonepe"],
    ["okaxis", "google_pay"],
    ["oksbi", "google_pay"],
    ["okhdfcbank", "google_pay"],
    ["okicici", "google_pay"],
    ["paytm", "paytm"],
    ["ptyes", "paytm"],
    ["ptsbi", "paytm"],
    ["upi", "bhim"],
    ["YBL", "phonepe"],
    ["axisbank", undefined],
    ["hdfcbank", undefined],
    ["icici", undefined],
    ["", undefined],
    [undefined, undefined],
  ];
  for (const [handle, app] of rows) assertEquals(paymentAppFromUpiHandle(handle), app, String(handle));
});

Deno.test("paymentInsertId: a Cashfree id up to 36 alphanumerics and dashes, else undefined", () => {
  assertEquals(paymentInsertId("49988825"), "49988825");
  assertEquals(paymentInsertId(" 49988825 "), "49988825");
  assertEquals(paymentInsertId("ab-SUBV2ODRFhdJuHlcQYyFw-1"), "ab-SUBV2ODRFhdJuHlcQYyFw-1");
  assertEquals(paymentInsertId("a".repeat(36)), "a".repeat(36));
  for (const id of ["", "  ", "a".repeat(37), "CH_123", "12.5", "id:1", "9910000000@ybl", null, undefined]) {
    assertEquals(paymentInsertId(id), undefined, String(id));
  }
});

Deno.test("trialExpiredReason: cancel and terminal mandate states end a trial; ON_HOLD does not", () => {
  assertEquals(trialExpiredReason("CUSTOMER_CANCELLED"), "cancelled_in_trial");
  assertEquals(trialExpiredReason("CANCELLED"), "cancelled_in_trial");
  assertEquals(trialExpiredReason("EXPIRED"), "mandate_expired");
  assertEquals(trialExpiredReason("completed"), "mandate_completed");
  assertEquals(trialExpiredReason("CARD_EXPIRED"), "card_expired");
  for (const status of ["ON_HOLD", "ACTIVE", "PAUSED", "CUSTOMER_PAUSED", "LINK_EXPIRED", "BANK_APPROVAL_PENDING", ""]) {
    assertEquals(trialExpiredReason(status), null, status);
  }
});

Deno.test("isTrialExpiryCandidate: started, not yet reported, and a cancelled row only for the cancel", () => {
  const started = { status: "active", start_date: "2025-08-07T05:04:23+00:00", trial_expired_at: null };
  assert(isTrialExpiryCandidate(started, "cancelled_in_trial"));
  assert(isTrialExpiryCandidate(started, "mandate_expired"));
  assert(!isTrialExpiryCandidate({ ...started, start_date: null }, "cancelled_in_trial"));
  assert(!isTrialExpiryCandidate({ ...started, status: "pending", start_date: null }, "mandate_expired"));
  assert(!isTrialExpiryCandidate({ ...started, trial_expired_at: "2025-08-08T05:04:23+00:00" }, "cancelled_in_trial"));
  assert(isTrialExpiryCandidate({ ...started, status: "cancelled" }, "cancelled_in_trial"));
  assert(!isTrialExpiryCandidate({ ...started, status: "cancelled" }, "mandate_expired"));
  assert(!isTrialExpiryCandidate({ ...started, status: "cancelled" }, "card_expired"));
  // Trial rows (and legacy rows without a plan_type) keep today's behaviour.
  assert(isTrialExpiryCandidate({ ...started, plan_type: "trial" }, "cancelled_in_trial"));
  assert(isTrialExpiryCandidate({ ...started, plan_type: null }, "mandate_expired"));
});

Deno.test("isTrialExpiryCandidate: a paid monthly row never sends trial_expired", () => {
  const monthly = {
    status: "active",
    plan_type: "monthly",
    start_date: "2025-08-07T05:04:23+00:00",
    trial_expired_at: null,
  };
  for (const reason of ["cancelled_in_trial", "mandate_expired", "mandate_completed", "card_expired"] as const) {
    assert(!isTrialExpiryCandidate(monthly, reason), reason);
    assert(!isTrialExpiryCandidate({ ...monthly, status: "cancelled" }, reason), `cancelled ${reason}`);
    assert(!isTrialExpiryCandidate({ ...monthly, plan_type: " MONTHLY " }, reason), `spaced ${reason}`);
  }
});

Deno.test("trial_expired from EXPIRED: reason, counts and the subscription id only", async () => {
  const change = parseStatusChange((await fixture("status_changed_expired")).data);
  const reason = trialExpiredReason(change.status);
  assertEquals(reason, "mandate_expired");
  assertEquals(sent("trial_expired", trialExpiredProps({
    reason: reason!,
    ringtonesCreated: 0,
    daysSinceTrialStart: daysSince("2025-08-07T05:04:23+00:00", Date.parse("2025-09-06T05:00:47Z")),
    subscriptionId: change.subscriptionId,
  })), {
    reason: "mandate_expired",
    ringtones_created: 0,
    days_since_trial_start: 29,
    subscription_id: "mozuyYwUCbWEfJVVRLi",
  });
  assertEquals(
    sent("trial_expired", trialExpiredProps({ reason: "cancelled_in_trial", subscriptionId: "s" })),
    { reason: "cancelled_in_trial", subscription_id: "s" },
  );
});

Deno.test("subscription_paid keeps the existing props and adds retry and mismatch signals", async () => {
  const payment = parsePayment((await fixture("payment_success_charge")).data);
  assertEquals(payment.nextScheduleDate, "2025-09-07T12:00:00");
  const props = sent("subscription_paid", paidProps(payment, {
    renewalNumber: 3,
    subscriptionRenewalNumber: 1,
    billingMonth: "2025-08",
    daysSinceTrialStart: daysSince("2025-07-25T17:07:53+00:00", Date.parse("2025-08-07T05:10:12Z")),
    expectedAmount: 399,
  }));
  assertEquals(props, {
    amount: 399,
    currency: "INR",
    payment_type: "recurring",
    renewal_number: 3,
    billing_month: "2025-08",
    subscription_id: "mozh4iRHSsjre7GkDNz",
    cf_payment_id: "49585655",
    subscription_renewal_number: 1,
    is_first_charge: true,
    retry_attempts: 2,
    is_retry_recovery: true,
    payment_group: "upi",
    upi_handle: "ybl",
    days_since_trial_start: 12,
    amount_mismatch: false,
  });
  const mismatch = paidProps(payment, { renewalNumber: 1, subscriptionRenewalNumber: 1, billingMonth: "", expectedAmount: 299 });
  assertEquals(mismatch.amount_mismatch, true);
  assertEquals(paidProps(payment, { renewalNumber: 1, subscriptionRenewalNumber: 1, billingMonth: "" }).amount_mismatch, undefined);
});

Deno.test("subscription_renewal_failed: bucketed reason for FAILED and CANCELLED charges", async () => {
  assertEquals(sent("subscription_renewal_failed", renewalFailedProps(parsePayment((await fixture("payment_failed")).data))), {
    amount: 399,
    currency: "INR",
    payment_status: "failed",
    failure_reason: "insufficient_funds",
    retry_attempts: 0,
    subscription_id: "mozh4iRHSsjre7GkDNz",
    cf_payment_id: "49585655",
  });
  const cancelled = sent("subscription_renewal_failed", renewalFailedProps(parsePayment((await fixture("payment_cancelled")).data)));
  assertEquals(cancelled.payment_status, "cancelled");
  assertEquals(cancelled.failure_reason, "payment_cancelled");
});

Deno.test("subscription_renewal_notified carries the IST schedule date", async () => {
  assertEquals(sent("subscription_renewal_notified", renewalNotifiedProps(parsePayment((await fixture("payment_notification_initiated")).data))), {
    amount: 399,
    payment_schedule_date: "2025-08-08",
    subscription_id: "moziva9hyjiLtCuGN74",
    cf_payment_id: "49970855",
  });
});

Deno.test("subscription_cancelled keeps the existing props and adds who and from what", async () => {
  const change = parseStatusChange((await fixture("status_changed_customer_cancelled")).data);
  assertEquals(sent("subscription_cancelled", cancelledProps(change, {
    rowStatus: "active",
    lifetimeRenewals: 4,
    subscriptionRenewals: 2,
    userDowngraded: false,
  })), {
    cancellation_status: "customer_cancelled",
    subscription_id: "mozuyYwUCbWEfJVVRLi",
    renewals_before_cancel: 4,
    cancelled_by: "customer",
    previous_status: "active",
    cancelled_during_trial: false,
    user_downgraded: false,
  });
  assertEquals(cancelPreviousStatus("pending", 0), "pending");
  assertEquals(cancelPreviousStatus("active", 0), "trial");
  assertEquals(cancelPreviousStatus("active", 1), "active");
  assertEquals(cancelPreviousStatus("active", 0, "trial"), "trial");
  assertEquals(cancelPreviousStatus("active", 0, null), "trial");
});

Deno.test("subscription_cancelled for a paid monthly row: previous_status active, never cancelled_during_trial", async () => {
  const change = parseStatusChange((await fixture("status_changed_customer_cancelled")).data);
  const props = sent("subscription_cancelled", cancelledProps(change, {
    rowStatus: "active",
    planType: "monthly",
    lifetimeRenewals: 0,
    subscriptionRenewals: 0,
    userDowngraded: true,
  }));
  assertEquals(props.previous_status, "active");
  assertEquals(props.cancelled_during_trial, false);
  assertEquals(cancelPreviousStatus("active", 0, "monthly"), "active");
  assertEquals(cancelPreviousStatus("active", 3, "monthly"), "active");
  // A monthly mandate cancelled before it was authorised is still pending.
  assertEquals(cancelPreviousStatus("pending", 0, "monthly"), "pending");
  // The same never-charged row as a trial is a cancel during the trial.
  const trialProps = cancelledProps(change, {
    rowStatus: "active",
    planType: "trial",
    lifetimeRenewals: 0,
    subscriptionRenewals: 0,
    userDowngraded: true,
  });
  assertEquals(trialProps.cancelled_during_trial, true);
});

Deno.test("subscription_status_changed: on hold with the next schedule date", async () => {
  const change = parseStatusChange((await fixture("status_changed_on_hold")).data);
  assertEquals(sent("subscription_status_changed", statusChangedProps(change, "ACTIVE")), {
    status: "on_hold",
    previous_status: "active",
    transition: "on_hold",
    is_reactivation: false,
    next_schedule_date: "2025-09-08",
    subscription_id: "mozuyYwUCbWEfJVVRLi",
  });
  const pending = parseStatusChange((await fixture("status_changed_bank_approval_pending")).data);
  assertEquals(sent("subscription_status_changed", statusChangedProps(pending, null)), {
    status: "bank_approval_pending",
    transition: "bank_approval_pending",
    is_reactivation: false,
    subscription_id: "mozuyYwUCbWEfJVVRLi",
  });
});

Deno.test("subscription_refund_processed never carries refund_note", async () => {
  const refund = parseRefund((await fixture("refund_status")).data);
  assertEquals(refund.cfPaymentIds, ["49778199", "yCzJxeT2aXDqI"]);
  assertEquals(sent("subscription_refund_processed", refundProps(refund, "recurring")), {
    refund_status: "success",
    refund_amount: 1000,
    currency: "INR",
    refund_speed: "standard",
    original_payment_type: "recurring",
  });
  assertEquals(refundProps(refund, "other").original_payment_type, undefined);
});

Deno.test("paymentGroup and upiHandle: groups, card variants, and handles only", () => {
  assertEquals(paymentGroup({ payment_group: "debit_card" }), "card");
  assertEquals(paymentGroup({ payment_group: null, payment_method: { enach: {} } }), "enach");
  assertEquals(paymentGroup({ payment_group: "net_banking" }), "other");
  assertEquals(paymentGroup({}), undefined);
  assertEquals(upiHandle({ payment_method: { upi: { upi_id: "9910000000@YBL" } } }), "ybl");
  assertEquals(upiHandle({ payment_method: { upi: { upi_id: "9910000000" } } }), undefined);
  assertEquals(upiHandle({ payment_method: { upi: { upi_id: "a@<script>" } } }), undefined);
  assertEquals(upiHandle({ payment_method: null }), undefined);
});

Deno.test("pickEventProps drops keys outside the event allowlist", () => {
  assertEquals(pickEventProps("subscription_refund_processed", { refund_status: "success", refund_note: "Tesg Refund" }), {
    refund_status: "success",
  });
});

Deno.test("eventKey: deterministic, bounded, and hashed to a valid insert id", async () => {
  const payment = parsePayment((await fixture("payment_failed")).data);
  const refund = parseRefund((await fixture("refund_status")).data);
  const keys = [
    eventKey.trial(17),
    eventKey.trialExpired(17),
    eventKey.mandateAuthFailed(payment, 1),
    eventKey.mandateAuthFailed({ ...payment, cfPaymentId: "" }, 1),
    eventKey.paid(payment.cfPaymentId),
    eventKey.renewalFailed("SUBSCRIPTION_PAYMENT_FAILED", payment),
    eventKey.renewalNotified(payment),
    eventKey.cancel(17),
    eventKey.statusChanged("22393526", "ON_HOLD", 1754543685000),
    eventKey.refund(refund),
    eventKey.statusChanged("x".repeat(300), "ON_HOLD", 1),
  ];
  for (const key of keys) {
    assert(key.length <= 200, key);
    assertMatch(await mixpanelInsertId(key), /^[0-9a-f]{32}$/);
  }
  assertEquals(eventKey.renewalFailed("SUBSCRIPTION_PAYMENT_FAILED", payment), "SUBSCRIPTION_PAYMENT_FAILED:49585655:0");
  assertEquals(eventKey.renewalFailed("SUBSCRIPTION_PAYMENT_FAILED", { ...payment, retryAttempts: 1 }), "SUBSCRIPTION_PAYMENT_FAILED:49585655:1");
  assertEquals(eventKey.trialExpired(17), "trial_expired:17");
  assertEquals(eventKey.refund(refund), "refund:SUB_21ebb4bf-e84f-4afa-bb09-07aac433abe4:SUCCESS");
});

Deno.test("daysSince: whole days, undefined when unknown or in the future", () => {
  const event = Date.parse("2025-08-10T00:00:00Z");
  assertEquals(daysSince("2025-08-07T00:00:00+00:00", event), 3);
  assertEquals(daysSince(null, event), undefined);
  assertEquals(daysSince("2025-08-11T00:00:00+00:00", event), undefined);
});
