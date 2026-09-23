import type { TtsInputSpec } from "./audio";
import { MixError } from "./errors";

/** Parsed, validated `POST /mix` body. Numbers are already clamped to sane ranges. */
export interface MixRequest {
  renderId: string;
  bedUrl: string;
  tts: TtsPayload;
  slotStartMs: number;
  slotEndMs: number;
  slotGainDb: number;
  duckDb: number;
  maxTempo: number;
  maxDurationMs: number;
  title: string;
}

export interface TtsPayload {
  /** Decoded audio bytes (raw PCM or a container, per `input`). */
  pcm: Buffer;
  mime: string;
  sampleRate: number;
  input: TtsInputSpec;
}

export const REQUEST_DEFAULTS = {
  slotGainDb: 3,
  duckDb: -6,
  maxTempo: 1.3,
  maxDurationMs: 30_000,
  sampleRate: 24_000,
  mime: "audio/L16;codec=pcm;rate=24000",
  title: "MeraTune Ringtone",
} as const;

export const REQUEST_LIMITS = {
  renderIdMaxLength: 128,
  bedUrlMaxLength: 2048,
  mimeMaxLength: 200,
  titleMaxLength: 200,
  slotStartMaxMs: 600_000,
  slotMinMs: 100,
  slotMaxMs: 15_000,
  slotGainDb: { min: -24, max: 24 },
  duckDb: { min: -40, max: 0 },
  maxTempo: { min: 1, max: 4 },
  maxDurationMs: { min: 1_000, max: 120_000 },
  sampleRate: { min: 8_000, max: 96_000 },
  channels: { min: 1, max: 2 },
  /** Raw PCM longer than this is rejected before ffmpeg ever runs. */
  rawTtsMaxSeconds: 20,
  rawTtsMinSeconds: 0.05,
} as const;

const RENDER_ID_PATTERN = /^[A-Za-z0-9._-]+$/;
const BASE64_PATTERN = /^[A-Za-z0-9+/_-]*={0,2}$/;
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS = /[\u0000-\u001f\u007f-\u009f]/g;

const CONTAINER_SUBTYPES = new Set([
  "wav",
  "x-wav",
  "wave",
  "vnd.wave",
  "mpeg",
  "mp3",
  "mpeg3",
  "ogg",
  "opus",
  "flac",
  "x-flac",
  "mp4",
  "m4a",
  "x-m4a",
  "aac",
  "webm",
]);

function bad(message: string): MixError {
  return new MixError("BAD_REQUEST", message);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function requireString(obj: Record<string, unknown>, key: string, maxLength: number): string {
  const value = obj[key];
  if (typeof value !== "string" || value.length === 0) throw bad(`${key} must be a non-empty string`);
  if (value.length > maxLength) throw bad(`${key} is longer than ${maxLength} characters`);
  return value;
}

function optionalString(obj: Record<string, unknown>, key: string, maxLength: number): string | undefined {
  const value = obj[key];
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "string") throw bad(`${key} must be a string`);
  if (value.length > maxLength) throw bad(`${key} is longer than ${maxLength} characters`);
  return value;
}

function requireNumber(obj: Record<string, unknown>, key: string): number {
  const value = obj[key];
  if (typeof value !== "number" || !Number.isFinite(value)) throw bad(`${key} must be a finite number`);
  return value;
}

function optionalNumber(
  obj: Record<string, unknown>,
  key: string,
  fallback: number,
  range: { min: number; max: number },
): number {
  const value = obj[key];
  if (value === undefined || value === null) return fallback;
  if (typeof value !== "number" || !Number.isFinite(value)) throw bad(`${key} must be a finite number`);
  return clamp(value, range.min, range.max);
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function sanitizeTitle(raw: string | undefined): string {
  const cleaned = (raw ?? "").replace(CONTROL_CHARS, " ").replace(/\s+/g, " ").trim();
  if (!cleaned) return REQUEST_DEFAULTS.title;
  return Array.from(cleaned).slice(0, REQUEST_LIMITS.titleMaxLength).join("").trim() || REQUEST_DEFAULTS.title;
}

/**
 * Maps `tts.mime` (e.g. Gemini's `audio/L16;codec=pcm;rate=24000`) to ffmpeg
 * input flags. Container types (wav/mp3/...) need no flags; anything else is
 * treated as raw PCM, little-endian unless the mime says `endian=big`.
 */
export function describeTtsInput(mime: string, sampleRateOverride?: number): TtsInputSpec {
  const [head = "", ...paramParts] = mime.split(";");
  const [type = "", subtype = ""] = head.trim().toLowerCase().split("/");
  const params = new Map<string, string>();
  for (const part of paramParts) {
    const eq = part.indexOf("=");
    if (eq > 0) {
      params.set(
        part.slice(0, eq).trim().toLowerCase(),
        part
          .slice(eq + 1)
          .trim()
          .toLowerCase()
          .replace(/^"|"$/g, ""),
      );
    }
  }
  if (type !== "audio" && type !== "application") {
    throw bad(`tts.mime "${mime}" is not an audio type`);
  }
  if (CONTAINER_SUBTYPES.has(subtype)) {
    return { kind: "container" };
  }

  const bitsFromSubtype = /^(?:x-)?l(\d+)$/.exec(subtype)?.[1];
  const bits = Number.parseInt(bitsFromSubtype ?? params.get("bits") ?? "16", 10);
  let format: string;
  switch (bits) {
    case 8:
      format = "u8";
      break;
    case 16:
      format = "s16";
      break;
    case 24:
      format = "s24";
      break;
    case 32: {
      const codec = `${params.get("codec") ?? ""} ${params.get("encoding") ?? ""}`;
      format = codec.includes("float") ? "f32" : "s32";
      break;
    }
    default:
      throw bad(`tts.mime "${mime}" has an unsupported bit depth`);
  }
  if (format !== "u8") {
    const endian = params.get("endian") ?? params.get("endianness") ?? "little";
    format += endian === "big" ? "be" : "le";
  }

  const rateFromMime = Number.parseInt(params.get("rate") ?? "", 10);
  const sampleRate =
    sampleRateOverride ?? (Number.isFinite(rateFromMime) ? rateFromMime : REQUEST_DEFAULTS.sampleRate);
  if (sampleRate < REQUEST_LIMITS.sampleRate.min || sampleRate > REQUEST_LIMITS.sampleRate.max) {
    throw bad(`tts sample rate ${sampleRate} is outside ${REQUEST_LIMITS.sampleRate.min}-${REQUEST_LIMITS.sampleRate.max} Hz`);
  }
  const channels = Number.parseInt(params.get("channels") ?? "1", 10);
  if (!Number.isInteger(channels) || channels < REQUEST_LIMITS.channels.min || channels > REQUEST_LIMITS.channels.max) {
    throw bad(`tts.mime "${mime}" has an unsupported channel count`);
  }
  return { kind: "raw", format, sampleRate, channels, bytesPerSample: bits / 8 };
}

function optionalSampleRate(tts: Record<string, unknown>): number | undefined {
  const value = tts.sample_rate;
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "number" || !Number.isInteger(value)) throw bad("tts.sample_rate must be an integer");
  if (value < REQUEST_LIMITS.sampleRate.min || value > REQUEST_LIMITS.sampleRate.max) {
    throw bad(`tts.sample_rate must be between ${REQUEST_LIMITS.sampleRate.min} and ${REQUEST_LIMITS.sampleRate.max}`);
  }
  return value;
}

/** Drops a trailing partial sample frame so ffmpeg never sees a torn sample. */
function alignToFrames(pcm: Buffer, bytesPerSample: number, channels: number): Buffer {
  const frameBytes = bytesPerSample * channels;
  const usable = pcm.length - (pcm.length % frameBytes);
  return usable === pcm.length ? pcm : pcm.subarray(0, usable);
}

/** Validates the JSON body of `POST /mix`. Throws MixError(BAD_REQUEST | TTS_EMPTY | TTS_TOO_LONG). */
export function parseMixRequest(body: unknown): MixRequest {
  if (!isRecord(body)) throw bad("JSON object body required");

  const renderId = requireString(body, "render_id", REQUEST_LIMITS.renderIdMaxLength);
  if (!RENDER_ID_PATTERN.test(renderId)) {
    throw bad("render_id may contain only letters, digits, '.', '_' and '-'");
  }

  const bedUrl = requireString(body, "bed_url", REQUEST_LIMITS.bedUrlMaxLength);
  try {
    new URL(bedUrl);
  } catch {
    throw bad("bed_url must be an absolute URL");
  }

  const ttsRaw = body.tts;
  if (!isRecord(ttsRaw)) throw bad("tts must be an object");
  const pcmBase64 = requireString(ttsRaw, "pcm_base64", Number.MAX_SAFE_INTEGER).replace(/\s+/g, "");
  if (!BASE64_PATTERN.test(pcmBase64)) throw bad("tts.pcm_base64 is not valid base64");
  const decoded = Buffer.from(pcmBase64, "base64");
  if (decoded.length === 0) throw new MixError("TTS_EMPTY", "tts.pcm_base64 decoded to zero bytes");

  const mime = (optionalString(ttsRaw, "mime", REQUEST_LIMITS.mimeMaxLength) ?? "").trim() || REQUEST_DEFAULTS.mime;
  const sampleRateOverride = optionalSampleRate(ttsRaw);
  const input = describeTtsInput(mime, sampleRateOverride);

  let pcm: Buffer = decoded;
  let sampleRate = sampleRateOverride ?? REQUEST_DEFAULTS.sampleRate;
  if (input.kind === "raw") {
    pcm = alignToFrames(decoded, input.bytesPerSample, input.channels);
    sampleRate = input.sampleRate;
    const seconds = pcm.length / (input.bytesPerSample * input.channels * input.sampleRate);
    if (seconds < REQUEST_LIMITS.rawTtsMinSeconds) {
      throw new MixError("TTS_EMPTY", `tts clip is only ${Math.round(seconds * 1000)} ms of audio`);
    }
    if (seconds > REQUEST_LIMITS.rawTtsMaxSeconds) {
      throw new MixError(
        "TTS_TOO_LONG",
        `tts clip is ${seconds.toFixed(1)} s before trimming; limit is ${REQUEST_LIMITS.rawTtsMaxSeconds} s`,
      );
    }
  }

  const slotStartMs = Math.round(requireNumber(body, "slot_start_ms"));
  const slotEndMs = Math.round(requireNumber(body, "slot_end_ms"));
  if (slotStartMs < 0 || slotStartMs > REQUEST_LIMITS.slotStartMaxMs) {
    throw bad(`slot_start_ms must be between 0 and ${REQUEST_LIMITS.slotStartMaxMs}`);
  }
  if (slotEndMs <= slotStartMs) throw bad("slot_end_ms must be greater than slot_start_ms");
  const slotLengthMs = slotEndMs - slotStartMs;
  if (slotLengthMs < REQUEST_LIMITS.slotMinMs || slotLengthMs > REQUEST_LIMITS.slotMaxMs) {
    throw bad(`name slot must be between ${REQUEST_LIMITS.slotMinMs} and ${REQUEST_LIMITS.slotMaxMs} ms long`);
  }

  const slotGainDb = optionalNumber(body, "slot_gain_db", REQUEST_DEFAULTS.slotGainDb, REQUEST_LIMITS.slotGainDb);
  const duckDb = optionalNumber(body, "duck_db", REQUEST_DEFAULTS.duckDb, REQUEST_LIMITS.duckDb);
  const maxTempo = optionalNumber(body, "max_tempo", REQUEST_DEFAULTS.maxTempo, REQUEST_LIMITS.maxTempo);
  const maxDurationMs = Math.round(
    optionalNumber(body, "max_duration_ms", REQUEST_DEFAULTS.maxDurationMs, REQUEST_LIMITS.maxDurationMs),
  );
  const title = sanitizeTitle(optionalString(body, "title", REQUEST_LIMITS.titleMaxLength * 8));

  return {
    renderId,
    bedUrl,
    tts: { pcm, mime, sampleRate, input },
    slotStartMs,
    slotEndMs,
    slotGainDb,
    duckDb,
    maxTempo,
    maxDurationMs,
    title,
  };
}
