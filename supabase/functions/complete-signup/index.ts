import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";
import type { ServiceClient } from "../_shared/supabase-client.ts";
import { issueUserToken } from "../_shared/user-sessions.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

/**
 * A verified signup session stays usable this long after the OTP's own expiry, enough to type a
 * name. Without a cut-off an unused session_token could mint a 365-day api_token forever.
 */
const SIGNUP_SESSION_GRACE_MS = 60 * 60 * 1000;
const INVALID_SESSION_MESSAGE = "Invalid or expired session. Please sign in again.";

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

/** Signup must not fail because token issuance did; the app then falls back to legacy user_id auth. */
async function issueApiToken(
  supabase: ServiceClient,
  userId: number,
  appVersion: unknown,
): Promise<string | null> {
  try {
    return await issueUserToken(supabase, userId, typeof appVersion === "string" ? appVersion : null);
  } catch (err) {
    console.error("complete-signup: api_token issue failed:", err);
    return null;
  }
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const { session_token, name, app_version } = await req.json();
    if (!session_token || !name) {
      return jsonResponse({ error: "Session token and name are required" }, 400);
    }

    const trimmedName = String(name).trim();
    if (trimmedName.length < 2 || trimmedName.length > 100) {
      return jsonResponse({ error: "Name must be between 2 and 100 characters" }, 400);
    }

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    );

    const { data: session, error: sessionError } = await supabase
      .from("otp_sessions")
      .select("id, phone, verified, expires_at")
      .eq("session_token", session_token)
      .eq("verified", true)
      .maybeSingle();

    if (sessionError || !session) {
      return jsonResponse({ error: INVALID_SESSION_MESSAGE }, 401);
    }

    const otpExpiresAt = Date.parse(String(session.expires_at));
    if (Number.isNaN(otpExpiresAt) || Date.now() > otpExpiresAt + SIGNUP_SESSION_GRACE_MS) {
      return jsonResponse({ error: INVALID_SESSION_MESSAGE }, 401);
    }

    const { data: existingUser } = await supabase
      .from("users")
      .select("id, phone, name, status, created_at, updated_at")
      .eq("phone", session.phone)
      .maybeSingle();

    let user;

    if (existingUser) {
      const { data, error } = await supabase
        .from("users")
        .update({ name: trimmedName, updated_at: new Date().toISOString() })
        .eq("id", existingUser.id)
        .select("id, phone, name, status, created_at, updated_at")
        .single();

      if (error) {
        return jsonResponse({ error: "Could not update profile" }, 500);
      }
      user = data;
    } else {
      const { data, error } = await supabase
        .from("users")
        .insert({ phone: session.phone, name: trimmedName })
        .select("id, phone, name, status, created_at, updated_at")
        .single();

      if (error) {
        // Code and message only: PostgREST `details` can echo the row, e.g. `Key (phone)=(…)`.
        console.error("complete-signup: insert user failed", { code: error.code, message: error.message });
        return jsonResponse({ error: "Could not create account" }, 500);
      }
      user = data;
    }

    await supabase.from("otp_sessions").delete().eq("id", session.id);

    const apiToken = await issueApiToken(supabase, Number(user.id), app_version);

    return jsonResponse({ success: true, user, api_token: apiToken });
  } catch (err) {
    console.error("complete-signup error:", err);
    return jsonResponse({ error: "Internal server error" }, 500);
  }
});
