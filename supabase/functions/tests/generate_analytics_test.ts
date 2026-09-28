// deno test --allow-read supabase/functions/tests
import { assert, assertEquals } from "jsr:@std/assert@1";
import { cleanProps, type MixpanelProps } from "../_shared/mixpanel.ts";
import type { ErrorCode } from "../generate-ringtone/errors.ts";
import {
  AUTO_REPOSTED_CODES,
  durationMinutes,
  failureReason,
  GENERATION_SOURCE,
  generationFailedProps,
  insertIdParts,
  isRetryableCode,
  MATCH_COUNT_CAP,
  NAME_LOOKUP_COMPLETED,
  nameLengthOf,
  nameLookupProps,
  reportsFailure,
  reportsLookup,
  RINGTONE_CREATED,
  RINGTONE_GENERATION_FAILED,
  ringtoneCreatedProps,
  runInBackground,
} from "../generate-ringtone/analytics.ts";
import { type PlanQuota, type QuotaSnapshot, quotaSnapshot } from "../generate-ringtone/quota.ts";

const DEFAULT_PLAN: PlanQuota = { plan: "default", period: "day", limit: 5 };
const MEMBER_PLAN: PlanQuota = { plan: "member", period: "month", limit: 50 };
function quota(used: number, plan: PlanQuota = DEFAULT_PLAN): QuotaSnapshot {
  return quotaSnapshot(plan, used, new Date("2026-09-28T10:00:00.000Z"), 50);
}

const TUNE_ID = "7f1c2a9e-0000-4000-8000-000000000001";
const GENERATION_ID = "0b6f0e4e-0000-4000-8000-000000000002";
const CLIENT_REQUEST_ID = "5d1f9a3c-0000-4000-8000-000000000003";
const TUNE = { tuneId: TUNE_ID, categoryName: "Bhakti", gender: "Female" };
const SHARED = {
  language: "Hindi",
  generationId: GENERATION_ID,
  clientRequestId: CLIENT_REQUEST_ID,
  appVersion: "1.3.0",
};

/** Every server error code, read from the ErrorCode union in errors.ts. */
async function serverErrorCodes(): Promise<ErrorCode[]> {
  const source = await Deno.readTextFile(new URL("../generate-ringtone/errors.ts", import.meta.url));
  const union = source.match(/export type ErrorCode =([\s\S]*?);/);
  assert(union, "ErrorCode union not found in errors.ts");
  return [...union[1].matchAll(/"([A-Z_]+)"/g)].map((match) => match[1] as ErrorCode);
}

/** `NAME(retryable)` entries of the app's GenerationErrorCode enum. */
async function appRetryableTable(): Promise<Map<string, boolean>> {
  const source = await Deno.readTextFile(
    new URL(
      "../../../app/src/main/java/com/spacewire/meratune/data/RingtoneGenerationRepository.kt",
      import.meta.url,
    ),
  );
  const body = source.match(/enum class GenerationErrorCode\(val retryable: Boolean\) \{([\s\S]*?);/);
  assert(body, "GenerationErrorCode enum not found");
  return new Map([...body[1].matchAll(/([A-Z_]+)\((true|false)\)/g)].map((m) => [m[1], m[2] === "true"]));
}

Deno.test("retryable matches the app's GenerationErrorCode for every server code", async () => {
  const codes = await serverErrorCodes();
  const app = await appRetryableTable();
  assert(codes.length >= 18, `only ${codes.length} codes parsed`);
  for (const code of codes) {
    assert(app.has(code), `${code} is missing from GenerationErrorCode`);
    assertEquals(isRetryableCode(code), app.get(code), code);
  }
});

Deno.test("failure_reason is the lower-cased error code (the app's analyticsValue)", () => {
  assertEquals(failureReason("QUOTA_EXCEEDED"), "quota_exceeded");
  assertEquals(failureReason("NAME_TOO_LONG_FOR_SONG"), "name_too_long_for_song");
});

Deno.test("reportsFailure: every terminal code except UNAUTHORIZED and the auto re-posted busy codes", async () => {
  assertEquals([...AUTO_REPOSTED_CODES].sort(), ["GENERATION_IN_PROGRESS", "TTS_RATE_LIMITED"]);
  for (const code of await serverErrorCodes()) {
    const expected = code !== "UNAUTHORIZED" && code !== "GENERATION_IN_PROGRESS" && code !== "TTS_RATE_LIMITED";
    assertEquals(reportsFailure(code), expected, code);
  }
  // Not auto re-posted (the app only offers a Retry button), so the server reports it.
  assert(reportsFailure("SERVICE_BUSY"));
});

Deno.test("reportsLookup: on success and every outcome the app does not re-post", () => {
  assert(reportsLookup(null));
  assert(reportsLookup("QUOTA_EXCEEDED"));
  assert(reportsLookup("TTS_FAILED"));
  assert(!reportsLookup("GENERATION_IN_PROGRESS"));
  assert(!reportsLookup("TTS_RATE_LIMITED"));
});

Deno.test("durationMinutes: generation time in minutes with one decimal", () => {
  assertEquals(durationMinutes(0), 0);
  assertEquals(durationMinutes(1_200), 0);
  assertEquals(durationMinutes(3_000), 0.1);
  assertEquals(durationMinutes(45_000), 0.8);
  assertEquals(durationMinutes(90_000), 1.5);
  assertEquals(durationMinutes(-5), 0);
});

Deno.test("nameLengthOf counts code points", () => {
  assertEquals(nameLengthOf("Priya"), 5);
  assertEquals(nameLengthOf("प्रिया"), 6);
  assertEquals(nameLengthOf("𝓐na"), 3);
});

Deno.test("ringtoneCreatedProps: spec props, voice as male/female, source creation_flow", () => {
  assertEquals(
    ringtoneCreatedProps({
      ...SHARED,
      tune: TUNE,
      cached: false,
      audioDurationMs: 28_500,
      latencyMs: 21_400,
      quota: quota(2),
    }),
    {
      tune_id: TUNE_ID,
      sample_id: TUNE_ID,
      category: "Bhakti",
      voice: "female",
      language: "Hindi",
      generation_id: GENERATION_ID,
      client_request_id: CLIENT_REQUEST_ID,
      app_version: "1.3.0",
      cached: false,
      duration_ms: 28_500,
      latency_ms: 21_400,
      duration_minutes: 0.4,
      quota_used_today: 2,
      quota_daily_limit: 5,
      quota_plan: "default",
      quota_period: "day",
      source: GENERATION_SOURCE,
    },
  );
});

Deno.test("ringtoneCreatedProps: a member's quota props carry the month's count and limit", () => {
  const props = ringtoneCreatedProps({
    ...SHARED,
    tune: TUNE,
    cached: false,
    audioDurationMs: 28_500,
    latencyMs: 21_400,
    quota: quota(17, MEMBER_PLAN),
  });
  assertEquals(
    [props.quota_used_today, props.quota_daily_limit, props.quota_plan, props.quota_period],
    [17, 50, "member", "month"],
  );
});

Deno.test("ringtoneCreatedProps: unknown audio length, voice and ids are omitted after cleanProps", () => {
  const cleaned = cleanProps(ringtoneCreatedProps({
    language: "English",
    generationId: GENERATION_ID,
    clientRequestId: null,
    appVersion: null,
    tune: { tuneId: TUNE_ID, categoryName: "", gender: "unknown" },
    cached: true,
    audioDurationMs: null,
    latencyMs: 310,
    quota: quota(0),
  }));
  for (const key of ["duration_ms", "voice", "category", "client_request_id", "app_version"]) {
    assert(!(key in cleaned), `${key} should be omitted`);
  }
  assertEquals(cleaned.cached, true);
  assertEquals(cleaned.quota_used_today, 0);
  assertEquals(cleaned.duration_minutes, 0);
});

Deno.test("generationFailedProps: code, retryable and quota for a quota rejection", () => {
  assertEquals(
    generationFailedProps({
      ...SHARED,
      tune: TUNE,
      code: "QUOTA_EXCEEDED",
      httpStatus: 429,
      latencyMs: 640,
      quota: quotaSnapshot(MEMBER_PLAN, 50, new Date("2026-09-28T10:00:00.000Z"), 50, "plan"),
    }),
    {
      tune_id: TUNE_ID,
      sample_id: TUNE_ID,
      category: "Bhakti",
      voice: "female",
      language: "Hindi",
      generation_id: GENERATION_ID,
      client_request_id: CLIENT_REQUEST_ID,
      app_version: "1.3.0",
      failure_reason: "quota_exceeded",
      retryable: false,
      http_status: 429,
      latency_ms: 640,
      quota_used_today: 50,
      quota_daily_limit: 50,
      quota_plan: "member",
      quota_period: "month",
    },
  );
});

Deno.test("generationFailedProps: before the tune loads only the requested id is known", () => {
  const cleaned = cleanProps(generationFailedProps({
    language: "Hindi",
    generationId: null,
    clientRequestId: CLIENT_REQUEST_ID,
    appVersion: "1.3.0",
    tune: { tuneId: TUNE_ID },
    code: "TTS_FAILED",
    httpStatus: 502,
    latencyMs: 9_000,
  }));
  assertEquals(cleaned.tune_id, TUNE_ID);
  assertEquals(cleaned.sample_id, TUNE_ID);
  assertEquals(cleaned.retryable, true);
  for (const key of ["category", "voice", "generation_id", "quota_used_today", "quota_daily_limit", "quota_plan", "quota_period"]) {
    assert(!(key in cleaned), `${key} should be omitted`);
  }
  assert(!("tune_id" in cleanProps(generationFailedProps({ ...SHARED, tune: null, code: "INVALID_NAME", httpStatus: 400, latencyMs: 5 }))));
});

Deno.test("nameLookupProps: has_match covers the sample's own recording and other samples", () => {
  const base = {
    sampleId: TUNE_ID,
    language: "Hindi",
    nameLength: 5,
    generationId: GENERATION_ID,
    clientRequestId: CLIENT_REQUEST_ID,
    appVersion: "1.3.0",
  };
  assertEquals(nameLookupProps({ ...base, exactMatch: false, matchCount: 3 }), {
    has_match: true,
    match_count: 3,
    exact_match: false,
    name_length: 5,
    sample_id: TUNE_ID,
    language: "Hindi",
    generation_id: GENERATION_ID,
    client_request_id: CLIENT_REQUEST_ID,
    app_version: "1.3.0",
  });
  const none = nameLookupProps({ ...base, exactMatch: false, matchCount: 0 });
  assertEquals([none.has_match, none.match_count], [false, 0]);
  // Sample-name hit: the stock recording sings it, no ringtone_renders row needed.
  const sample = nameLookupProps({ ...base, exactMatch: true, matchCount: 0 });
  assertEquals([sample.has_match, sample.exact_match], [true, true]);
  assertEquals(nameLookupProps({ ...base, exactMatch: false, matchCount: 5_000 }).match_count, MATCH_COUNT_CAP);
});

Deno.test("nameLookupProps: a failed count omits match_count and has_match unless exact", () => {
  const base = { sampleId: TUNE_ID, language: "Hindi", nameLength: 4, generationId: null, clientRequestId: null, appVersion: null };
  const unknown = cleanProps(nameLookupProps({ ...base, exactMatch: false, matchCount: null }));
  assert(!("match_count" in unknown));
  assert(!("has_match" in unknown));
  assertEquals(unknown.exact_match, false);
  assertEquals(cleanProps(nameLookupProps({ ...base, exactMatch: true, matchCount: null })).has_match, true);
});

Deno.test("every builder key survives cleanProps and stays inside the allowlist", () => {
  const allowed = new Set([
    "tune_id", "sample_id", "category", "voice", "language", "generation_id", "client_request_id",
    "app_version", "cached", "duration_ms", "latency_ms", "duration_minutes", "quota_used_today",
    "quota_daily_limit", "quota_plan", "quota_period", "source", "failure_reason", "retryable", "http_status",
    "has_match", "match_count", "exact_match", "name_length",
  ]);
  const all: MixpanelProps[] = [
    ringtoneCreatedProps({ ...SHARED, tune: TUNE, cached: false, audioDurationMs: 1, latencyMs: 1, quota: quota(1) }),
    generationFailedProps({ ...SHARED, tune: TUNE, code: "QUOTA_EXCEEDED", httpStatus: 429, latencyMs: 1, quota: quota(5) }),
    nameLookupProps({ ...SHARED, sampleId: TUNE_ID, nameLength: 5, exactMatch: false, matchCount: 1 }),
  ];
  for (const props of all) {
    assertEquals(Object.keys(cleanProps(props)).sort(), Object.keys(props).sort());
    for (const key of Object.keys(props)) assert(allowed.has(key), `unexpected key ${key}`);
  }
});

Deno.test("insertIdParts: one key per generation row, else per request", () => {
  const ids = { generationId: GENERATION_ID, clientRequestId: CLIENT_REQUEST_ID, distinctId: "42", requestStartedAtMs: 1 };
  assertEquals(insertIdParts(RINGTONE_CREATED, ids), [RINGTONE_CREATED, GENERATION_ID]);
  assertEquals(insertIdParts(NAME_LOOKUP_COMPLETED, ids), [NAME_LOOKUP_COMPLETED, GENERATION_ID]);
  // No row (e.g. SERVICE_BUSY before insert): a user retry reuses the client_request_id.
  const noRow = { ...ids, generationId: null };
  assertEquals(insertIdParts(RINGTONE_GENERATION_FAILED, noRow), [RINGTONE_GENERATION_FAILED, CLIENT_REQUEST_ID, "42", 1]);
  assertEquals(insertIdParts(RINGTONE_GENERATION_FAILED, { ...noRow, requestStartedAtMs: 2 })[3], 2);
});

Deno.test("runInBackground: hands the task to waitUntil and swallows task errors", async () => {
  const kept: Promise<unknown>[] = [];
  let ran = false;
  const done = runInBackground(async () => {
    await Promise.resolve();
    ran = true;
  }, { waitUntil: (promise) => kept.push(promise) });
  assertEquals(kept.length, 1);
  await done;
  assert(ran);

  const originalError = console.error;
  const logged: unknown[][] = [];
  console.error = (...args: unknown[]) => logged.push(args);
  try {
    await runInBackground(() => Promise.reject(new TypeError("boom")), { waitUntil: (p) => p });
    await runInBackground(() => {
      throw new Error("sync");
    }, undefined);
    // A throwing waitUntil does not stop the task.
    let alsoRan = false;
    await runInBackground(() => {
      alsoRan = true;
      return Promise.resolve();
    }, {
      waitUntil: () => {
        throw new Error("no keep-alive");
      },
    });
    assert(alsoRan);
  } finally {
    console.error = originalError;
  }
  assertEquals(logged.map((args) => args[1]), ["TypeError", "Error"]);
});
