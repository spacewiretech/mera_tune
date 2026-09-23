import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";
import type { ServiceClient } from "../_shared/supabase-client.ts";
import { issueUserToken } from "../_shared/user-sessions.ts";

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

async function sha256(value: string): Promise<string> {
  const data = new TextEncoder().encode(value);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** Login must not fail because token issuance did; the app then falls back to legacy user_id auth. */
async function issueApiToken(
  supabase: ServiceClient,
  userId: number,
  appVersion: unknown,
): Promise<string | null> {
  try {
    return await issueUserToken(supabase, userId, typeof appVersion === "string" ? appVersion : null);
  } catch (err) {
    console.error("verify-otp: api_token issue failed:", err);
    return null;
  }
}

function normalizePhone(raw: string): string | null {
  const digits = raw.replace(/\D/g, "");
  if (digits.length === 10) return digits;
  if (digits.length === 12 && digits.startsWith("91")) return digits.slice(2);
  return null;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const { phone, otp, app_version } = await req.json();
    if (!phone || !otp) {
      return jsonResponse({ error: "Phone and OTP are required" }, 400);
    }

    const normalized = normalizePhone(String(phone));
    if (!normalized) {
      return jsonResponse({ error: "Invalid phone number" }, 400);
    }

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    );

    const { data: session, error: sessionError } = await supabase
      .from("otp_sessions")
      .select("id, otp_hash, expires_at, verified")
      .eq("phone", normalized)
      .eq("verified", false)
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();

    if (sessionError || !session) {
      return jsonResponse({ error: "OTP expired or not found. Please request a new code." }, 400);
    }

    if (new Date(session.expires_at) < new Date()) {
      return jsonResponse({ error: "OTP has expired. Please request a new code." }, 400);
    }

    const otpHash = await sha256(String(otp).trim());
    if (otpHash !== session.otp_hash) {
      return jsonResponse({ error: "Invalid verification code" }, 400);
    }

    const sessionToken = crypto.randomUUID();

    const { error: updateError } = await supabase
      .from("otp_sessions")
      .update({ verified: true, session_token: sessionToken })
      .eq("id", session.id);

    if (updateError) {
      return jsonResponse({ error: "Could not verify OTP" }, 500);
    }

    const { data: existingUser } = await supabase
      .from("users")
      .select("id, phone, name, status, created_at, updated_at")
      .eq("phone", normalized)
      .maybeSingle();

    if (existingUser?.name) {
      const apiToken = await issueApiToken(supabase, Number(existingUser.id), app_version);
      // The OTP session has done its job for a returning user; the api_token carries auth from here.
      await supabase.from("otp_sessions").delete().eq("id", session.id);
      return jsonResponse({
        verified: true,
        needs_name: false,
        session_token: sessionToken,
        api_token: apiToken,
        user: existingUser,
      });
    }

    return jsonResponse({
      verified: true,
      needs_name: true,
      session_token: sessionToken,
      user: existingUser ?? null,
    });
  } catch (err) {
    console.error("verify-otp error:", err);
    return jsonResponse({ error: "Internal server error" }, 500);
  }
});
