// deno test --allow-read supabase/functions/tests
import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  ATTEMPT_FAILURE_CODES,
  attemptFilter,
  attemptLimitFor,
  BILLED_FAILURE_CODES,
  dailyLimitFor,
  DEFAULT_DAILY_ATTEMPT_LIMIT,
  freshRenderFilter,
  globalDailyLimit,
  globalRenderFilter,
  INFLIGHT_RENDER_MS,
  isFlagEnabled,
  isInflightRender,
  isStaleProcessing,
  istDateString,
  istDayStart,
  istNextMidnight,
  parseBoundedFloat,
  parsePositiveInt,
  quotaExceeded,
  quotaWindow,
  retryableRequestFilter,
  secondsUntilIstMidnight,
  STALE_PROCESSING_MS,
  withinLimit,
} from "../generate-ringtone/quota.ts";

Deno.test("istDayStart: afternoon IST maps to 18:30 UTC of the previous UTC day", () => {
  // 2026-09-23 10:00 UTC = 15:30 IST on the 23rd -> day start 00:00 IST 23rd = 2026-09-22T18:30Z
  const now = new Date("2026-09-23T10:00:00.000Z");
  assertEquals(istDayStart(now).toISOString(), "2026-09-22T18:30:00.000Z");
  assertEquals(istDateString(now), "2026-09-23");
});

Deno.test("istDayStart: late UTC evening is already the next IST day", () => {
  // 2026-09-23 20:00 UTC = 01:30 IST on the 24th -> day start 2026-09-23T18:30Z
  const now = new Date("2026-09-23T20:00:00.000Z");
  assertEquals(istDayStart(now).toISOString(), "2026-09-23T18:30:00.000Z");
  assertEquals(istDateString(now), "2026-09-24");
  assertEquals(istNextMidnight(now).toISOString(), "2026-09-24T18:30:00.000Z");
});

Deno.test("istDayStart: exactly midnight IST belongs to the new day", () => {
  const now = new Date("2026-09-23T18:30:00.000Z");
  assertEquals(istDayStart(now).toISOString(), "2026-09-23T18:30:00.000Z");
  assertEquals(secondsUntilIstMidnight(now), 24 * 60 * 60);
});

Deno.test("secondsUntilIstMidnight rounds up and is never below 1", () => {
  const justBefore = new Date("2026-09-23T18:29:59.400Z");
  assertEquals(secondsUntilIstMidnight(justBefore), 1);
  const oneHourBefore = new Date("2026-09-23T17:30:00.000Z");
  assertEquals(secondsUntilIstMidnight(oneHourBefore), 3600);
});

Deno.test("quotaWindow derives all three cut-offs from now", () => {
  const now = new Date("2026-09-23T10:00:00.000Z");
  const window = quotaWindow(now);
  assertEquals(window.dayStartIso, "2026-09-22T18:30:00.000Z");
  assertEquals(window.processingSinceIso, new Date(now.getTime() - STALE_PROCESSING_MS).toISOString());
  assertEquals(window.inflightSinceIso, new Date(now.getTime() - INFLIGHT_RENDER_MS).toISOString());
});

Deno.test("config parsing falls back on garbage", () => {
  assertEquals(parsePositiveInt("5", 1), 5);
  assertEquals(parsePositiveInt(" 12 ", 1), 12);
  assertEquals(parsePositiveInt("0", 7), 7);
  assertEquals(parsePositiveInt("-3", 7), 7);
  assertEquals(parsePositiveInt("abc", 7), 7);
  assertEquals(parsePositiveInt(undefined, 7), 7);
  assertEquals(parseBoundedFloat("1.3", 1, 1, 2), 1.3);
  assertEquals(parseBoundedFloat("9", 1, 1, 2), 2);
  assertEquals(parseBoundedFloat("nan", 1.5, 1, 2), 1.5);
  assert(isFlagEnabled({ generate_enabled: "true" }, "generate_enabled", false));
  assert(!isFlagEnabled({ generate_enabled: "false" }, "generate_enabled", true));
  assert(isFlagEnabled({}, "generate_enabled", true));
  assert(isFlagEnabled({ generate_enabled: "" }, "generate_enabled", true));
});

Deno.test("daily limits depend on auth mode and default when unset", () => {
  const config = { generate_daily_limit: "5", generate_legacy_daily_limit: "3", generate_global_daily_limit: "2000" };
  assertEquals(dailyLimitFor(config, "token"), 5);
  assertEquals(dailyLimitFor(config, "legacy_user_id"), 3);
  assertEquals(globalDailyLimit(config), 2000);
  assertEquals(dailyLimitFor({}, "token"), 5);
  assertEquals(dailyLimitFor({}, "legacy_user_id"), 3);
  assertEquals(globalDailyLimit({}), 2000);
});

Deno.test("quota math: the limit-th render is the last allowed one", () => {
  assert(!quotaExceeded(0, 5));
  assert(!quotaExceeded(4, 5));
  assert(quotaExceeded(5, 5));
  assert(quotaExceeded(6, 5));
});

Deno.test("stale processing rows are abandoned just after the 150 s wall clock; renders block for 90 s", () => {
  assert(STALE_PROCESSING_MS > 150_000, "a live request must never look stale");
  assert(STALE_PROCESSING_MS <= 180_000, "a crashed request must not block its retry for long");
  const now = new Date("2026-09-23T10:00:00.000Z");
  const fresh = new Date(now.getTime() - 60_000).toISOString();
  const live = new Date(now.getTime() - 150_000).toISOString();
  const old = new Date(now.getTime() - STALE_PROCESSING_MS).toISOString();
  assert(!isStaleProcessing(fresh, now));
  assert(!isStaleProcessing(live, now));
  assert(isStaleProcessing(old, now));
  assert(isStaleProcessing("not-a-date", now));
  assert(isInflightRender(fresh, now));
  assert(!isInflightRender(new Date(now.getTime() - INFLIGHT_RENDER_MS).toISOString(), now));
  assert(!isInflightRender("not-a-date", now));
});

Deno.test("attempt limit defaults to 12 and never drops below the daily limit", () => {
  assertEquals(DEFAULT_DAILY_ATTEMPT_LIMIT, 12);
  assertEquals(attemptLimitFor({}, 5), 12);
  assertEquals(attemptLimitFor({ generate_daily_attempt_limit: "20" }, 5), 20);
  assertEquals(attemptLimitFor({ generate_daily_attempt_limit: "3" }, 5), 5);
  assertEquals(attemptLimitFor({ generate_daily_attempt_limit: "junk" }, 3), 12);
  assertEquals(attemptLimitFor({}, 30), 30);
});

Deno.test("fresh filter counts ready and recent processing rows only", () => {
  const since = "2026-09-23T09:57:10.000Z";
  assertEquals(freshRenderFilter(since), `status.eq.ready,and(status.eq.processing,created_at.gt."${since}")`);
});

Deno.test("global filter also counts failures that Gemini already billed", () => {
  const since = "2026-09-23T09:57:10.000Z";
  const filter = globalRenderFilter(since);
  assert(filter.startsWith(freshRenderFilter(since) + ","), filter);
  for (const code of ["NAME_TOO_LONG_FOR_SONG", "MIX_FAILED", "UPLOAD_FAILED", "TTS_FAILED"]) {
    assert(BILLED_FAILURE_CODES.includes(code), code);
  }
  assert(filter.endsWith(`and(status.eq.failed,error_code.in.(${BILLED_FAILURE_CODES.join(",")}))`), filter);
  // Failures before the Gemini call must not eat the global budget.
  for (const code of ["QUOTA_EXCEEDED", "GENERATION_IN_PROGRESS", "TTS_RATE_LIMITED", "INVALID_NAME"]) {
    assert(!BILLED_FAILURE_CODES.includes(code), code);
  }
});

Deno.test("attempt filter counts in-flight/ready rows and possibly-billed failures only", () => {
  assertEquals(
    attemptFilter(),
    `error_code.is.null,error_code.in.(${[...BILLED_FAILURE_CODES, "NAME_REJECTED"].join(",")})`,
  );
  for (const code of [...BILLED_FAILURE_CODES, "NAME_REJECTED"]) {
    assert(ATTEMPT_FAILURE_CODES.includes(code), code);
  }
  // Never billed: a Gemini outage (429/503 -> auto re-posts) must not lock the user out.
  for (const code of ["TTS_RATE_LIMITED", "GENERATION_IN_PROGRESS", "INTERNAL", "QUOTA_EXCEEDED"]) {
    assert(!ATTEMPT_FAILURE_CODES.includes(code), code);
  }
});

Deno.test("retry claim filter is the exact complement of the fresh window for processing rows", () => {
  const now = new Date("2026-09-23T10:00:00.000Z");
  const { processingSinceIso } = quotaWindow(now);
  assertEquals(
    retryableRequestFilter(processingSinceIso),
    `status.eq.failed,and(status.eq.processing,created_at.lte."${processingSinceIso}")`,
  );
  // created_at == processingSinceIso is stale (claimable) and not fresh: gt vs lte.
  assert(isStaleProcessing(processingSinceIso, now));
});

Deno.test("withinLimit admits only the earliest rows of a parallel burst", () => {
  // limit 5, four earlier rows today, then parallel requests a and b both inserted.
  const ordered = ["r1", "r2", "r3", "r4", "a"];
  assert(withinLimit(ordered, "a", 5));
  assert(!withinLimit(ordered, "b", 5), "b sorts after the limit-th row");
  assert(withinLimit(["r1", "b"], "b", 5));
  // Fewer rows than the limit: room left even if our own row was not read back.
  assert(withinLimit(["r1", "r2"], "missing", 5));
  assert(!withinLimit([], "x", 0));
});
