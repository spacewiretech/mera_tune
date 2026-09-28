/**
 * Pure helpers for the generate-ringtone quota and idempotency rules. No I/O, so they are unit
 * tested in supabase/functions/tests/quota_test.ts.
 *
 * Quota day = IST calendar day, quota month = IST calendar month (UTC+05:30, no DST). Three limits:
 *   - fresh renders per user, by plan (planQuotaFor, from users.status): cached=false
 *     generated_ringtones rows that are ready, or processing and younger than STALE_PROCESSING_MS
 *     (a row stuck in processing longer than that is treated as abandoned), counted over the
 *     plan's period:
 *       trial   (status trial)   generate_trial_daily_limit per IST day (default 2);
 *       member  (status active)  generate_member_monthly_limit per IST month (default 50);
 *       default (anything else)  generate_daily_limit per IST day (default 5).
 *     Legacy `user_id` callers are held to a per-day limit whatever the plan (see planQuotaFor);
 *   - attempts per user per IST day, for every plan (generate_daily_attempt_limit): cached=false
 *     rows that may have billed Gemini (processing, ready, or failed with an ATTEMPT_FAILURE_CODES
 *     code), so renders that failed after Gemini billed them are bounded too, but rate-limited
 *     re-posts are not. For a monthly plan it is the abuse guard that stops a member spending the
 *     month in one day;
 *   - global per IST day (generate_global_daily_limit): ringtone_renders rows counted like fresh
 *     renders, plus failed rows whose error_code says Gemini had already been billed
 *     (BILLED_FAILURE_CODES).
 * Cached hits (sample name, render cache) never count toward any of them.
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
export const DEFAULT_TRIAL_DAILY_LIMIT = 2;
export const DEFAULT_MEMBER_MONTHLY_LIMIT = 50;
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

/** trial = users.status trial; member = users.status active; default = any other status. */
export type QuotaPlan = "trial" | "member" | "default";
/** day = IST calendar day; month = IST calendar month. */
export type QuotaPeriod = "day" | "month";
/** Fresh renders allowed per `period` on `plan`. */
export type PlanQuota = { plan: QuotaPlan; period: QuotaPeriod; limit: number };
/** Which limit a QUOTA_EXCEEDED hit: the plan's, or the per-IST-day attempt cap behind every plan. */
export type QuotaExceededReason = "plan" | "attempts";

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

/** UTC instant of 00:00 IST on the 1st of the IST calendar month that contains `now`. */
export function istMonthStart(now: Date = new Date()): Date {
  const shifted = new Date(now.getTime() + IST_OFFSET_MS);
  return new Date(Date.UTC(shifted.getUTCFullYear(), shifted.getUTCMonth(), 1) - IST_OFFSET_MS);
}

/** UTC instant of 00:00 IST on the 1st of the IST calendar month after the one containing `now`. */
export function istNextMonthStart(now: Date = new Date()): Date {
  const shifted = new Date(now.getTime() + IST_OFFSET_MS);
  // Date.UTC rolls month 12 over into January of the next year.
  return new Date(Date.UTC(shifted.getUTCFullYear(), shifted.getUTCMonth() + 1, 1) - IST_OFFSET_MS);
}

/** Start of the quota period containing `now`: rows created at/after it count. */
export function periodStart(now: Date, period: QuotaPeriod): Date {
  return period === "month" ? istMonthStart(now) : istDayStart(now);
}

/** End of the quota period containing `now`, i.e. when the plan limit resets. */
export function periodEnd(now: Date, period: QuotaPeriod): Date {
  return period === "month" ? istNextMonthStart(now) : istNextMidnight(now);
}

/** Whole seconds until the plan limit resets; never less than 1. */
export function secondsUntilPeriodEnd(now: Date, period: QuotaPeriod): number {
  const remainingMs = periodEnd(now, period).getTime() - now.getTime();
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

export function memberMonthlyLimit(config: Record<string, string>): number {
  return parsePositiveInt(config.generate_member_monthly_limit, DEFAULT_MEMBER_MONTHLY_LIMIT);
}

/**
 * The fresh-render limit of a caller, from users.status (trimmed, case-insensitive):
 *   trial   -> { trial, day, generate_trial_daily_limit (2) }
 *   active  -> { member, month, generate_member_monthly_limit (50) }
 *   other   -> { default, day, generate_daily_limit (5) }: none / expired / cancelled / blank.
 * A legacy `user_id` caller (old app versions, no session token) is always limited per IST day, to
 * the lower of generate_legacy_daily_limit (3) and a daily plan's own limit; for the monthly member
 * plan the legacy limit alone applies, so a guessable user_id never unlocks the monthly allowance.
 * `plan` still names the caller's plan in that case.
 */
export function planQuotaFor(
  config: Record<string, string>,
  status: string | null | undefined,
  authMode: AuthMode,
): PlanQuota {
  const normalized = String(status ?? "").trim().toLowerCase();
  let quota: PlanQuota;
  if (normalized === "trial") {
    quota = { plan: "trial", period: "day", limit: parsePositiveInt(config.generate_trial_daily_limit, DEFAULT_TRIAL_DAILY_LIMIT) };
  } else if (normalized === "active") {
    quota = { plan: "member", period: "month", limit: memberMonthlyLimit(config) };
  } else {
    quota = { plan: "default", period: "day", limit: dailyLimitFor(config, "token") };
  }
  if (authMode === "token") return quota;
  const legacyLimit = dailyLimitFor(config, "legacy_user_id");
  return {
    plan: quota.plan,
    period: "day",
    limit: quota.period === "day" ? Math.min(legacyLimit, quota.limit) : legacyLimit,
  };
}

/** Attempts per user per IST day; never below the fresh-render limit it backs up. */
export function attemptLimitFor(config: Record<string, string>, dailyLimit: number): number {
  return Math.max(parsePositiveInt(config.generate_daily_attempt_limit, DEFAULT_DAILY_ATTEMPT_LIMIT), dailyLimit);
}

/**
 * The attempt cap stays per IST day on every plan. A daily plan's limit is its floor, as before; a
 * monthly limit is not a per-day number, so a member gets the configured cap (default 12) as an
 * abuse guard.
 */
export function attemptLimitForPlan(config: Record<string, string>, planQuota: PlanQuota): number {
  return attemptLimitFor(config, planQuota.period === "day" ? planQuota.limit : 0);
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
 * Post-insert admission: `orderedIds` are the period's counted rows in creation order (at most
 * `limit` of them). The request is admitted when its own row is among the first `limit`, so of several
 * parallel requests that all passed the pre-insert count, only the earliest ones proceed. Fewer
 * than `limit` rows means there is room whether or not our row was read back (e.g. a clock-skew
 * edge at IST midnight), so that also admits.
 */
export function withinLimit(orderedIds: ReadonlyArray<string>, ownId: string, limit: number): boolean {
  if (orderedIds.length < limit) return true;
  return orderedIds.slice(0, Math.max(0, limit)).includes(ownId);
}

export type QuotaWindow = {
  /** ISO instant: rows created at/after this are "today" (attempt and global caps). */
  dayStartIso: string;
  /** ISO instant: fresh renders created at/after this count toward the plan (= dayStartIso for day plans). */
  periodStartIso: string;
  /** ISO instant: processing rows created after this still count as in flight. */
  processingSinceIso: string;
  /** ISO instant: processing renders created after this block duplicates. */
  inflightSinceIso: string;
};

export function quotaWindow(now: Date = new Date(), period: QuotaPeriod = "day"): QuotaWindow {
  return {
    dayStartIso: istDayStart(now).toISOString(),
    periodStartIso: periodStart(now, period).toISOString(),
    processingSinceIso: new Date(now.getTime() - STALE_PROCESSING_MS).toISOString(),
    inflightSinceIso: new Date(now.getTime() - INFLIGHT_RENDER_MS).toISOString(),
  };
}

/**
 * The `quota` object of generate-ringtone responses (success and error bodies) and of
 * name-ringtones mine mode. `used_today` / `daily_limit` are the pre-plan names, kept for older
 * app versions: always the same numbers as `used` / `limit`, even for a monthly plan.
 */
export type QuotaSnapshot = {
  used_today: number;
  daily_limit: number;
  plan: QuotaPlan;
  period: QuotaPeriod;
  /** The caller's fresh renders counted in the current period. */
  used: number;
  limit: number;
  /** ISO instant the period ends: next 00:00 IST, or 00:00 IST on the 1st of next month. */
  resets_at: string;
  /** The member plan's monthly limit, whatever the caller's plan (the app's trial copy uses it). */
  member_monthly_limit: number;
  /** Only in QUOTA_EXCEEDED bodies. */
  exceeded?: QuotaExceededReason;
};

export function quotaSnapshot(
  planQuota: PlanQuota,
  used: number,
  now: Date,
  memberMonthly: number,
  exceeded?: QuotaExceededReason,
): QuotaSnapshot {
  const snapshot: QuotaSnapshot = {
    used_today: used,
    daily_limit: planQuota.limit,
    plan: planQuota.plan,
    period: planQuota.period,
    used,
    limit: planQuota.limit,
    resets_at: periodEnd(now, planQuota.period).toISOString(),
    member_monthly_limit: memberMonthly,
  };
  if (exceeded) snapshot.exceeded = exceeded;
  return snapshot;
}

/**
 * QUOTA_EXCEEDED retry_after_seconds: the plan limit resets at the end of its period (day or
 * month); the attempt cap behind every plan at the next IST midnight.
 */
export function quotaRetryAfterSeconds(now: Date, quota: QuotaSnapshot): number {
  return quota.exceeded === "attempts" ? secondsUntilIstMidnight(now) : secondsUntilPeriodEnd(now, quota.period);
}

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
