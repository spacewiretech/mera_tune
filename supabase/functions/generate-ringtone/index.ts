import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import {
  mixpanelInsertId,
  resolveMixpanelToken,
  trackMixpanelEvent,
  updateMixpanelPeople,
} from "../_shared/mixpanel.ts";
import { createServiceClient, type ServiceClient } from "../_shared/supabase-client.ts";
import { resolveUserIdFromToken } from "../_shared/user-sessions.ts";
import { normalizeVoiceGender, ttsVoiceGender } from "../_shared/tts-voices.ts";
import {
  generationFailedProps,
  insertIdParts,
  MATCH_COUNT_CAP,
  NAME_LOOKUP_COMPLETED,
  nameLengthOf,
  nameLookupProps,
  reportsFailure,
  reportsLookup,
  RINGTONE_CREATED,
  RINGTONE_GENERATION_FAILED,
  ringtoneCreatedProps,
  runInBackground,
  type TuneFacts,
} from "./analytics.ts";
import { normalizeAuthoredName, sanitizeName, spokenName } from "./names.ts";
import {
  ApiError,
  MESSAGES,
  mixerFailure,
  safeDetail,
  type Stage,
  stageErrorCode,
  withTimeout,
} from "./errors.ts";
import {
  attemptFilter,
  attemptLimitFor,
  type AuthMode,
  dailyLimitFor,
  freshRenderFilter,
  globalDailyLimit,
  globalRenderFilter,
  isFlagEnabled,
  isStaleProcessing,
  parseBoundedFloat,
  parsePositiveInt,
  type QuotaSnapshot,
  quotaExceeded,
  type QuotaWindow,
  quotaWindow,
  retryableRequestFilter,
  secondsUntilIstMidnight,
  withinLimit,
} from "./quota.ts";
import {
  DEFAULT_TTS_MODEL,
  resolveFallbackModel,
  resolveTtsEndpoint,
  synthesizeName,
  TtsRateLimited,
  TtsRejected,
  type TtsResult,
  TtsUnavailable,
} from "./tts.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

type SupabaseClient = ServiceClient;

const OUTPUT_BUCKET = "generated-ringtones";
const BED_BUCKET = "tune-beds";
const BED_SIGNED_URL_TTL_S = 300;
const MIXER_TIMEOUT_MS = 60_000;
/** A stalled upload must fail inside the `upload` stage, well before the 150 s wall clock. */
const UPLOAD_TIMEOUT_MS = 20_000;
const IN_PROGRESS_RETRY_S = 3;
const MAX_CLIENT_REQUEST_ID_LENGTH = 100;
const MAX_TUNE_ID_LENGTH = 64;
const MAX_LANGUAGE_LENGTH = 40;
const MAX_APP_VERSION_LENGTH = 40;
const BLOCKED_SUBSCRIPTION_STATUSES: ReadonlySet<string> = new Set(["none", "expired", "cancelled"]);

const TUNE_COLUMNS = [
  "id",
  "name",
  "category_id",
  "gender",
  "language",
  "tune_url",
  "is_active",
  "is_personalizable",
  "featured_rank",
  "bed_path",
  "name_slot_start_ms",
  "name_slot_end_ms",
  "sample_name",
  "tts_voice_name",
  "tts_style_prompt",
  "slot_gain_db",
  "duck_db",
  "title_template",
  "assets_version",
  "category:category_id(id, name, image_url)",
].join(", ");

type GenerateRequest = {
  userId: number | null;
  userToken: string | null;
  tuneId: string;
  name: unknown;
  language: string;
  clientRequestId: string | null;
  appVersion: string | null;
};

/** Strings, never null: the app's `Category` model has non-null defaults and rejects explicit null. */
type TuneCategory = { id: string; name: string; image_url: string };

type TuneRow = {
  id: string;
  name: string;
  category_id: string | null;
  gender: string | null;
  language: string | null;
  tune_url: string;
  is_active: boolean;
  is_personalizable: boolean;
  featured_rank: number | null;
  bed_path: string | null;
  name_slot_start_ms: number | null;
  name_slot_end_ms: number | null;
  sample_name: string | null;
  tts_voice_name: string | null;
  tts_style_prompt: string | null;
  slot_gain_db: number | string | null;
  duck_db: number | string | null;
  title_template: string | null;
  assets_version: number;
  category: TuneCategory | null;
};

type MixResult = {
  bytes: ArrayBuffer;
  durationMs: number | null;
  ttsDurationMs: number | null;
  tempo: number | null;
};

type LogRowFields = {
  user_id: number;
  tune_id: string;
  render_id: string | null;
  client_request_id: string | null;
  name_display: string;
  name_normalized: string;
  language: string;
  voice: string;
  title: string | null;
  cached: boolean;
  status: "processing" | "ready";
  auth_mode: AuthMode;
  latency_ms: number | null;
  completed_at: string | null;
};

/** Set once the exact-match check ran; `matchCount` resolves in the background and never rejects. */
type NameLookup = {
  sampleId: string;
  language: string;
  nameLength: number;
  exactMatch: boolean;
  matchCount: Promise<number | null>;
  completedAtMs: number;
};

/** What the Mixpanel events know about one request, filled in as it advances. */
type AnalyticsState = {
  startedAtMs: number;
  supabase: SupabaseClient | null;
  config: Record<string, string> | null;
  body: GenerateRequest | null;
  userId: number | null;
  tune: TuneRow | null;
  language: string | null;
  lookup: NameLookup | null;
};

type CreatedOutcome = {
  generationId: string;
  cached: boolean;
  durationMs: number | null;
  latencyMs: number;
  completedAt: string;
  quota: QuotaSnapshot;
};

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function errorResponse(err: ApiError): Response {
  const body: Record<string, unknown> = { error: err.message, error_code: err.code };
  if (err.retryAfterSeconds !== undefined) body.retry_after_seconds = err.retryAfterSeconds;
  if (err.quota) body.quota = err.quota;
  return jsonResponse(body, err.status);
}

async function getConfig(supabase: SupabaseClient): Promise<Record<string, string>> {
  const { data, error } = await supabase.from("app_config").select("key, value");
  if (error) throw new Error(`Failed to load app_config: ${error.message}`);
  const config: Record<string, string> = {};
  for (const row of data ?? []) config[String(row.key)] = String(row.value ?? "");
  return config;
}

function secret(envName: string, config: Record<string, string>, configKey: string): string {
  return Deno.env.get(envName)?.trim() || config[configKey]?.trim() || "";
}

function optionalString(value: unknown, maxLength: number): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (!trimmed) return null;
  return trimmed.slice(0, maxLength);
}

function parseBody(raw: unknown): GenerateRequest {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    throw new ApiError(400, "INVALID_REQUEST", "Request body must be a JSON object");
  }
  const body = raw as Record<string, unknown>;

  const tuneId = optionalString(body.tune_id, MAX_TUNE_ID_LENGTH + 1);
  if (!tuneId || tuneId.length > MAX_TUNE_ID_LENGTH) {
    throw new ApiError(400, "INVALID_REQUEST", "tune_id is required");
  }

  const language = optionalString(body.language, MAX_LANGUAGE_LENGTH + 1);
  if (!language || language.length > MAX_LANGUAGE_LENGTH) {
    throw new ApiError(400, "INVALID_REQUEST", "language is required");
  }

  const clientRequestId = optionalString(body.client_request_id, MAX_CLIENT_REQUEST_ID_LENGTH + 1);
  if (clientRequestId && clientRequestId.length > MAX_CLIENT_REQUEST_ID_LENGTH) {
    throw new ApiError(400, "INVALID_REQUEST", "client_request_id is too long");
  }

  let userId: number | null = null;
  if (body.user_id !== undefined && body.user_id !== null && body.user_id !== "") {
    const parsed = typeof body.user_id === "number" ? body.user_id : Number(String(body.user_id).trim());
    if (!Number.isInteger(parsed) || parsed <= 0) {
      throw new ApiError(400, "INVALID_REQUEST", "user_id must be a positive integer");
    }
    userId = parsed;
  }

  const userToken = optionalString(body.user_token, 512);
  if (body.user_token !== undefined && body.user_token !== null && typeof body.user_token !== "string") {
    throw new ApiError(400, "INVALID_REQUEST", "user_token must be a string");
  }

  if (!userId && !userToken) {
    throw new ApiError(401, "UNAUTHORIZED", "Please log in again");
  }

  return {
    userId,
    userToken,
    tuneId,
    name: body.name,
    language,
    clientRequestId,
    appVersion: optionalString(body.app_version, MAX_APP_VERSION_LENGTH),
  };
}

function normalizeCategory(raw: unknown): TuneCategory | null {
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (!value || typeof value !== "object") return null;
  const record = value as Record<string, unknown>;
  if (record.id === undefined || record.id === null) return null;
  return {
    id: String(record.id),
    name: record.name === undefined || record.name === null ? "" : String(record.name),
    image_url: typeof record.image_url === "string" ? record.image_url : "",
  };
}

function toNumber(value: unknown, fallback: number): number {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    const parsed = Number.parseFloat(value);
    if (Number.isFinite(parsed)) return parsed;
  }
  return fallback;
}

async function loadTune(supabase: SupabaseClient, tuneId: string): Promise<TuneRow> {
  const { data, error } = await supabase.from("tune").select(TUNE_COLUMNS).eq("id", tuneId).maybeSingle();
  if (error) {
    // Invalid uuid text hits 22P02; treat it like an unknown tune instead of INTERNAL.
    if (error.code === "22P02") throw new ApiError(404, "TUNE_NOT_FOUND", "This song is not available");
    throw new Error(`tune lookup failed: ${error.message}`);
  }
  // TUNE_COLUMNS is a runtime string, so supabase-js cannot infer the row type; cast once here.
  const row = (data ?? null) as unknown as TuneRow | null;
  if (!row || row.is_active === false) {
    throw new ApiError(404, "TUNE_NOT_FOUND", "This song is not available");
  }
  return { ...row, category: normalizeCategory(row.category) };
}

function assertPersonalizable(tune: TuneRow): void {
  if (
    !tune.is_personalizable ||
    !tune.bed_path ||
    tune.name_slot_start_ms === null || tune.name_slot_start_ms === undefined ||
    tune.name_slot_end_ms === null || tune.name_slot_end_ms === undefined ||
    !tune.tts_voice_name ||
    !tune.sample_name
  ) {
    throw new ApiError(422, "TUNE_NOT_PERSONALIZABLE", MESSAGES.notPersonalizable);
  }
  const voiceGender = ttsVoiceGender(tune.tts_voice_name);
  const tuneGender = normalizeVoiceGender(tune.gender);
  if (!voiceGender || !tuneGender || voiceGender !== tuneGender) {
    // Authoring bug, not a transient failure: logged for ops, non-retryable for the app.
    console.error("generate-ringtone: tune voice misconfigured", {
      tune_id: tune.id,
      tts_voice_name: tune.tts_voice_name,
      gender: tune.gender,
      voice_gender: voiceGender,
    });
    throw new ApiError(422, "TUNE_NOT_PERSONALIZABLE", MESSAGES.notPersonalizable);
  }
}

function buildTitle(template: string | null, display: string): string | null {
  const trimmed = template?.trim();
  if (!trimmed) return null;
  return trimmed.includes("{name}") ? trimmed.split("{name}").join(display) : `${trimmed} ${display}`;
}

function inProgressError(): ApiError {
  return new ApiError(409, "GENERATION_IN_PROGRESS", "This ringtone is already being made", {
    retryAfterSeconds: IN_PROGRESS_RETRY_S,
  });
}

function quotaError(now: Date, quota: QuotaSnapshot, message: string = MESSAGES.dailyLimit): ApiError {
  return new ApiError(429, "QUOTA_EXCEEDED", message, {
    retryAfterSeconds: secondsUntilIstMidnight(now),
    quota,
  });
}

/** Count over one user's cached=false generated_ringtones rows created today; callers add an `or=` filter. */
function userRowsToday(supabase: SupabaseClient, userId: number, window: QuotaWindow) {
  return supabase
    .from("generated_ringtones")
    .select("id", { count: "exact", head: true })
    .eq("user_id", userId)
    .eq("cached", false)
    .gte("created_at", window.dayStartIso);
}

async function countUserFreshRenders(
  supabase: SupabaseClient,
  userId: number,
  window: QuotaWindow,
): Promise<number> {
  const { count, error } = await userRowsToday(supabase, userId, window).or(freshRenderFilter(window.processingSinceIso));
  if (error) throw new Error(`user quota count failed: ${error.message}`);
  return count ?? 0;
}

/** Every fresh-render attempt today, whatever its outcome (admission rejections excluded). */
async function countUserAttempts(
  supabase: SupabaseClient,
  userId: number,
  window: QuotaWindow,
): Promise<number> {
  const { count, error } = await userRowsToday(supabase, userId, window).or(attemptFilter());
  if (error) throw new Error(`user attempt count failed: ${error.message}`);
  return count ?? 0;
}

async function countGlobalRenders(supabase: SupabaseClient, window: QuotaWindow): Promise<number> {
  const { count, error } = await supabase
    .from("ringtone_renders")
    .select("id", { count: "exact", head: true })
    .gte("created_at", window.dayStartIso)
    .or(globalRenderFilter(window.processingSinceIso));
  if (error) throw new Error(`global quota count failed: ${error.message}`);
  return count ?? 0;
}

/** Ids of the first `limit` of today's rows matching `filter`, in creation order. */
async function firstUserRowIds(
  supabase: SupabaseClient,
  userId: number,
  window: QuotaWindow,
  filter: string,
  limit: number,
): Promise<string[]> {
  const { data, error } = await supabase
    .from("generated_ringtones")
    .select("id")
    .eq("user_id", userId)
    .eq("cached", false)
    .gte("created_at", window.dayStartIso)
    .or(filter)
    .order("created_at", { ascending: true })
    .order("id", { ascending: true })
    .limit(limit);
  if (error) throw new Error(`admission lookup failed: ${error.message}`);
  return (data ?? []).map((row) => String(row.id));
}

/**
 * Counting and inserting are separate statements, so parallel requests can all pass the
 * pre-insert quota check. After our row exists, re-read today's rows in creation order: only the
 * earliest `limit` rows may go on to bill Gemini. A rejected row is marked QUOTA_EXCEEDED, which
 * the attempt count ignores, so a lost race does not burn an attempt.
 */
async function admitAfterInsert(
  supabase: SupabaseClient,
  userId: number,
  generationId: string,
  window: QuotaWindow,
  dailyLimit: number,
  attemptLimit: number,
): Promise<"ok" | "daily_limit" | "attempt_limit"> {
  const [fresh, attempts] = await Promise.all([
    firstUserRowIds(supabase, userId, window, freshRenderFilter(window.processingSinceIso), dailyLimit),
    firstUserRowIds(supabase, userId, window, attemptFilter(), attemptLimit),
  ]);
  if (!withinLimit(fresh, generationId, dailyLimit)) return "daily_limit";
  if (!withinLimit(attempts, generationId, attemptLimit)) return "attempt_limit";
  return "ok";
}

/**
 * A retry with the same client_request_id after a failed or abandoned attempt. The old row keeps
 * its outcome (ops SQL, attempt cap) and gives up the client_request_id so the retry inserts a
 * fresh row. The conditional update is the claim: it only matches while the row still holds the
 * client_request_id, so of two concurrent retries exactly one matches (Postgres re-checks the WHERE
 * on the row the winner committed); the other gets 409 and re-posts, then finds the winner's row.
 */
async function releaseClientRequestId(
  supabase: SupabaseClient,
  generationId: string,
  clientRequestId: string,
  window: QuotaWindow,
): Promise<void> {
  const { data, error } = await supabase
    .from("generated_ringtones")
    .update({ client_request_id: null })
    .eq("id", generationId)
    .eq("client_request_id", clientRequestId)
    .or(retryableRequestFilter(window.processingSinceIso))
    .select("id");
  if (error) throw new Error(`generated_ringtones release failed: ${error.message}`);
  if (!data || data.length === 0) throw inProgressError();
}

async function writeLogRow(supabase: SupabaseClient, fields: LogRowFields): Promise<string> {
  const { data, error } = await supabase.from("generated_ringtones").insert(fields).select("id").single();
  if (error) {
    // Unique (user_id, client_request_id): a parallel request with the same id got there first.
    if (error.code === "23505") throw inProgressError();
    throw new Error(`generated_ringtones insert failed: ${error.message}`);
  }
  return String(data.id);
}

async function markFailed(
  supabase: SupabaseClient,
  renderId: string | null,
  generationId: string | null,
  code: string,
  detail: string | null,
  latencyMs: number,
  now: string,
): Promise<void> {
  if (renderId) {
    const { error } = await supabase
      .from("ringtone_renders")
      .update({ status: "failed", error_code: code, error: detail, updated_at: now, completed_at: now })
      .eq("id", renderId)
      .eq("status", "processing");
    if (error) console.error("generate-ringtone: could not mark render failed", { render_id: renderId, message: error.message });
  }
  if (generationId) {
    const { error } = await supabase
      .from("generated_ringtones")
      .update({ status: "failed", error_code: code, latency_ms: latencyMs, completed_at: now })
      .eq("id", generationId)
      .eq("status", "processing");
    if (error) console.error("generate-ringtone: could not mark generation failed", { generation_id: generationId, message: error.message });
  }
}

function headerInt(response: Response, name: string): number | null {
  const raw = response.headers.get(name);
  if (raw === null) return null;
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) ? parsed : null;
}

function headerFloat(response: Response, name: string): number | null {
  const raw = response.headers.get(name);
  if (raw === null) return null;
  const parsed = Number.parseFloat(raw);
  return Number.isFinite(parsed) ? parsed : null;
}

function isAbortError(err: unknown): boolean {
  return (err as { name?: string } | null)?.name === "AbortError";
}

async function callMixer(mixerUrl: string, mixerSecret: string, payload: Record<string, unknown>): Promise<MixResult> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), MIXER_TIMEOUT_MS);
  try {
    const response = await fetch(`${mixerUrl}/mix`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-mixer-secret": mixerSecret,
      },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });

    if (!response.ok) {
      const text = await response.text().catch(() => "");
      let code = "";
      let message = "";
      try {
        const parsed = JSON.parse(text) as { code?: unknown; error?: unknown };
        code = String(parsed.code ?? "");
        message = String(parsed.error ?? "");
      } catch {
        message = text;
      }
      const detail = `mixer ${response.status} ${code || "no_code"}: ${message.slice(0, 200)}`;
      const failure = mixerFailure(response.status, code);
      if (failure) {
        throw new ApiError(failure.status, failure.code, failure.message, {
          detail,
          logLevel: failure.logLevel ?? undefined,
        });
      }
      throw new Error(detail);
    }

    const contentType = (response.headers.get("content-type") ?? "").toLowerCase();
    if (!contentType.includes("audio/mpeg")) {
      throw new Error(`mixer returned unexpected content-type "${contentType || "(none)"}"`);
    }
    const bytes = await response.arrayBuffer();
    if (bytes.byteLength === 0) throw new Error("mixer returned an empty body");

    return {
      bytes,
      durationMs: headerInt(response, "X-Mix-Duration-Ms"),
      ttsDurationMs: headerInt(response, "X-Mix-Tts-Duration-Ms"),
      tempo: headerFloat(response, "X-Mix-Tempo"),
    };
  } catch (err) {
    if (isAbortError(err)) throw new Error(`mixer timeout after ${MIXER_TIMEOUT_MS} ms`);
    throw err;
  } finally {
    clearTimeout(timer);
  }
}

function successBody(params: {
  generationId: string;
  renderId: string | null;
  ringtoneUrl: string;
  title: string | null;
  tune: TuneRow;
  language: string;
  durationMs: number | null;
  cached: boolean;
  quota: QuotaSnapshot;
}): Record<string, unknown> {
  return {
    generation_id: params.generationId,
    render_id: params.renderId,
    ringtone_url: params.ringtoneUrl,
    title: params.title,
    tune_id: params.tune.id,
    tune_name: params.tune.name,
    category: params.tune.category,
    language: params.language,
    voice: params.tune.gender ?? "",
    duration_ms: params.durationMs,
    cached: params.cached,
    quota: params.quota,
  };
}

/** Ready renders of this name in this language, any sample, capped. Resolves null on any error. */
async function countNameMatches(
  supabase: SupabaseClient,
  nameNormalized: string,
  language: string,
): Promise<number | null> {
  try {
    const { data, error } = await supabase
      .from("ringtone_renders")
      .select("id")
      .eq("name_normalized", nameNormalized)
      .eq("language", language)
      .eq("status", "ready")
      .limit(MATCH_COUNT_CAP);
    if (error) {
      console.warn("generate-ringtone: name lookup failed", { code: error.code });
      return null;
    }
    return (data ?? []).length;
  } catch (err) {
    console.warn("generate-ringtone: name lookup failed", { error: err instanceof Error ? err.name : "unknown" });
    return null;
  }
}

function tuneFacts(tune: TuneRow): TuneFacts {
  return { tuneId: tune.id, categoryName: tune.category?.name, gender: tune.gender };
}

/** The loaded tune, else the requested id (e.g. TUNE_NOT_FOUND). */
function analyticsTune(state: AnalyticsState): TuneFacts | null {
  if (state.tune) return tuneFacts(state.tune);
  return state.body ? { tuneId: state.body.tuneId } : null;
}

async function mixpanelToken(state: AnalyticsState): Promise<string> {
  const token = resolveMixpanelToken(state.config ?? {});
  if (token || state.config) return token;
  state.config = await getConfig(state.supabase ??= createServiceClient());
  return resolveMixpanelToken(state.config);
}

/** User of a request that failed before auth (e.g. INVALID_NAME), resolved the way auth would. */
async function attributeUser(state: AnalyticsState): Promise<number | null> {
  const body = state.body;
  if (!body) return null;
  const supabase = state.supabase ??= createServiceClient();
  if (body.userToken) {
    const session = await resolveUserIdFromToken(supabase, body.userToken);
    if (!session || (body.userId !== null && body.userId !== session.userId)) return null;
    return session.userId;
  }
  state.config ??= await getConfig(supabase);
  return isFlagEnabled(state.config, "generate_allow_legacy_user_id", false) ? body.userId : null;
}

async function trackLookup(
  state: AnalyticsState,
  token: string,
  distinctId: string,
  generationId: string | null,
): Promise<void> {
  const lookup = state.lookup;
  if (!lookup) return;
  const clientRequestId = state.body?.clientRequestId ?? null;
  const props = nameLookupProps({
    sampleId: lookup.sampleId,
    language: lookup.language,
    nameLength: lookup.nameLength,
    exactMatch: lookup.exactMatch,
    matchCount: await lookup.matchCount,
    generationId,
    clientRequestId,
    appVersion: state.body?.appVersion ?? null,
  });
  const insertId = await mixpanelInsertId(...insertIdParts(NAME_LOOKUP_COMPLETED, {
    generationId,
    clientRequestId,
    distinctId,
    requestStartedAtMs: state.startedAtMs,
  }));
  await trackMixpanelEvent(token, distinctId, NAME_LOOKUP_COMPLETED, props, { insertId, timeMs: lookup.completedAtMs });
}

/** name_lookup_completed + ringtone_created + last_ringtone_category, after the response. */
function reportCreated(state: AnalyticsState, created: CreatedOutcome): void {
  runInBackground(async () => {
    const tune = state.tune;
    if (state.userId === null || !tune) return;
    const distinctId = String(state.userId);
    const token = await mixpanelToken(state);
    const clientRequestId = state.body?.clientRequestId ?? null;
    const props = ringtoneCreatedProps({
      tune: tuneFacts(tune),
      language: state.language,
      generationId: created.generationId,
      clientRequestId,
      appVersion: state.body?.appVersion ?? null,
      cached: created.cached,
      audioDurationMs: created.durationMs,
      latencyMs: created.latencyMs,
      quota: created.quota,
    });
    const insertId = await mixpanelInsertId(...insertIdParts(RINGTONE_CREATED, {
      generationId: created.generationId,
      clientRequestId,
      distinctId,
      requestStartedAtMs: state.startedAtMs,
    }));
    await Promise.all([
      trackLookup(state, token, distinctId, created.generationId),
      trackMixpanelEvent(token, distinctId, RINGTONE_CREATED, props, {
        insertId,
        timeMs: Date.parse(created.completedAt),
      }),
      updateMixpanelPeople(token, distinctId, { set: { last_ringtone_category: tune.category?.name } }),
    ]);
  });
}

/**
 * name_lookup_completed (when the lookup ran) + ringtone_generation_failed, after the response.
 * Nothing for UNAUTHORIZED (the app reports it) or the busy codes the app re-posts.
 */
function reportFailure(
  state: AnalyticsState,
  error: ApiError,
  generationId: string | null,
  latencyMs: number,
  failedAtMs: number,
): void {
  const sendFailure = reportsFailure(error.code);
  const sendLookup = state.lookup !== null && reportsLookup(error.code);
  if (!sendFailure && !sendLookup) return;
  runInBackground(async () => {
    const userId = state.userId ?? await attributeUser(state);
    if (userId === null) return;
    const distinctId = String(userId);
    const token = await mixpanelToken(state);
    const tasks: Promise<unknown>[] = [];
    if (sendLookup) tasks.push(trackLookup(state, token, distinctId, generationId));
    if (sendFailure) {
      const clientRequestId = state.body?.clientRequestId ?? null;
      const props = generationFailedProps({
        tune: analyticsTune(state),
        language: state.language ?? state.body?.language ?? null,
        generationId,
        clientRequestId,
        appVersion: state.body?.appVersion ?? null,
        code: error.code,
        httpStatus: error.status,
        latencyMs,
        quota: error.quota,
      });
      const insertId = await mixpanelInsertId(...insertIdParts(RINGTONE_GENERATION_FAILED, {
        generationId,
        clientRequestId,
        distinctId,
        requestStartedAtMs: state.startedAtMs,
      }));
      tasks.push(trackMixpanelEvent(token, distinctId, RINGTONE_GENERATION_FAILED, props, { insertId, timeMs: failedAtMs }));
    }
    await Promise.all(tasks);
  });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") {
    return errorResponse(new ApiError(405, "INVALID_REQUEST", "Use POST"));
  }

  const startedAt = Date.now();
  let stage: Stage = "parse";
  let supabase: SupabaseClient | null = null;
  let renderId: string | null = null;
  let generationId: string | null = null;
  /** Forms of the user's name that must never reach a log line or an error column. */
  let redactions: string[] = [];
  const analytics: AnalyticsState = {
    startedAtMs: startedAt,
    supabase: null,
    config: null,
    body: null,
    userId: null,
    tune: null,
    language: null,
    lookup: null,
  };

  try {
    // 1. Body + name.
    const rawBody = await req.json().catch(() => {
      throw new ApiError(400, "INVALID_REQUEST", "Body must be valid JSON");
    });
    const body = parseBody(rawBody);
    analytics.body = body;

    const name = sanitizeName(body.name);
    if (!name.ok) throw new ApiError(400, name.code, name.message);
    // What Gemini says depends on the cache key only; the per-user title keeps the typed casing.
    const spoken = spokenName(name.normalized);
    redactions = [name.display, spoken];

    // Config + kill switch + secrets.
    stage = "config";
    supabase = createServiceClient();
    analytics.supabase = supabase;
    const config = await getConfig(supabase);
    analytics.config = config;

    if (!isFlagEnabled(config, "generate_enabled", true)) {
      throw new ApiError(503, "SERVICE_UNAVAILABLE", "Ringtone generation is paused right now. Please try again later.");
    }
    const geminiApiKey = secret("GEMINI_API_KEY", config, "gemini_api_key");
    const mixerUrl = secret("MIXER_URL", config, "mixer_url").replace(/\/+$/, "");
    const mixerSecret = secret("MIXER_SHARED_SECRET", config, "mixer_shared_secret");
    if (!geminiApiKey || !mixerUrl || !mixerSecret) {
      console.error("generate-ringtone: missing secrets", {
        gemini: Boolean(geminiApiKey),
        mixer_url: Boolean(mixerUrl),
        mixer_secret: Boolean(mixerSecret),
      });
      throw new ApiError(503, "SERVICE_UNAVAILABLE", MESSAGES.notConfigured);
    }
    const ttsModel = config.gemini_tts_model?.trim() || DEFAULT_TTS_MODEL;
    const ttsFallbackModel = resolveFallbackModel(config.gemini_tts_fallback_model);
    const ttsEndpoint = resolveTtsEndpoint(config.gemini_tts_endpoint);
    if (!ttsEndpoint) {
      console.error("generate-ringtone: unsupported gemini_tts_endpoint; only generate_content is implemented", {
        value: String(config.gemini_tts_endpoint ?? "").slice(0, 40),
      });
      throw new ApiError(503, "SERVICE_UNAVAILABLE", MESSAGES.notConfigured);
    }
    const maxDurationMs = parsePositiveInt(config.generate_max_duration_ms, 30_000);
    const maxTempo = parseBoundedFloat(config.generate_tts_max_tempo, 1.3, 1, 2);

    // 2. Auth.
    stage = "auth";
    let userId: number;
    let authMode: AuthMode;
    if (body.userToken) {
      const session = await resolveUserIdFromToken(supabase, body.userToken);
      if (!session) throw new ApiError(401, "UNAUTHORIZED", "Session expired. Please log in again.");
      if (body.userId !== null && body.userId !== session.userId) {
        throw new ApiError(401, "UNAUTHORIZED", "Session does not match this account. Please log in again.");
      }
      userId = session.userId;
      authMode = "token";
    } else {
      if (!isFlagEnabled(config, "generate_allow_legacy_user_id", false) || body.userId === null) {
        throw new ApiError(401, "UNAUTHORIZED", "Please log in again");
      }
      userId = body.userId;
      authMode = "legacy_user_id";
    }
    analytics.userId = userId;

    const { data: user, error: userError } = await supabase
      .from("users")
      .select("id, status")
      .eq("id", userId)
      .maybeSingle();
    if (userError) throw new Error(`user lookup failed: ${userError.message}`);
    if (!user) throw new ApiError(401, "UNAUTHORIZED", "Account not found. Please log in again.");

    if (isFlagEnabled(config, "generate_require_subscription", false)) {
      const status = String(user.status ?? "none").toLowerCase();
      if (BLOCKED_SUBSCRIPTION_STATUSES.has(status)) {
        throw new ApiError(403, "SUBSCRIPTION_REQUIRED", "A subscription is required to create ringtones");
      }
    }

    // 3. Language gate.
    stage = "language";
    const { data: languageRows, error: languageError } = await supabase
      .from("generation_languages")
      .select("language, tts_enabled");
    if (languageError) throw new Error(`generation_languages lookup failed: ${languageError.message}`);
    const requestedLanguage = body.language.toLowerCase();
    const languageRow = (languageRows ?? []).find((row) => String(row.language).toLowerCase() === requestedLanguage);
    if (!languageRow || languageRow.tts_enabled !== true) {
      throw new ApiError(422, "UNSUPPORTED_LANGUAGE", `${body.language} is not supported for name ringtones yet`);
    }
    const language = String(languageRow.language);
    analytics.language = language;

    const now = new Date();
    const window = quotaWindow(now);
    const dailyLimit = dailyLimitFor(config, authMode);

    // 4. Idempotency by (user_id, client_request_id).
    stage = "idempotency";
    if (body.clientRequestId) {
      const { data: existing, error: existingError } = await supabase
        .from("generated_ringtones")
        .select("id, status, cached, render_id, title, tune_id, language, created_at")
        .eq("user_id", userId)
        .eq("client_request_id", body.clientRequestId)
        .maybeSingle();
      if (existingError) throw new Error(`idempotency lookup failed: ${existingError.message}`);

      if (existing) {
        if (existing.status === "ready") {
          stage = "tune";
          const tune = await loadTune(supabase, String(existing.tune_id));
          let ringtoneUrl = tune.tune_url;
          let durationMs: number | null = null;
          if (existing.render_id) {
            const { data: render } = await supabase
              .from("ringtone_renders")
              .select("public_url, duration_ms")
              .eq("id", existing.render_id)
              .maybeSingle();
            if (render?.public_url) {
              ringtoneUrl = String(render.public_url);
              durationMs = render.duration_ms ?? null;
            }
          }
          const usedToday = await countUserFreshRenders(supabase, userId, window);
          return jsonResponse(successBody({
            generationId: String(existing.id),
            renderId: existing.render_id ? String(existing.render_id) : null,
            ringtoneUrl,
            title: existing.title ?? null,
            tune,
            language: String(existing.language ?? language),
            durationMs,
            cached: Boolean(existing.cached),
            quota: { used_today: usedToday, daily_limit: dailyLimit },
          }));
        }
        if (existing.status === "processing" && !isStaleProcessing(String(existing.created_at), now)) {
          throw inProgressError();
        }
        // failed, or processing for longer than the stale window: claim the retry (409 if another
        // retry already did); this attempt then writes its own row below.
        await releaseClientRequestId(supabase, String(existing.id), body.clientRequestId, window);
      }
    }

    // 5. Tune.
    stage = "tune";
    const tune = await loadTune(supabase, body.tuneId);
    analytics.tune = tune;
    assertPersonalizable(tune);
    // name_lookup_completed: the cross-sample count runs alongside the cache check and is only
    // awaited by the background event.
    const matchCount = countNameMatches(supabase, name.normalized, language);
    const completeLookup = (exactMatch: boolean) => {
      analytics.lookup = {
        sampleId: tune.id,
        language,
        nameLength: nameLengthOf(name.display),
        exactMatch,
        matchCount,
        completedAtMs: Date.now(),
      };
    };
    const ttsVoice = tune.tts_voice_name!;
    const voice = tune.gender ?? "";
    const title = buildTitle(tune.title_template, name.display);
    // The style prompt follows the song, not the request: the cache key carries no language, and a
    // Hindi song reached through the Hindi fallback must sound the same for every requester.
    const ttsLanguage = tune.language?.trim() || language;

    const baseLogFields = {
      user_id: userId,
      tune_id: tune.id,
      client_request_id: body.clientRequestId,
      name_display: name.display,
      name_normalized: name.normalized,
      language,
      voice,
      title,
      auth_mode: authMode,
    };

    // Sample name: the stock recording already sings this name.
    if (normalizeAuthoredName(tune.sample_name) === name.normalized) {
      completeLookup(true);
      // Counted before the ready row exists (cached rows never count): a failed count must not
      // turn a delivered ringtone into ringtone_generation_failed.
      const usedToday = await countUserFreshRenders(supabase, userId, window);
      const quota = { used_today: usedToday, daily_limit: dailyLimit };
      const completedAt = new Date().toISOString();
      const latencyMs = Date.now() - startedAt;
      generationId = await writeLogRow(supabase, {
        ...baseLogFields,
        render_id: null,
        cached: true,
        status: "ready",
        latency_ms: latencyMs,
        completed_at: completedAt,
      });
      reportCreated(analytics, { generationId, cached: true, durationMs: null, latencyMs, completedAt, quota });
      return jsonResponse(successBody({
        generationId,
        renderId: null,
        ringtoneUrl: tune.tune_url,
        title,
        tune,
        language,
        durationMs: null,
        cached: true,
        quota,
      }));
    }

    // 6. Render cache.
    stage = "cache";
    const { data: cachedRender, error: cacheError } = await supabase
      .from("ringtone_renders")
      .select("id, public_url, duration_ms")
      .eq("tune_id", tune.id)
      .eq("assets_version", tune.assets_version)
      .eq("name_normalized", name.normalized)
      .eq("tts_voice", ttsVoice)
      .eq("status", "ready")
      .maybeSingle();
    if (cacheError) throw new Error(`render cache lookup failed: ${cacheError.message}`);
    completeLookup(Boolean(cachedRender?.public_url));

    if (cachedRender?.public_url) {
      const usedToday = await countUserFreshRenders(supabase, userId, window);
      const quota = { used_today: usedToday, daily_limit: dailyLimit };
      const completedAt = new Date().toISOString();
      const latencyMs = Date.now() - startedAt;
      generationId = await writeLogRow(supabase, {
        ...baseLogFields,
        render_id: String(cachedRender.id),
        cached: true,
        status: "ready",
        latency_ms: latencyMs,
        completed_at: completedAt,
      });
      const durationMs = cachedRender.duration_ms ?? null;
      reportCreated(analytics, { generationId, cached: true, durationMs, latencyMs, completedAt, quota });
      return jsonResponse(successBody({
        generationId,
        renderId: String(cachedRender.id),
        ringtoneUrl: String(cachedRender.public_url),
        title,
        tune,
        language,
        durationMs,
        cached: true,
        quota,
      }));
    }

    // A render of this exact key already came back NAME_TOO_LONG from the mixer. That failure is
    // decided by the slot width and the name, so another Gemini call would be billed for nothing.
    // Clearing it after re-authoring the slot or raising generate_tts_max_tempo: bump
    // tune.assets_version, or delete those failed ringtone_renders rows.
    const { data: tooLong, error: tooLongError } = await supabase
      .from("ringtone_renders")
      .select("id")
      .eq("tune_id", tune.id)
      .eq("assets_version", tune.assets_version)
      .eq("name_normalized", name.normalized)
      .eq("tts_voice", ttsVoice)
      .eq("error_code", "NAME_TOO_LONG_FOR_SONG")
      .limit(1)
      .maybeSingle();
    if (tooLongError) throw new Error(`name-too-long lookup failed: ${tooLongError.message}`);
    if (tooLong) throw new ApiError(422, "NAME_TOO_LONG_FOR_SONG", MESSAGES.nameTooLong);

    // 7. Same render already in flight (another user or a double tap).
    const { data: inflight, error: inflightError } = await supabase
      .from("ringtone_renders")
      .select("id")
      .eq("tune_id", tune.id)
      .eq("assets_version", tune.assets_version)
      .eq("name_normalized", name.normalized)
      .eq("tts_voice", ttsVoice)
      .eq("status", "processing")
      .gt("created_at", window.inflightSinceIso)
      .limit(1)
      .maybeSingle();
    if (inflightError) throw new Error(`inflight lookup failed: ${inflightError.message}`);
    if (inflight) throw inProgressError();

    // 8. Quota: fresh renders and attempts per user (by auth mode), then global.
    stage = "quota";
    const attemptLimit = attemptLimitFor(config, dailyLimit);
    const [usedToday, attemptsToday] = await Promise.all([
      countUserFreshRenders(supabase, userId, window),
      countUserAttempts(supabase, userId, window),
    ]);
    if (quotaExceeded(usedToday, dailyLimit)) {
      throw quotaError(now, { used_today: usedToday, daily_limit: dailyLimit });
    }
    if (quotaExceeded(attemptsToday, attemptLimit)) {
      console.warn("generate-ringtone: daily attempt cap reached", { user_id: userId, attempts: attemptsToday, limit: attemptLimit });
      throw quotaError(now, { used_today: usedToday, daily_limit: dailyLimit }, MESSAGES.tooManyTries);
    }
    const globalUsed = await countGlobalRenders(supabase, window);
    if (quotaExceeded(globalUsed, globalDailyLimit(config))) {
      console.warn("generate-ringtone: global daily limit reached", { used: globalUsed });
      throw new ApiError(429, "SERVICE_BUSY", "Too many ringtones are being made right now. Please try again later.", {
        retryAfterSeconds: secondsUntilIstMidnight(now),
      });
    }

    // 9. Bookkeeping rows.
    stage = "insert";
    const { data: renderRow, error: renderInsertError } = await supabase
      .from("ringtone_renders")
      .insert({
        tune_id: tune.id,
        assets_version: tune.assets_version,
        name_normalized: name.normalized,
        name_display: spoken,
        language: ttsLanguage,
        voice,
        tts_model: ttsModel,
        tts_voice: ttsVoice,
        status: "processing",
      })
      .select("id")
      .single();
    if (renderInsertError) throw new Error(`ringtone_renders insert failed: ${renderInsertError.message}`);
    renderId = String(renderRow.id);

    generationId = await writeLogRow(supabase, {
      ...baseLogFields,
      render_id: renderId,
      cached: false,
      status: "processing",
      latency_ms: null,
      completed_at: null,
    });

    // Close the count-then-insert race before anything is billed.
    stage = "admission";
    const admission = await admitAfterInsert(supabase, userId, generationId, window, dailyLimit, attemptLimit);
    if (admission !== "ok") {
      console.warn("generate-ringtone: rejected after insert", { user_id: userId, generation_id: generationId, reason: admission });
      throw admission === "daily_limit"
        ? quotaError(now, { used_today: dailyLimit, daily_limit: dailyLimit })
        : quotaError(now, { used_today: usedToday, daily_limit: dailyLimit }, MESSAGES.tooManyTries);
    }

    // 10. TTS.
    stage = "tts";
    let tts: TtsResult;
    try {
      tts = await synthesizeName(
        { apiKey: geminiApiKey, model: ttsModel, fallbackModel: ttsFallbackModel, endpoint: ttsEndpoint },
        {
          name: spoken,
          voiceName: ttsVoice,
          stylePrompt: tune.tts_style_prompt,
          category: tune.category?.name ?? null,
          language: ttsLanguage,
        },
      );
    } catch (err) {
      if (err instanceof TtsRateLimited) {
        throw new ApiError(503, "TTS_RATE_LIMITED", "The voice service is busy. Retrying shortly.", {
          retryAfterSeconds: err.retryAfterSeconds,
        });
      }
      if (err instanceof TtsRejected) {
        console.warn("generate-ringtone: tts rejected prompt", { detail: err.detail });
        throw new ApiError(400, "NAME_REJECTED", "This name cannot be used. Please try another name.");
      }
      if (err instanceof TtsUnavailable) {
        // Configuration detail stays in the logs; the client only learns the service is unavailable.
        throw new ApiError(503, "SERVICE_UNAVAILABLE", MESSAGES.notConfigured, { detail: err.message });
      }
      throw err;
    }

    // 11. Signed bed URL.
    stage = "bed";
    const { data: signed, error: signError } = await supabase.storage
      .from(BED_BUCKET)
      .createSignedUrl(tune.bed_path!, BED_SIGNED_URL_TTL_S);
    if (signError || !signed?.signedUrl) {
      throw new Error(`bed signed url failed: ${signError?.message ?? "no url"}`);
    }

    // 12. Mix.
    stage = "mix";
    const mix = await callMixer(mixerUrl, mixerSecret, {
      render_id: renderId,
      bed_url: signed.signedUrl,
      tts: { pcm_base64: tts.pcmBase64, mime: tts.mime, sample_rate: tts.sampleRate },
      slot_start_ms: tune.name_slot_start_ms,
      slot_end_ms: tune.name_slot_end_ms,
      slot_gain_db: toNumber(tune.slot_gain_db, 3.0),
      duck_db: toNumber(tune.duck_db, -6.0),
      max_tempo: maxTempo,
      max_duration_ms: maxDurationMs,
      title: title ?? tune.name,
    });

    // 13. Upload.
    stage = "upload";
    const storagePath = `${tune.id}/${renderId}.mp3`;
    const { error: uploadError } = await withTimeout(
      supabase.storage
        .from(OUTPUT_BUCKET)
        .upload(storagePath, mix.bytes, { contentType: "audio/mpeg", cacheControl: "31536000", upsert: false }),
      UPLOAD_TIMEOUT_MS,
      "storage upload",
    );
    if (uploadError) throw new Error(`upload failed: ${uploadError.message}`);
    const publicUrl = supabase.storage.from(OUTPUT_BUCKET).getPublicUrl(storagePath).data.publicUrl;

    // 14. Finalize (unique ready key: a concurrent render may have won).
    stage = "finalize";
    const completedAt = new Date().toISOString();
    let finalRenderId = renderId;
    let finalUrl = publicUrl;
    let finalDurationMs = mix.durationMs;

    const { error: readyError } = await supabase
      .from("ringtone_renders")
      .update({
        status: "ready",
        storage_path: storagePath,
        public_url: publicUrl,
        duration_ms: mix.durationMs,
        tts_duration_ms: mix.ttsDurationMs,
        tempo_applied: mix.tempo,
        tts_model: tts.model,
        updated_at: completedAt,
        completed_at: completedAt,
      })
      .eq("id", renderId);

    if (readyError) {
      if (readyError.code !== "23505") throw new Error(`render finalize failed: ${readyError.message}`);
      const { data: winner } = await supabase
        .from("ringtone_renders")
        .select("id, public_url, duration_ms")
        .eq("tune_id", tune.id)
        .eq("assets_version", tune.assets_version)
        .eq("name_normalized", name.normalized)
        .eq("tts_voice", ttsVoice)
        .eq("status", "ready")
        .maybeSingle();
      if (!winner?.public_url) throw new Error("duplicate ready render but no winner row");

      await supabase
        .from("ringtone_renders")
        .update({
          status: "failed",
          error_code: "DUPLICATE",
          storage_path: storagePath,
          public_url: publicUrl,
          updated_at: completedAt,
          completed_at: completedAt,
        })
        .eq("id", renderId);
      const { error: removeError } = await supabase.storage.from(OUTPUT_BUCKET).remove([storagePath]);
      if (removeError) console.warn("generate-ringtone: duplicate object not removed", { path: storagePath });

      finalRenderId = String(winner.id);
      finalUrl = String(winner.public_url);
      finalDurationMs = winner.duration_ms ?? mix.durationMs;
    }

    const latencyMs = Date.now() - startedAt;
    const { error: logReadyError } = await supabase
      .from("generated_ringtones")
      .update({ status: "ready", render_id: finalRenderId, completed_at: completedAt, latency_ms: latencyMs })
      .eq("id", generationId);
    if (logReadyError) {
      console.error("generate-ringtone: could not mark generation ready", { generation_id: generationId, message: logReadyError.message });
    }

    console.log("generate-ringtone: ok", {
      generation_id: generationId,
      render_id: finalRenderId,
      tune_id: tune.id,
      language,
      auth_mode: authMode,
      cached: false,
      duplicate: finalRenderId !== renderId,
      tts_ms: tts.estimatedDurationMs,
      prompt_variant: tts.promptVariant,
      tts_attempt: tts.attempt,
      tts_model: tts.model,
      tempo: mix.tempo,
      latency_ms: latencyMs,
    });

    const quota = { used_today: usedToday + 1, daily_limit: dailyLimit };
    reportCreated(analytics, { generationId, cached: false, durationMs: finalDurationMs, latencyMs, completedAt, quota });
    return jsonResponse(successBody({
      generationId,
      renderId: finalRenderId,
      ringtoneUrl: finalUrl,
      title,
      tune,
      language,
      durationMs: finalDurationMs,
      cached: false,
      quota,
    }));
  } catch (err) {
    const latencyMs = Date.now() - startedAt;
    let apiError: ApiError;
    let rowCode: string;
    let detail: string | null = null;

    if (err instanceof ApiError) {
      apiError = err;
      rowCode = err.code;
      detail = err.detail ? safeDetail(err.detail, redactions) : null;
      const level = err.status >= 500 ? "error" : err.logLevel;
      if (level) {
        console[level]("generate-ringtone: failed", {
          stage,
          code: err.code,
          status: err.status,
          generation_id: generationId,
          render_id: renderId,
          detail,
        });
      }
    } else {
      const mapped = stageErrorCode(stage);
      apiError = new ApiError(mapped.status, mapped.code, mapped.status === 502
        ? MESSAGES.retryLater
        : "Internal server error");
      rowCode = mapped.code;
      detail = safeDetail(err, redactions);
      console.error("generate-ringtone: failed", { stage, code: rowCode, generation_id: generationId, render_id: renderId, detail });
    }

    const failedAt = new Date();
    if (supabase && (renderId || generationId)) {
      try {
        await markFailed(supabase, renderId, generationId, rowCode, detail, latencyMs, failedAt.toISOString());
      } catch (markError) {
        console.error("generate-ringtone: failure bookkeeping threw", { message: safeDetail(markError, redactions) });
      }
    }

    reportFailure(analytics, apiError, generationId, latencyMs, failedAt.getTime());
    return errorResponse(apiError);
  }
});
