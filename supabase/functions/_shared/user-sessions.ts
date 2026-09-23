import type { ServiceClient } from "./supabase-client.ts";

type SupabaseClient = ServiceClient;

/** Newest sessions kept per user when a new token is issued; older ones are revoked. */
export const MAX_ACTIVE_SESSIONS_PER_USER = 5;
export const SESSION_TTL_DAYS = 365;
const MAX_TOKEN_LENGTH = 256;

/**
 * Plain SHA-256 hex of the exact input. Deliberately separate from `_shared/meta.ts#sha256`,
 * which trims and lower-cases for Meta's matching rules; tokens are case-sensitive.
 */
export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

/** 32 random bytes, base64url without padding (43 chars). */
export function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function cleanAppVersion(appVersion: unknown): string | null {
  if (typeof appVersion !== "string") return null;
  const trimmed = appVersion.trim();
  return trimmed ? trimmed.slice(0, 40) : null;
}

/**
 * Issues a long-lived api_token for `userId`, stores only its SHA-256 and revokes everything but
 * the newest MAX_ACTIVE_SESSIONS_PER_USER sessions. Returns the raw token for the response body.
 * Throws when the insert fails; callers decide whether that blocks login.
 */
export async function issueUserToken(
  supabase: SupabaseClient,
  userId: number,
  appVersion?: string | null,
): Promise<string> {
  const token = randomToken();
  const tokenHash = await sha256Hex(token);
  const expiresAt = new Date(Date.now() + SESSION_TTL_DAYS * 24 * 60 * 60 * 1000).toISOString();

  const { error } = await supabase.from("user_sessions").insert({
    user_id: userId,
    token_hash: tokenHash,
    app_version: cleanAppVersion(appVersion),
    expires_at: expiresAt,
  });
  if (error) throw new Error(`user_sessions insert failed: ${error.message}`);

  await pruneSessions(supabase, userId);
  return token;
}

async function pruneSessions(supabase: SupabaseClient, userId: number): Promise<void> {
  const { data, error } = await supabase
    .from("user_sessions")
    .select("id")
    .eq("user_id", userId)
    .is("revoked_at", null)
    .order("created_at", { ascending: false });

  if (error || !data) {
    console.warn("user_sessions prune skipped:", error?.message ?? "no data");
    return;
  }

  const stale = data.slice(MAX_ACTIVE_SESSIONS_PER_USER).map((row) => row.id as string);
  if (stale.length === 0) return;

  const { error: revokeError } = await supabase
    .from("user_sessions")
    .update({ revoked_at: new Date().toISOString() })
    .in("id", stale);
  if (revokeError) console.warn("user_sessions prune failed:", revokeError.message);
}

export type ResolvedSession = {
  userId: number;
  sessionId: string;
};

/**
 * Resolves an api_token to its user. Returns null for unknown, revoked or expired tokens.
 * Throws when the lookup itself fails, so a database hiccup surfaces as a retryable 500 instead
 * of a 401 that would force the user to log in again. Touches `last_used_at` on success (best effort).
 */
export async function resolveUserIdFromToken(
  supabase: SupabaseClient,
  token: string,
): Promise<ResolvedSession | null> {
  const trimmed = (token ?? "").trim();
  if (!trimmed || trimmed.length > MAX_TOKEN_LENGTH) return null;

  const tokenHash = await sha256Hex(trimmed);
  const { data, error } = await supabase
    .from("user_sessions")
    .select("id, user_id, expires_at, revoked_at")
    .eq("token_hash", tokenHash)
    .maybeSingle();

  if (error) throw new Error(`user_sessions lookup failed: ${error.message}`);
  if (!data) return null;
  if (data.revoked_at) return null;
  const expiresAt = Date.parse(String(data.expires_at));
  if (Number.isNaN(expiresAt) || expiresAt <= Date.now()) return null;

  const { error: touchError } = await supabase
    .from("user_sessions")
    .update({ last_used_at: new Date().toISOString() })
    .eq("id", data.id);
  if (touchError) console.warn("user_sessions touch failed:", touchError.message);

  return { userId: Number(data.user_id), sessionId: String(data.id) };
}

/** Revokes every active session of a user (logout-everywhere / abuse handling). */
export async function revokeAllUserSessions(supabase: SupabaseClient, userId: number): Promise<void> {
  const { error } = await supabase
    .from("user_sessions")
    .update({ revoked_at: new Date().toISOString() })
    .eq("user_id", userId)
    .is("revoked_at", null);
  if (error) console.warn("user_sessions revoke-all failed:", error.message);
}
