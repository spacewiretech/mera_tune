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
  activationPeopleOps,
  eventKey,
  paymentInsertId,
  pickEventProps,
  type TrialActivation,
  trialPaymentId,
  trialPaymentSucceededProps,
} from "../_shared/subscription-analytics.ts";
import { activationFields, offerConfig, type OfferConfig } from "../_shared/subscription-offer.ts";

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

async function loadUser(supabase: ServiceClient, userId: number) {
  const { data: user } = await supabase
    .from("users")
    .select("id, phone, name, status, created_at, updated_at")
    .eq("id", userId)
    .single();
  return user;
}

async function loadSubscription(supabase: ServiceClient, id: number) {
  const { data, error } = await supabase.from("subscriptions").select("*").eq("id", id).maybeSingle();
  if (error) throw new Error(`Failed to load subscription: ${error.message}`);
  return data;
}

/**
 * The row create-subscription wrote for this mandate (its cashfree_subscription_id). Only when none
 * has it (a later create reused the pending row) the user's latest pending row, and only for the
 * user's own `mt_<user>_…` id: someone else's authorised mandate never activates this user's row.
 */
async function findActivationRow(
  supabase: ServiceClient,
  userId: number,
  cfSubId: string,
  subscriptionId: string,
) {
  if (cfSubId) {
    const { data, error } = await supabase
      .from("subscriptions")
      .select("*")
      .eq("user_id", userId)
      .eq("cashfree_subscription_id", cfSubId)
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();
    if (error) throw new Error(`Failed to load subscription: ${error.message}`);
    if (data) return data;
  }
  if (!subscriptionId.startsWith(`mt_${userId}_`)) return null;
  const { data, error } = await supabase
    .from("subscriptions")
    .select("*")
    .eq("user_id", userId)
    .eq("status", "pending")
    .order("created_at", { ascending: false })
    .limit(1)
    .maybeSingle();
  if (error) throw new Error(`Failed to load subscription: ${error.message}`);
  return data;
}

type SubscriptionRecord = NonNullable<Awaited<ReturnType<typeof findActivationRow>>>;

/**
 * Activates the pending row by its plan_type: `trial` (users.status trial, trial end) or `monthly`
 * (users.status active, runs to the first charge). The amounts are the row's, never app_config's.
 */
async function activateSubscription(
  supabase: ServiceClient,
  userId: number,
  row: SubscriptionRecord,
  cfSubId: string,
  paymentId: string,
  cfg: OfferConfig,
  cashfreeAuthAmount: unknown,
) {
  const now = new Date();
  const activation = activationFields(row, cfg, now.getTime(), cashfreeAuthAmount);

  const subUpdate: Record<string, unknown> = {
    status: "active",
    start_date: activation.startDate,
    end_date: activation.endDate,
    amount: activation.authAmount,
    next_billing_date: activation.nextBillingDate,
    cashfree_subscription_id: cfSubId || row.cashfree_subscription_id,
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
    .eq("id", row.id)
    .eq("status", "pending")
    .select("id");
  if (activateError) throw new Error(`Failed to activate subscription: ${activateError.message}`);
  const activated = (activatedRows?.length ?? 0) > 0;

  if (activated) {
    const { error: userError } = await supabase
      .from("users")
      .update({ status: activation.userStatus, updated_at: now.toISOString() })
      .eq("id", userId);
    if (userError) console.error("verify-subscription user status failed:", userId, userError.message);

    await recordAuthPayment(supabase, {
      userId,
      subscriptionRowId: row.id,
      paymentId,
      cfSubId: cfSubId || row.cashfree_subscription_id || "",
      amount: activation.authAmount,
      paidAt: now.toISOString(),
    });
  }

  return { activated, activation, startedAtMs: now.getTime() };
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
      updateMixpanelPeople(token, userId, activationPeopleOps(trial, startedAtMs)),
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
    const cfg = offerConfig(config);

    const { data: localSub } = await supabase
      .from("subscriptions")
      .select("*")
      .eq("user_id", userId)
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();

    if (localSub?.status === "active") {
      const user = await loadUser(supabase, userId);
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

    const row = await findActivationRow(supabase, userId, cfSubId, String(subscriptionId));
    // An active row: the webhook won. Cancelled / expired rows are never re-activated.
    if (row?.status === "active") {
      const user = await loadUser(supabase, userId);
      return jsonResponse({ active: true, user, subscription: row });
    }
    if (row?.status !== "pending") {
      return jsonResponse({ active: false, status: cfStatus || "PENDING" });
    }

    const result = await activateSubscription(
      supabase,
      userId,
      row,
      cfSubId,
      paymentId,
      cfg,
      authDetails.authorization_amount,
    );
    const subscription = await loadSubscription(supabase, row.id);
    if (!result.activated && subscription?.status !== "active") {
      return jsonResponse({ active: false, status: cfStatus || "PENDING" });
    }
    const user = await loadUser(supabase, userId);

    // Tracking only: ids from create-subscription are mt_<user>_<ts>, so a forged verify call
    // for someone else's subscription cannot create trial events.
    if (result.activated && String(subscriptionId).startsWith(`mt_${userId}_`)) {
      await trackTrialPaymentSucceeded(config, String(user?.id ?? userId), row.id, result.startedAtMs, {
        subscriptionId: String(subscriptionId),
        auth: authDetails,
        // The subscription entity has authorization_details.payment_id, not cf_payment_id.
        cfPaymentId: paymentId,
        authAmount: result.activation.authAmount,
        isTrial: result.activation.isTrial,
        trialDays: result.activation.trialDays,
        recurringAmount: cfg.recurringAmount,
        intervalMonths: cfg.intervalMonths,
        activatedVia: "app_verify",
      });
    }

    return jsonResponse({
      active: true,
      user,
      subscription,
      recurring_amount: cfg.recurringAmount,
      interval_months: cfg.intervalMonths,
    });
  } catch (err) {
    console.error("verify-subscription error:", err);
    return jsonResponse({ error: "Internal server error" }, 500);
  }
});
