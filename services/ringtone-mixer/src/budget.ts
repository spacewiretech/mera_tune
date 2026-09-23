import { MixError } from "./errors";

/**
 * Default wall-clock budget for one `POST /mix`. It sits under the edge's 60 s
 * mixer abort so the edge gets a real `DEADLINE_EXCEEDED` instead of its own timeout.
 */
export const DEFAULT_DEADLINE_MS = 55_000;
/** Accepted range for `MIX_DEADLINE_MS`; anything else falls back to the default. */
export const DEADLINE_LIMITS = { min: 1_000, max: 300_000 } as const;

/** Parses `MIX_DEADLINE_MS`. Returns `undefined` for a set-but-invalid value so the caller can warn. */
export function parseDeadlineMs(raw: string | undefined): number | undefined {
  const trimmed = (raw ?? "").trim();
  if (trimmed.length === 0) return DEFAULT_DEADLINE_MS;
  const value = Number(trimmed);
  if (!Number.isInteger(value) || value < DEADLINE_LIMITS.min || value > DEADLINE_LIMITS.max) return undefined;
  return value;
}

/** True for the two errors that stop a render from outside (time spent, client gone); never re-label them. */
export function isBudgetStop(err: unknown): err is MixError {
  return err instanceof MixError && (err.code === "DEADLINE_EXCEEDED" || err.code === "CLIENT_CLOSED");
}

export interface StepTimeout {
  /** Timeout to hand to the step; always >= 1 ms (0 would disable execFile's timeout). */
  timeoutMs: number;
  /** True when the time left, not the step's own cap, set `timeoutMs`: a kill at that timeout is a deadline expiry. */
  limited: boolean;
}

/**
 * Wall-clock budget for one render, shared by the bed download and every
 * ffmpeg/ffprobe call. Each step's timeout is min(its own cap, time left), and
 * `signal` (aborted when the HTTP client disconnects) kills whatever is running.
 */
export class RenderBudget {
  readonly totalMs: number;
  readonly deadlineAt: number;
  readonly signal: AbortSignal | undefined;

  constructor(totalMs: number, signal?: AbortSignal, startedAt: number = Date.now()) {
    this.totalMs = totalMs;
    this.deadlineAt = startedAt + totalMs;
    this.signal = signal;
  }

  get aborted(): boolean {
    return this.signal?.aborted === true;
  }

  remainingMs(now: number = Date.now()): number {
    return this.deadlineAt - now;
  }

  /** Throws `CLIENT_CLOSED` when the client has gone, `DEADLINE_EXCEEDED` when no time is left. */
  assertActive(step: string): void {
    if (this.aborted) throw this.closedError(step);
    if (this.remainingMs() <= 0) throw this.deadlineError(step);
  }

  /** Timeout for the next step, after `assertActive`. */
  timeoutFor(capMs: number, step: string): StepTimeout {
    this.assertActive(step);
    const remaining = Math.max(1, Math.ceil(this.remainingMs()));
    return remaining < capMs ? { timeoutMs: remaining, limited: true } : { timeoutMs: capMs, limited: false };
  }

  deadlineError(step: string, detail?: string): MixError {
    return new MixError("DEADLINE_EXCEEDED", `render deadline of ${this.totalMs} ms exceeded during ${step}`, detail);
  }

  closedError(step: string): MixError {
    return new MixError("CLIENT_CLOSED", `client disconnected; aborted during ${step}`);
  }
}
