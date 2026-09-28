/**
 * Request parsing and caller authentication shared by generate-ringtone and name-ringtones, so
 * both accept exactly the same credentials: `user_token` (an api_token from verify-otp /
 * complete-signup) or, while app_config.generate_allow_legacy_user_id is on, a bare `user_id`.
 * name-ringtones reads the caller's own rows only for `authMode === "token"` (rows.ts readsOwnRows).
 */
import type { ServiceClient } from "../_shared/supabase-client.ts";
import { resolveUserIdFromToken } from "../_shared/user-sessions.ts";
import { ApiError } from "./errors.ts";
import { type AuthMode, isFlagEnabled } from "./quota.ts";

const MAX_USER_TOKEN_LENGTH = 512;

export type Credentials = { userId: number | null; userToken: string | null };

export type Caller = { userId: number; authMode: AuthMode };

export async function getConfig(supabase: ServiceClient): Promise<Record<string, string>> {
  const { data, error } = await supabase.from("app_config").select("key, value");
  if (error) throw new Error(`Failed to load app_config: ${error.message}`);
  const config: Record<string, string> = {};
  for (const row of data ?? []) config[String(row.key)] = String(row.value ?? "");
  return config;
}

/** Trimmed string capped at `maxLength`; null for anything else or blank. */
export function optionalString(value: unknown, maxLength: number): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (!trimmed) return null;
  return trimmed.slice(0, maxLength);
}

/** `user_id` / `user_token` of a JSON body. 400 for malformed values, 401 when both are missing. */
export function parseCredentials(body: Record<string, unknown>): Credentials {
  let userId: number | null = null;
  if (body.user_id !== undefined && body.user_id !== null && body.user_id !== "") {
    const parsed = typeof body.user_id === "number" ? body.user_id : Number(String(body.user_id).trim());
    if (!Number.isInteger(parsed) || parsed <= 0) {
      throw new ApiError(400, "INVALID_REQUEST", "user_id must be a positive integer");
    }
    userId = parsed;
  }

  const userToken = optionalString(body.user_token, MAX_USER_TOKEN_LENGTH);
  if (body.user_token !== undefined && body.user_token !== null && typeof body.user_token !== "string") {
    throw new ApiError(400, "INVALID_REQUEST", "user_token must be a string");
  }

  if (!userId && !userToken) {
    throw new ApiError(401, "UNAUTHORIZED", "Please log in again");
  }
  return { userId, userToken };
}

/** Token first; the bare user_id only while the legacy flag is on. 401 otherwise. */
export async function authenticateCaller(
  supabase: ServiceClient,
  config: Record<string, string>,
  credentials: Credentials,
): Promise<Caller> {
  if (credentials.userToken) {
    const session = await resolveUserIdFromToken(supabase, credentials.userToken);
    if (!session) throw new ApiError(401, "UNAUTHORIZED", "Session expired. Please log in again.");
    if (credentials.userId !== null && credentials.userId !== session.userId) {
      throw new ApiError(401, "UNAUTHORIZED", "Session does not match this account. Please log in again.");
    }
    return { userId: session.userId, authMode: "token" };
  }
  if (!isFlagEnabled(config, "generate_allow_legacy_user_id", false) || credentials.userId === null) {
    throw new ApiError(401, "UNAUTHORIZED", "Please log in again");
  }
  return { userId: credentials.userId, authMode: "legacy_user_id" };
}

/** The caller's users row (subscription status); 401 when the account no longer exists. */
export async function requireUser(supabase: ServiceClient, userId: number): Promise<{ status: string }> {
  const { data: user, error } = await supabase
    .from("users")
    .select("id, status")
    .eq("id", userId)
    .maybeSingle();
  if (error) throw new Error(`user lookup failed: ${error.message}`);
  if (!user) throw new ApiError(401, "UNAUTHORIZED", "Account not found. Please log in again.");
  return { status: String(user.status ?? "none") };
}
