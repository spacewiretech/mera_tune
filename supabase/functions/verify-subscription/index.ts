import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";
import {
  extractAuthPaymentId,
  recordAuthPayment,
} from "../_shared/subscription-payments.ts";

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

async function getConfig(supabase: ReturnType<typeof createClient>) {
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
  supabase: ReturnType<typeof createClient>,
  userId: number,
  subscriptionId: string,
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
    updated_at: now.toISOString(),
  };
  if (paymentId) subUpdate.last_payment_id = paymentId;

  await supabase
    .from("subscriptions")
    .update(subUpdate)
    .eq("user_id", userId)
    .eq("status", "pending");

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

  return { user, subscription, recurring_amount: recurringAmount, interval_months: intervalMonths };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const { user_id: userId, subscription_id: subscriptionId } = await req.json();
    if (!userId || !subscriptionId) {
      return jsonResponse({ error: "user_id and subscription_id are required" }, 400);
    }

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    );

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
      subscriptionId,
      cfSubId,
      paymentId,
      authAmount,
      trialDays,
      recurringAmount,
      intervalMonths,
    );

    return jsonResponse({ active: true, ...result });
  } catch (err) {
    console.error("verify-subscription error:", err);
    return jsonResponse({ error: "Internal server error" }, 500);
  }
});
