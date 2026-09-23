import { createHash, timingSafeEqual } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import express, { type NextFunction, type Request, type Response } from "express";
import { FFMPEG_BIN, runTool } from "./audio";
import { assertBedUrlAllowed, bedFileNameFor, downloadBed, parseAllowlist, type BedDownloadPolicy } from "./bed";
import { DEFAULT_DEADLINE_MS, RenderBudget, parseDeadlineMs } from "./budget";
import { MixError, isMixError } from "./errors";
import { mix } from "./mix";
import { parseMixRequest } from "./request";

const JSON_BODY_LIMIT = "2mb";
const BED_POLICY: BedDownloadPolicy = { maxBytes: 15 * 1024 * 1024, timeoutMs: 20_000 };
const SHUTDOWN_GRACE_MS = 10_000;

type Severity = "DEBUG" | "INFO" | "WARNING" | "ERROR";

/** One JSON line per event; Cloud Run picks up `severity` and `message`. Never log the title or name. */
function log(severity: Severity, message: string, fields: Record<string, unknown> = {}): void {
  process.stdout.write(`${JSON.stringify({ severity, message, ...fields, time: new Date().toISOString() })}\n`);
}

function parsePort(raw: string | undefined): number {
  const value = Number.parseInt(raw ?? "", 10);
  return Number.isInteger(value) && value > 0 && value < 65_536 ? value : 8080;
}

/** Constant-time comparison; hashing first removes the length dependency. */
function secretsMatch(provided: string, expected: string): boolean {
  const a = createHash("sha256").update(provided, "utf8").digest();
  const b = createHash("sha256").update(expected, "utf8").digest();
  return timingSafeEqual(a, b);
}

const PORT = parsePort(process.env.PORT);
const SHARED_SECRET = process.env.MIXER_SHARED_SECRET ?? "";
const ALLOWED_BED_HOSTS = parseAllowlist(process.env.BED_HOST_ALLOWLIST);
const PARSED_DEADLINE_MS = parseDeadlineMs(process.env.MIX_DEADLINE_MS);
/** Wall-clock budget per POST /mix (bed download + every ffmpeg/ffprobe call). */
const MIX_DEADLINE_MS = PARSED_DEADLINE_MS ?? DEFAULT_DEADLINE_MS;

if (SHARED_SECRET.length === 0) {
  log("ERROR", "MIXER_SHARED_SECRET is not set; refusing to start");
  process.exit(1);
}
if (ALLOWED_BED_HOSTS.length === 0) {
  log("ERROR", "BED_HOST_ALLOWLIST is empty; refusing to start");
  process.exit(1);
}
if (PARSED_DEADLINE_MS === undefined) {
  log("WARNING", "MIX_DEADLINE_MS is invalid; using the default", { default_ms: DEFAULT_DEADLINE_MS });
}

function sendError(res: Response, err: MixError): void {
  // Nothing to send once the response started or the client socket is gone (CLIENT_CLOSED).
  if (res.headersSent || res.writableEnded || res.destroyed) return;
  res.status(err.httpStatus).json({ error: err.message, code: err.code });
}

function requireSecret(req: Request, res: Response, next: NextFunction): void {
  const provided = req.header("x-mixer-secret");
  if (typeof provided !== "string" || provided.length === 0 || !secretsMatch(provided, SHARED_SECRET)) {
    sendError(res, new MixError("UNAUTHORIZED", "Unauthorized"));
    return;
  }
  next();
}

async function handleMix(req: Request, res: Response): Promise<void> {
  const startedAt = Date.now();
  // Client disconnect before the response is finished aborts the render and kills
  // the running ffmpeg, freeing the concurrency slot. `res` "close" (not `req`,
  // which closes as soon as the body is read) with !writableEnded means the
  // connection dropped before we answered.
  const clientGone = new AbortController();
  const onClose = (): void => {
    if (!res.writableEnded) clientGone.abort();
  };
  res.on("close", onClose);
  if (res.destroyed || req.socket.destroyed) clientGone.abort();
  const budget = new RenderBudget(MIX_DEADLINE_MS, clientGone.signal, startedAt);
  let renderId = "unknown";
  let workDir: string | undefined;
  try {
    const parsed = parseMixRequest(req.body);
    renderId = parsed.renderId;
    const bedUrl = assertBedUrlAllowed(parsed.bedUrl, ALLOWED_BED_HOSTS);

    workDir = await mkdtemp(path.join(os.tmpdir(), "mix-"));
    const bedPath = path.join(workDir, bedFileNameFor(bedUrl));
    const bed = await downloadBed(bedUrl, bedPath, BED_POLICY, budget);
    const result = await mix(parsed, { bedPath, workDir }, budget);
    // Finished work is still sent after the deadline; only a vanished client drops it.
    if (budget.aborted) throw budget.closedError("response");

    res
      .status(200)
      .set({
        "Content-Type": "audio/mpeg",
        "Content-Length": String(result.mp3.length),
        "Cache-Control": "no-store",
        "X-Mix-Duration-Ms": String(result.durationMs),
        "X-Mix-Tts-Duration-Ms": String(result.ttsDurationMs),
        "X-Mix-Tempo": result.tempo.toFixed(3),
      })
      .send(result.mp3);

    log("INFO", "mix ok", {
      render_id: renderId,
      ms: Date.now() - startedAt,
      bed_bytes: bed.bytes,
      bed_host: bedUrl.host,
      mp3_bytes: result.mp3.length,
      duration_ms: result.durationMs,
      tts_duration_ms: result.ttsDurationMs,
      tempo: result.tempo,
      tts_mime: parsed.tts.mime,
    });
  } catch (err) {
    const mixErr = isMixError(err)
      ? err
      : new MixError("INTERNAL", "Internal error", err instanceof Error ? (err.stack ?? err.message) : String(err));
    log(mixErr.httpStatus >= 500 ? "ERROR" : "WARNING", "mix failed", {
      render_id: renderId,
      ms: Date.now() - startedAt,
      code: mixErr.code,
      http_status: mixErr.httpStatus,
      error: mixErr.message,
      detail: mixErr.detail,
      deadline_ms: budget.totalMs,
    });
    sendError(res, mixErr);
  } finally {
    res.off("close", onClose);
    if (workDir) {
      await rm(workDir, { recursive: true, force: true }).catch((cleanupErr: unknown) => {
        log("WARNING", "temp dir cleanup failed", { render_id: renderId, error: String(cleanupErr) });
      });
    }
  }
}

const app = express();
app.disable("x-powered-by");
app.disable("etag");

// Cloud Run's front end reserves paths ending in "z" (/healthz answers Google's own 404 there),
// so /health is the route to use; /healthz stays for local docker runs.
app.get(["/health", "/healthz"], (_req: Request, res: Response) => {
  res.status(200).json({ ok: true });
});

app.post(
  "/mix",
  requireSecret,
  express.json({ limit: JSON_BODY_LIMIT, strict: true, type: "application/json" }),
  (req: Request, res: Response) => {
    void handleMix(req, res);
  },
);

app.use((_req: Request, res: Response) => {
  sendError(res, new MixError("NOT_FOUND", "Not found"));
});

// Body-parser failures (oversize, malformed JSON) land here with a 4xx `status`.
app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
  const status = (err as { status?: unknown }).status;
  if (typeof status === "number" && status >= 400 && status < 500) {
    const tooLarge = status === 413;
    res
      .status(tooLarge ? 413 : 400)
      .json({ error: tooLarge ? `Request body exceeds ${JSON_BODY_LIMIT}` : "Malformed JSON body", code: "BAD_REQUEST" });
    return;
  }
  log("ERROR", "unhandled error", { error: err instanceof Error ? (err.stack ?? err.message) : String(err) });
  sendError(res, new MixError("INTERNAL", "Internal error"));
});

async function main(): Promise<void> {
  let ffmpegVersion = "unknown";
  try {
    const { stdout } = await runTool(FFMPEG_BIN, ["-version"], { timeoutMs: 10_000 });
    ffmpegVersion = stdout.split("\n")[0]?.trim() ?? "unknown";
  } catch (err) {
    log("ERROR", "ffmpeg is not runnable; refusing to start", { error: String(err) });
    process.exit(1);
  }

  const server = app.listen(PORT, () => {
    log("INFO", "ringtone-mixer listening", {
      port: PORT,
      ffmpeg: ffmpegVersion,
      bed_hosts: ALLOWED_BED_HOSTS,
      deadline_ms: MIX_DEADLINE_MS,
      node: process.version,
    });
  });
  server.keepAliveTimeout = 65_000;
  server.headersTimeout = 70_000;

  const shutdown = (signal: string): void => {
    log("INFO", "shutting down", { signal });
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), SHUTDOWN_GRACE_MS).unref();
  };
  process.on("SIGTERM", () => shutdown("SIGTERM"));
  process.on("SIGINT", () => shutdown("SIGINT"));
}

void main();
