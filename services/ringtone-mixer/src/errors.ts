/**
 * Error codes returned by the mixer as JSON `{ error, code }`.
 *
 * Callers (the `generate-ringtone` edge function) map on `code`, not the HTTP
 * status. `NAME_TOO_LONG` is the only 422 so the edge can also key on status.
 * `CLIENT_CLOSED` is never sent (the client is gone); it only labels the log line.
 */
export type MixErrorCode =
  | "BAD_REQUEST"
  | "UNAUTHORIZED"
  | "NOT_FOUND"
  | "BED_DOWNLOAD_FAILED"
  | "TTS_EMPTY"
  | "TTS_TOO_LONG"
  | "NAME_TOO_LONG"
  | "FFMPEG_FAILED"
  | "DEADLINE_EXCEEDED"
  | "CLIENT_CLOSED"
  | "INTERNAL";

const HTTP_STATUS: Record<MixErrorCode, number> = {
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
  NOT_FOUND: 404,
  BED_DOWNLOAD_FAILED: 502,
  TTS_EMPTY: 400,
  TTS_TOO_LONG: 400,
  NAME_TOO_LONG: 422,
  FFMPEG_FAILED: 500,
  DEADLINE_EXCEEDED: 504,
  CLIENT_CLOSED: 499,
  INTERNAL: 500,
};

export class MixError extends Error {
  readonly code: MixErrorCode;
  readonly httpStatus: number;
  /** Diagnostic detail for server logs only. Never sent to the client. */
  readonly detail: string | undefined;

  constructor(code: MixErrorCode, message: string, detail?: string) {
    super(message);
    this.name = "MixError";
    this.code = code;
    this.httpStatus = HTTP_STATUS[code];
    this.detail = detail;
  }
}

export function isMixError(value: unknown): value is MixError {
  return value instanceof MixError;
}

export function httpStatusFor(code: MixErrorCode): number {
  return HTTP_STATUS[code];
}
