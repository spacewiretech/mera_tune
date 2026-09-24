// deno test --allow-read supabase/functions/tests
import { assert, assertEquals, assertMatch, assertNotEquals } from "jsr:@std/assert@1";
import {
  billingMonthFromDate,
  cleanProps,
  istDate,
  istMonth,
  mixpanelDate,
  mixpanelInsertId,
  parseCashfreeTimeMs,
  resolveMixpanelToken,
  trackMixpanelEvent,
  updateMixpanelPeople,
} from "../_shared/mixpanel.ts";

type Call = { url: string; body: unknown };

/** Fake fetch that pops one scripted response per call and records the URL and JSON body. */
function scriptedFetch(responses: Array<Response | Error>, calls: Call[]): typeof fetch {
  return ((url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), body: JSON.parse(String(init?.body ?? "null")) });
    const next = responses.shift();
    if (!next) throw new Error("scriptedFetch: no response left");
    if (next instanceof Error) return Promise.reject(next);
    return Promise.resolve(next);
  }) as typeof fetch;
}

function mixpanelOk(): Response {
  return new Response(JSON.stringify({ status: 1, error: null }), { status: 200 });
}

Deno.test("mixpanelInsertId: deterministic 32 hex, order-sensitive, unambiguous parts", async () => {
  const id = await mixpanelInsertId("paid", "49585655");
  assertMatch(id, /^[0-9a-f]{32}$/);
  assertEquals(await mixpanelInsertId("paid", "49585655"), id);
  assertNotEquals(await mixpanelInsertId("49585655", "paid"), id);
  assertNotEquals(await mixpanelInsertId("a|b", "c"), await mixpanelInsertId("a", "b|c"));
  assertEquals(await mixpanelInsertId("x", null), await mixpanelInsertId("x", undefined));
  assertNotEquals(await mixpanelInsertId("x", null), await mixpanelInsertId("x", "null"));
  assertNotEquals(await mixpanelInsertId(1), await mixpanelInsertId("1"));
});

Deno.test("istMonth: the IST month flips at 18:30 UTC on the last day", () => {
  assertEquals(istMonth(Date.parse("2026-09-30T18:29:59Z")), "2026-09");
  assertEquals(istMonth(Date.parse("2026-09-30T18:30:00Z")), "2026-10");
  assertEquals(istMonth(Date.parse("2026-12-31T18:30:00Z")), "2027-01");
  assertEquals(istMonth(null), "");
  assertEquals(istMonth(NaN), "");
});

Deno.test("istDate and billingMonthFromDate read offsets and IST calendar days", () => {
  assertEquals(istDate(Date.parse("2025-08-07T20:00:00Z")), "2025-08-08");
  assertEquals(istDate(null), "");
  assertEquals(billingMonthFromDate("2026-09-30T23:59:59+05:30"), "2026-09");
  assertEquals(billingMonthFromDate("2026-10-01T00:00:00+05:30"), "2026-10");
  assertEquals(billingMonthFromDate(""), "");
  assertEquals(billingMonthFromDate("not a date"), "");
});

Deno.test("parseCashfreeTimeMs: offsets, naive IST times, dates, epochs and garbage", () => {
  assertEquals(parseCashfreeTimeMs("2025-08-07T10:31:35+05:30"), Date.parse("2025-08-07T05:01:35Z"));
  assertEquals(parseCashfreeTimeMs("2025-08-07T10:31:36"), Date.parse("2025-08-07T05:01:36Z"));
  assertEquals(parseCashfreeTimeMs("2025-08-07 10:31:36"), Date.parse("2025-08-07T05:01:36Z"));
  assertEquals(parseCashfreeTimeMs("2025-09-30"), Date.parse("2025-09-29T18:30:00Z"));
  assertEquals(parseCashfreeTimeMs("2025-08-07T05:01:35.123+00:00"), Date.parse("2025-08-07T05:01:35.123Z"));
  assertEquals(parseCashfreeTimeMs(1617695238078), 1617695238078);
  assertEquals(parseCashfreeTimeMs(1617695238), 1617695238000);
  assertEquals(parseCashfreeTimeMs(""), null);
  assertEquals(parseCashfreeTimeMs("   "), null);
  assertEquals(parseCashfreeTimeMs("garbage"), null);
  assertEquals(parseCashfreeTimeMs(null), null);
  assertEquals(parseCashfreeTimeMs(undefined), null);
});

Deno.test("mixpanelDate: Mixpanel profile date format in UTC", () => {
  assertEquals(mixpanelDate(Date.parse("2025-08-07T05:01:35.123Z")), "2025-08-07T05:01:35");
});

Deno.test("cleanProps: drops blanks, non-finite numbers, reserved and PII keys; truncates", () => {
  const cleaned = cleanProps({
    amount: 299,
    zero: 0,
    flag: false,
    label: "  trimmed  ",
    blank: "",
    spaces: "   ",
    missing: null,
    absent: undefined,
    nan: NaN,
    inf: Infinity,
    token: "t",
    distinct_id: "1",
    time: 1,
    platform: "android",
    $insert_id: "x",
    "Bad-Key": "x",
    customer_phone: "9910000000",
    upi_id: "9910000000@ybl",
    refund_note: "Tesg Refund",
    email: "john@dummy.com",
    long: "a".repeat(300),
  });
  assertEquals(Object.keys(cleaned).sort(), ["amount", "flag", "label", "long", "zero"]);
  assertEquals(cleaned.label, "trimmed");
  assertEquals((cleaned.long as string).length, 255);
});

Deno.test("resolveMixpanelToken: an empty or blank env secret falls back to app_config", () => {
  assertEquals(resolveMixpanelToken({ mixpanel_token: "cfg" }, ""), "cfg");
  assertEquals(resolveMixpanelToken({ mixpanel_token: " cfg " }, "   "), "cfg");
  assertEquals(resolveMixpanelToken({ mixpanel_token: "cfg" }, " env "), "env");
  assertEquals(resolveMixpanelToken({}, ""), "");
});

Deno.test("trackMixpanelEvent: /track with ip=0, event time, insert id and server platform", async () => {
  const calls: Call[] = [];
  const result = await trackMixpanelEvent(
    "tok",
    "42",
    "subscription_paid",
    { amount: 299, platform: "android", blank: "" },
    { insertId: "abc123", timeMs: 1754543685000, fetchImpl: scriptedFetch([mixpanelOk()], calls) },
  );
  assertEquals(result, { ok: true });
  assertEquals(calls.length, 1);
  assert(calls[0].url.startsWith("https://api.mixpanel.com/track?"));
  assert(calls[0].url.includes("ip=0"));
  assert(calls[0].url.includes("verbose=1"));
  const [record] = calls[0].body as Array<{ event: string; properties: Record<string, unknown> }>;
  assertEquals(record.event, "subscription_paid");
  assertEquals(record.properties, {
    amount: 299,
    token: "tok",
    distinct_id: "42",
    time: 1754543685000,
    platform: "server",
    $insert_id: "abc123",
  });
});

Deno.test("trackMixpanelEvent: default insert id is derived from event, user and time", async () => {
  const calls: Call[] = [];
  await trackMixpanelEvent("tok", "42", "e", {}, { timeMs: 5, fetchImpl: scriptedFetch([mixpanelOk()], calls) });
  const [record] = calls[0].body as Array<{ properties: Record<string, unknown> }>;
  assertEquals(record.properties.$insert_id, await mixpanelInsertId("e", "42", 5));
});

Deno.test("trackMixpanelEvent: Mixpanel rejections and HTTP errors are ok:false", async () => {
  const rejected = await trackMixpanelEvent("tok", "42", "e", {}, {
    fetchImpl: scriptedFetch([new Response(JSON.stringify({ status: 0, error: "bad time" }), { status: 200 })], []),
  });
  assertEquals(rejected, { ok: false, error: "bad time" });

  const serverError = await trackMixpanelEvent("tok", "42", "e", {}, {
    fetchImpl: scriptedFetch([new Response("oops", { status: 500 })], []),
  });
  assertEquals(serverError, { ok: false, error: "http_500" });
});

Deno.test("trackMixpanelEvent: a rejected fetch returns ok:false instead of throwing", async () => {
  const result = await trackMixpanelEvent("tok", "42", "e", {}, {
    fetchImpl: scriptedFetch([new TypeError("connection reset")], []),
  });
  assertEquals(result, { ok: false, error: "network" });
});

Deno.test("trackMixpanelEvent: a stalled fetch times out", async () => {
  const stalled = ((_url: string | URL | Request, init?: RequestInit) =>
    new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () => reject(init.signal?.reason));
    })) as typeof fetch;
  const result = await trackMixpanelEvent("tok", "42", "e", {}, { timeoutMs: 10, fetchImpl: stalled });
  assertEquals(result, { ok: false, error: "timeout" });
});

Deno.test("trackMixpanelEvent: missing token or distinct id never calls fetch", async () => {
  const calls: Call[] = [];
  const fetchImpl = scriptedFetch([], calls);
  assertEquals(await trackMixpanelEvent("", "42", "e", {}, { fetchImpl }), { ok: false, error: "token_missing" });
  assertEquals(await trackMixpanelEvent("tok", "", "e", {}, { fetchImpl }), {
    ok: false,
    error: "distinct_id_missing",
  });
  assertEquals(calls.length, 0);
});

Deno.test("updateMixpanelPeople: one operation per record with $ignore_time and ip=0", async () => {
  const calls: Call[] = [];
  const result = await updateMixpanelPeople("tok", "42", {
    set: { subscription_status: "active", blank: "", customer_phone: "9910000000" },
    setOnce: { first_paid_at: "2025-08-07T05:01:35" },
    unset: ["last_payment_failed_reason", "Bad Key"],
  }, { fetchImpl: scriptedFetch([mixpanelOk()], calls) });
  assertEquals(result, { ok: true });
  assert(calls[0].url.startsWith("https://api.mixpanel.com/engage?"));
  assert(calls[0].url.includes("ip=0"));
  assert(calls[0].url.includes("verbose=1"));
  const base = { $token: "tok", $distinct_id: "42", $ignore_time: true };
  assertEquals(calls[0].body, [
    { ...base, $set: { subscription_status: "active" } },
    { ...base, $set_once: { first_paid_at: "2025-08-07T05:01:35" } },
    { ...base, $unset: ["last_payment_failed_reason"] },
  ]);
});

Deno.test("updateMixpanelPeople: empty operations make no request", async () => {
  const calls: Call[] = [];
  const fetchImpl = scriptedFetch([], calls);
  assertEquals(await updateMixpanelPeople("tok", "42", {}, { fetchImpl }), { ok: true });
  assertEquals(await updateMixpanelPeople("tok", "42", { set: { a: "" }, unset: [] }, { fetchImpl }), { ok: true });
  assertEquals(calls.length, 0);
});

Deno.test("updateMixpanelPeople: engage failures are ok:false, never thrown", async () => {
  const rejected = await updateMixpanelPeople("tok", "42", { set: { a: 1 } }, {
    fetchImpl: scriptedFetch([new Response(JSON.stringify({ status: 0, error: "invalid token" }))], []),
  });
  assertEquals(rejected, { ok: false, error: "invalid token" });
  const network = await updateMixpanelPeople("tok", "42", { set: { a: 1 } }, {
    fetchImpl: scriptedFetch([new TypeError("dns")], []),
  });
  assertEquals(network, { ok: false, error: "network" });
});
