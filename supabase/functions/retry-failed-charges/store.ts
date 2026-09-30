/**
 * RetryStore on the service-role client: charge_retry_attempts, charge_failures (written by
 * cashfree-webhook), charge_retry_checks and the sweep RPC.
 */
import type { ServiceClient } from "../_shared/supabase-client.ts";
import type { AttemptPatch, AttemptRecord, Candidate, ChargeFailure, CheckRecord, NewAttempt, RetryStore } from "./job.ts";
import type { AttemptStatus } from "./policy.ts";

const ID_CHUNK = 200;

function chunks<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

const FAILURE_COLUMNS =
  "payment_id, merchant_subscription_id, cf_subscription_id, subscription_row_id, user_id, payment_status, failure_reason, retry_attempts, scheduled_on";

function toFailure(row: Record<string, unknown>): ChargeFailure {
  return {
    payment_id: String(row.payment_id),
    merchant_subscription_id: String(row.merchant_subscription_id),
    cf_subscription_id: (row.cf_subscription_id as string | null) ?? null,
    subscription_row_id: row.subscription_row_id == null ? null : Number(row.subscription_row_id),
    user_id: row.user_id == null ? null : Number(row.user_id),
    payment_status: String(row.payment_status ?? ""),
    failure_reason: (row.failure_reason as string | null) ?? null,
    retry_attempts: Number(row.retry_attempts ?? 0),
    scheduled_on: (row.scheduled_on as string | null) ?? null,
  };
}

function toAttempt(row: Record<string, unknown>): AttemptRecord {
  const numOrNull = (v: unknown) => (v == null ? null : Number(v));
  return {
    ...(row as unknown as AttemptRecord),
    id: Number(row.id),
    attempt: Number(row.attempt),
    subscription_row_id: Number(row.subscription_row_id),
    user_id: Number(row.user_id),
    failed_amount: numOrNull(row.failed_amount),
    retry_amount: numOrNull(row.retry_amount),
    send_errors: Number(row.send_errors ?? 0),
  };
}

export function supabaseRetryStore(supabase: ServiceClient): RetryStore {
  return {
    async candidates(since: string, limit: number, only: string[], checkAfterHours: number): Promise<Candidate[]> {
      const { data, error } = await supabase.rpc("charge_retry_candidates", {
        p_since: since,
        p_limit: limit,
        p_only: only.length ? only : null,
        p_check_after_hours: checkAfterHours,
      });
      if (error) throw new Error(`charge_retry_candidates failed: ${error.message}`);
      return ((data ?? []) as Record<string, unknown>[]).map((r) => ({
        subscription_row_id: Number(r.subscription_row_id),
        user_id: Number(r.user_id),
        cf_subscription_id: String(r.cf_subscription_id),
        merchant_subscription_id: (r.merchant_subscription_id as string | null) ?? null,
        account: (r.account as string | null) ?? null,
        due_on: String(r.due_on),
      }));
    },

    async openChains(limit: number): Promise<AttemptRecord[]> {
      const { data: open, error } = await supabase
        .from("charge_retry_attempts")
        .select("failed_payment_id")
        .in("status", ["pending", "requested"])
        .order("scheduled_for", { ascending: true })
        .limit(limit);
      if (error) throw new Error(`open attempts failed: ${error.message}`);
      const ids = [...new Set((open ?? []).map((r) => String(r.failed_payment_id)))];
      const rows: AttemptRecord[] = [];
      for (const part of chunks(ids, ID_CHUNK)) {
        const { data, error: chainError } = await supabase.from("charge_retry_attempts").select("*").in("failed_payment_id", part);
        if (chainError) throw new Error(`chain attempts failed: ${chainError.message}`);
        rows.push(...(data ?? []).map(toAttempt));
      }
      return rows;
    },

    async newFailures(since: string, limit: number): Promise<ChargeFailure[]> {
      const { data, error } = await supabase
        .from("charge_failures")
        .select(FAILURE_COLUMNS)
        .is("processed_at", null)
        .eq("payment_status", "FAILED")
        .eq("retry_attempts", 0)
        .gte("scheduled_on", since)
        .order("failed_at", { ascending: false })
        .limit(limit);
      if (error) throw new Error(`new failures failed: ${error.message}`);
      return (data ?? []).map(toFailure);
    },

    async failuresFor(paymentIds: string[]): Promise<ChargeFailure[]> {
      const out: ChargeFailure[] = [];
      for (const part of chunks(paymentIds, ID_CHUNK)) {
        const { data, error } = await supabase.from("charge_failures").select(FAILURE_COLUMNS).in("payment_id", part);
        if (error) throw new Error(`failures lookup failed: ${error.message}`);
        out.push(...(data ?? []).map(toFailure));
      }
      return out;
    },

    async markFailureProcessed(paymentId: string, outcome: string): Promise<void> {
      const { error } = await supabase
        .from("charge_failures")
        .update({ processed_at: new Date().toISOString(), processed_outcome: outcome })
        .eq("payment_id", paymentId)
        .is("processed_at", null);
      if (error) throw new Error(`mark failure processed failed: ${error.message}`);
    },

    async recurringPayments(rowIds: number[], sinceIso: string) {
      const out: Array<{ subscription_row_id: number; cf_payment_id: string; paid_at: string }> = [];
      for (const part of chunks(rowIds, ID_CHUNK)) {
        const { data, error } = await supabase
          .from("subscription_payments")
          .select("subscription_row_id, cf_payment_id, paid_at")
          .eq("payment_type", "recurring")
          .in("subscription_row_id", part)
          .gte("paid_at", sinceIso);
        if (error) throw new Error(`recurring payments failed: ${error.message}`);
        for (const r of data ?? []) {
          out.push({ subscription_row_id: Number(r.subscription_row_id), cf_payment_id: String(r.cf_payment_id), paid_at: String(r.paid_at) });
        }
      }
      return out;
    },

    async saveCheck(check: CheckRecord): Promise<void> {
      const { error } = await supabase.from("charge_retry_checks").upsert(check, { onConflict: "subscription_row_id" });
      if (error) throw new Error(`save check failed: ${error.message}`);
    },

    async insertChain(rows: NewAttempt[]): Promise<AttemptRecord[]> {
      const { data, error } = await supabase
        .from("charge_retry_attempts")
        .upsert(rows, { onConflict: "failed_payment_id,attempt", ignoreDuplicates: true })
        .select("*");
      if (error) throw new Error(`insert chain failed: ${error.message}`);
      return (data ?? []).map(toAttempt);
    },

    async updateAttempt(id: number, from: AttemptStatus[], patch: AttemptPatch): Promise<boolean> {
      const { data, error } = await supabase
        .from("charge_retry_attempts")
        .update({ ...patch, updated_at: new Date().toISOString() })
        .eq("id", id)
        .in("status", from)
        .select("id");
      if (error) throw new Error(`update attempt ${id} failed: ${error.message}`);
      return (data?.length ?? 0) > 0;
    },
  };
}
