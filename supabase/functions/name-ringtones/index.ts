import "jsr:@supabase/functions-js/edge-runtime.d.ts";
/**
 * name-ringtones: read-only lookup of ready personalized ringtones.
 *
 *   POST { user_id, user_token, name }         -> ringtones of that name (the create flow's
 *                                                "already exists" list), max 20
 *   POST { user_id, user_token, mine: true }   -> the caller's own ready ringtones, max 50
 *   optional `limit` lowers the cap.
 *
 * 200 { mode: "name" | "mine", ringtones: RingtoneOut[] } (see rows.ts), or
 * { error, error_code } with generate-ringtone's codes: 400 INVALID_REQUEST / INVALID_NAME /
 * NAME_REJECTED, 401 UNAUTHORIZED, 405 INVALID_REQUEST, 500 INTERNAL.
 *
 * Auth and name normalization are generate-ringtone's own code (request.ts, names.ts). The name
 * is never logged.
 */
import { createServiceClient, type ServiceClient } from "../_shared/supabase-client.ts";
import { ApiError, safeDetail } from "../generate-ringtone/errors.ts";
import { sanitizeName } from "../generate-ringtone/names.ts";
import { authenticateCaller, getConfig, requireUser } from "../generate-ringtone/request.ts";
import {
  GENERATION_COLUMNS,
  type GenerationRow,
  mineModeRows,
  nameModeRows,
  normalizeRows,
  parseRequest,
  RENDER_COLUMNS,
  type RenderRow,
  type RingtoneOut,
  sampleMatchIds,
  toGenerationRow,
  toRenderRow,
  toTuneRow,
  TUNE_COLUMNS,
  type TuneRow,
  uniqueIds,
} from "./rows.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

/** Upper bounds on rows read before filtering and dedupe. */
const RENDER_SCAN_LIMIT = 100;
const OWN_NAME_SCAN_LIMIT = 50;
const MINE_SCAN_LIMIT = 150;
const CATALOG_SCAN_LIMIT = 1000;

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function errorResponse(err: ApiError): Response {
  return jsonResponse({ error: err.message, error_code: err.code }, err.status);
}

async function loadTunes(supabase: ServiceClient, ids: string[]): Promise<Map<string, TuneRow>> {
  if (ids.length === 0) return new Map();
  const { data, error } = await supabase.from("tune").select(TUNE_COLUMNS).in("id", ids);
  if (error) throw new Error(`tune lookup failed: ${error.message}`);
  return new Map(normalizeRows(data, toTuneRow).map((tune) => [tune.id, tune]));
}

async function nameRingtones(
  supabase: ServiceClient,
  userId: number,
  name: { display: string; normalized: string },
  limit: number,
): Promise<RingtoneOut[]> {
  const [renders, catalog, own] = await Promise.all([
    supabase
      .from("ringtone_renders")
      .select(RENDER_COLUMNS)
      .eq("name_normalized", name.normalized)
      .eq("status", "ready")
      .not("public_url", "is", null)
      .order("created_at", { ascending: false })
      .limit(RENDER_SCAN_LIMIT),
    supabase
      .from("tune")
      .select("id, sample_name")
      .eq("is_active", true)
      .eq("is_personalizable", true)
      .not("sample_name", "is", null)
      .limit(CATALOG_SCAN_LIMIT),
    supabase
      .from("generated_ringtones")
      .select(GENERATION_COLUMNS)
      .eq("user_id", userId)
      .eq("name_normalized", name.normalized)
      .eq("status", "ready")
      .order("created_at", { ascending: false })
      .limit(OWN_NAME_SCAN_LIMIT),
  ]);
  if (renders.error) throw new Error(`render lookup failed: ${renders.error.message}`);
  if (catalog.error) throw new Error(`sample lookup failed: ${catalog.error.message}`);
  if (own.error) throw new Error(`own generation lookup failed: ${own.error.message}`);

  const renderRows: RenderRow[] = normalizeRows(renders.data, toRenderRow);
  const sampleTuneIds = sampleMatchIds(catalog.data ?? [], name.normalized);
  const tunesById = await loadTunes(supabase, uniqueIds([...sampleTuneIds, ...renderRows.map((r) => r.tune_id)]));

  return nameModeRows({
    display: name.display,
    sampleTuneIds,
    renders: renderRows,
    tunesById,
    ownGenerations: normalizeRows(own.data, toGenerationRow),
    limit,
  });
}

async function myRingtones(supabase: ServiceClient, userId: number, limit: number): Promise<RingtoneOut[]> {
  const { data, error } = await supabase
    .from("generated_ringtones")
    .select(GENERATION_COLUMNS)
    .eq("user_id", userId)
    .eq("status", "ready")
    .order("created_at", { ascending: false })
    .order("id", { ascending: false })
    .limit(MINE_SCAN_LIMIT);
  if (error) throw new Error(`generation lookup failed: ${error.message}`);
  const generations: GenerationRow[] = normalizeRows(data, toGenerationRow);
  if (generations.length === 0) return [];

  const renderIds = uniqueIds(generations.map((row) => row.render_id));
  const [renders, tunesById] = await Promise.all([
    renderIds.length === 0 ? Promise.resolve({ data: [], error: null }) : supabase
      .from("ringtone_renders")
      .select(RENDER_COLUMNS)
      .in("id", renderIds)
      .eq("status", "ready"),
    loadTunes(supabase, uniqueIds(generations.map((row) => row.tune_id))),
  ]);
  if (renders.error) throw new Error(`render lookup failed: ${renders.error.message}`);

  return mineModeRows({
    generations,
    rendersById: new Map(normalizeRows(renders.data, toRenderRow).map((render) => [render.id, render])),
    tunesById,
    limit,
  });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return errorResponse(new ApiError(405, "INVALID_REQUEST", "Use POST"));

  const startedAt = Date.now();
  let redactions: string[] = [];
  let mode = "unknown";
  try {
    const rawBody = await req.json().catch(() => {
      throw new ApiError(400, "INVALID_REQUEST", "Body must be valid JSON");
    });
    const request = parseRequest(rawBody);
    mode = request.mode;

    let name: { display: string; normalized: string } | null = null;
    if (request.mode === "name") {
      const sanitized = sanitizeName(request.name);
      if (!sanitized.ok) throw new ApiError(400, sanitized.code, sanitized.message);
      name = { display: sanitized.display, normalized: sanitized.normalized };
      redactions = [sanitized.display, sanitized.normalized];
    }

    const supabase = createServiceClient();
    const config = await getConfig(supabase);
    const { userId } = await authenticateCaller(supabase, config, request.credentials);
    await requireUser(supabase, userId);

    const ringtones = name
      ? await nameRingtones(supabase, userId, name, request.limit)
      : await myRingtones(supabase, userId, request.limit);

    console.log("name-ringtones: ok", { mode, count: ringtones.length, latency_ms: Date.now() - startedAt });
    return jsonResponse({ mode: request.mode, ringtones });
  } catch (err) {
    if (err instanceof ApiError) {
      if (err.status >= 500) console.error("name-ringtones: failed", { mode, code: err.code, status: err.status });
      return errorResponse(err);
    }
    console.error("name-ringtones: failed", { mode, code: "INTERNAL", detail: safeDetail(err, redactions) });
    return errorResponse(new ApiError(500, "INTERNAL", "Internal server error"));
  }
});
