/**
 * Quota count queries shared by generate-ringtone (its quota checks and response snapshots) and
 * name-ringtones mine mode (the caller's quota), so both report the same `used` number.
 * The rules themselves are the pure helpers in quota.ts.
 */
import type { ServiceClient } from "../_shared/supabase-client.ts";
import { freshRenderFilter, type QuotaWindow } from "./quota.ts";

/** Count over one user's cached=false generated_ringtones rows created at/after `sinceIso`; callers add an `or=` filter. */
export function userRowsSince(supabase: ServiceClient, userId: number, sinceIso: string) {
  return supabase
    .from("generated_ringtones")
    .select("id", { count: "exact", head: true })
    .eq("user_id", userId)
    .eq("cached", false)
    .gte("created_at", sinceIso);
}

/** The user's fresh renders in the plan's period (`window.periodStartIso`). */
export async function countUserFreshRenders(
  supabase: ServiceClient,
  userId: number,
  window: QuotaWindow,
): Promise<number> {
  const { count, error } = await userRowsSince(supabase, userId, window.periodStartIso)
    .or(freshRenderFilter(window.processingSinceIso));
  if (error) throw new Error(`user quota count failed: ${error.message}`);
  return count ?? 0;
}
