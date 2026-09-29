// deno test --allow-read supabase/functions/tests
// The OpenRouter provider of generate-ringtone/tts.ts: same plan, prompts, voice and TtsResult as
// the Gemini provider, over OpenRouter's POST /api/v1/audio/speech.
import { assert, assertEquals, assertRejects, assertStringIncludes } from "jsr:@std/assert@1";
import {
  buildTtsPrompt,
  DEFAULT_OPENROUTER_TTS_FALLBACK_MODEL,
  DEFAULT_OPENROUTER_TTS_MODEL,
  DEFAULT_TTS_STYLE,
  OPENROUTER_SPEECH_URL,
  openRouterSpeechRequestBody,
  resolveFallbackModel,
  synthesizeName,
  type TtsConfig,
  TtsFailed,
  TtsRateLimited,
  TtsRejected,
  TtsUnavailable,
} from "../generate-ringtone/tts.ts";

type Call = { url: string; auth: string; body: Record<string, unknown> };

/** Fake fetch: one scripted response per call; records URL, auth header and JSON body. */
function scriptedFetch(responses: Array<Response | Error>, calls: Call[]): typeof fetch {
  return ((url: string | URL | Request, init?: RequestInit) => {
    const headers = new Headers(init?.headers);
    calls.push({
      url: String(url),
      auth: headers.get("authorization") ?? "",
      body: JSON.parse(String(init?.body ?? "{}")),
    });
    const next = responses.shift();
    if (!next) throw new Error("scriptedFetch: no response left");
    if (next instanceof Error) return Promise.reject(next);
    return Promise.resolve(next);
  }) as typeof fetch;
}

/** `seconds` of 16-bit mono PCM at `rate`, with a recognisable byte pattern. */
function pcmBytes(seconds: number, rate = 24_000): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(Math.round(seconds * rate) * 2);
  for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 7) & 0xff;
  return bytes;
}

function toBase64(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

function audio(bytes: Uint8Array<ArrayBuffer>, contentType = "audio/pcm;rate=24000;channels=1"): Response {
  return new Response(bytes, { status: 200, headers: { "content-type": contentType } });
}

function error(status: number, message = "error", headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify({ error: { code: status, message } }), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}

const empty = () => audio(new Uint8Array(0));

function config(fetchImpl: typeof fetch, overrides: Partial<TtsConfig> = {}): TtsConfig {
  return {
    provider: "openrouter",
    apiKey: "test-openrouter-key",
    model: DEFAULT_OPENROUTER_TTS_MODEL,
    fallbackModel: DEFAULT_OPENROUTER_TTS_FALLBACK_MODEL,
    attemptTimeoutMs: 5_000,
    totalBudgetMs: 20_000,
    fetchImpl,
    ...overrides,
  };
}

const request = { name: "Ayush", voiceName: "Sulafat", stylePrompt: null, category: "Devotional", language: "Hindi" };

Deno.test("openrouter: defaults are Gemini TTS models on OpenRouter", () => {
  assertEquals(DEFAULT_OPENROUTER_TTS_MODEL, "google/gemini-3.1-flash-tts-preview");
  assertEquals(DEFAULT_OPENROUTER_TTS_FALLBACK_MODEL, "google/gemini-3.8-flash-tts");
  assertEquals(resolveFallbackModel("", DEFAULT_OPENROUTER_TTS_FALLBACK_MODEL), DEFAULT_OPENROUTER_TTS_FALLBACK_MODEL);
  assertEquals(resolveFallbackModel(" none ", DEFAULT_OPENROUTER_TTS_FALLBACK_MODEL), "");
  assertEquals(resolveFallbackModel("google/gemini-3.8-flash-lite-tts", DEFAULT_OPENROUTER_TTS_FALLBACK_MODEL), "google/gemini-3.8-flash-lite-tts");
});

Deno.test("openrouter: request is the speech endpoint with the Gemini prompt, voice and pcm", async () => {
  const calls: Call[] = [];
  await synthesizeName(config(scriptedFetch([audio(pcmBytes(1.2))], calls)), request);
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, OPENROUTER_SPEECH_URL);
  assertEquals(calls[0].auth, "Bearer test-openrouter-key");
  assertEquals(calls[0].body, {
    model: DEFAULT_OPENROUTER_TTS_MODEL,
    input: buildTtsPrompt("Ayush", DEFAULT_TTS_STYLE, "transcript"),
    voice: "Sulafat",
    response_format: "pcm",
  });
  assertEquals(openRouterSpeechRequestBody("m", "p", "Kore"), { model: "m", input: "p", voice: "Kore", response_format: "pcm" });
});

Deno.test("openrouter: the same audio gives the same TtsResult as the Gemini provider", async () => {
  const bytes = pcmBytes(1.5);
  const viaOpenRouter = await synthesizeName(config(scriptedFetch([audio(bytes)], [])), request);

  const geminiFetch = (() =>
    Promise.resolve(
      new Response(
        JSON.stringify({
          candidates: [{
            content: { parts: [{ inlineData: { mimeType: "audio/L16;codec=pcm;rate=24000", data: toBase64(bytes) } }] },
            finishReason: "STOP",
          }],
        }),
        { status: 200 },
      ),
    )) as typeof fetch;
  const viaGemini = await synthesizeName(
    {
      provider: "gemini",
      apiKey: "g",
      model: "gemini-3.1-flash-tts-preview",
      endpoint: "generate_content",
      attemptTimeoutMs: 5_000,
      totalBudgetMs: 20_000,
      fetchImpl: geminiFetch,
    },
    request,
  );

  // Everything the mixer and the render row see is identical; only the model slug differs.
  assertEquals(viaOpenRouter.pcmBase64, viaGemini.pcmBase64);
  assertEquals(viaOpenRouter.mime, viaGemini.mime);
  assertEquals(viaOpenRouter.mime, "audio/L16;codec=pcm;rate=24000");
  assertEquals(viaOpenRouter.sampleRate, viaGemini.sampleRate);
  assertEquals(viaOpenRouter.estimatedDurationMs, viaGemini.estimatedDurationMs);
  assertEquals(viaOpenRouter.estimatedDurationMs, 1500);
  assertEquals(viaOpenRouter.promptVariant, viaGemini.promptVariant);
  assertEquals(viaOpenRouter.attempt, viaGemini.attempt);
  assertEquals(viaOpenRouter.model, DEFAULT_OPENROUTER_TTS_MODEL);
});

Deno.test("openrouter: the sample rate comes from the content type", async () => {
  const result = await synthesizeName(
    config(scriptedFetch([audio(pcmBytes(1, 48_000), "audio/pcm;rate=48000;channels=1")], [])),
    request,
  );
  assertEquals(result.sampleRate, 48_000);
  assertEquals(result.mime, "audio/L16;codec=pcm;rate=48000");
  assertEquals(result.estimatedDurationMs, 1000);
  // No rate in the header: Gemini's 24 kHz.
  const plain = await synthesizeName(config(scriptedFetch([audio(pcmBytes(1), "audio/pcm")], [])), request);
  assertEquals(plain.sampleRate, 24_000);
});

Deno.test("openrouter: an empty answer moves to the sentence prompt, like Gemini", async () => {
  const calls: Call[] = [];
  const result = await synthesizeName(config(scriptedFetch([empty(), audio(pcmBytes(1.1))], calls)), request);
  assertEquals(result.promptVariant, "sentence");
  assertEquals(result.attempt, 2);
  assertEquals(calls[1].body.input, "Read aloud, warmly, like lovingly calling someone, this one name: Ayush.");
});

Deno.test("openrouter: a non-audio or stereo 200 is retried", async () => {
  const calls: Call[] = [];
  const json200 = new Response(JSON.stringify({ ok: true }), { status: 200, headers: { "content-type": "application/json" } });
  const stereo = audio(pcmBytes(1), "audio/pcm;rate=24000;channels=2");
  const result = await synthesizeName(config(scriptedFetch([json200, stereo, audio(pcmBytes(1))], calls)), request);
  assertEquals(calls.length, 3);
  assertEquals(result.attempt, 3);
});

Deno.test("openrouter: two empty answers fall back to the fallback model", async () => {
  const calls: Call[] = [];
  const result = await synthesizeName(config(scriptedFetch([empty(), empty(), audio(pcmBytes(1))], calls)), request);
  assertEquals(result.model, DEFAULT_OPENROUTER_TTS_FALLBACK_MODEL);
  assertEquals(calls[2].body.model, DEFAULT_OPENROUTER_TTS_FALLBACK_MODEL);
  assertEquals(calls[2].body.input, buildTtsPrompt("Ayush", DEFAULT_TTS_STYLE, "transcript"));
});

Deno.test("openrouter: a clip longer than the cap is another attempt", async () => {
  const calls: Call[] = [];
  const result = await synthesizeName(
    config(scriptedFetch([audio(pcmBytes(7)), audio(pcmBytes(1.2))], calls), { maxClipMs: 6_000 }),
    request,
  );
  assertEquals(calls.length, 2);
  assertEquals(result.estimatedDurationMs, 1200);
});

Deno.test("openrouter: a trailing odd byte is dropped (16-bit frames)", async () => {
  const bytes = pcmBytes(1);
  const odd = new Uint8Array(bytes.length + 1);
  odd.set(bytes);
  const result = await synthesizeName(config(scriptedFetch([audio(odd)], [])), request);
  assertEquals(result.pcmBase64, toBase64(bytes));
});

Deno.test("openrouter: 429 is TtsRateLimited with Retry-After, not retried locally", async () => {
  const calls: Call[] = [];
  const err = await assertRejects(
    () => synthesizeName(config(scriptedFetch([error(429, "rate limited", { "retry-after": "12" })], calls)), request),
    TtsRateLimited,
  );
  assertEquals(err.retryAfterSeconds, 12);
  assertEquals(calls.length, 1);
});

Deno.test("openrouter: 503 and 529 (overloaded) are TtsRateLimited", async () => {
  for (const status of [503, 529]) {
    const err = await assertRejects(() => synthesizeName(config(scriptedFetch([error(status)], [])), request), TtsRateLimited);
    assertEquals(err.retryAfterSeconds, 10, String(status));
  }
});

Deno.test("openrouter: 402 (no credits), 401, 403 and a bad request fail at once", async () => {
  for (const status of [402, 401, 403, 400]) {
    const calls: Call[] = [];
    const err = await assertRejects(
      () => synthesizeName(config(scriptedFetch([error(status, "Insufficient credits")], calls)), request),
      TtsFailed,
    );
    assertEquals(err.detail, `openrouter_${status}`);
    assertEquals(calls.length, 1, String(status));
  }
});

Deno.test("openrouter: 502 / 524 are retried within the plan", async () => {
  const calls: Call[] = [];
  const result = await synthesizeName(config(scriptedFetch([error(502), error(524), audio(pcmBytes(1))], calls)), request);
  assertEquals(calls.length, 3);
  assertEquals(result.attempt, 3);
});

Deno.test("openrouter: two content refusals reject the name, like Gemini safety blocks", async () => {
  const refusal = () => error(400, "Request blocked by safety filters");
  await assertRejects(() => synthesizeName(config(scriptedFetch([refusal(), refusal()], [])), request), TtsRejected);
  // One refusal can be spurious: the next attempt still runs.
  const result = await synthesizeName(config(scriptedFetch([refusal(), audio(pcmBytes(1))], [])), request);
  assertEquals(result.attempt, 2);
});

Deno.test("openrouter: a 4xx from the fallback model ends the plan without hiding earlier failures", async () => {
  const calls: Call[] = [];
  const err = await assertRejects(
    () => synthesizeName(config(scriptedFetch([empty(), empty(), error(404, "No such model")], calls)), request),
    TtsFailed,
  );
  assertEquals(err.detail, "fallback_openrouter_404");
  assertEquals(calls.length, 3);
});

Deno.test("openrouter: missing key or model is TtsUnavailable; gemini_tts_endpoint does not apply", async () => {
  const noKey = await assertRejects(() => synthesizeName(config(scriptedFetch([], []), { apiKey: "" }), request), TtsUnavailable);
  assertStringIncludes(noKey.message, "OPENROUTER_API_KEY");
  const noModel = await assertRejects(() => synthesizeName(config(scriptedFetch([], []), { model: "" }), request), TtsUnavailable);
  assertStringIncludes(noModel.message, "openrouter_tts_model");
  // The Gemini-only endpoint setting is ignored by the OpenRouter provider.
  const result = await synthesizeName(config(scriptedFetch([audio(pcmBytes(1))], []), { endpoint: "interactions" }), request);
  assert(result.pcmBase64.length > 0);
});

Deno.test("openrouter: a network error or timeout moves on to the next attempt", async () => {
  const calls: Call[] = [];
  const result = await synthesizeName(
    config(scriptedFetch([new TypeError("network down"), audio(pcmBytes(1))], calls)),
    request,
  );
  assertEquals(calls.length, 2);
  assertEquals(result.attempt, 2);
});
