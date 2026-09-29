/**
 * TTS client for generate-ringtone: Gemini direct, or Gemini's TTS models through OpenRouter.
 *
 * Provider (`TtsConfig.provider`, from app_config.openrouter_use): "gemini" calls
 * generativelanguage.googleapis.com generateContent (below); "openrouter" calls OpenRouter's
 * OpenAI-style POST /api/v1/audio/speech with a Google Gemini TTS model
 * (app_config.openrouter_tts_model / openrouter_tts_fallback_model). Both run the same attempt
 * plan, prompts, voice, clip-length check and safety handling, and both return the same TtsResult:
 * 16-bit little-endian mono PCM (OpenRouter answers `audio/pcm;rate=24000;channels=1`, Gemini
 * `audio/L16;codec=pcm;rate=24000`), with the mime normalised to Gemini's shape so the mixer
 * decodes both identically. OpenRouter's Gemini route only accepts response_format "pcm".
 *
 * Endpoint is config-driven (`app_config.gemini_tts_endpoint`):
 *   - `generate_content` (implemented): POST models/{model}:generateContent with
 *     responseModalities ["AUDIO"]; audio comes back as candidates[0].content.parts[].inlineData
 *     with mimeType like `audio/L16;codec=pcm;rate=24000` (16-bit mono PCM). Pin the exact shape
 *     with the live curl in docs/GENERATE_RINGTONE_API.md before changing anything here.
 *   - `interactions` (stub): reserved for gemini-3.1-flash-tts-preview. `resolveTtsEndpoint`
 *     rejects it (and any unknown value) so generate-ringtone answers 503 SERVICE_UNAVAILABLE at
 *     config load, before quota and bookkeeping; `synthesizeName` still throws TtsUnavailable as a
 *     backstop. The detailed reason is logged server-side only.
 *
 * Reliability (measured 2026-09-23 against gemini-2.5-flash-preview-tts): a one-word name often
 * comes back as HTTP 200 with no audio (finishReason OTHER), and a long "Say <style>: <name>"
 * prompt sometimes gets its style text read aloud. Two prompt shapes worked best, the transcript
 * form (18/20 over 10 names in 5 scripts) and a one-sentence form (15/20); the failures looked
 * random rather than per-name. So a render walks a fixed attempt plan (TTS_ATTEMPT_PLAN): transcript,
 * sentence, then the transcript on the fallback model (app_config.gemini_tts_fallback_model,
 * default gemini-3.1-flash-tts-preview, 10/10 in the same probe). An empty answer, a 5xx, a network
 * error, a timeout, a clip longer than maxClipMs (the model read more than the name) or a single
 * safety block moves on to the next attempt.
 *
 * Guards: per-attempt abort (25 s) and an overall budget (55 s) so TTS + mixer + upload stay under
 * the 150 s Edge wall clock; 429/503 -> TtsRateLimited(retryAfterSeconds) immediately; 400/401/403/404
 * on the primary model -> TtsFailed immediately (bad key, billing, model); two safety blocks ->
 * TtsRejected; nothing usable after the plan -> TtsFailed with the last attempt's detail.
 * OpenRouter maps the same way: 429/503/529 -> TtsRateLimited; 400/401/402/403/404 -> TtsFailed
 * (402 = no credits on the key); a 400 refusal counts as a safety block; 502/524/other 5xx, an
 * empty or non-audio answer, a timeout or a network error move on to the next attempt.
 *
 * The name is part of the prompt by design. It is never logged from this module.
 */

export type TtsEndpoint = "generate_content" | "interactions";

/** app_config.openrouter_use "true" -> openrouter, else gemini. */
export type TtsProvider = "gemini" | "openrouter";

export const GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta";
export const DEFAULT_TTS_MODEL = "gemini-2.5-flash-preview-tts";
/** Used for the last attempt when the primary model returned nothing usable. "none" disables it. */
export const DEFAULT_TTS_FALLBACK_MODEL = "gemini-3.1-flash-tts-preview";

export const OPENROUTER_SPEECH_URL = "https://openrouter.ai/api/v1/audio/speech";
/**
 * OpenRouter has no gemini-2.5-*-preview-tts, so its primary is the model the Gemini flow already
 * uses as its fallback (10/10 in the 2026-09-23 probe), and its fallback the 3.8 successor.
 */
export const DEFAULT_OPENROUTER_TTS_MODEL = "google/gemini-3.1-flash-tts-preview";
export const DEFAULT_OPENROUTER_TTS_FALLBACK_MODEL = "google/gemini-3.8-flash-tts";
export const DEFAULT_TTS_ATTEMPT_TIMEOUT_MS = 25_000;
export const DEFAULT_TTS_TOTAL_BUDGET_MS = 55_000;
export const DEFAULT_TTS_MAX_CLIP_MS = 6_000;
export const DEFAULT_SAMPLE_RATE = 24_000;
export const PCM_BYTES_PER_SAMPLE = 2;

const DEFAULT_RATE_LIMIT_RETRY_S = 20;
const DEFAULT_UNAVAILABLE_RETRY_S = 10;
const MAX_RETRY_AFTER_S = 300;
const MIN_ATTEMPT_MS = 3_000;
/** Safety blocks needed before the name is rejected; one can be spurious on a benign name. */
const REJECTIONS_TO_FAIL = 2;

export class TtsRateLimited extends Error {
  readonly retryAfterSeconds: number;
  constructor(retryAfterSeconds: number, message = "Gemini TTS is rate limited") {
    super(message);
    this.name = "TtsRateLimited";
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/** Misconfiguration or an endpoint that is not implemented; maps to 503 SERVICE_UNAVAILABLE. */
export class TtsUnavailable extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TtsUnavailable";
  }
}

/** Gemini refused the prompt (safety); maps to 400 NAME_REJECTED. */
export class TtsRejected extends Error {
  readonly detail: string;
  constructor(detail: string) {
    super("Gemini TTS rejected the prompt");
    this.name = "TtsRejected";
    this.detail = detail;
  }
}

/** Anything else; maps to 502 TTS_FAILED. `detail` is a short machine token for logs. */
export class TtsFailed extends Error {
  readonly detail: string;
  constructor(detail: string, message = "Gemini TTS failed") {
    super(message);
    this.name = "TtsFailed";
    this.detail = detail;
  }
}

export type TtsConfig = {
  /** Default "gemini". */
  provider?: TtsProvider;
  /** Gemini API key, or the OpenRouter key for provider "openrouter". */
  apiKey: string;
  /** Gemini model id, or an OpenRouter slug (google/gemini-...-tts) for provider "openrouter". */
  model: string;
  /** Model for the last attempt; empty or "none" = retry on `model` instead. */
  fallbackModel?: string;
  /** Gemini only; default generate_content. */
  endpoint?: TtsEndpoint;
  /** Abort a single Gemini call after this long. Default 25 s. */
  attemptTimeoutMs?: number;
  /** Total budget across all attempts. Default 55 s. */
  totalBudgetMs?: number;
  /** Estimated clips longer than this count as a failed attempt. Default 6000. */
  maxClipMs?: number;
  /** Injection point for tests. */
  fetchImpl?: typeof fetch;
};

export type TtsRequest = {
  /** Canonical spoken form of the name (names.ts `spokenName`), already sanitised. */
  name: string;
  /** Prebuilt voice name from _shared/tts-voices.ts. */
  voiceName: string;
  /** tune.tts_style_prompt; when empty the default style is derived from category + language. */
  stylePrompt?: string | null;
  category?: string | null;
  /**
   * The song's language (tune.language), not the request's: the render cache key has no language,
   * so the prompt must not depend on anything outside the key.
   */
  language: string;
};

/**
 * app_config.gemini_tts_endpoint -> the endpoint this module implements, or null for anything
 * else (including the not-yet-implemented `interactions`). Blank means the default.
 * generate-ringtone checks this at config load, before quota and bookkeeping rows.
 */
export function resolveTtsEndpoint(raw: string | null | undefined): TtsEndpoint | null {
  const value = (raw ?? "").trim().toLowerCase() || "generate_content";
  return value === "generate_content" ? "generate_content" : null;
}

export type TtsResult = {
  pcmBase64: string;
  /**
   * Gemini's mime string (OpenRouter's `audio/pcm;rate=…` is rewritten to the same
   * `audio/L16;codec=pcm;rate=…`), forwarded to the mixer so ffmpeg flags derive from it.
   */
  mime: string;
  sampleRate: number;
  estimatedDurationMs: number;
  promptVariant: PromptVariant;
  /** Model that produced the clip (primary or fallback). */
  model: string;
  /** 1-based attempt that succeeded. */
  attempt: number;
};

export type PromptVariant = "transcript" | "sentence";

/** Attempt order; `fallback: true` runs on TtsConfig.fallbackModel when one is configured. */
export const TTS_ATTEMPT_PLAN: ReadonlyArray<{ variant: PromptVariant; fallback: boolean }> = Object.freeze([
  { variant: "transcript", fallback: false },
  { variant: "sentence", fallback: false },
  { variant: "transcript", fallback: true },
]);

export const DEFAULT_TTS_STYLE = "warmly, like lovingly calling someone";

/**
 * Delivery style: tune.tts_style_prompt when authored, else DEFAULT_TTS_STYLE. Category and
 * language are deliberately not spelled out: long style text is what Gemini tends to read aloud.
 */
export function buildStylePrompt(req: Pick<TtsRequest, "stylePrompt">): string {
  return req.stylePrompt?.trim() || DEFAULT_TTS_STYLE;
}

/**
 * transcript: instruction line, blank line, then the name alone (the most reliable shape).
 * sentence:   one sentence that ends with the name and a full stop.
 */
export function buildTtsPrompt(name: string, style: string, variant: PromptVariant): string {
  const delivery = style.trim() || DEFAULT_TTS_STYLE;
  if (variant === "sentence") {
    const end = /[.!?\u0964]$/.test(name) ? "" : ".";
    return `Read aloud, ${delivery}, this one name: ${name}${end}`;
  }
  return `Speak the following name ${delivery}. Speak only the name.\n\n${name}`;
}

/**
 * Normalises app_config.gemini_tts_fallback_model (or openrouter_tts_fallback_model with its own
 * [fallbackDefault]): blank -> default, "none"/"off" -> disabled.
 */
export function resolveFallbackModel(
  raw: string | null | undefined,
  fallbackDefault: string = DEFAULT_TTS_FALLBACK_MODEL,
): string {
  const value = (raw ?? "").trim();
  if (!value) return fallbackDefault;
  return /^(none|off|disabled?)$/i.test(value) ? "" : value;
}

/** Parses `rate=24000` out of `audio/L16;codec=pcm;rate=24000`. */
export function parseSampleRate(mime: string | null | undefined, fallback = DEFAULT_SAMPLE_RATE): number {
  const match = /rate=(\d{4,6})/i.exec(mime ?? "");
  if (!match) return fallback;
  const rate = Number.parseInt(match[1], 10);
  return rate >= 8_000 && rate <= 96_000 ? rate : fallback;
}

/** Duration of 16-bit mono PCM from its base64 length: len * 0.75 bytes / (rate * 2). */
export function estimateDurationMs(
  base64Length: number,
  sampleRate: number,
  bytesPerSample = PCM_BYTES_PER_SAMPLE,
): number {
  if (base64Length <= 0 || sampleRate <= 0 || bytesPerSample <= 0) return 0;
  const bytes = base64Length * 0.75;
  return Math.round((bytes / (sampleRate * bytesPerSample)) * 1000);
}

/**
 * Retry hint from a 429/503: `Retry-After` header (seconds or HTTP date), else the RetryInfo
 * `retryDelay` ("20s") in the Google error details, else `fallback`. Capped at 300 s.
 */
export function parseRetryAfterSeconds(headers: Headers, body: unknown, fallback: number): number {
  const header = headers.get("retry-after");
  if (header) {
    const seconds = Number.parseInt(header, 10);
    if (Number.isFinite(seconds) && seconds > 0) return Math.min(seconds, MAX_RETRY_AFTER_S);
    const date = Date.parse(header);
    if (!Number.isNaN(date)) {
      const diff = Math.ceil((date - Date.now()) / 1000);
      if (diff > 0) return Math.min(diff, MAX_RETRY_AFTER_S);
    }
  }
  const details = (body as { error?: { details?: unknown[] } } | null)?.error?.details;
  if (Array.isArray(details)) {
    for (const detail of details) {
      const delay = (detail as { retryDelay?: unknown } | null)?.retryDelay;
      if (typeof delay !== "string") continue;
      const match = /^(\d+(?:\.\d+)?)s$/.exec(delay.trim());
      if (!match) continue;
      const seconds = Math.ceil(Number.parseFloat(match[1]));
      if (seconds > 0) return Math.min(seconds, MAX_RETRY_AFTER_S);
    }
  }
  return fallback;
}

function base64ToBytes(base64: string): Uint8Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

/** Joins several base64 PCM parts into one valid base64 string (decode, concat, re-encode). */
export function concatBase64(parts: string[]): string {
  if (parts.length === 1) return parts[0];
  const decoded = parts.map(base64ToBytes);
  const total = decoded.reduce((sum, part) => sum + part.length, 0);
  const joined = new Uint8Array(total);
  let offset = 0;
  for (const part of decoded) {
    joined.set(part, offset);
    offset += part.length;
  }
  return bytesToBase64(joined);
}

type GeminiPart = { text?: string; inlineData?: { mimeType?: string; data?: string } };

type GeminiResponse = {
  candidates?: Array<{ content?: { parts?: GeminiPart[] }; finishReason?: string }>;
  promptFeedback?: { blockReason?: string };
  error?: { code?: number; message?: string; status?: string; details?: unknown[] };
};

const REJECT_FINISH_REASONS: ReadonlySet<string> = new Set([
  "SAFETY",
  "PROHIBITED_CONTENT",
  "BLOCKLIST",
  "SPII",
  "RECITATION",
]);

const NON_RETRYABLE_STATUSES: ReadonlySet<number> = new Set([400, 401, 403, 404]);

type AttemptOutcome =
  | { kind: "ok"; result: TtsResult }
  | { kind: "retry"; detail: string }
  | { kind: "rejected"; detail: string };

function isAbortError(err: unknown): boolean {
  return (err as { name?: string } | null)?.name === "AbortError";
}

export function generateContentRequestBody(prompt: string, voiceName: string): Record<string, unknown> {
  return {
    contents: [{ role: "user", parts: [{ text: prompt }] }],
    generationConfig: {
      responseModalities: ["AUDIO"],
      speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName } } },
    },
  };
}

async function generateContentOnce(
  config: TtsConfig,
  model: string,
  prompt: string,
  voiceName: string,
  timeoutMs: number,
  variant: PromptVariant,
  attempt: number,
): Promise<AttemptOutcome> {
  const fetchImpl = config.fetchImpl ?? fetch;
  const url = `${GEMINI_API_BASE}/models/${encodeURIComponent(model)}:generateContent`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  let response: Response;
  let text: string;
  try {
    response = await fetchImpl(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": config.apiKey,
      },
      body: JSON.stringify(generateContentRequestBody(prompt, voiceName)),
      signal: controller.signal,
    });
    text = await response.text();
  } catch (err) {
    return { kind: "retry", detail: isAbortError(err) ? "timeout" : "network" };
  } finally {
    clearTimeout(timer);
  }

  let json: GeminiResponse | null = null;
  try {
    json = text ? (JSON.parse(text) as GeminiResponse) : null;
  } catch {
    json = null;
  }

  if (response.status === 429 || response.status === 503) {
    const fallback = response.status === 429 ? DEFAULT_RATE_LIMIT_RETRY_S : DEFAULT_UNAVAILABLE_RETRY_S;
    throw new TtsRateLimited(
      parseRetryAfterSeconds(response.headers, json, fallback),
      `Gemini TTS ${response.status} ${json?.error?.status ?? ""}`.trim(),
    );
  }
  if (NON_RETRYABLE_STATUSES.has(response.status)) {
    // Bad key, disabled billing, unknown model or voice: retrying cannot help.
    const status = json?.error?.status ?? "";
    const message = (json?.error?.message ?? "").slice(0, 200);
    throw new TtsFailed(`gemini_${response.status}${status ? `_${status}` : ""}`, `Gemini TTS ${response.status}: ${message}`);
  }
  if (!response.ok) return { kind: "retry", detail: `gemini_${response.status}` };
  if (!json) return { kind: "retry", detail: "bad_json" };

  const blockReason = json.promptFeedback?.blockReason;
  if (blockReason) return { kind: "rejected", detail: `blocked_${blockReason}` };

  const candidate = json.candidates?.[0];
  if (candidate?.finishReason && REJECT_FINISH_REASONS.has(candidate.finishReason)) {
    return { kind: "rejected", detail: `finish_${candidate.finishReason}` };
  }

  const audioParts = (candidate?.content?.parts ?? []).filter((part) =>
    typeof part.inlineData?.data === "string" &&
    part.inlineData.data.length > 0 &&
    (part.inlineData.mimeType ?? "").toLowerCase().startsWith("audio/")
  );
  if (audioParts.length === 0) {
    return { kind: "retry", detail: candidate?.finishReason ? `no_audio_${candidate.finishReason}` : "no_audio" };
  }

  const mime = audioParts[0].inlineData!.mimeType!;
  const pcmBase64 = concatBase64(audioParts.map((part) => part.inlineData!.data!));
  const sampleRate = parseSampleRate(mime);

  return {
    kind: "ok",
    result: {
      pcmBase64,
      mime,
      sampleRate,
      estimatedDurationMs: estimateDurationMs(pcmBase64.length, sampleRate),
      promptVariant: variant,
      model,
      attempt,
    },
  };
}

export function openRouterSpeechRequestBody(model: string, prompt: string, voiceName: string): Record<string, unknown> {
  // The same prompt text and prebuilt voice as generateContentRequestBody; pcm is the only format
  // OpenRouter's Gemini TTS route accepts.
  return { model, input: prompt, voice: voiceName, response_format: "pcm" };
}

type OpenRouterError = { error?: { code?: number | string; message?: string; metadata?: unknown } };

/** 4xx answers that are a content refusal rather than a bad request: counted like a Gemini safety block. */
const OPENROUTER_REFUSAL = /safety|blocked|prohibited|harmful|moderation|flagged/i;

/** 402 = no credits left on the OpenRouter key: like Gemini's 400/403 billing errors, not retryable. */
const OPENROUTER_NON_RETRYABLE: ReadonlySet<number> = new Set([400, 401, 402, 403, 404]);

/** Overloaded upstream: like Gemini's 503, the app re-posts later. 502 / 524 are retried in the plan. */
const OPENROUTER_BUSY: ReadonlySet<number> = new Set([503, 529]);

/** Parses `channels=2` out of `audio/pcm;rate=24000;channels=1`; absent means mono. */
function parseChannels(contentType: string): number {
  const match = /channels=(\d+)/i.exec(contentType);
  return match ? Number.parseInt(match[1], 10) : 1;
}

async function openRouterSpeechOnce(
  config: TtsConfig,
  model: string,
  prompt: string,
  voiceName: string,
  timeoutMs: number,
  variant: PromptVariant,
  attempt: number,
): Promise<AttemptOutcome> {
  const fetchImpl = config.fetchImpl ?? fetch;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  let response: Response;
  let bytes: Uint8Array | null = null;
  let errorJson: OpenRouterError | null = null;
  try {
    response = await fetchImpl(OPENROUTER_SPEECH_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${config.apiKey}`,
        "X-Title": "MeraTune",
      },
      body: JSON.stringify(openRouterSpeechRequestBody(model, prompt, voiceName)),
      signal: controller.signal,
    });
    if (response.ok) {
      bytes = new Uint8Array(await response.arrayBuffer());
    } else {
      const text = await response.text();
      try {
        errorJson = text ? (JSON.parse(text) as OpenRouterError) : null;
      } catch {
        errorJson = null;
      }
    }
  } catch (err) {
    return { kind: "retry", detail: isAbortError(err) ? "timeout" : "network" };
  } finally {
    clearTimeout(timer);
  }

  if (response.status === 429 || OPENROUTER_BUSY.has(response.status)) {
    const fallback = response.status === 429 ? DEFAULT_RATE_LIMIT_RETRY_S : DEFAULT_UNAVAILABLE_RETRY_S;
    throw new TtsRateLimited(
      parseRetryAfterSeconds(response.headers, errorJson, fallback),
      `OpenRouter TTS ${response.status}`,
    );
  }
  if (OPENROUTER_NON_RETRYABLE.has(response.status)) {
    const message = String(errorJson?.error?.message ?? "").slice(0, 200);
    if (response.status === 400 && OPENROUTER_REFUSAL.test(message)) {
      return { kind: "rejected", detail: "openrouter_refused" };
    }
    // Bad key, no credits, unknown model or voice: retrying cannot help.
    throw new TtsFailed(`openrouter_${response.status}`, `OpenRouter TTS ${response.status}: ${message}`);
  }
  if (!response.ok) return { kind: "retry", detail: `openrouter_${response.status}` };

  const contentType = (response.headers.get("content-type") ?? "").toLowerCase();
  if (!contentType.startsWith("audio/")) return { kind: "retry", detail: "not_audio" };
  if (parseChannels(contentType) !== 1) return { kind: "retry", detail: "not_mono" };
  // 16-bit frames: a trailing odd byte is not a sample.
  const pcm = bytes && bytes.length % 2 === 1 ? bytes.subarray(0, bytes.length - 1) : bytes;
  if (!pcm || pcm.length === 0) return { kind: "retry", detail: "no_audio" };

  const sampleRate = parseSampleRate(contentType);
  const pcmBase64 = bytesToBase64(pcm);
  return {
    kind: "ok",
    result: {
      pcmBase64,
      mime: `audio/L16;codec=pcm;rate=${sampleRate}`,
      sampleRate,
      estimatedDurationMs: estimateDurationMs(pcmBase64.length, sampleRate),
      promptVariant: variant,
      model,
      attempt,
    },
  };
}

/**
 * Synthesises the spoken name. Throws TtsUnavailable, TtsRateLimited, TtsRejected or TtsFailed.
 */
export async function synthesizeName(config: TtsConfig, req: TtsRequest): Promise<TtsResult> {
  const provider: TtsProvider = config.provider ?? "gemini";
  if (provider === "gemini") {
    const endpoint = config.endpoint ?? "generate_content";
    if (endpoint === "interactions") {
      throw new TtsUnavailable(
        "gemini_tts_endpoint=interactions is not implemented yet. Set app_config.gemini_tts_endpoint to " +
          "generate_content (gemini-2.5-*-preview-tts) until the Interactions API path ships.",
      );
    }
    if (endpoint !== "generate_content") {
      throw new TtsUnavailable(`Unknown gemini_tts_endpoint "${endpoint}"; expected generate_content or interactions`);
    }
  }
  const openRouter = provider === "openrouter";
  if (!config.apiKey) {
    throw new TtsUnavailable(openRouter ? "OPENROUTER_API_KEY is not configured" : "GEMINI_API_KEY is not configured");
  }
  if (!config.model) {
    throw new TtsUnavailable(openRouter ? "openrouter_tts_model is not configured" : "gemini_tts_model is not configured");
  }
  if (!req.voiceName) throw new TtsUnavailable("tts voice is not configured for this tune");
  const attemptOnce = openRouter ? openRouterSpeechOnce : generateContentOnce;

  const maxClipMs = config.maxClipMs ?? DEFAULT_TTS_MAX_CLIP_MS;
  const attemptTimeout = config.attemptTimeoutMs ?? DEFAULT_TTS_ATTEMPT_TIMEOUT_MS;
  const deadline = Date.now() + (config.totalBudgetMs ?? DEFAULT_TTS_TOTAL_BUDGET_MS);
  const fallbackModel = (config.fallbackModel ?? "").trim();
  const style = buildStylePrompt(req);

  let lastDetail = "budget_exhausted";
  let lastRejection = "";
  let rejections = 0;

  for (let i = 0; i < TTS_ATTEMPT_PLAN.length; i++) {
    const step = TTS_ATTEMPT_PLAN[i];
    const attempt = i + 1;
    const model = step.fallback && fallbackModel ? fallbackModel : config.model;
    const remaining = deadline - Date.now();
    if (remaining < MIN_ATTEMPT_MS) break;

    let outcome: AttemptOutcome;
    try {
      outcome = await attemptOnce(
        config,
        model,
        buildTtsPrompt(req.name, style, step.variant),
        req.voiceName,
        Math.min(attemptTimeout, remaining),
        step.variant,
        attempt,
      );
    } catch (err) {
      // A 4xx from the fallback model (not enabled for this key, renamed) must not hide the
      // primary model's earlier, retryable failures; it just ends the plan.
      if (err instanceof TtsFailed && model !== config.model) {
        lastDetail = `fallback_${err.detail}`;
        console.warn("generate-ringtone: tts fallback model failed", { provider, attempt, model, detail: err.detail });
        break;
      }
      throw err;
    }

    if (outcome.kind === "ok") {
      if (outcome.result.estimatedDurationMs <= maxClipMs) return outcome.result;
      lastDetail = "clip_too_long";
      console.warn("generate-ringtone: tts clip too long", {
        provider,
        attempt,
        variant: step.variant,
        model,
        estimated_ms: outcome.result.estimatedDurationMs,
        max_ms: maxClipMs,
      });
      continue;
    }
    if (outcome.kind === "rejected") {
      rejections += 1;
      lastRejection = outcome.detail;
      console.warn("generate-ringtone: tts safety block", { provider, attempt, variant: step.variant, model, detail: outcome.detail });
      if (rejections >= REJECTIONS_TO_FAIL) throw new TtsRejected(outcome.detail);
      continue;
    }
    lastDetail = outcome.detail;
    console.warn("generate-ringtone: tts attempt failed", { provider, attempt, variant: step.variant, model, detail: outcome.detail });
  }

  if (rejections >= REJECTIONS_TO_FAIL) throw new TtsRejected(lastRejection);
  throw new TtsFailed(lastDetail, `${openRouter ? "OpenRouter" : "Gemini"} TTS failed (${lastDetail})`);
}
