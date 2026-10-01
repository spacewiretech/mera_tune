// deno test --allow-read supabase/functions/tests
//
// The retry job (retry-failed-charges/job.ts) end to end on an in-memory store and a fake Cashfree:
// dry-run writes and POSTs nothing, a live run plans and sends, 429 stops the run without failing
// the attempt, a wrong RETRY response halts sends, and each mandate goes to its own account.
import { assert, assertEquals, assertFalse } from "jsr:@std/assert@1";
import { type Account, CashfreeClient, cashfreeAccounts, Limiter } from "../retry-failed-charges/cashfree.ts";
import {
  type AttemptPatch,
  type AttemptRecord,
  type Candidate,
  type ChargeFailure,
  type CheckRecord,
  type NewAttempt,
  type RetryStore,
  runRetryJob,
} from "../retry-failed-charges/job.ts";
import { type AttemptStatus, idempotencyKey, retryConfig } from "../retry-failed-charges/policy.ts";
import { daysSinceFailure, type RetryEvent, retryEventKey } from "../retry-failed-charges/analytics.ts";
import type { MixpanelProps } from "../_shared/mixpanel.ts";
import { pickEventProps, SERVER_EVENT_PROPS } from "../_shared/subscription-analytics.ts";

type Tracked = { event: RetryEvent; userId: number; props: MixpanelProps; key: string };

const ist = (s: string) => Date.parse(`${s}+05:30`);
const MT = "mt_877432_1790408546604";
const FAILED_ID = "1353204_355_1790408562302";
const INSUFFICIENT = "DEBIT FAILED | Insufficient Funds In Customer (Remitter) Account";

class MemoryStore implements RetryStore {
  attempts: AttemptRecord[] = [];
  checks = new Map<number, CheckRecord>();
  paid: Array<{ subscription_row_id: number; cf_payment_id: string; paid_at: string }> = [];
  /** charge_failures, as cashfree-webhook writes them. */
  failures: Array<ChargeFailure & { processed?: string }> = [];
  writes = 0;
  constructor(public cands: Candidate[] = []) {}

  newFailures(since: string, limit: number) {
    return Promise.resolve(this.failures
      .filter((f) => !f.processed && f.payment_status === "FAILED" && f.retry_attempts === 0 && (f.scheduled_on ?? "") >= since)
      .slice(0, limit));
  }
  failuresFor(ids: string[]) {
    return Promise.resolve(this.failures.filter((f) => ids.includes(f.payment_id)));
  }
  markFailureProcessed(id: string, outcome: string) {
    this.writes++;
    const f = this.failures.find((x) => x.payment_id === id);
    if (f && !f.processed) f.processed = outcome;
    return Promise.resolve();
  }
  /** The webhook's record of a failed charge (a regular one, or one of the job's retries). */
  recordFailure(paymentId: string, reason: string, retryAttempts: number, scheduledOn = "2026-09-27") {
    this.failures.push({
      payment_id: paymentId,
      merchant_subscription_id: MT,
      cf_subscription_id: "359414437",
      subscription_row_id: 567939,
      user_id: 877432,
      payment_status: "FAILED",
      failure_reason: reason,
      retry_attempts: retryAttempts,
      scheduled_on: scheduledOn,
    });
  }

  candidates(_since: string, limit: number, _only: string[]) {
    const open = new Set(this.attempts.filter((a) => a.status === "pending" || a.status === "requested").map((a) => a.subscription_row_id));
    return Promise.resolve(this.cands.filter((c) => !open.has(c.subscription_row_id)).slice(0, limit));
  }
  openChains(q: { limit: number; pendingThrough: string; requestedThrough: string }) {
    const pick = (rows: AttemptRecord[]) => rows.sort((x, y) => x.scheduled_for.localeCompare(y.scheduled_for)).slice(0, q.limit);
    const toSend = pick(this.attempts.filter((a) => a.status === "pending" && a.scheduled_for <= q.pendingThrough));
    const toResolve = pick(this.attempts.filter((a) =>
      a.status === "requested" && (a.retry_scheduled_for || a.scheduled_for) <= q.requestedThrough
    ));
    const open = new Set([...toSend, ...toResolve].map((a) => a.failed_payment_id));
    return Promise.resolve(this.attempts.filter((a) => open.has(a.failed_payment_id)).map((a) => ({ ...a })));
  }
  recurringPayments(rowIds: number[], sinceIso: string) {
    return Promise.resolve(this.paid.filter((p) => rowIds.includes(p.subscription_row_id) && p.paid_at >= sinceIso));
  }
  saveCheck(check: CheckRecord) {
    this.writes++;
    this.checks.set(check.subscription_row_id, check);
    return Promise.resolve();
  }
  insertChain(rows: NewAttempt[]) {
    this.writes++;
    const inserted: AttemptRecord[] = [];
    for (const r of rows) {
      if (this.attempts.some((a) => a.failed_payment_id === r.failed_payment_id && a.attempt === r.attempt)) continue;
      const rec = { ...r, id: this.attempts.length + 1 } as AttemptRecord;
      this.attempts.push(rec);
      inserted.push({ ...rec });
    }
    return Promise.resolve(inserted);
  }
  updateAttempt(id: number, from: AttemptStatus[], patch: AttemptPatch) {
    this.writes++;
    const a = this.attempts.find((x) => x.id === id);
    if (!a || !from.includes(a.status)) return Promise.resolve(false);
    Object.assign(a, patch);
    return Promise.resolve(true);
  }
}

type Call = { method: string; url: string; headers: Record<string, string>; body?: unknown };

/** Cashfree fake: `mandates` by account name and merchant id, legacy lookups by cf id. */
class FakeCashfree {
  calls: Call[] = [];
  retryStatus = 200;
  retryAmount = 299;
  mandateStatus = "ACTIVE";
  payments: Record<string, unknown>[] = [
    { payment_id: "CH_1", cf_payment_id: "1117136245", payment_type: "AUTH", payment_status: "SUCCESS", payment_amount: 3, payment_initiated_date: "2026-09-26T13:12:27+05:30" },
    {
      payment_id: FAILED_ID,
      cf_payment_id: "1117138074",
      payment_type: "CHARGE",
      payment_status: "FAILED",
      payment_amount: 299,
      payment_initiated_date: "2026-09-26T13:12:42+05:30",
      payment_schedule_date: "2026-09-27T00:10:00+05:30",
      retry_attempts: null,
      failure_details: { failure_reason: INSUFFICIENT },
    },
  ];
  retryPayments = new Map<string, Record<string, unknown>>();
  /** cf_subscription_id -> account name that has it. */
  owner: Record<string, string> = { "359414437": "primary" };
  /** The job's clock: Cashfree schedules a retry 24 h after the request (measured 2026-09-30). */
  clock = 0;
  private retries = 0;

  fetch: typeof fetch = async (input, init) => {
    const url = String(input);
    const headers = Object.fromEntries(Object.entries((init?.headers ?? {}) as Record<string, string>).map(([k, v]) => [k.toLowerCase(), v]));
    const method = init?.method ?? "GET";
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    this.calls.push({ method, url, headers, body });
    const json = (status: number, b: unknown) => new Response(JSON.stringify(b), { status });
    const clientId = headers["x-client-id"];
    const accountName = clientId === "old_id" ? "old" : "primary";

    const v2 = url.match(/\/api\/v2\/subscriptions\/(\d+)$/);
    if (v2) {
      if (this.owner[v2[1]] !== accountName) return json(400, { status: "ERROR", message: "Subscription doesn't exist" });
      return json(200, {
        status: "OK",
        subscription: { subscriptionId: MT, subReferenceId: Number(v2[1]), status: this.mandateStatus, scheduledOn: "2026-10-27 00:10:00", recurringAmount: 299 },
      });
    }
    const manage = url.match(/\/pg\/subscriptions\/([^/]+)\/payments\/([^/]+)\/manage$/);
    if (manage && method === "POST") {
      if (this.retryStatus !== 200) return json(this.retryStatus, { code: this.retryStatus === 429 ? "rate_limit" : "request_invalid" });
      this.retries++;
      const p = {
        payment_id: `1353204_355_${1790500000000 + this.retries}`,
        cf_payment_id: String(1200000000 + this.retries),
        payment_type: "CHARGE",
        payment_status: "INITIALIZED",
        payment_amount: this.retryAmount,
        payment_schedule_date: `${new Date(this.clock + 24 * 3_600_000 + 19_800_000).toISOString().slice(0, 19)}+05:30`,
        retry_attempts: this.retries,
      };
      this.retryPayments.set(p.payment_id, p);
      return json(200, p);
    }
    const one = url.match(/\/pg\/subscriptions\/([^/]+)\/payments\/([^/]+)$/);
    if (one) return json(200, this.retryPayments.get(decodeURIComponent(one[2])) ?? {});
    if (/\/pg\/subscriptions\/[^/]+\/payments$/.test(url)) return json(200, this.payments);
    if (/\/pg\/subscriptions\/[^/]+$/.test(url)) {
      return json(200, {
        subscription_id: MT,
        subscription_status: this.mandateStatus,
        next_schedule_date: "2026-10-27T00:10:00+05:30",
        plan_details: { plan_recurring_amount: 299 },
      });
    }
    return json(404, { code: "not_found" });
  };

  posts() {
    return this.calls.filter((c) => c.method !== "GET");
  }
}

const CANDIDATE: Candidate = {
  subscription_row_id: 567939,
  user_id: 877432,
  cf_subscription_id: "359414437",
  merchant_subscription_id: null,
  account: null,
  due_on: "2026-09-27",
};

function setup(opts: {
  mode?: string;
  now: number;
  store?: MemoryStore;
  cf?: FakeCashfree;
  extraAccounts?: string;
  only?: string;
  body?: Record<string, unknown>;
}) {
  const store = opts.store ?? new MemoryStore([CANDIDATE]);
  const cf = opts.cf ?? new FakeCashfree();
  cf.clock = opts.now;
  const config = {
    charge_retry_mode: opts.mode ?? "live",
    charge_retry_failures_since: "2026-09-26",
    charge_retry_only: opts.only ?? "",
    cashfree_client_id: "primary_id",
    cashfree_client_secret: "primary_secret",
    cashfree_extra_accounts: opts.extraAccounts ?? "",
  };
  const cfg = retryConfig(config, opts.body ?? {});
  const now = () => opts.now;
  const noSleep = () => Promise.resolve();
  const limiter = new Limiter(cfg.concurrency, 0, opts.now + 100_000, now, noSleep);
  const lookupLimiter = new Limiter(1, 0, opts.now + 100_000, now, noSleep);
  const cashfree = new CashfreeClient({
    environment: "production",
    apiVersion: "2025-01-01",
    accounts: cashfreeAccounts(config),
    limiter,
    lookupLimiter,
    fetchImpl: cf.fetch,
  });
  const events: Tracked[] = [];
  const track = (event: RetryEvent, userId: number, props: MixpanelProps, key: string) => {
    events.push({ event, userId, props, key });
  };
  return { store, cf, events, run: () => runRetryJob({ store, cashfree, limiter, lookupLimiter, cfg, now, track }) };
}

Deno.test("dry_run: plans and reports the first send, writes nothing, POSTs nothing", async () => {
  const { store, cf, run } = setup({ mode: "dry_run", now: ist("2026-09-28T07:00:00") });
  const report = await run();
  assertEquals(report.mode, "dry_run");
  assertEquals(report.checks.by_outcome, { planned: 1 });
  assertEquals(report.planned.attempts_by_day, { "2026-09-29": 1, "2026-10-01": 1, "2026-10-05": 1 });
  assertEquals(report.sends.would_send, 1);
  assertEquals(report.sends.by_day, { "2026-09-29": 1 });
  assertEquals(store.writes, 0);
  assertEquals(cf.posts().length, 0);
  // Lookup, then payments; the send reuses the mandate just fetched.
  assertEquals(cf.calls.map((c) => new URL(c.url).pathname), [
    "/api/v2/subscriptions/359414437",
    `/pg/subscriptions/${MT}/payments`,
  ]);
});

Deno.test("live dry run via the request body: a live config run with dry_run true sends nothing", async () => {
  const { store, cf, run } = setup({ mode: "live", now: ist("2026-09-28T07:00:00"), body: { dry_run: true } });
  const report = await run();
  assertEquals(report.mode, "dry_run");
  assertEquals(store.writes, 0);
  assertEquals(cf.posts().length, 0);
});

Deno.test("off: no call at all", async () => {
  const { cf, run } = setup({ mode: "off", now: ist("2026-09-28T07:00:00") });
  const report = await run();
  assertEquals(report.mode, "off");
  assertEquals(cf.calls.length, 0);
});

Deno.test("live: plans three attempts, sends RETRY for F+2 with the idempotency key, records the new charge", async () => {
  const { store, cf, run } = setup({ now: ist("2026-09-28T07:00:00") });
  const report = await run();
  assertEquals(report.sends.requested, 1);
  assertEquals(store.attempts.map((a) => [a.attempt, a.scheduled_for, a.status]), [
    [1, "2026-09-29", "requested"],
    [2, "2026-10-01", "pending"],
    [3, "2026-10-05", "pending"],
  ]);
  const [post] = cf.posts();
  assertEquals(new URL(post.url).pathname, `/pg/subscriptions/${MT}/payments/${FAILED_ID}/manage`);
  assertEquals(post.body, {
    subscription_id: MT,
    payment_id: FAILED_ID,
    action: "RETRY",
    action_details: { next_scheduled_time: "2026-09-29T00:00:00+05:30" },
  });
  assertEquals(post.headers["x-idempotency-key"], await idempotencyKey(FAILED_ID, 1));
  assertEquals(post.headers["x-api-version"], "2025-01-01");
  const first = store.attempts[0];
  assertEquals(first.retry_of_payment_id, FAILED_ID);
  assertEquals(first.retry_payment_id, "1353204_355_1790500000001");
  assertEquals(first.retry_cf_payment_id, "1200000001");
  assertEquals(first.retry_amount, 299);
  assertEquals(first.retry_scheduled_for, "2026-09-29");
  assertEquals(first.last_error, null);
  assertEquals(store.checks.get(567939)?.outcome, "planned");
  assertEquals(store.checks.get(567939)?.merchant_subscription_id, MT);

  // Same hour again: nothing new is sent (attempt 2 waits for attempt 1's outcome).
  const again = await run();
  assertEquals(again.sends.requested, 0);
  assertEquals(cf.posts().length, 1);
});

Deno.test("live: a failed retry (webhook) lets the next attempt retry the new failed charge; a recorded payment ends the chain", async () => {
  const store = new MemoryStore([CANDIDATE]);
  const cf = new FakeCashfree();
  await setup({ store, cf, now: ist("2026-09-28T07:00:00") }).run();
  const retryId = store.attempts[0].retry_payment_id!;
  // The retry fails at 00:10 IST on 2026-09-29; the webhook records it.
  store.recordFailure(retryId, INSUFFICIENT, 1, "2026-09-29");

  // Resolved from the webhook's record; attempt 2 (1 Oct) goes the day before.
  const waiting = await setup({ store, cf, now: ist("2026-09-29T02:00:00") }).run();
  assertEquals(waiting.resolved, { failed_insufficient_funds: 1 });
  assertEquals(waiting.sends.requested, 0);
  const second = await setup({ store, cf, now: ist("2026-09-30T02:00:00") }).run();
  assertEquals(second.sends.requested, 1);
  assertEquals(store.attempts.map((a) => a.status), ["failed", "requested", "pending"]);
  const post = cf.posts()[1];
  assertEquals(post.body, {
    subscription_id: MT,
    payment_id: retryId,
    action: "RETRY",
    action_details: { next_scheduled_time: "2026-10-01T00:00:00+05:30" },
  });

  // The second retry succeeds: the webhook records it in subscription_payments like any charge.
  store.paid.push({ subscription_row_id: 567939, cf_payment_id: store.attempts[1].retry_cf_payment_id!, paid_at: "2026-09-30T19:00:00.000Z" });
  const third = await setup({ store, cf, now: ist("2026-10-01T02:00:00") }).run();
  assertEquals(third.resolved, { succeeded: 1 });
  assertEquals(store.attempts.map((a) => [a.status, a.status_reason]), [
    ["failed", "insufficient_funds"],
    ["succeeded", "payment_recorded"],
    ["ended", "recovered"],
  ]);
  assertEquals(cf.posts().length, 2);
});

Deno.test("live: a retry that fails for another reason ends the chain", async () => {
  const store = new MemoryStore([CANDIDATE]);
  const cf = new FakeCashfree();
  await setup({ store, cf, now: ist("2026-09-28T07:00:00") }).run();
  store.recordFailure(store.attempts[0].retry_payment_id!, "Mandate revoked by customer", 1, "2026-09-29");
  const report = await setup({ store, cf, now: ist("2026-09-29T02:00:00") }).run();
  assertEquals(report.sends.requested, 0);
  assertEquals(store.attempts.map((a) => [a.status, a.status_reason]), [
    ["failed", "not_insufficient_funds"],
    ["ended", "retry_not_insufficient_funds"],
    ["ended", "retry_not_insufficient_funds"],
  ]);
});

Deno.test("live: 429 on RETRY stops the run and is not a failure", async () => {
  const cf = new FakeCashfree();
  cf.retryStatus = 429;
  const { store, run } = setup({ cf, now: ist("2026-09-28T07:00:00") });
  const report = await run();
  assertEquals(report.stopped, "throttled");
  assertEquals(report.sends.requested, 0);
  assertEquals(report.sends.rejected, {});
  assertEquals(store.attempts.map((a) => a.status), ["pending", "pending", "pending"]);
  assertEquals(store.attempts[0].send_errors ?? 0, 0);

  // Next run: resent with the same idempotency key.
  cf.retryStatus = 200;
  const next = await setup({ store, cf, now: ist("2026-09-28T08:00:00") }).run();
  assertEquals(next.sends.requested, 1);
  const keys = cf.posts().map((p) => p.headers["x-idempotency-key"]);
  assertEquals(new Set(keys).size, 1);
});

Deno.test("live: 5xx keeps the attempt pending, 4xx rejects it", async () => {
  const cf = new FakeCashfree();
  cf.retryStatus = 502;
  const a = setup({ cf, now: ist("2026-09-28T07:00:00") });
  const transient = await a.run();
  assertEquals(transient.sends.transient, 1);
  assertEquals(a.store.attempts[0].status, "pending");
  assertEquals(a.store.attempts[0].send_errors, 1);

  const cf2 = new FakeCashfree();
  cf2.retryStatus = 400;
  const b = setup({ cf: cf2, now: ist("2026-09-28T07:00:00") });
  const rejected = await b.run();
  assertEquals(rejected.sends.rejected, { request_invalid: 1 });
  assertEquals(b.store.attempts.map((x) => x.status), ["rejected", "pending", "pending"]);
});

Deno.test("live: a RETRY response for another amount halts all sends", async () => {
  const cf = new FakeCashfree();
  cf.retryAmount = 3;
  const second = { ...CANDIDATE, subscription_row_id: 2, cf_subscription_id: "359414438" };
  cf.owner["359414438"] = "primary";
  const { store, run } = setup({ cf, store: new MemoryStore([CANDIDATE, second]), now: ist("2026-09-28T07:00:00") });
  const report = await run();
  assertEquals(report.stopped, "response_mismatch");
  assertEquals(report.mismatches.length, 1);
  assertEquals(report.mismatches[0].mismatches, ["amount"]);
  assertEquals(cf.posts().length, 1);
  assertEquals(store.attempts[0].last_error, "response_mismatch:amount");
});

Deno.test("live: a mandate paused before the send ends the chain; a paused one found by the sweep is not planned", async () => {
  const store = new MemoryStore([CANDIDATE]);
  const cf = new FakeCashfree();
  await setup({ store, cf, now: ist("2026-09-28T07:00:00") }).run();
  store.recordFailure(store.attempts[0].retry_payment_id!, INSUFFICIENT, 1, "2026-09-29");
  cf.mandateStatus = "CUSTOMER_PAUSED";
  await setup({ store, cf, now: ist("2026-09-30T02:00:00") }).run();
  assertEquals(store.attempts.map((a) => [a.status, a.status_reason ?? null]), [
    ["failed", "insufficient_funds"],
    ["ended", "mandate_customer_paused"],
    ["ended", "mandate_customer_paused"],
  ]);

  const paused = new FakeCashfree();
  paused.mandateStatus = "CUSTOMER_PAUSED";
  const fresh = setup({ cf: paused, now: ist("2026-09-28T07:00:00") });
  const report = await fresh.run();
  assertEquals(report.checks.by_outcome, { mandate_customer_paused: 1 });
  assertEquals(fresh.store.attempts.length, 0);
  // Paused mandates are not asked for their payments.
  assertFalse(paused.calls.some((c) => c.url.endsWith("/payments")));
});

Deno.test("accounts: a mandate of an older account is retried through that account", async () => {
  const cf = new FakeCashfree();
  cf.owner = { "359414437": "old" };
  const withOld = setup({
    cf,
    now: ist("2026-09-28T07:00:00"),
    extraAccounts: JSON.stringify([{ name: "old", client_id: "old_id", client_secret: "old_secret" }]),
  });
  const report = await withOld.run();
  assertEquals(report.checks.by_account, { old: 1 });
  assertEquals(withOld.store.attempts[0].account, "old");
  assertEquals(cf.posts()[0].headers["x-client-id"], "old_id");

  // Without that account's credentials the mandate is recorded as not found and left alone.
  const cf2 = new FakeCashfree();
  cf2.owner = { "359414437": "old" };
  const primaryOnly = setup({ cf: cf2, now: ist("2026-09-28T07:00:00") });
  const r2 = await primaryOnly.run();
  assertEquals(r2.checks.by_outcome, { not_found_in_accounts: 1 });
  assertEquals(primaryOnly.store.checks.get(567939)?.outcome, "not_found_in_accounts");
  assertEquals(cf2.posts().length, 0);
});

Deno.test("only: another mandate of the same user is skipped", async () => {
  const { store, cf, run } = setup({ only: "mt_877432_1111111111111", now: ist("2026-09-28T07:00:00") });
  const report = await run();
  assertEquals(report.checks.examined, 0);
  assertEquals(store.writes, 0);
  assertEquals(cf.posts().length, 0);
});

Deno.test("backlog: a failure found three days late still gets 1 Oct (sent now) and 5 Oct (sent on 4 Oct)", async () => {
  const { store, cf, run } = setup({ now: ist("2026-09-30T12:00:00") });
  const report = await run();
  assertEquals(report.planned.skipped, { too_late: 1 });
  assertEquals(report.planned.attempts_by_day, { "2026-10-01": 1, "2026-10-05": 1 });
  assertEquals(store.attempts.map((a) => [a.attempt, a.status]), [[1, "skipped"], [2, "requested"], [3, "pending"]]);
  assertEquals(store.attempts[1].retry_scheduled_for, "2026-10-01");
  assertEquals(store.attempts[1].last_error, null);
  assertEquals(cf.posts().length, 1);
});

Deno.test("limiter: at most 3 in flight, starts spaced, nothing after a 429 or the deadline", async () => {
  let t = 0;
  const sleeps: number[] = [];
  const limiter = new Limiter(3, 300, 10_000, () => t, (ms) => {
    sleeps.push(ms);
    t += ms;
    return Promise.resolve();
  });
  let inFlight = 0;
  let peak = 0;
  await Promise.all(Array.from({ length: 6 }, () =>
    limiter.run(async () => {
      inFlight++;
      peak = Math.max(peak, inFlight);
      await Promise.resolve();
      inFlight--;
    })));
  assert(peak <= 3);
  assertEquals(limiter.requests, 6);
  assert(sleeps.every((ms) => ms > 0));
  limiter.throttled = true;
  assertEquals(await limiter.run(() => Promise.resolve(1)), null);
  const expired = new Limiter(3, 0, 5, () => 10);
  assertEquals(await expired.run(() => Promise.resolve(1)), null);
});

Deno.test("cashfreeAccounts: primary from app_config, extras from JSON, bad entries dropped", () => {
  const accounts: Account[] = cashfreeAccounts({
    cashfree_client_id: "p",
    cashfree_client_secret: "ps",
    cashfree_extra_accounts: JSON.stringify([
      { name: "old", client_id: "o", client_secret: "os" },
      { name: "primary", client_id: "x", client_secret: "y" },
      { name: "dupe", client_id: "p", client_secret: "z" },
      { name: "Bad Name", client_id: "b", client_secret: "c" },
      { name: "nosecret", client_id: "n" },
    ]),
  });
  assertEquals(accounts.map((a) => a.name), ["primary", "old"]);
  assertEquals(cashfreeAccounts({ cashfree_client_id: "p", cashfree_client_secret: "ps", cashfree_extra_accounts: "{not json" }).length, 1);
  assertEquals(cashfreeAccounts({}).length, 0);
});

Deno.test("webhook fast path: a recorded failure is planned at once and sent the day before its retry, with no legacy lookup", async () => {
  const store = new MemoryStore([]);
  store.recordFailure(FAILED_ID, INSUFFICIENT, 0);
  const cf = new FakeCashfree();
  const report = await setup({ store, cf, now: ist("2026-09-27T01:17:00") }).run();
  assertEquals(report.checks.by_source, { webhook: 1 });
  assertEquals(report.sends.requested, 0);
  assertEquals(store.attempts.map((a) => [a.scheduled_for, a.status]), [
    ["2026-09-29", "pending"],
    ["2026-10-01", "pending"],
    ["2026-10-05", "pending"],
  ]);
  assertEquals(store.failures[0].processed, "planned");
  assertFalse(cf.calls.some((c) => c.url.includes("/api/v2/")));
  assertEquals(cf.calls.map((c) => `${c.method} ${new URL(c.url).pathname}`), [
    `GET /pg/subscriptions/${MT}`,
    `GET /pg/subscriptions/${MT}/payments`,
  ]);

  // 28 Sep: the open chain sends attempt 1, for a debit on 29 Sep.
  const next = await setup({ store, cf, now: ist("2026-09-28T00:17:00") }).run();
  assertEquals(next.sends.requested, 1);
  assertEquals(store.attempts[0].retry_scheduled_for, "2026-09-29");
  assertEquals(cf.calls.slice(2).map((c) => `${c.method} ${new URL(c.url).pathname}`), [
    `GET /pg/subscriptions/${MT}`,
    `POST /pg/subscriptions/${MT}/payments/${FAILED_ID}/manage`,
  ]);

  // Processed failures are not looked at again; our own retry's failure (retry_attempts 1) is never a new chain.
  store.recordFailure("1353204_355_1790500000001", INSUFFICIENT, 1, "2026-09-29");
  const again = await setup({ store, cf, now: ist("2026-09-28T01:17:00") }).run();
  assertEquals(again.checks.examined, 0);
});

Deno.test("webhook fast path: dry run marks nothing processed", async () => {
  const store = new MemoryStore([]);
  store.recordFailure(FAILED_ID, INSUFFICIENT, 0);
  const report = await setup({ store, mode: "dry_run", now: ist("2026-09-28T01:17:00") }).run();
  assertEquals(report.sends.would_send, 1);
  assertEquals(store.failures[0].processed, undefined);
  assertEquals(store.writes, 0);
});

Deno.test("no webhook record: the retry's outcome is read at 22:00 IST, and the next retry still goes the day before its date", async () => {
  const store = new MemoryStore([CANDIDATE]);
  const cf = new FakeCashfree();
  await setup({ store, cf, now: ist("2026-09-28T07:00:00") }).run();
  const retryId = store.attempts[0].retry_payment_id!;
  cf.retryPayments.set(retryId, { ...cf.retryPayments.get(retryId), payment_status: "FAILED", failure_details: { failure_reason: INSUFFICIENT } });
  // Before 22:00 on the retry day: not read yet.
  const early = await setup({ store, cf, now: ist("2026-09-29T08:00:00") }).run();
  assertEquals(early.resolved, {});
  const late = await setup({ store, cf, now: ist("2026-09-29T22:17:00") }).run();
  assertEquals(late.resolved, { failed_insufficient_funds: 1 });
  assertEquals(store.attempts.map((a) => a.status), ["failed", "pending", "pending"]);
  const dayBefore = await setup({ store, cf, now: ist("2026-09-30T00:17:00") }).run();
  assertEquals(dayBefore.sends.requested, 1);
  assertEquals(store.attempts.map((a) => [a.status, a.retry_scheduled_for ?? null]), [
    ["failed", "2026-09-29"],
    ["requested", "2026-10-01"],
    ["pending", null],
  ]);
});

// ---- Mixpanel ----

/** Drops undefined values, as cleanProps does before sending. */
function sentProps(t: Tracked): MixpanelProps {
  return Object.fromEntries(Object.entries(pickEventProps(t.event, t.props)).filter(([, v]) => v !== undefined));
}

Deno.test("mixpanel: a live send tracks payment_retry_requested once, with allowlisted props only", async () => {
  const store = new MemoryStore([CANDIDATE]);
  const cf = new FakeCashfree();
  const first = setup({ store, cf, now: ist("2026-09-28T07:00:00") });
  await first.run();
  assertEquals(first.events.length, 1);
  const [t] = first.events;
  assertEquals(t.event, "payment_retry_requested");
  assertEquals(t.userId, 877432);
  assertEquals(t.key, `payment_retry_requested:${FAILED_ID}:1`);
  assertEquals(sentProps(t), {
    subscription_id: MT,
    attempt: 1,
    retry_date: "2026-09-29",
    failed_date: "2026-09-27",
    days_since_failure: 2,
    retry_number: 1,
    amount: 299,
    currency: "INR",
    cf_payment_id: "1200000001",
    failed_cf_payment_id: "1117138074",
  });
  // Every prop the builder sets is allowlisted (nothing is dropped).
  for (const k of Object.keys(t.props)) assert((SERVER_EVENT_PROPS.payment_retry_requested as readonly string[]).includes(k), k);

  // The next run in the same hour sends nothing new.
  const again = setup({ store, cf, now: ist("2026-09-28T08:00:00") });
  await again.run();
  assertEquals(again.events.length, 0);
});

Deno.test("mixpanel: outcomes are tracked once each: failed (will_retry, via webhook), then succeeded (payment recorded)", async () => {
  const store = new MemoryStore([CANDIDATE]);
  const cf = new FakeCashfree();
  await setup({ store, cf, now: ist("2026-09-28T07:00:00") }).run();
  store.recordFailure(store.attempts[0].retry_payment_id!, INSUFFICIENT, 1, "2026-09-29");

  const resolved = setup({ store, cf, now: ist("2026-09-29T02:00:00") });
  await resolved.run();
  assertEquals(resolved.events.map((e) => e.event), ["payment_retry_failed"]);
  assertEquals(sentProps(resolved.events[0]), {
    subscription_id: MT,
    attempt: 1,
    retry_date: "2026-09-29",
    failed_date: "2026-09-27",
    days_since_failure: 2,
    failure_reason: "insufficient_funds",
    will_retry: true,
    cf_payment_id: "1200000001",
    resolved_via: "webhook",
  });

  const sent = setup({ store, cf, now: ist("2026-09-30T02:00:00") });
  await sent.run();
  assertEquals(sent.events.map((e) => [e.event, e.props.attempt, e.props.retry_date]), [["payment_retry_requested", 2, "2026-10-01"]]);

  store.paid.push({ subscription_row_id: 567939, cf_payment_id: store.attempts[1].retry_cf_payment_id!, paid_at: "2026-09-30T19:00:00.000Z" });
  const won = setup({ store, cf, now: ist("2026-10-01T02:00:00") });
  await won.run();
  assertEquals(won.events.map((e) => e.event), ["payment_retry_succeeded"]);
  assertEquals(sentProps(won.events[0]), {
    subscription_id: MT,
    attempt: 2,
    retry_date: "2026-10-01",
    failed_date: "2026-09-27",
    days_since_failure: 4,
    amount: 299,
    currency: "INR",
    cf_payment_id: "1200000002",
    resolved_via: "payment_recorded",
  });
  assertEquals(won.events[0].key, retryEventKey("payment_retry_succeeded", store.attempts[1]));

  const later = setup({ store, cf, now: ist("2026-10-01T03:00:00") });
  await later.run();
  assertEquals(later.events.length, 0);
});

Deno.test("mixpanel: a retry failing for another reason says will_retry false", async () => {
  const store = new MemoryStore([CANDIDATE]);
  const cf = new FakeCashfree();
  await setup({ store, cf, now: ist("2026-09-28T07:00:00") }).run();
  store.recordFailure(store.attempts[0].retry_payment_id!, "Mandate revoked by customer", 1, "2026-09-29");
  const r = setup({ store, cf, now: ist("2026-09-29T02:00:00") });
  await r.run();
  assertEquals(r.events.map((e) => [e.event, e.props.failure_reason, e.props.will_retry]), [
    ["payment_retry_failed", "not_insufficient_funds", false],
  ]);
});

Deno.test("mixpanel: dry runs send nothing", async () => {
  const dry = setup({ mode: "dry_run", now: ist("2026-09-28T07:00:00") });
  const report = await dry.run();
  assertEquals(report.sends.would_send, 1);
  assertEquals(dry.events.length, 0);
});

Deno.test("daysSinceFailure: whole IST days, undefined when unknown or backwards", () => {
  assertEquals(daysSinceFailure("2026-09-27", "2026-10-05"), 8);
  assertEquals(daysSinceFailure("2026-09-27", "2026-09-29"), 2);
  assertEquals(daysSinceFailure("2026-12-30", "2027-01-05"), 6);
  assertEquals(daysSinceFailure("2026-09-27", "2026-09-26"), undefined);
  assertEquals(daysSinceFailure("", "2026-09-29"), undefined);
});

Deno.test("open chains: retries waiting for their day never crowd out today's sends", async () => {
  // 5 chains already requested for 2 Oct (waiting), 1 chain whose attempt for 3 Oct is due to go
  // out on 2 Oct. With room for only 2 chains of each kind, the 3 Oct send still goes.
  const store = new MemoryStore([]);
  const base = {
    user_id: 877432, account: "primary", merchant_subscription_id: MT, cf_subscription_id: "359414437",
    failed_cf_payment_id: null, failed_amount: 299, failed_on: "2026-09-30", status_reason: null,
  };
  for (let i = 0; i < 5; i++) {
    store.attempts.push({ ...base, id: 100 + i, subscription_row_id: 1000 + i, failed_payment_id: `waiting_${i}`, attempt: 1,
      scheduled_for: "2026-10-02", status: "requested", retry_payment_id: `r_${i}`, retry_scheduled_for: "2026-10-02",
      idempotency_key: `k_${i}` } as AttemptRecord);
  }
  store.attempts.push({ ...base, id: 200, subscription_row_id: 567939, failed_payment_id: FAILED_ID, failed_on: "2026-10-01",
    attempt: 1, scheduled_for: "2026-10-03", status: "pending", idempotency_key: "k_send" } as AttemptRecord);

  const loaded = await store.openChains({ limit: 2, pendingThrough: "2026-10-03", requestedThrough: "2026-10-02" });
  assert(loaded.some((a) => a.failed_payment_id === FAILED_ID));
  // The day before 2 Oct, nothing requested for 2 Oct is loaded yet.
  const early = await store.openChains({ limit: 2, pendingThrough: "2026-10-02", requestedThrough: "2026-10-01" });
  assertEquals(early.filter((a) => a.status === "requested").length, 0);

  const cf = new FakeCashfree();
  const r = await setup({ store, cf, now: ist("2026-10-02T09:00:00") }).run();
  assertEquals(r.sends.requested, 1);
  assertEquals(store.attempts.find((a) => a.id === 200)?.retry_scheduled_for, "2026-10-03");
});
