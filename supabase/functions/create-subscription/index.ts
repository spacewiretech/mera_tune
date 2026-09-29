import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createServiceClient, type ServiceClient } from "../_shared/supabase-client.ts";
import {
  type HistoryRow,
  offerConfig,
  phoneKey,
  phoneLikePattern,
  rowShowsAuthorisation,
  subscriptionOffer,
  type TrialHistory,
  trialEligibility,
} from "../_shared/subscription-offer.ts";

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
  if (error) throw new Error(`Failed to load app_config: ${error.message}`);
  const config: Record<string, string> = {};
  for (const row of data ?? []) config[row.key] = row.value ?? "";
  return config;
}

function cashfreeBaseUrl(environment: string) {
  return environment === "production"
    ? "https://api.cashfree.com/pg"
    : "https://sandbox.cashfree.com/pg";
}

function cashfreeErrorMessage(result: Record<string, unknown>): string {
  const message = result.message ?? result.error_description ?? result.error;
  if (typeof message === "string" && message.trim()) return message.trim();
  const code = result.code ?? result.error_code;
  if (typeof code === "string" && code.trim()) return code.trim();
  return "Could not create subscription";
}

const FALLBACK_CUSTOMER_NAME = "MeraTune User";

/** Cashfree rejects Indic/Unicode names with customer_name_invalid. */
function cashfreeCustomerName(name: string | null | undefined): string {
  const trimmed = (name ?? "").trim().replace(/\s+/g, " ");
  if (/^[A-Za-z][A-Za-z .'-]{2,99}$/.test(trimmed)) {
    return trimmed.slice(0, 100);
  }
  return FALLBACK_CUSTOMER_NAME;
}

function buildPlanDetails(config: Record<string, string>, recurringAmount: number, intervalMonths: number) {
  const usePlanId = config.cashfree_use_plan_id?.trim().toLowerCase() === "true";
  const planId = config.cashfree_plan_id?.trim();
  if (usePlanId && planId) {
    return { plan_id: planId };
  }
  return {
    plan_name: "MeraTune Premium",
    plan_type: "PERIODIC",
    plan_amount: recurringAmount,
    plan_max_amount: recurringAmount,
    plan_max_cycles: 100,
    plan_intervals: intervalMonths,
    plan_currency: "INR",
    plan_interval_type: "MONTH",
    plan_note: "MeraTune ringtone subscription",
  };
}

function isAuthenticationError(result: Record<string, unknown>): boolean {
  const type = String(result.type ?? "").toLowerCase();
  const message = String(result.message ?? "").toLowerCase();
  return type === "authentication_error" || message.includes("authentication failed");
}

async function createCashfreeSubscription(
  environment: string,
  clientId: string,
  clientSecret: string,
  apiVersion: string,
  body: Record<string, unknown>,
) {
  const response = await fetch(`${cashfreeBaseUrl(environment)}/subscriptions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-client-id": clientId,
      "x-client-secret": clientSecret,
      "x-api-version": apiVersion,
    },
    body: JSON.stringify(body),
  });
  const result = await response.json().catch(() => ({})) as Record<string, unknown>;
  return { response, result };
}

const HISTORY_COLUMNS = "id, status, plan_type, start_date, cashfree_status, phone, created_at";
/** Rows read per history query; pending rows are reused, so a user has only a few. */
const HISTORY_LIMIT = 50;

type HistorySubscription = HistoryRow & { id: number; phone?: string | null };

type UserRow = { id: number; phone: string; name: string | null; status: string | null };

/**
 * Trial evidence of the user and of the phone (last 10 digits, any user id). A failed query fails
 * closed (paid offer): a DB error never hands out a trial. `own` is the user's rows, newest first.
 */
async function loadTrialHistory(
  supabase: ServiceClient,
  user: UserRow,
): Promise<{ history: TrialHistory; own: HistorySubscription[] }> {
  const key = phoneKey(user.phone);
  const [ownResult, phoneResult, paymentsResult] = await Promise.all([
    supabase
      .from("subscriptions")
      .select(HISTORY_COLUMNS)
      .eq("user_id", user.id)
      .order("created_at", { ascending: false })
      .limit(HISTORY_LIMIT),
    key
      ? supabase
        .from("subscriptions")
        .select(HISTORY_COLUMNS)
        .like("phone", phoneLikePattern(key))
        .order("created_at", { ascending: false })
        .limit(HISTORY_LIMIT)
      : Promise.resolve({ data: [], error: null }),
    supabase
      .from("subscription_payments")
      .select("id")
      .eq("user_id", user.id)
      .in("payment_type", ["auth", "recurring"])
      .limit(1),
  ]);

  const failed: string[] = [];
  for (const [query, error] of [
    ["own", ownResult.error],
    ["phone", phoneResult.error],
    ["payments", paymentsResult.error],
  ] as const) {
    if (!error) continue;
    failed.push(query);
    console.error("create-subscription trial history query failed:", query, error.message);
  }

  const own = (ownResult.data ?? []) as HistorySubscription[];
  const samePhone = ((phoneResult.data ?? []) as HistorySubscription[])
    .filter((row) => phoneKey(row.phone) === key);
  return {
    history: {
      userStatus: user.status,
      subscriptions: [...own, ...samePhone],
      paymentCount: paymentsResult.data?.length ?? 0,
      lookupFailed: failed.length > 0,
    },
    own,
  };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const body = (await req.json()) ?? {};
    const userId = body.user_id;
    if (!userId) return jsonResponse({ error: "user_id is required" }, 400);
    const preview = body.preview === true;
    // Sent by app builds that show the paid offer; older builds would show ₹3 and then ask ₹299.
    const paidOfferSupported = body.paid_offer_supported === true;

    const supabase = createServiceClient();

    const config = await getConfig(supabase);
    const clientId = config.cashfree_client_id?.trim();
    const clientSecret = config.cashfree_client_secret?.trim();
    if (!preview && (!clientId || !clientSecret)) {
      return jsonResponse({ error: "Payment gateway is not configured" }, 503);
    }

    const { data: user, error: userError } = await supabase
      .from("users")
      .select("id, phone, name, status")
      .eq("id", userId)
      .single();

    if (userError || !user) return jsonResponse({ error: "User not found" }, 404);

    // On its own: with an active and a pending row, a combined lookup errors and a second mandate
    // would be created. Unknown means nothing is created.
    const { data: activeRows, error: activeError } = await supabase
      .from("subscriptions")
      .select("id")
      .eq("user_id", user.id)
      .eq("status", "active")
      .limit(1);
    if (activeError) {
      console.error("create-subscription active lookup failed:", activeError.message);
      return jsonResponse({ error: "Internal server error" }, 500);
    }
    if (activeRows?.length) {
      return jsonResponse({ error: "Subscription already active", error_code: "ALREADY_ACTIVE" }, 409);
    }

    const { history, own } = await loadTrialHistory(supabase, user as UserRow);
    const eligibility = trialEligibility(history);
    const nowMs = Date.now();
    const offer = subscriptionOffer(eligibility.eligible, offerConfig(config), nowMs);
    console.log(JSON.stringify({
      fn: "create-subscription",
      user_id: user.id,
      offer: offer.offer,
      reason: eligibility.reason,
      preview,
    }));

    if (preview) {
      return jsonResponse({
        offer: offer.offer,
        trial_eligible: offer.trialEligible,
        auth_amount: offer.authAmount,
        recurring_amount: offer.recurringAmount,
        interval_months: offer.intervalMonths,
        trial_days: offer.trialDays,
      });
    }

    if (offer.offer === "paid" && !paidOfferSupported) {
      return jsonResponse({
        error: "Naya plan dekhne ke liye MeraTune app update karein.",
        error_code: "APP_UPDATE_REQUIRED",
      }, 426);
    }

    const apiVersion = config.cashfree_api_version || "2025-01-01";
    const environment = config.cashfree_environment || "sandbox";
    const subscriptionId = `mt_${user.id}_${nowMs}`;

    const cashfreeBody = {
      subscription_id: subscriptionId,
      customer_details: {
        customer_name: cashfreeCustomerName(user.name),
        customer_email: `${user.phone}@meratune.app`,
        customer_phone: user.phone,
      },
      plan_details: buildPlanDetails(config, offer.recurringAmount, offer.intervalMonths),
      authorization_details: {
        // Trial: ₹3. Paid: the first month, so the next charge is a month out.
        authorization_amount: offer.authAmount,
        authorization_amount_refund: false,
        payment_methods: ["upi", "card", "enach"],
      },
      subscription_meta: {
        return_url: config.cashfree_return_url || "https://meratune.app/subscription/return",
      },
      subscription_first_charge_time: offer.firstChargeAt,
      subscription_expiry_time: "2100-01-01T23:59:59+05:30",
      subscription_tags: {
        user_id: String(user.id),
        phone: user.phone,
      },
    };

    let { response: cfResponse, result: cfResult } = await createCashfreeSubscription(
      environment,
      clientId!,
      clientSecret!,
      apiVersion,
      cashfreeBody,
    );

    const usePlanId = config.cashfree_use_plan_id?.trim().toLowerCase() === "true";
    if (!cfResponse.ok && usePlanId && !isAuthenticationError(cfResult)) {
      console.warn("Cashfree plan_id subscription failed, retrying inline plan:", cfResult);
      const inlineBody = {
        ...cashfreeBody,
        plan_details: buildPlanDetails(
          { ...config, cashfree_use_plan_id: "false" },
          offer.recurringAmount,
          offer.intervalMonths,
        ),
      };
      ({ response: cfResponse, result: cfResult } = await createCashfreeSubscription(
        environment,
        clientId!,
        clientSecret!,
        apiVersion,
        inlineBody,
      ));
    }

    if (!cfResponse.ok) {
      console.error("Cashfree create subscription error:", cfResponse.status, cfResult);
      if (isAuthenticationError(cfResult)) {
        return jsonResponse({
          error: "Cashfree authentication failed. Verify App ID and Secret Key match the same environment (production/sandbox) in app_config.",
        }, 502);
      }
      return jsonResponse({ error: cashfreeErrorMessage(cfResult) }, 502);
    }

    const sessionId = cfResult.subscription_session_id as string;
    const cfSubId = cfResult.cf_subscription_id as string;
    if (!sessionId) {
      return jsonResponse({ error: "Invalid response from payment gateway" }, 502);
    }

    // Activation reads plan_type, amount and (paid) next_billing_date from this row, never from
    // app_config, so a failed write returns an error: the session is never used, nothing is charged.
    const firstChargeIso = new Date(Date.parse(offer.firstChargeAt)).toISOString();
    const rowFields = {
      plan_type: offer.planType,
      status: "pending",
      amount: offer.authAmount,
      next_billing_date: firstChargeIso,
      cashfree_subscription_id: cfSubId,
      autopay_enabled: true,
    };
    // A pending row that shows an authorised mandate is kept as trial evidence, not overwritten.
    const reusable = own.find((row) => row.status === "pending" && !rowShowsAuthorisation(row));
    const { error: rowError } = reusable
      ? await supabase
        .from("subscriptions")
        .update({
          ...rowFields,
          // The abandoned mandate's status must not become the new one's previous_status.
          cashfree_status: null,
          cashfree_status_at: null,
          updated_at: new Date().toISOString(),
        })
        .eq("id", reusable.id)
      : await supabase.from("subscriptions").insert({
        user_id: user.id,
        phone: user.phone,
        ...rowFields,
      });
    if (rowError) {
      console.error("create-subscription row write failed:", rowError.message);
      return jsonResponse({ error: "Internal server error" }, 500);
    }

    return jsonResponse({
      subscription_id: subscriptionId,
      subscription_session_id: sessionId,
      cf_subscription_id: cfSubId,
      environment,
      auth_amount: offer.authAmount,
      recurring_amount: offer.recurringAmount,
      offer: offer.offer,
      first_charge_at: offer.firstChargeAt,
    });
  } catch (err) {
    console.error("create-subscription error:", err);
    return jsonResponse({ error: "Internal server error" }, 500);
  }
});
