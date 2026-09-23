// deno test --allow-read supabase/functions/tests
import { assert, assertEquals, assertRejects, assertStringIncludes } from "jsr:@std/assert@1";
import {
  buildStylePrompt,
  DEFAULT_TTS_FALLBACK_MODEL,
  DEFAULT_TTS_STYLE,
  resolveFallbackModel,
  TTS_ATTEMPT_PLAN,
  buildTtsPrompt,
  concatBase64,
  estimateDurationMs,
  generateContentRequestBody,
  parseRetryAfterSeconds,
  parseSampleRate,
  resolveTtsEndpoint,
  synthesizeName,
  type TtsConfig,
  TtsFailed,
  TtsRateLimited,
  TtsRejected,
  TtsUnavailable,
} from "../generate-ringtone/tts.ts";

/** Base64 length for `seconds` of 16-bit mono PCM at `rate` (multiple of 4). */
function base64LengthForSeconds(seconds: number, rate = 24_000): number {
  const bytes = seconds * rate * 2;
  return Math.ceil(bytes / 3) * 4;
}

function audioResponse(base64Length: number, mime = "audio/L16;codec=pcm;rate=24000"): Response {
  return new Response(
    JSON.stringify({
      candidates: [{
        content: { parts: [{ inlineData: { mimeType: mime, data: "A".repeat(base64Length) } }] },
        finishReason: "STOP",
      }],
    }),
    { status: 200, headers: { "content-type": "application/json" } },
  );
}

type Call = { url: string; prompt: string; voice: string };

/** Fake fetch that pops one scripted response per call and records the prompt it received. */
function scriptedFetch(responses: Array<Response | Error>, calls: Call[]): typeof fetch {
  return ((url: string | URL | Request, init?: RequestInit) => {
    const body = JSON.parse(String(init?.body ?? "{}"));
    calls.push({
      url: String(url),
      prompt: body.contents?.[0]?.parts?.[0]?.text ?? "",
      voice: body.generationConfig?.speechConfig?.voiceConfig?.prebuiltVoiceConfig?.voiceName ?? "",
    });
    const next = responses.shift();
    if (!next) throw new Error("scriptedFetch: no response left");
    if (next instanceof Error) return Promise.reject(next);
    return Promise.resolve(next);
  }) as typeof fetch;
}

function config(fetchImpl: typeof fetch, overrides: Partial<TtsConfig> = {}): TtsConfig {
  return {
    apiKey: "test-key",
    model: "gemini-2.5-flash-preview-tts",
    endpoint: "generate_content",
    attemptTimeoutMs: 5_000,
    totalBudgetMs: 20_000,
    fetchImpl,
    ...overrides,
  };
}

const request = { name: "Ayush", voiceName: "Sulafat", stylePrompt: null, category: "Devotional", language: "Hindi" };
const FALLBACK = "gemini-3.1-flash-tts-preview";
const empty = () =>
  new Response(JSON.stringify({ candidates: [{ finishReason: "OTHER", index: 0 }] }), { status: 200 });
const safety = () => new Response(JSON.stringify({ promptFeedback: { blockReason: "SAFETY" } }), { status: 200 });

Deno.test("parseSampleRate reads rate= from the Gemini mime and falls back sanely", () => {
  assertEquals(parseSampleRate("audio/L16;codec=pcm;rate=24000"), 24_000);
  assertEquals(parseSampleRate("audio/L16;codec=pcm;rate=48000"), 48_000);
  assertEquals(parseSampleRate("audio/pcm"), 24_000);
  assertEquals(parseSampleRate("audio/L16;rate=999"), 24_000);
  assertEquals(parseSampleRate(null), 24_000);
});

Deno.test("estimateDurationMs: one second of 24 kHz 16-bit mono", () => {
  assertEquals(estimateDurationMs(base64LengthForSeconds(1), 24_000), 1000);
  assertEquals(estimateDurationMs(base64LengthForSeconds(2.5), 24_000), 2500);
  assertEquals(estimateDurationMs(0, 24_000), 0);
  assertEquals(estimateDurationMs(100, 0), 0);
});

Deno.test("prompts: transcript and sentence shapes with the default or authored style", () => {
  assertEquals(buildStylePrompt({ stylePrompt: null }), DEFAULT_TTS_STYLE);
  assertEquals(buildStylePrompt({ stylePrompt: "  " }), DEFAULT_TTS_STYLE);
  assertEquals(buildStylePrompt({ stylePrompt: " softly, like a lullaby " }), "softly, like a lullaby");
  assertEquals(
    buildTtsPrompt("Ayush", DEFAULT_TTS_STYLE, "transcript"),
    "Speak the following name warmly, like lovingly calling someone. Speak only the name.\n\nAyush",
  );
  assertEquals(
    buildTtsPrompt("Ayush", DEFAULT_TTS_STYLE, "sentence"),
    "Read aloud, warmly, like lovingly calling someone, this one name: Ayush.",
  );
  assertEquals(buildTtsPrompt("J.K.", "softly", "sentence"), "Read aloud, softly, this one name: J.K.", "no double stop");
  assertEquals(buildTtsPrompt("आयुष", "", "transcript"), `Speak the following name ${DEFAULT_TTS_STYLE}. Speak only the name.\n\nआयुष`);
});

Deno.test("attempt plan: transcript, sentence, then transcript on the fallback model", () => {
  assertEquals(TTS_ATTEMPT_PLAN.map((s) => `${s.variant}${s.fallback ? "+fallback" : ""}`), [
    "transcript",
    "sentence",
    "transcript+fallback",
  ]);
});

Deno.test("resolveFallbackModel: blank -> default, none/off -> disabled, else as given", () => {
  assertEquals(resolveFallbackModel(undefined), DEFAULT_TTS_FALLBACK_MODEL);
  assertEquals(resolveFallbackModel(" "), DEFAULT_TTS_FALLBACK_MODEL);
  assertEquals(resolveFallbackModel("none"), "");
  assertEquals(resolveFallbackModel("OFF"), "");
  assertEquals(resolveFallbackModel("disabled"), "");
  assertEquals(resolveFallbackModel(" gemini-2.5-pro-preview-tts "), "gemini-2.5-pro-preview-tts");
});

Deno.test("generateContentRequestBody has the AUDIO modality and prebuilt voice", () => {
  const body = generateContentRequestBody("Say: Ayush", "Sulafat") as Record<string, unknown>;
  assertEquals(body.contents, [{ role: "user", parts: [{ text: "Say: Ayush" }] }]);
  assertEquals(body.generationConfig, {
    responseModalities: ["AUDIO"],
    speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: "Sulafat" } } },
  });
});

Deno.test("parseRetryAfterSeconds: header, RetryInfo, fallback, cap", () => {
  assertEquals(parseRetryAfterSeconds(new Headers({ "retry-after": "30" }), null, 20), 30);
  assertEquals(parseRetryAfterSeconds(new Headers({ "retry-after": "9999" }), null, 20), 300);
  const retryInfo = { error: { details: [{ "@type": "type.googleapis.com/google.rpc.RetryInfo", retryDelay: "12s" }] } };
  assertEquals(parseRetryAfterSeconds(new Headers(), retryInfo, 20), 12);
  assertEquals(parseRetryAfterSeconds(new Headers(), { error: {} }, 20), 20);
  assertEquals(parseRetryAfterSeconds(new Headers(), null, 7), 7);
});

Deno.test("concatBase64 joins parts into one valid base64 string", () => {
  assertEquals(concatBase64([btoa("ab"), btoa("cd")]), btoa("abcd"));
  assertEquals(concatBase64([btoa("hello")]), btoa("hello"));
  assertEquals(atob(concatBase64([btoa("a"), btoa("bc"), btoa("def")])), "abcdef");
});

Deno.test("synthesizeName: happy path is one transcript call on the primary model", async () => {
  const calls: Call[] = [];
  const fetchImpl = scriptedFetch([audioResponse(base64LengthForSeconds(1.5))], calls);
  const result = await synthesizeName(config(fetchImpl, { fallbackModel: FALLBACK }), request);
  assertEquals(result.sampleRate, 24_000);
  assertEquals(result.mime, "audio/L16;codec=pcm;rate=24000");
  assertEquals(result.estimatedDurationMs, 1500);
  assertEquals(result.promptVariant, "transcript");
  assertEquals(result.attempt, 1);
  assertEquals(result.model, "gemini-2.5-flash-preview-tts");
  assertEquals(calls.length, 1);
  assertStringIncludes(calls[0].url, "/models/gemini-2.5-flash-preview-tts:generateContent");
  assertEquals(calls[0].voice, "Sulafat");
  assert(calls[0].prompt.endsWith("\n\nAyush"), calls[0].prompt);
});

Deno.test("synthesizeName: empty answer (finishReason OTHER) moves to the sentence prompt", async () => {
  const calls: Call[] = [];
  const fetchImpl = scriptedFetch([empty(), audioResponse(base64LengthForSeconds(1.1))], calls);
  const result = await synthesizeName(config(fetchImpl, { fallbackModel: FALLBACK }), request);
  assertEquals(result.promptVariant, "sentence");
  assertEquals(result.attempt, 2);
  assertEquals(calls[1].prompt, "Read aloud, warmly, like lovingly calling someone, this one name: Ayush.");
});

Deno.test("synthesizeName: two empty answers fall back to the fallback model", async () => {
  const calls: Call[] = [];
  const fetchImpl = scriptedFetch([empty(), empty(), audioResponse(base64LengthForSeconds(1.4))], calls);
  const result = await synthesizeName(config(fetchImpl, { fallbackModel: FALLBACK }), request);
  assertEquals(result.attempt, 3);
  assertEquals(result.model, FALLBACK);
  assertEquals(result.promptVariant, "transcript");
  assertStringIncludes(calls[2].url, `/models/${FALLBACK}:generateContent`);
});

Deno.test("synthesizeName: without a fallback model the third attempt stays on the primary", async () => {
  const calls: Call[] = [];
  const fetchImpl = scriptedFetch([empty(), empty(), audioResponse(base64LengthForSeconds(1))], calls);
  const result = await synthesizeName(config(fetchImpl, { fallbackModel: "" }), request);
  assertEquals(result.model, "gemini-2.5-flash-preview-tts");
  assertStringIncludes(calls[2].url, "/models/gemini-2.5-flash-preview-tts:generateContent");
});

Deno.test("synthesizeName: nothing usable after the plan fails with the last detail", async () => {
  const calls: Call[] = [];
  const fetchImpl = scriptedFetch([empty(), empty(), empty()], calls);
  const err = await assertRejects(() => synthesizeName(config(fetchImpl, { fallbackModel: FALLBACK }), request), TtsFailed);
  assertEquals(err.detail, "no_audio_OTHER");
  assertEquals(calls.length, 3);
});

Deno.test("synthesizeName: 429 surfaces TtsRateLimited with the RetryInfo delay", async () => {
  const calls: Call[] = [];
  const body = { error: { code: 429, status: "RESOURCE_EXHAUSTED", details: [{ retryDelay: "17s" }] } };
  const fetchImpl = scriptedFetch([new Response(JSON.stringify(body), { status: 429 })], calls);
  const err = await assertRejects(() => synthesizeName(config(fetchImpl), request), TtsRateLimited);
  assertEquals(err.retryAfterSeconds, 17);
  assertEquals(calls.length, 1, "rate limits are not retried locally");
});

Deno.test("synthesizeName: 503 surfaces TtsRateLimited with the default delay", async () => {
  const fetchImpl = scriptedFetch([new Response("", { status: 503 })], []);
  const err = await assertRejects(() => synthesizeName(config(fetchImpl), request), TtsRateLimited);
  assertEquals(err.retryAfterSeconds, 10);
});

Deno.test("synthesizeName: a single safety block is retried (it can be spurious)", async () => {
  const calls: Call[] = [];
  const fetchImpl = scriptedFetch([safety(), audioResponse(base64LengthForSeconds(1))], calls);
  const result = await synthesizeName(config(fetchImpl), request);
  assertEquals(result.attempt, 2);
  assertEquals(calls.length, 2);
});

Deno.test("synthesizeName: two safety blocks become TtsRejected", async () => {
  const calls: Call[] = [];
  const fetchImpl = scriptedFetch([safety(), safety()], calls);
  const err = await assertRejects(() => synthesizeName(config(fetchImpl), request), TtsRejected);
  assertEquals(err.detail, "blocked_SAFETY");
  assertEquals(calls.length, 2);
});

Deno.test("synthesizeName: a 500 is retried, then succeeds", async () => {
  const calls: Call[] = [];
  const fetchImpl = scriptedFetch([new Response("boom", { status: 500 }), audioResponse(base64LengthForSeconds(1))], calls);
  const result = await synthesizeName(config(fetchImpl), request);
  assertEquals(result.estimatedDurationMs, 1000);
  assertEquals(calls.length, 2);
});

Deno.test("synthesizeName: network errors walk the plan, then fail", async () => {
  const calls: Call[] = [];
  const reset = () => new TypeError("connection reset");
  const fetchImpl = scriptedFetch([reset(), reset(), reset()], calls);
  const err = await assertRejects(() => synthesizeName(config(fetchImpl), request), TtsFailed);
  assertEquals(err.detail, "network");
  assertEquals(calls.length, 3);
});

Deno.test("synthesizeName: 400/401/403 on the primary model are not retried", async () => {
  const calls: Call[] = [];
  const body = { error: { code: 400, status: "INVALID_ARGUMENT", message: "API key not valid" } };
  const fetchImpl = scriptedFetch([new Response(JSON.stringify(body), { status: 400 })], calls);
  const err = await assertRejects(() => synthesizeName(config(fetchImpl, { fallbackModel: FALLBACK }), request), TtsFailed);
  assertEquals(err.detail, "gemini_400_INVALID_ARGUMENT");
  assertEquals(calls.length, 1);
});

Deno.test("synthesizeName: a 4xx from the fallback model ends the plan as TtsFailed", async () => {
  const calls: Call[] = [];
  const notFound = new Response(JSON.stringify({ error: { code: 404, status: "NOT_FOUND" } }), { status: 404 });
  const fetchImpl = scriptedFetch([empty(), empty(), notFound], calls);
  const err = await assertRejects(() => synthesizeName(config(fetchImpl, { fallbackModel: FALLBACK }), request), TtsFailed);
  assertEquals(err.detail, "fallback_gemini_404_NOT_FOUND");
  assertEquals(calls.length, 3);
});

Deno.test("synthesizeName: an over-long clip (instructions read aloud) moves to the next prompt", async () => {
  const calls: Call[] = [];
  const fetchImpl = scriptedFetch([audioResponse(base64LengthForSeconds(14)), audioResponse(base64LengthForSeconds(1.2))], calls);
  const result = await synthesizeName(config(fetchImpl), request);
  assertEquals(result.promptVariant, "sentence");
  assertEquals(result.estimatedDurationMs, 1200);
  assertEquals(calls.length, 2);
});

Deno.test("synthesizeName: too long on every attempt fails with clip_too_long", async () => {
  const long = () => audioResponse(base64LengthForSeconds(7));
  const err = await assertRejects(() => synthesizeName(config(scriptedFetch([long(), long(), long()], [])), request), TtsFailed);
  assertEquals(err.detail, "clip_too_long");
});

Deno.test("synthesizeName: interactions endpoint is stubbed behind the flag", async () => {
  const calls: Call[] = [];
  const fetchImpl = scriptedFetch([], calls);
  const err = await assertRejects(
    () => synthesizeName(config(fetchImpl, { endpoint: "interactions" }), request),
    TtsUnavailable,
  );
  assertStringIncludes(err.message, "not implemented");
  assertEquals(calls.length, 0);
});

Deno.test("resolveTtsEndpoint accepts only the implemented generate_content endpoint", () => {
  assertEquals(resolveTtsEndpoint("generate_content"), "generate_content");
  assertEquals(resolveTtsEndpoint(" Generate_Content "), "generate_content");
  assertEquals(resolveTtsEndpoint(""), "generate_content");
  assertEquals(resolveTtsEndpoint(undefined), "generate_content");
  assertEquals(resolveTtsEndpoint(null), "generate_content");
  assertEquals(resolveTtsEndpoint("interactions"), null, "stub is rejected at config load");
  assertEquals(resolveTtsEndpoint("generateContent"), null);
  assertEquals(resolveTtsEndpoint("anything"), null);
});

Deno.test("synthesizeName: missing key or model is a configuration error", async () => {
  const fetchImpl = scriptedFetch([], []);
  await assertRejects(() => synthesizeName(config(fetchImpl, { apiKey: "" }), request), TtsUnavailable);
  await assertRejects(() => synthesizeName(config(fetchImpl, { model: "" }), request), TtsUnavailable);
  await assertRejects(() => synthesizeName(config(fetchImpl), { ...request, voiceName: "" }), TtsUnavailable);
  assert(true);
});
