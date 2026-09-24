import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createServiceClient, type ServiceClient } from "../_shared/supabase-client.ts";
import {
  istDate,
  istMonth,
  mixpanelDate,
  mixpanelInsertId,
  type MixpanelProps,
  parseCashfreeTimeMs,
  type PeopleOps,
  resolveMixpanelToken,
  trackMixpanelEvent,
  updateMixpanelPeople,
} from "../_shared/mixpanel.ts";
import {
  eventTimeFromIso,
  type MetaConversionEvent,
  metaCredentials,
  trackMetaConversion,
} from "../_shared/meta.ts";
import {
  extractAuthPaymentId,
  recordAuthPayment,
} from "../_shared/subscription-payments.ts";
import {
  CANCELLED_STATUSES,
  cancelledProps,
  chargeSkipReason,
  daysSince,
  eventKey,
  failureReasonBucket,
  isTrialExpiryCandidate,
  mandateAuthFailedProps,
  paidProps,
  parsePayment,
  type PaymentFields,
  paymentInsertId,
  parseRefund,
  parseStatusChange,
  pickEventProps,
  refundProps,
  renewalFailedProps,
  renewalNotifiedProps,
  scrubFailureText,
  type ServerEvent,
  shouldApplyStatus,
  type StatusChange,
  statusChangedProps,
  statusSlug,
  type TrialActivation,
  type TrialExpiredReason,
  trialExpiredProps,
  trialExpiredReason,
  trialPaymentId,
  trialPaymentSucceededProps,
  trialPeopleOps,
  webhookRoute,
} from "../_shared/subscription-analytics.ts";
import {
  parseSignatureMode,
  signatureDecision,
  timestampSkewMs,
  verifyCashfreeSignature,
} from "./signature.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type, x-webhook-signature, x-webhook-timestamp",
};

const SUBSCRIPTION_COLUMNS =
  "id, user_id, status, cashfree_subscription_id, cashfree_status, cashfree_status_at, start_date, trial_expired_at, created_at";

type Json = Record<string, unknown>;

type WebhookContext = {
  supabase: ServiceClient;
  config: Record<string, string>;
  token: string;
  type: string;
  eventTimeMs: number;
};

type ServerEventSend = {
  event: ServerEvent;
  props: MixpanelProps;
  key: string;
  /** Used as-is instead of hashing `key` (a valid Cashfree payment id). */
  insertId?: string;
  people?: PeopleOps;
  meta?: MetaConversionEvent;
};

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

/** Fails closed: without app_config the secret is unknown, so Cashfree must retry later. */
async function getConfig(supabase: ServiceClient) {
  const { data, error } = await supabase.from("app_config").select("key, value");
  if (error) throw new Error(`Failed to load app_config: ${error.message}`);
  const config: Record<string, string> = {};
  for (const row of data ?? []) config[row.key] = row.value ?? "";
  return config;
}

async function findSubscription(supabase: ServiceClient, cfSubId: string) {
  if (!cfSubId) return null;

  const { data, error } = await supabase
    .from("subscriptions")
    .select(SUBSCRIPTION_COLUMNS)
    .eq("cashfree_subscription_id", cfSubId)
    .order("created_at", { ascending: false })
    .limit(1)
    .maybeSingle();
  if (error) throw new Error(`Failed to load subscription: ${error.message}`);
  return data;
}

type SubscriptionRow = NonNullable<Awaited<ReturnType<typeof findSubscription>>>;

async function findSubscriptionById(supabase: ServiceClient, id: number) {
  const { data, error } = await supabase
    .from("subscriptions")
    .select(SUBSCRIPTION_COLUMNS)
    .eq("id", id)
    .maybeSingle();
  if (error) throw new Error(`Failed to load subscription: ${error.message}`);
  return data;
}

/** One query, summed in JS (PostgREST aggregates are off by default). */
async function paymentStats(supabase: ServiceClient, userId: number, subscriptionRowId: number) {
  const { data, error } = await supabase
    .from("subscription_payments")
    .select("amount, payment_type, subscription_row_id")
    .eq("user_id", userId);
  if (error) throw new Error(`Failed to load subscription payments: ${error.message}`);

  let lifetimeRecurring = 0;
  let subscriptionRecurring = 0;
  let lifetimeRevenue = 0;
  for (const row of data ?? []) {
    lifetimeRevenue += Number(row.amount) || 0;
    if (row.payment_type !== "recurring") continue;
    lifetimeRecurring++;
    if (Number(row.subscription_row_id) === Number(subscriptionRowId)) subscriptionRecurring++;
  }
  return { lifetimeRecurring, subscriptionRecurring, lifetimeRevenue };
}

function roundMoney(value: number): number {
  return Math.round(value * 100) / 100;
}

/** Mixpanel, people and Meta in parallel; analytics failures are logged and never fail the webhook. */
async function emit(ctx: WebhookContext, userId: number, send: ServerEventSend): Promise<void> {
  try {
    const distinctId = String(userId);
    const insertId = send.insertId ?? await mixpanelInsertId(send.key);
    const tasks: Promise<unknown>[] = [
      trackMixpanelEvent(ctx.token, distinctId, send.event, pickEventProps(send.event, send.props), {
        insertId,
        timeMs: ctx.eventTimeMs,
      }),
    ];
    if (send.people) tasks.push(updateMixpanelPeople(ctx.token, distinctId, send.people));
    if (send.meta) {
      const { datasetId, accessToken } = metaCredentials(ctx.config);
      tasks.push(trackMetaConversion(datasetId, accessToken, send.meta));
    }
    const results = await Promise.allSettled(tasks);
    for (const result of results) {
      if (result.status === "rejected") console.error("cashfree-webhook analytics task failed:", send.event);
    }
  } catch (err) {
    console.error("cashfree-webhook analytics failed:", send.event, err instanceof Error ? err.name : "unknown");
  }
}

async function handleAuthStatus(ctx: WebhookContext, data: Json) {
  const payment = parsePayment(data);
  if (payment.paymentStatus === "SUCCESS") return await activateTrial(ctx, data, payment);
  if (payment.paymentStatus === "FAILED" || payment.paymentStatus === "CANCELLED") {
    return await handleAuthFailed(ctx, payment);
  }
  return { received: true, skipped: `auth_${statusSlug(payment.paymentStatus)}` };
}

async function activateTrial(ctx: WebhookContext, data: Json, payment: PaymentFields) {
  const { supabase, config } = ctx;
  const cfSubId = payment.cfSubId;
  const paymentId = extractAuthPaymentId(data, cfSubId);

  const subscription = await findSubscription(supabase, cfSubId);
  if (!subscription) {
    return { received: true, skipped: "subscription_not_found" };
  }

  const authAmount = parseFloat(config.subscription_auth_amount || "3");
  const trialDays = parseInt(config.subscription_trial_days || "1", 10);
  const recurringAmount = parseFloat(config.subscription_recurring_amount || "299");
  const intervalMonths = parseInt(config.subscription_interval_months || "1", 10);
  const now = new Date();
  const trialEnd = new Date(now.getTime() + trialDays * 24 * 60 * 60 * 1000);
  const nextBilling = new Date(now.getTime() + 24 * 60 * 60 * 1000);
  const subUpdate: Record<string, unknown> = {
    status: "active",
    start_date: now.toISOString(),
    end_date: trialEnd.toISOString(),
    amount: authAmount,
    next_billing_date: nextBilling.toISOString(),
    cashfree_subscription_id: cfSubId || subscription.cashfree_subscription_id,
    autopay_enabled: true,
    cashfree_status: "ACTIVE",
    cashfree_status_at: new Date(ctx.eventTimeMs).toISOString(),
    updated_at: now.toISOString(),
  };
  if (paymentId) subUpdate.last_payment_id = paymentId;

  // Only a pending row flips, so a late AUTH cannot re-activate a cancelled one, and exactly one
  // of this webhook and verify-subscription wins the row and sends trial_payment_succeeded.
  const { data: activatedRows, error: activateError } = await supabase
    .from("subscriptions")
    .update(subUpdate)
    .eq("id", subscription.id)
    .eq("status", "pending")
    .select("id");
  if (activateError) throw new Error(`Failed to activate subscription: ${activateError.message}`);
  const activated = (activatedRows?.length ?? 0) > 0;

  if (activated) {
    const { error: userError } = await supabase
      .from("users")
      .update({ status: "trial", updated_at: now.toISOString() })
      .eq("id", subscription.user_id);
    if (userError) console.error("Failed to set user trial:", subscription.user_id, userError.message);
  }

  const recorded = await recordAuthPayment(supabase, {
    userId: subscription.user_id,
    subscriptionRowId: subscription.id,
    paymentId,
    cfSubId: cfSubId || subscription.cashfree_subscription_id || "",
    amount: authAmount,
    paidAt: now.toISOString(),
  });

  if (activated) {
    const trial: TrialActivation = {
      subscriptionId: payment.subscriptionId,
      auth: payment.auth,
      cfPaymentId: payment.cfPaymentId,
      configAuthAmount: authAmount,
      trialDays,
      recurringAmount,
      intervalMonths,
      activatedVia: "webhook",
    };
    await emit(ctx, subscription.user_id, {
      event: "trial_payment_succeeded",
      props: trialPaymentSucceededProps(trial),
      key: eventKey.trial(subscription.id),
      insertId: paymentInsertId(trialPaymentId(trial)),
      people: trialPeopleOps(now.getTime(), trialDays),
    });
    console.log("Subscription activated via webhook:", payment.subscriptionId, subscription.user_id);
  }
  return { received: true, activated, auth_payment_recorded: recorded };
}

async function handleAuthFailed(ctx: WebhookContext, payment: PaymentFields) {
  const subscription = await findSubscription(ctx.supabase, payment.cfSubId);
  if (!subscription) {
    return { received: true, skipped: "subscription_not_found" };
  }

  const reason = failureReasonBucket(payment.failureText, payment.paymentStatus, "auth");
  await emit(ctx, subscription.user_id, {
    event: "mandate_auth_failed",
    props: mandateAuthFailedProps(payment),
    key: eventKey.mandateAuthFailed(payment, ctx.eventTimeMs),
    people: { set: { last_auth_failed_reason: reason } },
  });
  console.log("mandate_auth_failed tracked:", subscription.user_id, reason, scrubFailureText(payment.failureText));
  return { received: true, mandate_auth_failed: true };
}

async function handlePaymentSuccess(ctx: WebhookContext, data: Json) {
  const { supabase, config } = ctx;
  const payment = parsePayment(data);
  if (payment.paymentStatus !== "SUCCESS") {
    return { received: true, skipped: `payment_${statusSlug(payment.paymentStatus)}` };
  }
  const skipReason = chargeSkipReason(payment);
  if (skipReason) return { received: true, skipped: skipReason };

  const { data: existingPayment, error: existingError } = await supabase
    .from("subscription_payments")
    .select("id")
    .eq("cf_payment_id", payment.cfPaymentId)
    .maybeSingle();
  if (existingError) throw new Error(`Failed to check payment: ${existingError.message}`);
  if (existingPayment) {
    return { received: true, skipped: "duplicate_payment" };
  }

  const subscription = await findSubscription(supabase, payment.cfSubId);
  if (!subscription) {
    console.warn("subscription_paid: subscription not found", payment.cfSubId, payment.subscriptionId);
    return { received: true, skipped: "subscription_not_found" };
  }

  const stats = await paymentStats(supabase, subscription.user_id, subscription.id);
  const renewalNumber = stats.lifetimeRecurring + 1;
  const subscriptionRenewalNumber = stats.subscriptionRecurring + 1;
  const billingMonth = istMonth(parseCashfreeTimeMs(payment.scheduleDate) ?? ctx.eventTimeMs);
  const paidAt = new Date(ctx.eventTimeMs).toISOString();
  const nowIso = new Date().toISOString();

  // Status writes are idempotent and go first: if one fails, the retry is not blocked by the
  // payment row below, which is the once-guard for the event.
  const { error: subscriptionError } = await supabase
    .from("subscriptions")
    .update({
      status: "active",
      amount: payment.amount,
      last_payment_id: payment.cfPaymentId,
      autopay_enabled: true,
      updated_at: nowIso,
    })
    .eq("id", subscription.id);
  if (subscriptionError) throw new Error(`Failed to update subscription: ${subscriptionError.message}`);

  const { error: userError } = await supabase
    .from("users")
    .update({ status: "active", updated_at: nowIso })
    .eq("id", subscription.user_id);
  if (userError) throw new Error(`Failed to update user: ${userError.message}`);

  const { data: inserted, error: insertError } = await supabase
    .from("subscription_payments")
    .upsert({
      user_id: subscription.user_id,
      subscription_row_id: subscription.id,
      cf_payment_id: payment.cfPaymentId,
      cashfree_subscription_id: payment.cfSubId || subscription.cashfree_subscription_id,
      amount: payment.amount,
      payment_type: "recurring",
      billing_month: billingMonth,
      renewal_number: renewalNumber,
      paid_at: paidAt,
    }, { onConflict: "cf_payment_id", ignoreDuplicates: true })
    .select("id");
  if (insertError) throw new Error(`Failed to record payment: ${insertError.message}`);
  if (!inserted?.length) {
    return { received: true, skipped: "duplicate_payment" };
  }

  const distinctId = String(subscription.user_id);
  await emit(ctx, subscription.user_id, {
    event: "subscription_paid",
    props: paidProps(payment, {
      renewalNumber,
      subscriptionRenewalNumber,
      billingMonth,
      daysSinceTrialStart: daysSince(subscription.start_date, ctx.eventTimeMs),
      expectedAmount: parseFloat(config.subscription_recurring_amount || "0"),
    }),
    key: eventKey.paid(payment.cfPaymentId),
    people: {
      set: {
        subscription_status: "active",
        total_renewals: renewalNumber,
        last_billing_month: billingMonth,
        last_renewal_amount: payment.amount,
        last_payment_at: mixpanelDate(ctx.eventTimeMs),
        lifetime_revenue: roundMoney(stats.lifetimeRevenue + payment.amount),
        next_billing_date: istDate(parseCashfreeTimeMs(payment.nextScheduleDate)),
      },
      unset: ["last_payment_failed_reason"],
    },
    meta: {
      eventName: "Subscribe",
      eventTime: eventTimeFromIso(paidAt),
      eventId: payment.cfPaymentId,
      externalId: distinctId,
      customData: {
        currency: "INR",
        value: payment.amount,
        content_type: "subscription",
        renewal_number: renewalNumber,
        billing_month: billingMonth,
        subscription_id: payment.subscriptionId,
      },
    },
  });

  console.log("subscription_paid tracked:", subscription.user_id, renewalNumber);
  return { received: true, subscription_paid: true, renewal_number: renewalNumber };
}

/**
 * PAYMENT_FAILED / PAYMENT_CANCELLED. Never inserts into subscription_payments: its UNIQUE
 * cf_payment_id would block the later successful retry of the same charge.
 */
async function handlePaymentFailed(ctx: WebhookContext, data: Json) {
  const payment = parsePayment(data);
  if (payment.paymentType !== "CHARGE") return { received: true, skipped: "auth_payment" };
  if (!payment.cfPaymentId) return { received: true, skipped: "missing_payment_id" };
  payment.paymentStatus ||= ctx.type === "SUBSCRIPTION_PAYMENT_CANCELLED" ? "CANCELLED" : "FAILED";

  const subscription = await findSubscription(ctx.supabase, payment.cfSubId);
  if (!subscription) {
    return { received: true, skipped: "subscription_not_found" };
  }

  const reason = failureReasonBucket(payment.failureText, payment.paymentStatus, "charge");
  await emit(ctx, subscription.user_id, {
    event: "subscription_renewal_failed",
    props: renewalFailedProps(payment),
    key: eventKey.renewalFailed(ctx.type, payment),
    people: {
      set: {
        last_payment_failed_reason: reason,
        last_payment_failed_at: mixpanelDate(ctx.eventTimeMs),
      },
    },
  });
  console.log(
    "subscription_renewal_failed tracked:",
    subscription.user_id,
    reason,
    payment.retryAttempts,
    scrubFailureText(payment.failureText),
  );
  return { received: true, subscription_renewal_failed: true };
}

async function handlePaymentNotification(ctx: WebhookContext, data: Json) {
  const payment = parsePayment(data);
  const subscription = await findSubscription(ctx.supabase, payment.cfSubId);
  if (!subscription) {
    return { received: true, skipped: "subscription_not_found" };
  }

  await emit(ctx, subscription.user_id, {
    event: "subscription_renewal_notified",
    props: renewalNotifiedProps(payment),
    key: eventKey.renewalNotified(payment),
    people: { set: { next_billing_date: istDate(parseCashfreeTimeMs(payment.scheduleDate)) } },
  });
  return { received: true, subscription_renewal_notified: true };
}

async function handleStatusChanged(ctx: WebhookContext, data: Json) {
  const change = parseStatusChange(data);
  if (!change.status) return { received: true, skipped: "missing_status" };

  const subscription = await findSubscription(ctx.supabase, change.cfSubId);
  if (!subscription) {
    console.warn("subscription status: subscription not found", change.cfSubId, change.subscriptionId);
    return { received: true, skipped: "subscription_not_found" };
  }

  if (CANCELLED_STATUSES.has(change.status)) return await handleCancelled(ctx, change, subscription);
  return await handleOtherStatus(ctx, change, subscription);
}

/** Ready generations that were rendered for this user (not served from the render cache). */
async function countCreatedRingtones(supabase: ServiceClient, userId: number): Promise<number | undefined> {
  const { count, error } = await supabase
    .from("generated_ringtones")
    .select("id", { count: "exact", head: true })
    .eq("user_id", userId)
    .eq("status", "ready")
    .eq("cached", false);
  if (error) {
    console.warn("trial_expired: ringtone count failed", error.message);
    return undefined;
  }
  return count ?? 0;
}

/**
 * trial_expired, once per subscription row: the trial_expired_at compare-and-set is the guard. Only
 * for a row that started a trial and has no recurring charge. A failed claim throws, so the Cashfree
 * retry sends it; the callers run this after their own event, which the retry then skips.
 */
async function trackTrialExpired(
  ctx: WebhookContext,
  subscription: SubscriptionRow,
  reason: TrialExpiredReason,
  subscriptionId: string,
  subscriptionRecurring?: number,
): Promise<boolean> {
  if (!isTrialExpiryCandidate(subscription, reason)) return false;
  const { supabase } = ctx;
  const recurring = subscriptionRecurring ??
    (await paymentStats(supabase, subscription.user_id, subscription.id)).subscriptionRecurring;
  if (recurring > 0) return false;
  const ringtonesCreated = await countCreatedRingtones(supabase, subscription.user_id);

  let claim = supabase
    .from("subscriptions")
    .update({ trial_expired_at: new Date(ctx.eventTimeMs).toISOString() })
    .eq("id", subscription.id)
    .is("trial_expired_at", null)
    .not("start_date", "is", null);
  if (reason !== "cancelled_in_trial") claim = claim.neq("status", "cancelled");
  const { data: claimed, error } = await claim.select("id");
  if (error) throw new Error(`Failed to mark trial expired: ${error.message}`);
  if (!claimed?.length) return false;

  await emit(ctx, subscription.user_id, {
    event: "trial_expired",
    props: trialExpiredProps({
      reason,
      ringtonesCreated,
      daysSinceTrialStart: daysSince(subscription.start_date, ctx.eventTimeMs),
      subscriptionId,
    }),
    key: eventKey.trialExpired(subscription.id),
  });
  console.log("trial_expired tracked:", subscription.user_id, reason, ringtonesCreated);
  return true;
}

async function handleCancelled(ctx: WebhookContext, change: StatusChange, subscription: SubscriptionRow) {
  const { supabase } = ctx;
  if (subscription.status === "cancelled") {
    // A retry after trial_expired failed below. Rows cancelled before this code have no cancel
    // cashfree_status, so they are not reported late.
    if (CANCELLED_STATUSES.has(String(subscription.cashfree_status ?? ""))) {
      await trackTrialExpired(ctx, subscription, "cancelled_in_trial", change.subscriptionId);
    }
    return { received: true, skipped: "already_cancelled" };
  }

  const stats = await paymentStats(supabase, subscription.user_id, subscription.id);
  const nowIso = new Date().toISOString();

  // users.status follows only the user's latest subscription, so cancelling an old mandate after a
  // resubscribe keeps access. Runs before the subscription write so a failed write is retried in full.
  const { data: latest, error: latestError } = await supabase
    .from("subscriptions")
    .select("id")
    .eq("user_id", subscription.user_id)
    .order("created_at", { ascending: false })
    .limit(1)
    .maybeSingle();
  if (latestError) throw new Error(`Failed to load latest subscription: ${latestError.message}`);

  const isLatest = !!latest && Number(latest.id) === Number(subscription.id);
  let downgradedHere = false;
  if (isLatest) {
    const { data: downgraded, error: userError } = await supabase
      .from("users")
      .update({ status: "cancelled", updated_at: nowIso })
      .eq("id", subscription.user_id)
      .in("status", ["trial", "active"])
      .select("id");
    if (userError) throw new Error(`Failed to cancel user: ${userError.message}`);
    downgradedHere = (downgraded?.length ?? 0) > 0;
  }

  const { data: cancelled, error: subscriptionError } = await supabase
    .from("subscriptions")
    .update({
      status: "cancelled",
      autopay_enabled: false,
      cashfree_status: change.status,
      cashfree_status_at: new Date(ctx.eventTimeMs).toISOString(),
      updated_at: nowIso,
    })
    .eq("id", subscription.id)
    .neq("status", "cancelled")
    .select("id");
  if (subscriptionError) {
    throw new Error(`Failed to cancel subscription: ${subscriptionError.message}`);
  }
  if (!cancelled?.length) {
    return { received: true, skipped: "already_cancelled" };
  }

  // user_downgraded is decided only by the delivery that won the row, from the resulting state: a
  // retry after a failed subscription write, or a concurrent delivery, finds the user already
  // downgraded. No throw until the event is sent: a retry would skip as already_cancelled.
  let userDowngraded = downgradedHere;
  if (isLatest && !userDowngraded) {
    const { data: user, error: userReadError } = await supabase
      .from("users")
      .select("status")
      .eq("id", subscription.user_id)
      .maybeSingle();
    if (userReadError) console.warn("subscription cancel: user status read failed", userReadError.message);
    userDowngraded = user?.status === "cancelled";
  }

  const people: PeopleOps = {
    set: {
      autopay_enabled: false,
      cashfree_subscription_status: statusSlug(change.status),
      subscription_status: userDowngraded ? "cancelled" : undefined,
    },
  };
  await emit(ctx, subscription.user_id, {
    event: "subscription_cancelled",
    props: cancelledProps(change, {
      rowStatus: String(subscription.status ?? ""),
      lifetimeRenewals: stats.lifetimeRecurring,
      subscriptionRenewals: stats.subscriptionRecurring,
      userDowngraded,
    }),
    key: eventKey.cancel(subscription.id),
    people,
  });

  console.log("subscription_cancelled tracked:", subscription.user_id, change.status, userDowngraded);

  const trialExpired = await trackTrialExpired(
    ctx,
    subscription,
    "cancelled_in_trial",
    change.subscriptionId,
    stats.subscriptionRecurring,
  );
  return { received: true, subscription_cancelled: true, user_downgraded: userDowngraded, trial_expired: trialExpired };
}

/**
 * EXPIRED, COMPLETED and CARD_EXPIRED also end an unconverted trial. trial_expired is tried on a
 * duplicate delivery too, so a retry after a failed claim still sends it; a stale one never does.
 */
async function handleOtherStatus(ctx: WebhookContext, change: StatusChange, subscription: SubscriptionRow) {
  const result: Record<string, unknown> = await applyCashfreeStatus(ctx, change, subscription);
  const reason = trialExpiredReason(change.status);
  if (reason && result.skipped !== "status_stale") {
    result.trial_expired = await trackTrialExpired(ctx, subscription, reason, change.subscriptionId);
  }
  return result;
}

/**
 * ON_HOLD, PAUSED, ACTIVE, EXPIRED, … are tracked only: access still follows subscriptions.status
 * and users.status, which change only on cancel. Writes cashfree_status with a compare-and-set.
 */
async function applyCashfreeStatus(ctx: WebhookContext, change: StatusChange, subscription: SubscriptionRow) {
  const { supabase } = ctx;
  let row: SubscriptionRow | null = subscription;

  for (let attempt = 0; attempt < 2 && row; attempt++) {
    const previousStatus = (row.cashfree_status as string | null) ?? null;
    const previousAt = (row.cashfree_status_at as string | null) ?? null;
    const decision = shouldApplyStatus(
      previousStatus,
      parseCashfreeTimeMs(previousAt),
      change.status,
      ctx.eventTimeMs,
    );
    if (decision !== "apply") return { received: true, skipped: `status_${decision}` };

    const update = supabase
      .from("subscriptions")
      .update({
        cashfree_status: change.status,
        cashfree_status_at: new Date(ctx.eventTimeMs).toISOString(),
      })
      .eq("id", row.id);
    const guarded = previousAt == null
      ? update.is("cashfree_status_at", null)
      : update.eq("cashfree_status_at", previousAt);
    const { data: updated, error } = await guarded.select("id");
    if (error) throw new Error(`Failed to store Cashfree status: ${error.message}`);

    if (updated?.length) {
      const nextBilling = istDate(parseCashfreeTimeMs(change.nextScheduleDate));
      await emit(ctx, subscription.user_id, {
        event: "subscription_status_changed",
        props: statusChangedProps(change, previousStatus),
        key: eventKey.statusChanged(change.cfSubId, change.status, ctx.eventTimeMs),
        people: {
          set: {
            cashfree_subscription_status: statusSlug(change.status),
            next_billing_date: nextBilling,
          },
        },
      });
      console.log("subscription_status_changed tracked:", subscription.user_id, previousStatus, change.status);
      return { received: true, status_changed: statusSlug(change.status) };
    }

    row = await findSubscriptionById(supabase, row.id);
  }
  return { received: true, skipped: "status_concurrent_update" };
}

/** The payload has no subscription id; the user is resolved through the refunded payment row. */
async function handleRefundStatus(ctx: WebhookContext, data: Json) {
  const refund = parseRefund(data);
  if (!refund.cfPaymentIds.length) return { received: true, skipped: "missing_payment_id" };

  const { data: payment, error } = await ctx.supabase
    .from("subscription_payments")
    .select("user_id, payment_type")
    .in("cf_payment_id", refund.cfPaymentIds)
    .limit(1)
    .maybeSingle();
  if (error) throw new Error(`Failed to load refunded payment: ${error.message}`);
  if (!payment) {
    console.warn("subscription_refund_processed: payment not found", refund.cfPaymentIds);
    return { received: true, skipped: "payment_not_found" };
  }

  await emit(ctx, payment.user_id, {
    event: "subscription_refund_processed",
    props: refundProps(refund, String(payment.payment_type ?? "")),
    key: eventKey.refund(refund),
  });
  return { received: true, subscription_refund_processed: true };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const rawBody = await req.text();
    const signature = req.headers.get("x-webhook-signature") ?? "";
    const timestamp = req.headers.get("x-webhook-timestamp") ?? "";

    const supabase = createServiceClient();
    const config = await getConfig(supabase);
    const secret = config.cashfree_client_secret?.trim() ?? "";
    const mode = parseSignatureMode(config.cashfree_webhook_signature_mode);

    const verdict = await verifyCashfreeSignature(rawBody, timestamp, signature, secret);
    if (!signatureDecision(verdict, mode).accept) {
      console.error("cashfree-webhook signature rejected:", verdict, mode);
      return jsonResponse({ error: "Invalid signature", error_code: verdict }, 401);
    }
    if (verdict !== "valid") {
      console.error("cashfree-webhook accepted without a valid signature (log_only):", verdict);
    }

    let payload: Json;
    try {
      const parsed = JSON.parse(rawBody);
      payload = parsed && typeof parsed === "object" ? parsed as Json : {};
    } catch {
      return jsonResponse({ error: "Invalid JSON", error_code: "invalid_json" }, 400);
    }

    const type = typeof payload.type === "string" ? payload.type : "";
    const data = (payload.data && typeof payload.data === "object" ? payload.data : {}) as Json;
    const ctx: WebhookContext = {
      supabase,
      config,
      token: resolveMixpanelToken(config),
      type,
      eventTimeMs: parseCashfreeTimeMs(payload.event_time) ?? Date.now(),
    };
    console.log(JSON.stringify({
      fn: "cashfree-webhook",
      type,
      verdict,
      mode,
      skew_ms: timestampSkewMs(timestamp),
    }));

    let result: Record<string, unknown>;
    switch (webhookRoute(type)) {
      case "auth_status":
        result = await handleAuthStatus(ctx, data);
        break;
      case "payment_success":
        result = await handlePaymentSuccess(ctx, data);
        break;
      case "payment_failed":
        result = await handlePaymentFailed(ctx, data);
        break;
      case "payment_notification":
        result = await handlePaymentNotification(ctx, data);
        break;
      case "status_changed":
        result = await handleStatusChanged(ctx, data);
        break;
      case "refund_status":
        result = await handleRefundStatus(ctx, data);
        break;
      default:
        result = { received: true, ignored: type };
    }

    return jsonResponse(result);
  } catch (err) {
    console.error("cashfree-webhook error:", err);
    return jsonResponse({ error: "Webhook processing failed", error_code: "processing_failed" }, 500);
  }
});
