import { createClient } from "jsr:@supabase/supabase-js@2";

type SupabaseClient = ReturnType<typeof createClient>;

/** Real Cashfree charge IDs (e.g. CH_…) — never the subscription id. */
export function isRealCashfreePaymentId(paymentId: string, cfSubId: string): boolean {
  const id = paymentId.trim();
  if (!id) return false;
  if (cfSubId && id === String(cfSubId).trim()) return false;
  return true;
}

export function extractAuthPaymentId(
  source: Record<string, unknown> | null | undefined,
  cfSubId: string,
): string {
  if (!source) return "";
  const details = (source.authorisation_details ??
    source.authorization_details ??
    {}) as Record<string, unknown>;
  const candidate =
    details.cf_payment_id?.toString() ||
    details.payment_id?.toString() ||
    source.cf_payment_id?.toString() ||
    source.payment_id?.toString() ||
    "";
  return isRealCashfreePaymentId(candidate, cfSubId) ? candidate.trim() : "";
}

export async function recordAuthPayment(
  supabase: SupabaseClient,
  params: {
    userId: number;
    subscriptionRowId: number;
    paymentId: string;
    cfSubId: string;
    amount: number;
    paidAt: string;
  },
): Promise<boolean> {
  const paymentId = params.paymentId.trim();
  const cfSubId = params.cfSubId.trim();
  if (!isRealCashfreePaymentId(paymentId, cfSubId)) return false;

  const { data: existingAuth } = await supabase
    .from("subscription_payments")
    .select("id")
    .eq("subscription_row_id", params.subscriptionRowId)
    .eq("payment_type", "auth")
    .maybeSingle();

  if (existingAuth) return false;

  const { error } = await supabase.from("subscription_payments").upsert({
    user_id: params.userId,
    subscription_row_id: params.subscriptionRowId,
    cf_payment_id: paymentId,
    cashfree_subscription_id: cfSubId || null,
    amount: params.amount,
    payment_type: "auth",
    paid_at: params.paidAt,
  }, { onConflict: "cf_payment_id", ignoreDuplicates: true });

  if (error) {
    console.warn("recordAuthPayment skipped:", error.message);
    return false;
  }
  return true;
}
