/**
 * Pure helpers for the generate-ringtone quota and idempotency rules. No I/O, so they are unit
 * tested in supabase/functions/tests/quota_test.ts.
 *
 * Quota day = IST calendar day (UTC+05:30, no DST). Three limits, all on rows created today:
 *   - fresh renders per user (generate_daily_limit / generate_legacy_daily_limit): cached=false
 *     generated_ringtones rows that are ready, or processing and younger than STALE_PROCESSING_MS
 *     (a row stuck in processing longer than that is treated as abandoned);
 *   - attempts per user (generate_daily_attempt_limit): cached=false rows that may have billed
 *     Gemini (processing, ready, or failed with an ATTEMPT_FAILURE_CODES code), so renders that
 *     failed after Gemini billed them are bounded too, but rate-limited re-posts are not;
 *   - global (generate_global_daily_limit): ringtone_renders rows counted like fresh renders, plus
 *     failed rows whose error_code says Gemini had already been billed (BILLED_FAILURE_CODES).
 */
export const IST_OFFSET_MS = 330 * 60 * 1000;
export const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * Processing rows older than this are not counted toward quota and may be re-attempted. Just over
 * the 150 s Edge Function wall clock: a request cannot still be running after that long.
 */
export const STALE_PROCESSING_MS = 170 * 1000;
/** A processing ringtone_renders row younger than this blocks a duplicate render (409). */
export const INFLIGHT_RENDER_MS = 90 * 1000;

export const DEFAULT_DAILY_LIMIT = 5;
export const DEFAULT_LEGACY_DAILY_LIMIT = 3;
export const DEFAULT_DAILY_ATTEMPT_LIMIT = 12;
export const DEFAULT_GLOBAL_DAILY_LIMIT = 2000;

/**
 * ringtone_renders.error_code values written after the Gemini call succeeded (or may have), so
 * the render was billed even though it ended `failed`. `DUPLICATE` = a finished render that lost
 * the ready-key race; `STALE` = swept by pg_cron after the isolate died.
 */
export const BILLED_FAILURE_CODES: ReadonlyArray<string> = Object.freeze([
  "NAME_TOO_LONG_FOR_SONG",
  "MIX_FAILED",
  "UPLOAD_FAILED",
  "TTS_FAILED",
  "TUNE_NOT_PERSONALIZABLE",
  "SERVICE_UNAVAILABLE",
  "DUPLICATE",
  "STALE",
]);

/** error_code of rows rejected by the post-insert admission check; they never reached Gemini. */
export const QUOTA_REJECTED_CODE = "QUOTA_EXCEEDED";

export type AuthMode = "token" | "legacy_user_id";

/** UTC instant of 00:00 IST on the IST calendar day that contains `now`. */
export function istDayStart(now: Date = new Date()): Date {
  const shifted = new Date(now.getTime() + IST_OFFSET_MS);
  const dayStartShifted = Date.UTC(shifted.getUTCFullYear(), shifted.getUTCMonth(), shifted.getUTCDate());
  return new Date(dayStartShifted - IST_OFFSET_MS);
}

/** UTC instant of the next 00:00 IST after `now`. */
export function istNextMidnight(now: Date = new Date()): Date {
  return new Date(istDayStart(now).getTime() + DAY_MS);
}

/** Whole seconds until the quota resets; never less than 1. */
export function secondsUntilIstMidnight(now: Date = new Date()): number {
  const remainingMs = istNextMidnight(now).getTime() - now.getTime();
  return Math.max(1, Math.ceil(remainingMs / 1000));
}

/** YYYY-MM-DD of the IST calendar day (for logs and ops SQL). */
export function istDateString(now: Date = new Date()): string {
  const shifted = new Date(now.getTime() + IST_OFFSET_MS);
  const y = shifted.getUTCFullYear();
  const m = String(shifted.getUTCMonth() + 1).padStart(2, "0");
  const d = String(shifted.getUTCDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

export function parsePositiveInt(value: string | undefined | null, fallback: number): number {
  if (value === undefined || value === null) return fallback;
  const trimmed = String(value).trim();
  if (!/^\d+$/.test(trimmed)) return fallback;
  const parsed = Number.parseInt(trimmed, 10);
  return parsed > 0 ? parsed : fallback;
}

export function parseBoundedFloat(
  value: string | undefined | null,
  fallback: number,
  min: number,
  max: number,
): number {
  if (value === undefined || value === null) return fallback;
  const parsed = Number.parseFloat(String(value).trim());
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

/** app_config flags are stored as the strings "true" / "false". Missing or blank -> fallback. */
export function isFlagEnabled(config: Record<string, string>, key: string, fallback: boolean): boolean {
  const raw = config[key];
  if (raw === undefined) return fallback;
  const value = raw.trim().toLowerCase();
  if (value === "") return fallback;
  return value === "true" || value === "1" || value === "yes";
}

export function dailyLimitFor(config: Record<string, string>, authMode: AuthMode): number {
  return authMode === "token"
    ? parsePositiveInt(config.generate_daily_limit, DEFAULT_DAILY_LIMIT)
    : parsePositiveInt(config.generate_legacy_daily_limit, DEFAULT_LEGACY_DAILY_LIMIT);
}

/** Attempts per user per IST day; never below the fresh-render limit it backs up. */
export function attemptLimitFor(config: Record<string, string>, dailyLimit: number): number {
  return Math.max(parsePositiveInt(config.generate_daily_attempt_limit, DEFAULT_DAILY_ATTEMPT_LIMIT), dailyLimit);
}

export function globalDailyLimit(config: Record<string, string>): number {
  return parsePositiveInt(config.generate_global_daily_limit, DEFAULT_GLOBAL_DAILY_LIMIT);
}

/** PostgREST `or=` filter for rows that count as a fresh render (ready, or recently processing). */
export function freshRenderFilter(processingSinceIso: string): string {
  return `status.eq.ready,and(status.eq.processing,created_at.gt."${processingSinceIso}")`;
}

/** PostgREST `or=` filter for the global cap: fresh renders plus failures Gemini already billed. */
export function globalRenderFilter(processingSinceIso: string): string {
  return `${freshRenderFilter(processingSinceIso)},and(status.eq.failed,error_code.in.(${BILLED_FAILURE_CODES.join(",")}))`;
}

/**
 * error_code values on generated_ringtones that count toward the per-user attempt cap: failures
 * that happened after the Gemini call (BILLED_FAILURE_CODES) plus NAME_REJECTED (Gemini read and
 * refused the name). Deliberately excluded: TTS_RATE_LIMITED (Gemini answered 429/503, nothing
 * billed, and the app re-posts automatically), GENERATION_IN_PROGRESS, INTERNAL and admission
 * rejections (QUOTA_EXCEEDED). Counting those would lock a user out during a Gemini outage.
 */
export const ATTEMPT_FAILURE_CODES: ReadonlyArray<string> = Object.freeze([
  ...BILLED_FAILURE_CODES,
  "NAME_REJECTED",
]);

/**
 * PostgREST `or=` filter for per-user attempts: rows still processing or ready (no error_code),
 * plus failures that may have billed Gemini.
 */
export function attemptFilter(): string {
  return `error_code.is.null,error_code.in.(${ATTEMPT_FAILURE_CODES.join(",")})`;
}

/**
 * PostgREST `or=` filter for a generated_ringtones row a retry may take over: failed, or processing
 * past the stale window. Exact complement of `isStaleProcessing` for processing rows.
 */
export function retryableRequestFilter(processingSinceIso: string): string {
  return `status.eq.failed,and(status.eq.processing,created_at.lte."${processingSinceIso}")`;
}

/**
 * Post-insert admission: `orderedIds` are today's counted rows in creation order (at most `limit`
 * of them). The request is admitted when its own row is among the first `limit`, so of several
 * parallel requests that all passed the pre-insert count, only the earliest ones proceed. Fewer
 * than `limit` rows means there is room whether or not our row was read back (e.g. a clock-skew
 * edge at IST midnight), so that also admits.
 */
export function withinLimit(orderedIds: ReadonlyArray<string>, ownId: string, limit: number): boolean {
  if (orderedIds.length < limit) return true;
  return orderedIds.slice(0, Math.max(0, limit)).includes(ownId);
}

export type QuotaWindow = {
  /** ISO instant: rows created at/after this are "today". */
  dayStartIso: string;
  /** ISO instant: processing rows created after this still count as in flight. */
  processingSinceIso: string;
  /** ISO instant: processing renders created after this block duplicates. */
  inflightSinceIso: string;
};

export function quotaWindow(now: Date = new Date()): QuotaWindow {
  return {
    dayStartIso: istDayStart(now).toISOString(),
    processingSinceIso: new Date(now.getTime() - STALE_PROCESSING_MS).toISOString(),
    inflightSinceIso: new Date(now.getTime() - INFLIGHT_RENDER_MS).toISOString(),
  };
}

export type QuotaSnapshot = { used_today: number; daily_limit: number };

export function quotaExceeded(usedToday: number, dailyLimit: number): boolean {
  return usedToday >= dailyLimit;
}

/** True when a processing row is old enough to be treated as abandoned. */
export function isStaleProcessing(createdAtIso: string, now: Date = new Date()): boolean {
  const createdAt = Date.parse(createdAtIso);
  if (Number.isNaN(createdAt)) return true;
  return now.getTime() - createdAt >= STALE_PROCESSING_MS;
}

/** True when a processing render is recent enough to make a new request wait (409). */
export function isInflightRender(createdAtIso: string, now: Date = new Date()): boolean {
  const createdAt = Date.parse(createdAtIso);
  if (Number.isNaN(createdAt)) return false;
  return now.getTime() - createdAt < INFLIGHT_RENDER_MS;
}
