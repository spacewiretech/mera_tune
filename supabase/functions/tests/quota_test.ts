// deno test --allow-read supabase/functions/tests
import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  ATTEMPT_FAILURE_CODES,
  attemptFilter,
  attemptLimitFor,
  attemptLimitForPlan,
  BILLED_FAILURE_CODES,
  dailyLimitFor,
  DEFAULT_DAILY_ATTEMPT_LIMIT,
  DEFAULT_MEMBER_MONTHLY_LIMIT,
  DEFAULT_TRIAL_DAILY_LIMIT,
  freshRenderFilter,
  globalDailyLimit,
  globalRenderFilter,
  INFLIGHT_RENDER_MS,
  isFlagEnabled,
  isInflightRender,
  isStaleProcessing,
  istDateString,
  istDayStart,
  istMonthStart,
  istNextMidnight,
  istNextMonthStart,
  memberMonthlyLimit,
  parseBoundedFloat,
  parsePositiveInt,
  periodEnd,
  periodStart,
  type PlanQuota,
  planQuotaFor,
  quotaExceeded,
  quotaRetryAfterSeconds,
  quotaSnapshot,
  quotaWindow,
  retryableRequestFilter,
  secondsUntilIstMidnight,
  secondsUntilPeriodEnd,
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

Deno.test("quotaWindow derives all cut-offs from now", () => {
  const now = new Date("2026-09-23T10:00:00.000Z");
  const window = quotaWindow(now);
  assertEquals(window.dayStartIso, "2026-09-22T18:30:00.000Z");
  assertEquals(window.periodStartIso, window.dayStartIso, "a day plan counts from today");
  assertEquals(window.processingSinceIso, new Date(now.getTime() - STALE_PROCESSING_MS).toISOString());
  assertEquals(window.inflightSinceIso, new Date(now.getTime() - INFLIGHT_RENDER_MS).toISOString());
  assertEquals(quotaWindow(now, "day"), window);
});

Deno.test("quotaWindow for a monthly plan counts fresh renders from the IST month, attempts from today", () => {
  const now = new Date("2026-09-23T10:00:00.000Z");
  const window = quotaWindow(now, "month");
  assertEquals(window.periodStartIso, "2026-08-31T18:30:00.000Z");
  assertEquals(window.dayStartIso, "2026-09-22T18:30:00.000Z");
  assertEquals(window.processingSinceIso, quotaWindow(now).processingSinceIso);
  assertEquals(window.inflightSinceIso, quotaWindow(now).inflightSinceIso);
});

Deno.test("istMonthStart / istNextMonthStart: mid-month", () => {
  // 2026-09-23 15:30 IST -> September IST = [2026-09-01 00:00 IST, 2026-10-01 00:00 IST)
  const now = new Date("2026-09-23T10:00:00.000Z");
  assertEquals(istMonthStart(now).toISOString(), "2026-08-31T18:30:00.000Z");
  assertEquals(istNextMonthStart(now).toISOString(), "2026-09-30T18:30:00.000Z");
});

Deno.test("istMonthStart: the last IST millisecond of a month and IST midnight on the 1st", () => {
  const lastMs = new Date("2026-09-30T18:29:59.999Z"); // 23:59:59.999 IST on 30 September
  assertEquals(istMonthStart(lastMs).toISOString(), "2026-08-31T18:30:00.000Z");
  assertEquals(istNextMonthStart(lastMs).toISOString(), "2026-09-30T18:30:00.000Z");
  const firstInstant = new Date("2026-09-30T18:30:00.000Z"); // 00:00 IST on 1 October
  assertEquals(istMonthStart(firstInstant).toISOString(), "2026-09-30T18:30:00.000Z");
  assertEquals(istNextMonthStart(firstInstant).toISOString(), "2026-10-31T18:30:00.000Z");
  // Still 30 September in UTC, already 1 October in IST.
  const utcSeptember = new Date("2026-09-30T19:00:00.000Z");
  assertEquals(istMonthStart(utcSeptember).toISOString(), "2026-09-30T18:30:00.000Z");
  assertEquals(periodEnd(utcSeptember, "month").toISOString(), "2026-10-31T18:30:00.000Z");
});

Deno.test("istNextMonthStart: December rolls over into January of the next year", () => {
  const december = new Date("2026-12-31T18:29:59.999Z"); // 23:59:59.999 IST on 31 December
  assertEquals(istMonthStart(december).toISOString(), "2026-11-30T18:30:00.000Z");
  assertEquals(istNextMonthStart(december).toISOString(), "2026-12-31T18:30:00.000Z");
  const january = new Date("2026-12-31T19:00:00.000Z"); // 00:30 IST on 1 January 2027
  assertEquals(istMonthStart(january).toISOString(), "2026-12-31T18:30:00.000Z");
  assertEquals(istNextMonthStart(january).toISOString(), "2027-01-31T18:30:00.000Z");
  assertEquals(periodEnd(january, "month").toISOString(), "2027-01-31T18:30:00.000Z");
});

Deno.test("istNextMonthStart: February, including a leap year", () => {
  assertEquals(istNextMonthStart(new Date("2027-02-15T10:00:00.000Z")).toISOString(), "2027-02-28T18:30:00.000Z");
  assertEquals(istNextMonthStart(new Date("2028-02-15T10:00:00.000Z")).toISOString(), "2028-02-29T18:30:00.000Z");
});

Deno.test("periodStart / periodEnd: day is the IST day, month the IST month", () => {
  const now = new Date("2026-09-23T20:00:00.000Z");
  assertEquals(periodStart(now, "day").toISOString(), istDayStart(now).toISOString());
  assertEquals(periodEnd(now, "day").toISOString(), istNextMidnight(now).toISOString());
  assertEquals(periodStart(now, "month").toISOString(), istMonthStart(now).toISOString());
  assertEquals(periodEnd(now, "month").toISOString(), istNextMonthStart(now).toISOString());
});

Deno.test("secondsUntilPeriodEnd rounds up and is never below 1", () => {
  assertEquals(secondsUntilPeriodEnd(new Date("2026-09-30T18:29:59.999Z"), "month"), 1);
  assertEquals(secondsUntilPeriodEnd(new Date("2026-09-30T18:29:59.400Z"), "month"), 1);
  // At IST midnight on 1 October the whole of October (31 days) is left.
  assertEquals(secondsUntilPeriodEnd(new Date("2026-09-30T18:30:00.000Z"), "month"), 31 * 24 * 60 * 60);
  assertEquals(secondsUntilPeriodEnd(new Date("2026-12-31T19:00:00.000Z"), "month"), 31 * 24 * 60 * 60 - 30 * 60);
  for (const iso of ["2026-09-23T10:00:00.000Z", "2026-09-23T18:29:59.400Z", "2026-09-23T18:30:00.000Z"]) {
    const now = new Date(iso);
    assertEquals(secondsUntilPeriodEnd(now, "day"), secondsUntilIstMidnight(now), iso);
    assert(secondsUntilPeriodEnd(now, "month") >= secondsUntilPeriodEnd(now, "day"), iso);
    assert(secondsUntilPeriodEnd(now, "month") >= 1, iso);
  }
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

Deno.test("planQuotaFor: users.status picks the plan (token auth)", () => {
  assertEquals(DEFAULT_TRIAL_DAILY_LIMIT, 2);
  assertEquals(DEFAULT_MEMBER_MONTHLY_LIMIT, 50);
  assertEquals(planQuotaFor({}, "trial", "token"), { plan: "trial", period: "day", limit: 2 });
  assertEquals(planQuotaFor({}, "active", "token"), { plan: "member", period: "month", limit: 50 });
  for (const status of ["none", "expired", "cancelled", "", "   ", "pending", null, undefined]) {
    assertEquals(planQuotaFor({}, status, "token"), { plan: "default", period: "day", limit: 5 }, String(status));
  }
  // Trimmed and case-insensitive.
  assertEquals(planQuotaFor({}, " Trial ", "token").plan, "trial");
  assertEquals(planQuotaFor({}, "ACTIVE", "token").plan, "member");
  assertEquals(planQuotaFor({}, "Expired", "token").plan, "default");
});

Deno.test("planQuotaFor: app_config overrides and bad values fall back to the defaults", () => {
  const config = {
    generate_trial_daily_limit: "4",
    generate_member_monthly_limit: " 100 ",
    generate_daily_limit: "7",
    generate_legacy_daily_limit: "1",
  };
  assertEquals(planQuotaFor(config, "trial", "token"), { plan: "trial", period: "day", limit: 4 });
  assertEquals(planQuotaFor(config, "active", "token"), { plan: "member", period: "month", limit: 100 });
  assertEquals(planQuotaFor(config, "none", "token"), { plan: "default", period: "day", limit: 7 });
  assertEquals(memberMonthlyLimit(config), 100);
  for (const bad of ["0", "-1", "abc", "", "2.5"]) {
    const garbage = { generate_trial_daily_limit: bad, generate_member_monthly_limit: bad, generate_daily_limit: bad };
    assertEquals(planQuotaFor(garbage, "trial", "token").limit, 2, bad);
    assertEquals(planQuotaFor(garbage, "active", "token").limit, 50, bad);
    assertEquals(planQuotaFor(garbage, "expired", "token").limit, 5, bad);
    assertEquals(memberMonthlyLimit(garbage), 50, bad);
  }
});

Deno.test("planQuotaFor: legacy user_id callers are limited per day, never above the legacy limit", () => {
  // Defaults: legacy 3/day.
  assertEquals(planQuotaFor({}, "trial", "legacy_user_id"), { plan: "trial", period: "day", limit: 2 });
  assertEquals(planQuotaFor({}, "active", "legacy_user_id"), { plan: "member", period: "day", limit: 3 });
  assertEquals(planQuotaFor({}, "none", "legacy_user_id"), { plan: "default", period: "day", limit: 3 });
  // A daily plan below the legacy limit keeps its own limit; above it, the legacy limit wins.
  assertEquals(planQuotaFor({ generate_trial_daily_limit: "10" }, "trial", "legacy_user_id").limit, 3);
  const generousLegacy = { generate_legacy_daily_limit: "8", generate_daily_limit: "5" };
  assertEquals(planQuotaFor(generousLegacy, "none", "legacy_user_id").limit, 5);
  assertEquals(planQuotaFor(generousLegacy, "trial", "legacy_user_id").limit, 2);
  // The monthly allowance never applies to a legacy caller: the legacy daily limit does.
  assertEquals(planQuotaFor(generousLegacy, "active", "legacy_user_id"), { plan: "member", period: "day", limit: 8 });
});

Deno.test("attempt cap stays per day: a monthly limit is not its floor", () => {
  const member: PlanQuota = { plan: "member", period: "month", limit: 50 };
  assertEquals(attemptLimitForPlan({}, member), DEFAULT_DAILY_ATTEMPT_LIMIT);
  assertEquals(attemptLimitForPlan({ generate_daily_attempt_limit: "20" }, member), 20);
  assertEquals(attemptLimitForPlan({ generate_daily_attempt_limit: "3" }, member), 3);
  assertEquals(attemptLimitForPlan({}, planQuotaFor({}, "trial", "token")), 12);
  assertEquals(attemptLimitForPlan({}, planQuotaFor({}, "none", "token")), 12);
  // A daily plan's limit is still the floor, as before.
  assertEquals(attemptLimitForPlan({ generate_daily_attempt_limit: "3" }, { plan: "default", period: "day", limit: 5 }), 5);
  assertEquals(attemptLimitForPlan({}, { plan: "trial", period: "day", limit: 30 }), 30);
});

Deno.test("quotaSnapshot: the contract shape, old names mirroring used / limit", () => {
  const now = new Date("2026-09-28T10:00:00.000Z");
  const trial = quotaSnapshot(planQuotaFor({}, "trial", "token"), 1, now, 50);
  assertEquals(trial, {
    used_today: 1,
    daily_limit: 2,
    plan: "trial",
    period: "day",
    used: 1,
    limit: 2,
    resets_at: "2026-09-28T18:30:00.000Z",
    member_monthly_limit: 50,
  });
  assertEquals(Object.keys(trial), [
    "used_today", "daily_limit", "plan", "period", "used", "limit", "resets_at", "member_monthly_limit",
  ]);
  const member = quotaSnapshot(planQuotaFor({}, "active", "token"), 12, now, 50);
  assertEquals(member, {
    used_today: 12,
    daily_limit: 50,
    plan: "member",
    period: "month",
    used: 12,
    limit: 50,
    resets_at: "2026-09-30T18:30:00.000Z",
    member_monthly_limit: 50,
  });
  const fallback = quotaSnapshot(planQuotaFor({}, "none", "token"), 0, now, 80);
  assertEquals([fallback.plan, fallback.period, fallback.limit, fallback.member_monthly_limit], ["default", "day", 5, 80]);
  assert(!("exceeded" in trial) && !("exceeded" in member));
});

Deno.test("quotaSnapshot: exceeded only when given", () => {
  const now = new Date("2026-09-28T10:00:00.000Z");
  const plan = quotaSnapshot(planQuotaFor({}, "active", "token"), 50, now, 50, "plan");
  assertEquals(plan.exceeded, "plan");
  assertEquals(Object.keys(plan).at(-1), "exceeded");
  const attempts = quotaSnapshot(planQuotaFor({}, "trial", "token"), 1, now, 50, "attempts");
  assertEquals([attempts.exceeded, attempts.used, attempts.resets_at], ["attempts", 1, "2026-09-28T18:30:00.000Z"]);
});

Deno.test("quotaRetryAfterSeconds: plan limit until its period ends, attempt cap until IST midnight", () => {
  const now = new Date("2026-09-28T10:00:00.000Z"); // 15:30 IST, 28 September
  const untilMidnight = 8.5 * 60 * 60;
  const untilOctober = 2 * 24 * 60 * 60 + untilMidnight;
  const member = planQuotaFor({}, "active", "token");
  const trial = planQuotaFor({}, "trial", "token");
  assertEquals(quotaRetryAfterSeconds(now, quotaSnapshot(member, 50, now, 50, "plan")), untilOctober);
  assertEquals(quotaRetryAfterSeconds(now, quotaSnapshot(member, 20, now, 50, "attempts")), untilMidnight);
  assertEquals(quotaRetryAfterSeconds(now, quotaSnapshot(trial, 2, now, 50, "plan")), untilMidnight);
  assertEquals(quotaRetryAfterSeconds(now, quotaSnapshot(trial, 1, now, 50, "attempts")), untilMidnight);
  const lastMs = new Date("2026-09-30T18:29:59.999Z");
  assertEquals(quotaRetryAfterSeconds(lastMs, quotaSnapshot(member, 50, lastMs, 50, "plan")), 1);
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
