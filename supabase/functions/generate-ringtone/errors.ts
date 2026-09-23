/**
 * Error vocabulary of generate-ringtone and the pure mappings from internal failures onto it.
 * No I/O, so the mappings are unit tested in supabase/functions/tests/errors_test.ts.
 */
import type { QuotaSnapshot } from "./quota.ts";

export type ErrorCode =
  | "INVALID_REQUEST"
  | "INVALID_NAME"
  | "NAME_REJECTED"
  | "UNAUTHORIZED"
  | "SUBSCRIPTION_REQUIRED"
  | "TUNE_NOT_FOUND"
  | "GENERATION_IN_PROGRESS"
  | "UNSUPPORTED_LANGUAGE"
  | "TUNE_NOT_PERSONALIZABLE"
  | "NAME_TOO_LONG_FOR_SONG"
  | "QUOTA_EXCEEDED"
  | "SERVICE_BUSY"
  | "TTS_FAILED"
  | "MIX_FAILED"
  | "UPLOAD_FAILED"
  | "TTS_RATE_LIMITED"
  | "SERVICE_UNAVAILABLE"
  | "INTERNAL";

/** Drives the error code chosen in `catch` for unexpected exceptions. */
export type Stage =
  | "parse"
  | "config"
  | "auth"
  | "language"
  | "idempotency"
  | "tune"
  | "cache"
  | "quota"
  | "insert"
  | "admission"
  | "tts"
  | "bed"
  | "mix"
  | "upload"
  | "finalize";

export type LogLevel = "error" | "warn";

export class ApiError extends Error {
  readonly status: number;
  readonly code: ErrorCode;
  readonly retryAfterSeconds?: number;
  readonly quota?: QuotaSnapshot;
  /** Server-side diagnostic for logs and `ringtone_renders.error`. Never sent to the client. */
  readonly detail?: string;
  /** Log this failure at this level even when it is a 4xx. 5xx are always logged as errors. */
  readonly logLevel?: LogLevel;

  constructor(
    status: number,
    code: ErrorCode,
    message: string,
    extra: { retryAfterSeconds?: number; quota?: QuotaSnapshot; detail?: string; logLevel?: LogLevel } = {},
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.retryAfterSeconds = extra.retryAfterSeconds;
    this.quota = extra.quota;
    this.detail = extra.detail;
    this.logLevel = extra.logLevel;
  }
}

export const MESSAGES = Object.freeze({
  notConfigured: "Ringtone generation is not configured",
  retryLater: "Could not create the ringtone right now. Please try again.",
  notPersonalizable: "This song cannot be personalized yet",
  nameTooLong: "This name does not fit this song. Please pick another song.",
  dailyLimit: "Daily limit reached. Please try again tomorrow.",
  tooManyTries: "Too many tries today. Please try again tomorrow.",
});

/** Status and code for an unexpected exception, by the stage it was thrown in. */
export function stageErrorCode(stage: Stage): { status: number; code: ErrorCode } {
  switch (stage) {
    case "tts":
      return { status: 502, code: "TTS_FAILED" };
    case "bed":
    case "mix":
      return { status: 502, code: "MIX_FAILED" };
    case "upload":
      return { status: 502, code: "UPLOAD_FAILED" };
    default:
      return { status: 500, code: "INTERNAL" };
  }
}

export type MixerFailure = {
  status: number;
  code: ErrorCode;
  message: string;
  logLevel: LogLevel | null;
};

/**
 * Maps a non-2xx mixer response (`{ error, code }` from services/ringtone-mixer/src/errors.ts)
 * onto the error the app sees. `null` means "transient or unknown": the caller throws a plain
 * Error and the `mix` stage turns it into a retryable 502 MIX_FAILED.
 *
 *   401/403 (any body)           -> 503 SERVICE_UNAVAILABLE  shared secret / IAM mismatch: ops must fix
 *   422 NAME_TOO_LONG            -> 422 NAME_TOO_LONG_FOR_SONG
 *   TTS_EMPTY / TTS_TOO_LONG     -> 502 TTS_FAILED            a fresh Gemini take may be fine
 *   400 BAD_REQUEST              -> 422 TUNE_NOT_PERSONALIZABLE  authoring data the mixer rejects
 *                                                               (e.g. slot beyond the end of the bed)
 */
export function mixerFailure(httpStatus: number, mixerCode: string): MixerFailure | null {
  if (httpStatus === 401 || httpStatus === 403) {
    return { status: 503, code: "SERVICE_UNAVAILABLE", message: MESSAGES.notConfigured, logLevel: "error" };
  }
  if (httpStatus === 422 && mixerCode === "NAME_TOO_LONG") {
    return { status: 422, code: "NAME_TOO_LONG_FOR_SONG", message: MESSAGES.nameTooLong, logLevel: null };
  }
  if (mixerCode === "TTS_EMPTY" || mixerCode === "TTS_TOO_LONG") {
    return { status: 502, code: "TTS_FAILED", message: MESSAGES.retryLater, logLevel: "warn" };
  }
  if (httpStatus === 400 && mixerCode === "BAD_REQUEST") {
    return { status: 422, code: "TUNE_NOT_PERSONALIZABLE", message: MESSAGES.notPersonalizable, logLevel: "error" };
  }
  return null;
}

/**
 * Short, name-free description of an error for logs and the render row. Every non-empty entry of
 * `redact` (the display and spoken forms of the name) is replaced by `[name]`.
 */
export function safeDetail(err: unknown, redact: ReadonlyArray<string | null | undefined> = []): string {
  let text = err instanceof Error ? `${err.name}: ${err.message}` : String(err);
  const needles = [...new Set(redact.filter((value): value is string => Boolean(value)))]
    .sort((a, b) => b.length - a.length);
  for (const needle of needles) text = text.split(needle).join("[name]");
  return text.slice(0, 300);
}

export class TimeoutError extends Error {
  constructor(label: string, ms: number) {
    super(`${label} timed out after ${ms} ms`);
    this.name = "TimeoutError";
  }
}

/**
 * Rejects with TimeoutError when `promise` has not settled after `ms`. The underlying work is not
 * cancelled (supabase-js storage uploads take no AbortSignal); a late result is ignored.
 */
export function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new TimeoutError(label, ms)), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}
