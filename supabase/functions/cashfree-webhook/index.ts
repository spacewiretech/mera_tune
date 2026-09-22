import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";
import {
  billingMonthFromDate,
  setMixpanelPeople,
  trackMixpanelEvent,
} from "../_shared/mixpanel.ts";
import {
  eventTimeFromIso,
  metaCredentials,
  trackMetaConversion,
} from "../_shared/meta.ts";
import {
  extractAuthPaymentId,
  recordAuthPayment,
} from "../_shared/subscription-payments.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type, x-webhook-signature, x-webhook-timestamp",
};

const CANCELLED_STATUSES = new Set([
  "CUSTOMER_CANCELLED",
  "CANCELLED",
]);

type SupabaseClient = ReturnType<typeof createClient>;

async function getConfig(supabase: SupabaseClient) {
  const { data } = await supabase.from("app_config").select("key, value");
  const config: Record<string, string> = {};
  for (const row of data ?? []) config[row.key] = row.value ?? "";
  return config;
}

function mixpanelToken(config: Record<string, string>): string {
  return Deno.env.get("MIXPANEL_TOKEN")?.trim() ?? config.mixpanel_token?.trim() ?? "";
}

async function verifySignature(
  rawBody: string,
  timestamp: string,
  signature: string,
  secret: string,
): Promise<boolean> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signed = await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(timestamp + rawBody),
  );
  const computed = btoa(String.fromCharCode(...new Uint8Array(signed)));
  return computed === signature;
}

async function findSubscription(
  supabase: SupabaseClient,
  cfSubId: string,
) {
  if (!cfSubId) return null;

  const { data } = await supabase
    .from("subscriptions")
    .select("id, user_id, status, cashfree_subscription_id")
    .eq("cashfree_subscription_id", cfSubId)
    .order("created_at", { ascending: false })
    .limit(1)
    .maybeSingle();

  return data;
}

async function handleAuthStatus(
  supabase: SupabaseClient,
  config: Record<string, string>,
  data: Record<string, unknown>,
) {
  const paymentStatus = (data.payment_status as string)?.toUpperCase();
  const subscriptionId = data.subscription_id as string;
  const cfSubId = data.cf_subscription_id?.toString() ?? "";
  const paymentId = extractAuthPaymentId(data, cfSubId);

  if (paymentStatus !== "SUCCESS") {
    return { received: true, skipped: true };
  }

  const subscription = await findSubscription(supabase, cfSubId);
  if (!subscription) {
    return { received: true };
  }

  const authAmount = parseFloat(config.subscription_auth_amount || "3");
  const trialDays = parseInt(config.subscription_trial_days || "1", 10);
  const now = new Date();
  const alreadyActive = subscription.status === "active";

  if (!alreadyActive) {
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
      updated_at: now.toISOString(),
    };
    if (paymentId) subUpdate.last_payment_id = paymentId;

    await supabase
      .from("subscriptions")
      .update(subUpdate)
      .eq("id", subscription.id);

    await supabase
      .from("users")
      .update({ status: "trial", updated_at: now.toISOString() })
      .eq("id", subscription.user_id);
  }

  const recorded = await recordAuthPayment(supabase, {
    userId: subscription.user_id,
    subscriptionRowId: subscription.id,
    paymentId,
    cfSubId: cfSubId || subscription.cashfree_subscription_id || "",
    amount: authAmount,
    paidAt: now.toISOString(),
  });

  if (!alreadyActive) {
    console.log("Subscription activated via webhook:", subscriptionId, subscription.user_id);
  }
  return { received: true, activated: !alreadyActive, auth_payment_recorded: recorded };
}

async function handlePaymentSuccess(
  supabase: SupabaseClient,
  config: Record<string, string>,
  data: Record<string, unknown>,
  eventTime: string,
) {
  const paymentStatus = (data.payment_status as string)?.toUpperCase();
  const paymentType = (data.payment_type as string)?.toUpperCase();
  const paymentAmount = Number(data.payment_amount ?? 0);
  const recurringAmount = parseFloat(config.subscription_recurring_amount || "299");
  const cfPaymentId = data.cf_payment_id?.toString() ?? data.payment_id?.toString() ?? "";
  const cfSubId = data.cf_subscription_id?.toString() ?? "";
  const merchantSubscriptionId = data.subscription_id as string | undefined;
  const token = mixpanelToken(config);

  if (paymentStatus !== "SUCCESS" || paymentType !== "CHARGE") {
    return { received: true, skipped: true };
  }

  if (paymentAmount < recurringAmount) {
    return { received: true, skipped: true, reason: "below_recurring_amount" };
  }

  if (!cfPaymentId) {
    return { received: true, skipped: true, reason: "missing_payment_id" };
  }

  const { data: existingPayment } = await supabase
    .from("subscription_payments")
    .select("id")
    .eq("cf_payment_id", cfPaymentId)
    .maybeSingle();

  if (existingPayment) {
    return { received: true, skipped: true, reason: "duplicate_payment" };
  }

  const subscription = await findSubscription(supabase, cfSubId);
  if (!subscription) {
    console.warn("subscription_paid: subscription not found", cfSubId, merchantSubscriptionId);
    return { received: true, skipped: true, reason: "subscription_not_found" };
  }

  const { count: priorRecurringCount } = await supabase
    .from("subscription_payments")
    .select("id", { count: "exact", head: true })
    .eq("user_id", subscription.user_id)
    .eq("payment_type", "recurring");

  const renewalNumber = (priorRecurringCount ?? 0) + 1;
  const scheduleDate = (data.payment_schedule_date as string) || eventTime;
  const billingMonth = billingMonthFromDate(scheduleDate);
  const paidAt = eventTime || new Date().toISOString();

  await supabase.from("subscription_payments").insert({
    user_id: subscription.user_id,
    subscription_row_id: subscription.id,
    cf_payment_id: cfPaymentId,
    cashfree_subscription_id: cfSubId || subscription.cashfree_subscription_id,
    amount: paymentAmount,
    payment_type: "recurring",
    billing_month: billingMonth,
    renewal_number: renewalNumber,
    paid_at: paidAt,
  });

  await supabase
    .from("subscriptions")
    .update({
      status: "active",
      amount: paymentAmount,
      last_payment_id: cfPaymentId,
      autopay_enabled: true,
      updated_at: new Date().toISOString(),
    })
    .eq("id", subscription.id);

  await supabase
    .from("users")
    .update({ status: "active", updated_at: new Date().toISOString() })
    .eq("id", subscription.user_id);

  const distinctId = subscription.user_id.toString();
  await trackMixpanelEvent(token, distinctId, "subscription_paid", {
    amount: paymentAmount,
    currency: "INR",
    payment_type: "recurring",
    renewal_number: renewalNumber,
    billing_month: billingMonth,
    subscription_id: merchantSubscriptionId ?? cfSubId,
    cf_payment_id: cfPaymentId,
  }, cfPaymentId);

  await setMixpanelPeople(token, distinctId, {
    subscription_status: "active",
    total_renewals: renewalNumber,
    last_billing_month: billingMonth,
    last_renewal_amount: paymentAmount,
  });

  const { datasetId, accessToken } = metaCredentials(config);
  await trackMetaConversion(datasetId, accessToken, {
    eventName: "Subscribe",
    eventTime: eventTimeFromIso(paidAt),
    eventId: cfPaymentId,
    externalId: distinctId,
    customData: {
      currency: "INR",
      value: paymentAmount,
      content_type: "subscription",
      renewal_number: renewalNumber,
      billing_month: billingMonth,
      subscription_id: merchantSubscriptionId ?? cfSubId,
    },
  });

  console.log("subscription_paid tracked:", subscription.user_id, renewalNumber);
  return { received: true, subscription_paid: true, renewal_number: renewalNumber };
}

async function handleStatusChanged(
  supabase: SupabaseClient,
  config: Record<string, string>,
  data: Record<string, unknown>,
) {
  const details = (data.subscription_details ?? data) as Record<string, unknown>;
  const status = (details.subscription_status as string)?.toUpperCase() ?? "";
  const cfSubId = details.cf_subscription_id?.toString() ?? "";
  const merchantSubscriptionId = details.subscription_id as string | undefined;
  const token = mixpanelToken(config);

  if (!CANCELLED_STATUSES.has(status)) {
    return { received: true, skipped: true };
  }

  const subscription = await findSubscription(supabase, cfSubId);
  if (!subscription) {
    console.warn("subscription_cancelled: subscription not found", cfSubId, merchantSubscriptionId);
    return { received: true, skipped: true, reason: "subscription_not_found" };
  }

  const { count: renewalsBeforeCancel } = await supabase
    .from("subscription_payments")
    .select("id", { count: "exact", head: true })
    .eq("user_id", subscription.user_id)
    .eq("payment_type", "recurring");

  const { error: subscriptionError } = await supabase
    .from("subscriptions")
    .update({
      status: "cancelled",
      autopay_enabled: false,
      updated_at: new Date().toISOString(),
    })
    .eq("id", subscription.id);
  if (subscriptionError) {
    throw new Error(`Failed to cancel subscription: ${subscriptionError.message}`);
  }

  const { error: userError } = await supabase
    .from("users")
    .update({ status: "cancelled", updated_at: new Date().toISOString() })
    .eq("id", subscription.user_id);
  if (userError) {
    throw new Error(`Failed to cancel user: ${userError.message}`);
  }

  const distinctId = subscription.user_id.toString();
  const insertId = `cancel_${subscription.id}_${status}`;

  await trackMixpanelEvent(token, distinctId, "subscription_cancelled", {
    cancellation_status: status.toLowerCase(),
    subscription_id: merchantSubscriptionId ?? cfSubId,
    renewals_before_cancel: renewalsBeforeCancel ?? 0,
  }, insertId);

  await setMixpanelPeople(token, distinctId, {
    subscription_status: "cancelled",
  });

  console.log("subscription_cancelled tracked:", subscription.user_id, status);
  return { received: true, subscription_cancelled: true };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const rawBody = await req.text();
    const signature = req.headers.get("x-webhook-signature") ?? "";
    const timestamp = req.headers.get("x-webhook-timestamp") ?? "";

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    );

    const config = await getConfig(supabase);
    const secret = config.cashfree_client_secret?.trim() ?? "";

    if (secret && signature && timestamp) {
      const valid = await verifySignature(rawBody, timestamp, signature, secret);
      if (!valid) {
        return new Response(JSON.stringify({ error: "Invalid signature" }), { status: 401 });
      }
    }

    const payload = JSON.parse(rawBody);
    const eventType = payload.type as string;
    const data = payload.data ?? {};
    const eventTime = payload.event_time as string;

    let result: Record<string, unknown> = { received: true };

    switch (eventType) {
      case "SUBSCRIPTION_AUTH_STATUS":
        result = await handleAuthStatus(supabase, config, data);
        break;
      case "SUBSCRIPTION_PAYMENT_SUCCESS":
        result = await handlePaymentSuccess(supabase, config, data, eventTime);
        break;
      case "SUBSCRIPTION_STATUS_CHANGED":
        result = await handleStatusChanged(supabase, config, data);
        break;
      default:
        result = { received: true, ignored: eventType };
    }

    return new Response(JSON.stringify(result), {
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err) {
    console.error("cashfree-webhook error:", err);
    return new Response(JSON.stringify({ error: "Webhook processing failed" }), { status: 500 });
  }
});
