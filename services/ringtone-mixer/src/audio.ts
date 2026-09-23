import { execFile, type ChildProcess } from "node:child_process";
import type { RenderBudget } from "./budget";
import { MixError } from "./errors";

export const FFMPEG_BIN: string = process.env.FFMPEG_BIN ?? "ffmpeg";
export const FFPROBE_BIN: string = process.env.FFPROBE_BIN ?? "ffprobe";

/** Hard ceiling for any single ffmpeg/ffprobe invocation; a render budget can only shorten it. */
export const TOOL_TIMEOUT_MS = 40_000;
/** Cap on captured stdout + stderr per invocation. */
export const TOOL_MAX_BUFFER = 8 * 1024 * 1024;

export interface ToolOutput {
  stdout: string;
  stderr: string;
}

interface ExecFailure extends Error {
  code?: number | string | null;
  killed?: boolean;
  signal?: NodeJS.Signals | null;
}

/**
 * ffmpeg at `-loglevel info` echoes each input's metadata block (`Metadata:` followed by
 * indented `key : value` lines). Our output mp3 carries `title=<personalized title>`, which
 * contains the user's name, so those lines must never reach a log.
 */
export function stripMetadataLines(text: string): string {
  const out: string[] = [];
  // Indent of the current `Metadata:` header; its entries are indented deeper. -1 = not inside one.
  let metadataIndent = -1;
  for (const line of text.split(/\r?\n/)) {
    const indent = line.length - line.trimStart().length;
    if (/^\s*Metadata:\s*$/.test(line)) {
      metadataIndent = indent;
      continue;
    }
    if (metadataIndent >= 0 && indent > metadataIndent && line.trim() !== "") continue;
    metadataIndent = -1;
    if (/^\s*(title|artist|album|comment)\s*:/i.test(line)) continue;
    out.push(line);
  }
  return out.join("\n");
}

/** Keeps the last `maxChars` of a tool's stderr for logs, without metadata lines. */
export function tailOf(text: string, maxChars = 600): string {
  const trimmed = stripMetadataLines(text).trim();
  return trimmed.length <= maxChars ? trimmed : "..." + trimmed.slice(-maxChars);
}

export interface RunToolOptions {
  /** Per-call cap; defaults to TOOL_TIMEOUT_MS. */
  timeoutMs?: number;
  /** Render budget: shortens the cap to the time left and kills the child on client disconnect. */
  budget?: RenderBudget;
}

/**
 * Runs `bin` with argv `args` (no shell), enforcing the timeout and output cap.
 * With a budget the timeout is min(cap, time left) and the child is SIGKILLed
 * when the budget's signal aborts. Non-zero exit, timeout, deadline expiry,
 * client disconnect or missing binary are surfaced as MixError.
 */
export function runTool(bin: string, args: string[], options: RunToolOptions = {}): Promise<ToolOutput> {
  const { budget } = options;
  const capMs = options.timeoutMs ?? TOOL_TIMEOUT_MS;
  return new Promise((resolve, reject) => {
    // Throws (-> rejects) before spawning when the client is gone or no time is left.
    const { timeoutMs, limited } = budget ? budget.timeoutFor(capMs, bin) : { timeoutMs: capMs, limited: false };
    // Killed by hand rather than via execFile's `signal` option, which sends SIGTERM
    // (execFile does not forward `killSignal` to it); SIGKILL cannot be ignored.
    let child: ChildProcess | undefined;
    const onClientClosed = (): void => {
      child?.kill("SIGKILL");
    };
    child = execFile(
      bin,
      args,
      {
        timeout: timeoutMs,
        maxBuffer: TOOL_MAX_BUFFER,
        killSignal: "SIGKILL",
        windowsHide: true,
        encoding: "utf8",
      },
      (error, stdout, stderr) => {
        budget?.signal?.removeEventListener("abort", onClientClosed);
        if (error) {
          const failure = error as ExecFailure;
          const detail = tailOf(stderr);
          if (budget?.aborted) {
            reject(budget.closedError(bin));
          } else if (failure.killed || failure.signal === "SIGKILL") {
            reject(
              limited && budget
                ? // A killed child's stderr is a truncated start-up banner: no diagnostic value, and it
                  // may carry the input's metadata (the personalized title).
                  budget.deadlineError(bin)
                : new MixError("FFMPEG_FAILED", `${bin} timed out after ${timeoutMs} ms`, detail),
            );
          } else if (failure.code === "ENOENT") {
            reject(new MixError("INTERNAL", `${bin} is not installed`, failure.message));
          } else {
            reject(new MixError("FFMPEG_FAILED", `${bin} exited with status ${String(failure.code)}`, detail));
          }
          return;
        }
        resolve({ stdout, stderr });
      },
    );
    budget?.signal?.addEventListener("abort", onClientClosed, { once: true });
  });
}

export interface FfmpegOptions {
  /** `info` only for astats reads. */
  loglevel?: "error" | "info";
  budget?: RenderBudget;
}

/** ffmpeg with the fixed flags every render uses. */
export function ffmpeg(args: string[], options: FfmpegOptions = {}): Promise<ToolOutput> {
  const loglevel = options.loglevel ?? "error";
  return runTool(FFMPEG_BIN, ["-hide_banner", "-nostdin", "-nostats", "-loglevel", loglevel, "-y", ...args], {
    budget: options.budget,
  });
}

/** How the TTS bytes must be presented to ffmpeg; derived from `tts.mime`. */
export interface RawPcmInput {
  kind: "raw";
  /** ffmpeg raw demuxer name, e.g. `s16le`. */
  format: string;
  sampleRate: number;
  channels: number;
  bytesPerSample: number;
}

export interface ContainerInput {
  kind: "container";
}

export type TtsInputSpec = RawPcmInput | ContainerInput;

/** ffmpeg input flags to place before `-i` for the TTS file. */
export function inputArgsFor(spec: TtsInputSpec): string[] {
  if (spec.kind === "raw") {
    return ["-f", spec.format, "-ar", String(spec.sampleRate), "-ac", String(spec.channels)];
  }
  return [];
}

export interface ProbeInfo {
  durationSec: number;
  codecName?: string;
  sampleRate?: number;
  channels?: number;
}

interface FfprobeJson {
  format?: { duration?: string };
  streams?: Array<{ codec_name?: string; sample_rate?: string; channels?: number }>;
}

/** Container duration plus first audio stream codec/sample rate. */
export async function probe(filePath: string, budget?: RenderBudget): Promise<ProbeInfo> {
  const { stdout } = await runTool(
    FFPROBE_BIN,
    [
      "-v",
      "error",
      "-select_streams",
      "a:0",
      "-show_entries",
      "format=duration:stream=codec_name,sample_rate,channels",
      "-of",
      "json",
      filePath,
    ],
    { budget },
  );
  let parsed: FfprobeJson;
  try {
    parsed = JSON.parse(stdout) as FfprobeJson;
  } catch {
    throw new MixError("FFMPEG_FAILED", "ffprobe returned unparseable output", tailOf(stdout));
  }
  const stream = parsed.streams?.[0];
  const duration = Number.parseFloat(parsed.format?.duration ?? "");
  const sampleRate = Number.parseInt(stream?.sample_rate ?? "", 10);
  const info: ProbeInfo = { durationSec: Number.isFinite(duration) && duration > 0 ? duration : 0 };
  if (stream?.codec_name) info.codecName = stream.codec_name;
  if (Number.isFinite(sampleRate)) info.sampleRate = sampleRate;
  if (typeof stream?.channels === "number" && stream.channels > 0) info.channels = stream.channels;
  return info;
}

export interface TimeWindow {
  startSec: number;
  endSec: number;
}

export interface LevelStats {
  /** Overall RMS in dBFS across all channels; -Infinity for digital silence, NaN when unavailable. */
  rmsDb: number;
  /** Overall sample peak in dBFS. */
  peakDb: number;
  /** Decoded samples per channel (exact, after any window). */
  samples: number;
}

/** Formats seconds for a filter argument without exponent notation. */
export function secs(value: number): string {
  return value.toFixed(4);
}

export interface MeasureOptions {
  /** ffmpeg input flags placed before `-i`. */
  inputArgs?: string[];
  budget?: RenderBudget;
}

/**
 * Decodes `filePath` (optionally only `window`) through `astats` and returns
 * overall RMS, peak and sample count. astats reports at `info` level, so this
 * is the one place ffmpeg runs above `-loglevel error`.
 */
export async function measureLevels(
  filePath: string,
  window?: TimeWindow,
  options: MeasureOptions = {},
): Promise<LevelStats> {
  const chain: string[] = [];
  if (window) {
    chain.push(`atrim=start=${secs(window.startSec)}:end=${secs(window.endSec)}`, "asetpts=PTS-STARTPTS");
  }
  chain.push("astats=measure_perchannel=none:measure_overall=RMS_level+Peak_level+Number_of_samples");
  const { stderr } = await ffmpeg(
    [...(options.inputArgs ?? []), "-i", filePath, "-af", chain.join(","), "-f", "null", "-"],
    { loglevel: "info", budget: options.budget },
  );
  return {
    rmsDb: parseLevel(stderr, "RMS level dB"),
    peakDb: parseLevel(stderr, "Peak level dB"),
    samples: parseCount(stderr, "Number of samples"),
  };
}

function parseLevel(text: string, label: string): number {
  const match = new RegExp(`${label}:\\s*(\\S+)`).exec(text);
  const token = match?.[1];
  if (token === undefined) return Number.NaN;
  const lower = token.toLowerCase();
  if (lower === "-inf") return Number.NEGATIVE_INFINITY;
  if (lower === "inf") return Number.POSITIVE_INFINITY;
  if (lower.includes("nan")) return Number.NaN;
  const value = Number.parseFloat(lower);
  return Number.isFinite(value) ? value : Number.NaN;
}

function parseCount(text: string, label: string): number {
  const match = new RegExp(`${label}:\\s*(\\d+)`).exec(text);
  const token = match?.[1];
  if (token === undefined) return 0;
  const value = Number.parseInt(token, 10);
  return Number.isFinite(value) ? value : 0;
}
