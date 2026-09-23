// deno test --allow-read supabase/functions/tests
import { assert, assertEquals, assertRejects } from "jsr:@std/assert@1";
import {
  ApiError,
  mixerFailure,
  safeDetail,
  stageErrorCode,
  TimeoutError,
  withTimeout,
} from "../generate-ringtone/errors.ts";

Deno.test("stageErrorCode: upstream stages are retryable 502s, the rest INTERNAL", () => {
  assertEquals(stageErrorCode("tts"), { status: 502, code: "TTS_FAILED" });
  assertEquals(stageErrorCode("bed"), { status: 502, code: "MIX_FAILED" });
  assertEquals(stageErrorCode("mix"), { status: 502, code: "MIX_FAILED" });
  assertEquals(stageErrorCode("upload"), { status: 502, code: "UPLOAD_FAILED" });
  for (const stage of ["parse", "config", "auth", "quota", "insert", "admission", "finalize"] as const) {
    assertEquals(stageErrorCode(stage), { status: 500, code: "INTERNAL" }, stage);
  }
});

Deno.test("mixerFailure: secret or IAM rejection is a 503 for ops, whatever the body", () => {
  for (const [status, code] of [[401, "UNAUTHORIZED"], [403, ""], [401, ""]] as const) {
    const failure = mixerFailure(status, code);
    assertEquals(failure?.status, 503);
    assertEquals(failure?.code, "SERVICE_UNAVAILABLE");
    assertEquals(failure?.logLevel, "error");
  }
});

Deno.test("mixerFailure: NAME_TOO_LONG is the non-retryable 422 the app shows", () => {
  const failure = mixerFailure(422, "NAME_TOO_LONG");
  assertEquals(failure?.status, 422);
  assertEquals(failure?.code, "NAME_TOO_LONG_FOR_SONG");
  assertEquals(failure?.logLevel, null);
});

Deno.test("mixerFailure: BAD_REQUEST means the song's authoring data is unusable", () => {
  const failure = mixerFailure(400, "BAD_REQUEST");
  assertEquals(failure?.status, 422);
  assertEquals(failure?.code, "TUNE_NOT_PERSONALIZABLE");
  assertEquals(failure?.logLevel, "error");
});

Deno.test("mixerFailure: an unusable Gemini clip is TTS_FAILED (retryable)", () => {
  for (const code of ["TTS_EMPTY", "TTS_TOO_LONG"]) {
    const failure = mixerFailure(400, code);
    assertEquals(failure?.status, 502, code);
    assertEquals(failure?.code, "TTS_FAILED", code);
  }
});

Deno.test("mixerFailure: transient or unknown failures fall through to MIX_FAILED", () => {
  assertEquals(mixerFailure(500, "FFMPEG_FAILED"), null);
  assertEquals(mixerFailure(500, "INTERNAL"), null);
  assertEquals(mixerFailure(502, "BED_DOWNLOAD_FAILED"), null);
  assertEquals(mixerFailure(504, ""), null);
  assertEquals(mixerFailure(413, "BAD_REQUEST"), null, "oversized payload is our bug, not the song's");
  assertEquals(mixerFailure(429, ""), null);
  assertEquals(mixerFailure(500, "NAME_TOO_LONG"), null, "NAME_TOO_LONG only counts with its 422");
});

Deno.test("ApiError carries server-only detail and log level", () => {
  const err = new ApiError(422, "TUNE_NOT_PERSONALIZABLE", "This song cannot be personalized yet", {
    detail: "mixer 400 BAD_REQUEST: slot beyond bed",
    logLevel: "error",
  });
  assertEquals(err.status, 422);
  assertEquals(err.message, "This song cannot be personalized yet");
  assertEquals(err.detail, "mixer 400 BAD_REQUEST: slot beyond bed");
  assertEquals(err.logLevel, "error");
});

Deno.test("safeDetail redacts every form of the name and caps the length", () => {
  const err = new Error("upstream said AYUSH and Ayush twice: Ayush");
  assertEquals(safeDetail(err, ["AYUSH", "Ayush"]), "Error: upstream said [name] and [name] twice: [name]");
  assertEquals(safeDetail("plain", [null, undefined, ""]), "plain");
  assertEquals(safeDetail(new Error("x".repeat(400))).length, 300);
});

Deno.test("withTimeout resolves fast work and rejects stalled work", async () => {
  assertEquals(await withTimeout(Promise.resolve(7), 50, "fast"), 7);
  const stalled = new Promise<never>(() => {});
  const err = await assertRejects(() => withTimeout(stalled, 10, "storage upload"), TimeoutError);
  assert(err.message.includes("storage upload timed out after 10 ms"), err.message);
});

Deno.test("withTimeout passes through the original rejection", async () => {
  await assertRejects(() => withTimeout(Promise.reject(new RangeError("boom")), 50, "x"), RangeError, "boom");
});
