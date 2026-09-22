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

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const { session_token, name } = await req.json();
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
      return jsonResponse({ error: "Invalid or expired session. Please sign in again." }, 401);
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
        console.error("Insert user error:", error);
        return jsonResponse({ error: "Could not create account" }, 500);
      }
      user = data;
    }

    await supabase.from("otp_sessions").delete().eq("id", session.id);

    return jsonResponse({ success: true, user });
  } catch (err) {
    console.error("complete-signup error:", err);
    return jsonResponse({ error: "Internal server error" }, 500);
  }
});
