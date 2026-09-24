import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createServiceClient, type ServiceClient } from "../_shared/supabase-client.ts";
import {
  extractAuthPaymentId,
  recordAuthPayment,
} from "../_shared/subscription-payments.ts";
import {
  mixpanelInsertId,
  resolveMixpanelToken,
  trackMixpanelEvent,
  updateMixpanelPeople,
} from "../_shared/mixpanel.ts";
import {
  eventKey,
  paymentInsertId,
  pickEventProps,
  type TrialActivation,
  trialPaymentId,
  trialPaymentSucceededProps,
  trialPeopleOps,
} from "../_shared/subscription-analytics.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

async function getConfig(supabase: ServiceClient) {
  const { data, error } = await supabase.from("app_config").select("key, value");
  if (error) throw new Error(error.message);
  const config: Record<string, string> = {};
  for (const row of data ?? []) config[row.key] = row.value ?? "";
  return config;
}

function cashfreeBaseUrl(environment: string) {
  return environment === "production"
    ? "https://api.cashfree.com/pg"
    : "https://sandbox.cashfree.com/pg";
}

async function activateTrial(
  supabase: ServiceClient,
  userId: number,
  cfSubId: string,
  paymentId: string,
  authAmount: number,
  trialDays: number,
  recurringAmount: number,
  intervalMonths: number,
) {
  const now = new Date();
  const trialEnd = new Date(now.getTime() + trialDays * 24 * 60 * 60 * 1000);
  const nextBilling = new Date(now.getTime() + 24 * 60 * 60 * 1000);

  const subUpdate: Record<string, unknown> = {
    status: "active",
    start_date: now.toISOString(),
    end_date: trialEnd.toISOString(),
    amount: authAmount,
    next_billing_date: nextBilling.toISOString(),
    cashfree_subscription_id: cfSubId,
    autopay_enabled: true,
    // Same as the AUTH_STATUS webhook's activation, so later STATUS_CHANGED dedupe and
    // previous_status do not depend on which side won the row.
    cashfree_status: "ACTIVE",
    cashfree_status_at: now.toISOString(),
    updated_at: now.toISOString(),
  };
  if (paymentId) subUpdate.last_payment_id = paymentId;

  // Pending-only, so exactly one of this and the AUTH_STATUS webhook wins the row (trial_payment_succeeded).
  const { data: activatedRows, error: activateError } = await supabase
    .from("subscriptions")
    .update(subUpdate)
    .eq("user_id", userId)
    .eq("status", "pending")
    .select("id");
  if (activateError) console.error("verify-subscription activate failed:", activateError.message);

  await supabase
    .from("users")
    .update({ status: "trial", updated_at: now.toISOString() })
    .eq("id", userId);

  const { data: user } = await supabase
    .from("users")
    .select("id, phone, name, status, created_at, updated_at")
    .eq("id", userId)
    .single();

  const { data: subscription } = await supabase
    .from("subscriptions")
    .select("*")
    .eq("user_id", userId)
    .order("created_at", { ascending: false })
    .limit(1)
    .maybeSingle();

  if (subscription?.id) {
    await recordAuthPayment(supabase, {
      userId,
      subscriptionRowId: subscription.id,
      paymentId,
      cfSubId: cfSubId || subscription.cashfree_subscription_id || "",
      amount: authAmount,
      paidAt: now.toISOString(),
    });
  }

  const activatedIds = (activatedRows ?? []).map((row) => row.id);
  return {
    response: { user, subscription, recurring_amount: recurringAmount, interval_months: intervalMonths },
    trialRowId: activatedIds.includes(subscription?.id) ? subscription.id : activatedIds[0],
    startedAtMs: now.getTime(),
  };
}

async function trackTrialPaymentSucceeded(
  config: Record<string, string>,
  userId: string,
  trialRowId: number,
  startedAtMs: number,
  trial: TrialActivation,
) {
  try {
    const token = resolveMixpanelToken(config);
    const insertId = paymentInsertId(trialPaymentId(trial)) ?? await mixpanelInsertId(eventKey.trial(trialRowId));
    const props = pickEventProps("trial_payment_succeeded", trialPaymentSucceededProps(trial));
    await Promise.allSettled([
      trackMixpanelEvent(token, userId, "trial_payment_succeeded", props, { insertId, timeMs: startedAtMs }),
      updateMixpanelPeople(token, userId, trialPeopleOps(startedAtMs, trial.trialDays)),
    ]);
  } catch (err) {
    console.error("verify-subscription analytics failed:", err instanceof Error ? err.name : "unknown");
  }
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const { user_id: userId, subscription_id: subscriptionId } = await req.json();
    if (!userId || !subscriptionId) {
      return jsonResponse({ error: "user_id and subscription_id are required" }, 400);
    }

    const supabase = createServiceClient();

    const config = await getConfig(supabase);
    const clientId = config.cashfree_client_id?.trim();
    const clientSecret = config.cashfree_client_secret?.trim();
    const apiVersion = config.cashfree_api_version || "2025-01-01";
    const environment = config.cashfree_environment || "sandbox";
    const authAmount = parseFloat(config.subscription_auth_amount || "3");
    const recurringAmount = parseFloat(config.subscription_recurring_amount || "299");
    const trialDays = parseInt(config.subscription_trial_days || "1", 10);
    const intervalMonths = parseInt(config.subscription_interval_months || "1", 10);

    const { data: localSub } = await supabase
      .from("subscriptions")
      .select("*")
      .eq("user_id", userId)
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();

    if (localSub?.status === "active") {
      const { data: user } = await supabase
        .from("users")
        .select("id, phone, name, status, created_at, updated_at")
        .eq("id", userId)
        .single();
      return jsonResponse({ active: true, user, subscription: localSub });
    }

    const cfResponse = await fetch(
      `${cashfreeBaseUrl(environment)}/subscriptions/${subscriptionId}`,
      {
        headers: {
          "x-client-id": clientId!,
          "x-client-secret": clientSecret!,
          "x-api-version": apiVersion,
        },
      },
    );

    const cfResult = await cfResponse.json().catch(() => ({}));
    if (!cfResponse.ok) {
      return jsonResponse({ error: "Could not verify subscription status" }, 502);
    }

    const cfStatus = (cfResult.subscription_status as string)?.toUpperCase();
    const authDetails = (cfResult.authorisation_details ??
      cfResult.authorization_details ??
      {}) as Record<string, unknown>;
    const authStatus = (authDetails.authorization_status as string)?.toUpperCase();
    const isAuthorized =
      cfStatus === "ACTIVE" ||
      authStatus === "SUCCESS" ||
      authStatus === "ACTIVE";

    if (!isAuthorized) {
      return jsonResponse({ active: false, status: cfStatus || "PENDING" });
    }

    const cfSubId =
      cfResult.cf_subscription_id?.toString() || localSub?.cashfree_subscription_id || "";
    const paymentId = extractAuthPaymentId(cfResult as Record<string, unknown>, cfSubId);

    const result = await activateTrial(
      supabase,
      userId,
      cfSubId,
      paymentId,
      authAmount,
      trialDays,
      recurringAmount,
      intervalMonths,
    );

    // Tracking only: ids from create-subscription are mt_<user>_<ts>, so a forged verify call
    // for someone else's subscription cannot create trial events.
    if (result.trialRowId != null && String(subscriptionId).startsWith(`mt_${userId}_`)) {
      await trackTrialPaymentSucceeded(config, String(result.response.user?.id ?? userId), result.trialRowId, result.startedAtMs, {
        subscriptionId: String(subscriptionId),
        auth: authDetails,
        // The subscription entity has authorization_details.payment_id, not cf_payment_id.
        cfPaymentId: paymentId,
        configAuthAmount: authAmount,
        trialDays,
        recurringAmount,
        intervalMonths,
        activatedVia: "app_verify",
      });
    }

    return jsonResponse({ active: true, ...result.response });
  } catch (err) {
    console.error("verify-subscription error:", err);
    return jsonResponse({ error: "Internal server error" }, 500);
  }
});
