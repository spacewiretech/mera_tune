import { writeFile } from "node:fs/promises";
import path from "node:path";
import type { RenderBudget } from "./budget";
import { MixError } from "./errors";

export interface BedDownloadPolicy {
  maxBytes: number;
  timeoutMs: number;
}

export const DEFAULT_BED_POLICY: BedDownloadPolicy = {
  maxBytes: 15 * 1024 * 1024,
  timeoutMs: 20_000,
};

const AUDIO_EXTENSIONS = new Set([".mp3", ".wav", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".flac"]);
const NON_AUDIO_CONTENT_TYPES = ["text/html", "application/json", "application/xml", "text/xml"];

/** `BED_HOST_ALLOWLIST` is comma-separated; entries may be `host`, `host:port` or `*.suffix`. */
export function parseAllowlist(raw: string | undefined): string[] {
  return (raw ?? "")
    .split(",")
    .map((entry) => entry.trim().toLowerCase())
    .filter((entry) => entry.length > 0);
}

export function hostAllowed(host: string, allowlist: readonly string[]): boolean {
  const candidate = host.toLowerCase();
  return allowlist.some((entry) => {
    if (entry.startsWith("*.")) {
      const suffix = entry.slice(1); // ".example.com"
      return candidate.length > suffix.length && candidate.endsWith(suffix);
    }
    return candidate === entry;
  });
}

/**
 * SSRF guard for `bed_url`: absolute https URL, no embedded credentials, host
 * (including any explicit port) on the allow-list. Redirects are refused at
 * download time, so the allow-list applies to the final host as well.
 */
export function assertBedUrlAllowed(rawUrl: string, allowlist: readonly string[]): URL {
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    throw new MixError("BAD_REQUEST", "bed_url must be an absolute URL");
  }
  if (url.protocol !== "https:") throw new MixError("BAD_REQUEST", "bed_url must use https");
  if (url.username || url.password) throw new MixError("BAD_REQUEST", "bed_url must not embed credentials");
  if (!hostAllowed(url.host, allowlist)) throw new MixError("BAD_REQUEST", "bed_url host is not allow-listed");
  return url;
}

/** Local file name for the bed; keeps a known audio extension, ffmpeg probes the rest by content. */
export function bedFileNameFor(url: URL): string {
  const ext = path.posix.extname(url.pathname).toLowerCase();
  return AUDIO_EXTENSIONS.has(ext) ? `bed${ext}` : "bed.bin";
}

function describeError(err: unknown): string {
  if (err instanceof Error) {
    const cause = (err as { cause?: { code?: string; message?: string } }).cause;
    const causeText = cause ? ` (cause: ${cause.code ?? cause.message ?? "unknown"})` : "";
    return `${err.name}: ${err.message}${causeText}`;
  }
  return String(err);
}

export interface BedDownload {
  bytes: number;
  contentType: string | null;
}

/**
 * Streams the bed to `destPath`, enforcing the byte cap while reading and the
 * overall timeout. With a budget the timeout is min(policy, time left) and the
 * fetch is aborted when the client disconnects. Failures are `BED_DOWNLOAD_FAILED`,
 * except `DEADLINE_EXCEEDED` (budget ran out) and `CLIENT_CLOSED`.
 */
export async function downloadBed(
  url: URL,
  destPath: string,
  policy: BedDownloadPolicy = DEFAULT_BED_POLICY,
  budget?: RenderBudget,
): Promise<BedDownload> {
  const step = "bed download";
  const { timeoutMs, limited } = budget
    ? budget.timeoutFor(policy.timeoutMs, step)
    : { timeoutMs: policy.timeoutMs, limited: false };
  const controller = new AbortController();
  let timerFired = false;
  const timer = setTimeout(() => {
    timerFired = true;
    controller.abort();
  }, timeoutMs);
  const onClientClosed = (): void => controller.abort();
  budget?.signal?.addEventListener("abort", onClientClosed, { once: true });
  /** Deadline/disconnect take precedence over the generic download error. */
  const stopError = (): MixError | undefined => {
    if (budget?.aborted) return budget.closedError(step);
    if (timerFired && limited && budget) return budget.deadlineError(step);
    return undefined;
  };
  try {
    let response: Response;
    try {
      response = await fetch(url, {
        method: "GET",
        redirect: "error",
        signal: controller.signal,
        headers: {
          accept: "audio/*, */*;q=0.5",
          "user-agent": "meratune-ringtone-mixer/1.0",
        },
      });
    } catch (err) {
      throw (
        stopError() ??
        new MixError(
          "BED_DOWNLOAD_FAILED",
          timerFired ? `bed download timed out after ${timeoutMs} ms` : "bed download failed",
          describeError(err),
        )
      );
    }
    if (!response.ok) {
      throw new MixError("BED_DOWNLOAD_FAILED", `bed download failed with HTTP ${response.status}`);
    }
    const contentType = response.headers.get("content-type");
    if (contentType && NON_AUDIO_CONTENT_TYPES.some((type) => contentType.toLowerCase().startsWith(type))) {
      throw new MixError("BED_DOWNLOAD_FAILED", `bed URL returned ${contentType} instead of audio`);
    }
    const declared = Number.parseInt(response.headers.get("content-length") ?? "", 10);
    if (Number.isFinite(declared) && declared > policy.maxBytes) {
      throw new MixError("BED_DOWNLOAD_FAILED", `bed is ${declared} bytes; limit is ${policy.maxBytes}`);
    }
    if (!response.body) throw new MixError("BED_DOWNLOAD_FAILED", "bed response had no body");

    const chunks: Buffer[] = [];
    let total = 0;
    try {
      for await (const chunk of response.body as AsyncIterable<Uint8Array>) {
        total += chunk.byteLength;
        if (total > policy.maxBytes) {
          controller.abort();
          throw new MixError("BED_DOWNLOAD_FAILED", `bed exceeds ${policy.maxBytes} bytes`);
        }
        chunks.push(Buffer.from(chunk.buffer, chunk.byteOffset, chunk.byteLength));
      }
    } catch (err) {
      if (err instanceof MixError) throw err;
      throw (
        stopError() ??
        new MixError(
          "BED_DOWNLOAD_FAILED",
          timerFired ? `bed download timed out after ${timeoutMs} ms` : "bed download was interrupted",
          describeError(err),
        )
      );
    }
    if (total === 0) throw new MixError("BED_DOWNLOAD_FAILED", "bed download was empty");
    await writeFile(destPath, Buffer.concat(chunks, total));
    return { bytes: total, contentType };
  } finally {
    clearTimeout(timer);
    budget?.signal?.removeEventListener("abort", onClientClosed);
  }
}
