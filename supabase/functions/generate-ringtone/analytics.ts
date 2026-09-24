/**
 * Mixpanel events of generate-ringtone. The server owns generation outcomes: it sends
 * name_lookup_completed, ringtone_created and ringtone_generation_failed; the app keeps
 * ringtone_generation_started and reports only failures that carry no server error_code (or
 * UNAUTHORIZED, where the server has no user). Pure builders, unit tested in
 * supabase/functions/tests/generate_analytics_test.ts. The name, its normalized form and the title
 * are never inputs here.
 */
import type { MixpanelProps } from "../_shared/mixpanel.ts";
import { normalizeVoiceGender } from "../_shared/tts-voices.ts";
import type { ErrorCode } from "./errors.ts";
import type { QuotaSnapshot } from "./quota.ts";

export const NAME_LOOKUP_COMPLETED = "name_lookup_completed";
export const RINGTONE_CREATED = "ringtone_created";
export const RINGTONE_GENERATION_FAILED = "ringtone_generation_failed";
export type GenerationEvent =
  | typeof NAME_LOOKUP_COMPLETED
  | typeof RINGTONE_CREATED
  | typeof RINGTONE_GENERATION_FAILED;

export const GENERATION_SOURCE = "creation_flow";
export const MATCH_COUNT_CAP = 100;

/**
 * Busy answers RingtoneGenerationViewModel re-posts by itself. The re-post reports the outcome, so
 * these report nothing; when the app's wait budget runs out it reports `timeout` itself.
 */
export const AUTO_REPOSTED_CODES: ReadonlySet<ErrorCode> = new Set<ErrorCode>([
  "GENERATION_IN_PROGRESS",
  "TTS_RATE_LIMITED",
]);

/** Mirrors `GenerationErrorCode.retryable` in the app's RingtoneGenerationRepository.kt. */
const RETRYABLE_CODES: ReadonlySet<ErrorCode> = new Set<ErrorCode>([
  "GENERATION_IN_PROGRESS",
  "SERVICE_BUSY",
  "TTS_FAILED",
  "MIX_FAILED",
  "UPLOAD_FAILED",
  "TTS_RATE_LIMITED",
  "SERVICE_UNAVAILABLE",
  "INTERNAL",
]);

export function isRetryableCode(code: ErrorCode): boolean {
  return RETRYABLE_CODES.has(code);
}

/** Whether an error response gets a server ringtone_generation_failed. */
export function reportsFailure(code: ErrorCode): boolean {
  return code !== "UNAUTHORIZED" && !AUTO_REPOSTED_CODES.has(code);
}

/** Whether a completed name lookup is reported with this outcome (`null` = success). */
export function reportsLookup(code: ErrorCode | null): boolean {
  return code === null || !AUTO_REPOSTED_CODES.has(code);
}

export function failureReason(code: ErrorCode): string {
  return code.toLowerCase();
}

/** Generation time in minutes, one decimal. */
export function durationMinutes(latencyMs: number): number {
  return Math.round(Math.max(0, latencyMs) / 6000) / 10;
}

/** Code points, the unit of the 30-character name rule. */
export function nameLengthOf(display: string): number {
  return Array.from(display).length;
}

export type TuneFacts = {
  tuneId: string;
  categoryName?: string | null;
  gender?: string | null;
};

type SharedFacts = {
  language: string | null;
  generationId: string | null;
  clientRequestId: string | null;
  appVersion: string | null;
};

function tuneProps(tune: TuneFacts): MixpanelProps {
  return {
    tune_id: tune.tuneId,
    sample_id: tune.tuneId,
    category: tune.categoryName,
    voice: normalizeVoiceGender(tune.gender),
  };
}

function sharedProps(facts: SharedFacts): MixpanelProps {
  return {
    language: facts.language,
    generation_id: facts.generationId,
    client_request_id: facts.clientRequestId,
    app_version: facts.appVersion,
  };
}

/** `has_match` = this sample already sings the name, or any ready render of it in this language. */
export function nameLookupProps(input: {
  sampleId: string;
  language: string;
  nameLength: number;
  exactMatch: boolean;
  matchCount: number | null;
  generationId: string | null;
  clientRequestId: string | null;
  appVersion: string | null;
}): MixpanelProps {
  const matchCount = input.matchCount === null ? null : Math.min(Math.max(0, input.matchCount), MATCH_COUNT_CAP);
  const hasMatch = input.exactMatch || (matchCount !== null && matchCount > 0) ? true : matchCount === null ? null : false;
  return {
    has_match: hasMatch,
    match_count: matchCount,
    exact_match: input.exactMatch,
    name_length: input.nameLength,
    sample_id: input.sampleId,
    ...sharedProps(input),
  };
}

export function ringtoneCreatedProps(input: SharedFacts & {
  tune: TuneFacts;
  cached: boolean;
  audioDurationMs: number | null;
  latencyMs: number;
  quota: QuotaSnapshot;
}): MixpanelProps {
  return {
    ...tuneProps(input.tune),
    ...sharedProps(input),
    cached: input.cached,
    duration_ms: input.audioDurationMs,
    latency_ms: input.latencyMs,
    duration_minutes: durationMinutes(input.latencyMs),
    quota_used_today: input.quota.used_today,
    quota_daily_limit: input.quota.daily_limit,
    source: GENERATION_SOURCE,
  };
}

export function generationFailedProps(input: SharedFacts & {
  tune: TuneFacts | null;
  code: ErrorCode;
  httpStatus: number;
  latencyMs: number;
  quota?: QuotaSnapshot;
}): MixpanelProps {
  return {
    ...(input.tune ? tuneProps(input.tune) : {}),
    ...sharedProps(input),
    failure_reason: failureReason(input.code),
    retryable: isRetryableCode(input.code),
    http_status: input.httpStatus,
    latency_ms: input.latencyMs,
    quota_used_today: input.quota?.used_today,
    quota_daily_limit: input.quota?.daily_limit,
  };
}

/**
 * Parts for `mixpanelInsertId`: the generation row when there is one (each row reports once).
 * Without a row, retries share the client_request_id, so the request start keeps them apart.
 */
export function insertIdParts(
  event: GenerationEvent,
  ids: { generationId: string | null; clientRequestId: string | null; distinctId: string; requestStartedAtMs: number },
): Array<string | number | null> {
  if (ids.generationId) return [event, ids.generationId];
  return [event, ids.clientRequestId, ids.distinctId, ids.requestStartedAtMs];
}

type BackgroundRuntime = { waitUntil?: (promise: Promise<unknown>) => unknown };

/**
 * Runs `task` without holding up the response: EdgeRuntime.waitUntil keeps the isolate alive for
 * it when available, otherwise it is fire-and-forget. Never throws; the returned promise never rejects.
 */
export function runInBackground(
  task: () => Promise<unknown>,
  runtime: BackgroundRuntime | undefined = (globalThis as { EdgeRuntime?: BackgroundRuntime }).EdgeRuntime,
): Promise<void> {
  const promise = (async () => {
    try {
      await task();
    } catch (err) {
      console.error("generate-ringtone: analytics failed", err instanceof Error ? err.name : "unknown");
    }
  })();
  try {
    if (typeof runtime?.waitUntil === "function") runtime.waitUntil(promise);
  } catch {
    // Still running; only the keep-alive is lost.
  }
  return promise;
}
