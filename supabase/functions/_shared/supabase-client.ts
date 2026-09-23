import { createClient } from "jsr:@supabase/supabase-js@2";

/**
 * Service-role client for Edge Functions. Every function creates its client the same way, so the
 * inferred return type here is the one shared helper signatures should use: with current
 * supabase-js, `ReturnType<typeof createClient>` (no arguments) resolves to a client whose schema
 * is `never`, which makes every `.from()` untyped and rejects the real client at call sites.
 */
export function createServiceClient() {
  return createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  );
}

export type ServiceClient = ReturnType<typeof createServiceClient>;
