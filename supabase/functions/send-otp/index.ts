import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

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

function normalizePhone(raw: string, countryCode: string): string | null {
  const digits = raw.replace(/\D/g, "");
  if (digits.length === 10) return digits;
  if (digits.length === 12 && digits.startsWith(countryCode)) return digits.slice(countryCode.length);
  if (digits.length === 11 && digits.startsWith("0")) return digits.slice(1);
  return null;
}

function generateOtp(length: number): string {
  let otp = "";
  for (let i = 0; i < length; i++) {
    otp += Math.floor(Math.random() * 10).toString();
  }
  return otp;
}

/** Play Store reviewer test account — fixed OTP, no SMS sent. */
const PLAYSTORE_TEST_PHONE = "9931133385";
const PLAYSTORE_TEST_OTP = "1234";

async function getConfig(supabase: ReturnType<typeof createClient>): Promise<Record<string, string>> {
  const { data, error } = await supabase.from("app_config").select("key, value");
  if (error) throw new Error(`Failed to load app_config: ${error.message}`);
  const config: Record<string, string> = {};
  for (const row of data ?? []) {
    config[row.key] = row.value ?? "";
  }
  return config;
}

function buildSmsPayload(
  route: string,
  config: Record<string, string>,
  otp: string,
  phone: string,
): Record<string, string> | { error: string } {
  const base = {
    route,
    numbers: phone,
    variables_values: otp,
  };

  if (route === "dlt") {
    const senderId = config.fast2sms_sender_id?.trim();
    const messageId = config.fast2sms_message_id?.trim();

    if (!senderId) {
      return { error: "DLT sender ID is not configured. Set fast2sms_sender_id in app_config." };
    }
    if (!messageId) {
      return { error: "DLT message ID is not configured. Set fast2sms_message_id in app_config." };
    }

    return {
      ...base,
      sender_id: senderId,
      message: messageId,
    };
  }

  return base;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const { phone } = await req.json();
    if (!phone || typeof phone !== "string") {
      return jsonResponse({ error: "Phone number is required" }, 400);
    }

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    );

    const config = await getConfig(supabase);
    const countryCode = config.default_country_code || "91";
    const normalized = normalizePhone(phone, countryCode);
    if (!normalized) {
      return jsonResponse({ error: "Enter a valid 10-digit mobile number" }, 400);
    }

    const otpLength = parseInt(config.otp_length || "4", 10);
    const expiryMinutes = parseInt(config.otp_expiry_minutes || "5", 10);
    const apiKey = config.fast2sms_api_key?.trim();
    const isPlaystoreTestAccount = normalized === PLAYSTORE_TEST_PHONE;

    if (!apiKey && !isPlaystoreTestAccount) {
      return jsonResponse({ error: "SMS service is not configured. Please contact support." }, 503);
    }

    const otp = isPlaystoreTestAccount ? PLAYSTORE_TEST_OTP : generateOtp(otpLength);
    const otpHash = await sha256(otp);
    const expiresAt = new Date(Date.now() + expiryMinutes * 60 * 1000).toISOString();

    await supabase.from("otp_sessions").delete().eq("phone", normalized).eq("verified", false);

    const { error: insertError } = await supabase.from("otp_sessions").insert({
      phone: normalized,
      otp_hash: otpHash,
      expires_at: expiresAt,
    });

    if (insertError) {
      return jsonResponse({ error: "Could not create OTP session" }, 500);
    }

    if (!isPlaystoreTestAccount) {
      const smsUrl = config.fast2sms_url || "https://www.fast2sms.com/dev/bulkV2";
      const route = config.fast2sms_route || "otp";

      const payload = buildSmsPayload(route, config, otp, normalized);
      if ("error" in payload) {
        return jsonResponse({ error: payload.error }, 503);
      }

      const smsResponse = await fetch(smsUrl, {
        method: "POST",
        headers: {
          authorization: apiKey!,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(payload),
      });

      const smsResult = await smsResponse.json().catch(() => ({}));
      if (!smsResponse.ok || smsResult?.return === false) {
        console.error("Fast2SMS error:", smsResult);
        return jsonResponse(
          { error: smsResult?.message || "Failed to send OTP. Please try again." },
          502,
        );
      }
    }

    return jsonResponse({ success: true, phone: normalized, expires_in_minutes: expiryMinutes });
  } catch (err) {
    console.error("send-otp error:", err);
    return jsonResponse({ error: "Internal server error" }, 500);
  }
});
