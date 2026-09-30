/**
 * Cashfree calls for the retry job. Every call goes through a limiter: at most `concurrency` in
 * flight, starts at least `minIntervalMs` apart, and nothing new after a 429 or the deadline.
 * create-subscription and verify-subscription share the merchant's limits.
 *
 * The DB stores only the numeric cf_subscription_id, and the 2025-01-01 API addresses a mandate by
 * the merchant id (`mt_<user>_<ms>`), so a mandate seen for the first time is looked up through the
 * legacy read API (`/api/v2/subscriptions/{cf_subscription_id}`), which returns it. That API allows
 * about 60 requests a minute per IP (measured 2026-09-30), so it has its own slower limiter, and a
 * 429 there stops only lookups. Everything else, including RETRY, is the 2025-01-01 API.
 */
import { type CfMandate, type CfPayment, cashfreeDay, retryBody, upper } from "./policy.ts";

export type Account = { name: string; clientId: string; clientSecret: string };

export type CfCall<T> =
  | { ok: true; status: number; body: T }
  | { ok: false; status: number; code: string; throttled: boolean; stopped: boolean; body?: unknown };

const REQUEST_TIMEOUT_MS = 15_000;

export class Limiter {
  throttled = false;
  requests = 0;
  private active = 0;
  private nextStartMs = 0;
  private waiters: Array<() => void> = [];

  constructor(
    readonly concurrency: number,
    readonly minIntervalMs: number,
    readonly deadlineMs: number,
    private readonly now: () => number = Date.now,
    private readonly sleep: (ms: number) => Promise<void> = (ms) => new Promise((ok) => setTimeout(ok, ms)),
  ) {}

  /** False once throttled or past the deadline: callers stop starting work. */
  get open(): boolean {
    return !this.throttled && this.now() < this.deadlineMs;
  }

  async run<T>(fn: () => Promise<T>): Promise<T | null> {
    while (this.active >= this.concurrency) await new Promise<void>((ok) => this.waiters.push(ok));
    this.active++;
    try {
      const wait = this.nextStartMs - this.now();
      this.nextStartMs = Math.max(this.now(), this.nextStartMs) + this.minIntervalMs;
      if (wait > 0) await this.sleep(wait);
      if (!this.open) return null;
      this.requests++;
      return await fn();
    } finally {
      this.active--;
      this.waiters.shift()?.();
    }
  }
}

function text(value: unknown): string {
  if (typeof value === "string") return value.trim();
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return "";
}

function num(value: unknown): number | null {
  const n = typeof value === "number" ? value : typeof value === "string" && value.trim() ? Number(value) : NaN;
  return Number.isFinite(n) ? n : null;
}

/** 2025-01-01 subscription entity. */
export function mandateFromPg(body: Record<string, unknown>): CfMandate {
  const plan = (body.plan_details ?? {}) as Record<string, unknown>;
  return {
    merchantSubscriptionId: text(body.subscription_id),
    status: upper(body.subscription_status),
    nextChargeOn: cashfreeDay(body.next_schedule_date),
    recurringAmount: num(plan.plan_recurring_amount),
  };
}

/** Legacy `/api/v2/subscriptions/{subReferenceId}` entity (camelCase, naive IST times). */
export function mandateFromV2(body: Record<string, unknown>): CfMandate | null {
  const sub = body.subscription as Record<string, unknown> | undefined;
  if (!sub || typeof sub !== "object") return null;
  return {
    merchantSubscriptionId: text(sub.subscriptionId),
    status: upper(sub.status),
    nextChargeOn: cashfreeDay(sub.scheduledOn),
    recurringAmount: num(sub.recurringAmount),
  };
}

/** The legacy API answers 400 "Subscription doesn't exist" for a mandate of another account. */
export function isNotFound(status: number, body: unknown): boolean {
  if (status !== 400 && status !== 404) return false;
  const b = (body ?? {}) as Record<string, unknown>;
  return /exist|not.?found/i.test(`${text(b.message)} ${text(b.code)}`);
}

function errorCode(status: number, body: unknown): string {
  const b = (body ?? {}) as Record<string, unknown>;
  const code = text(b.code).toLowerCase().replace(/[^a-z0-9_]+/g, "_").slice(0, 40);
  return code || `http_${status}`;
}

export type CashfreeOptions = {
  environment: string;
  apiVersion: string;
  accounts: Account[];
  /** 2025-01-01 API calls; a 429 here stops the whole run. */
  limiter: Limiter;
  /** Legacy lookups (one per mandate the job has not seen); a 429 here stops only lookups. */
  lookupLimiter: Limiter;
  fetchImpl?: typeof fetch;
};

export class CashfreeClient {
  private readonly pgBase: string;
  private readonly v2Base: string;
  private readonly fetchImpl: typeof fetch;

  constructor(private readonly opts: CashfreeOptions) {
    const prod = opts.environment === "production";
    this.pgBase = prod ? "https://api.cashfree.com/pg" : "https://sandbox.cashfree.com/pg";
    this.v2Base = prod ? "https://api.cashfree.com/api/v2" : "https://test.cashfree.com/api/v2";
    this.fetchImpl = opts.fetchImpl ?? fetch;
  }

  get accounts(): Account[] {
    return this.opts.accounts;
  }

  account(name: string | null | undefined): Account | undefined {
    return this.opts.accounts.find((a) => a.name === (name || "primary"));
  }

  private async call<T>(url: string, init: RequestInit, limiter = this.opts.limiter): Promise<CfCall<T>> {
    const result = await limiter.run(async (): Promise<CfCall<T>> => {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
      try {
        const res = await this.fetchImpl(url, { ...init, signal: controller.signal });
        const body = await res.json().catch(() => null);
        if (res.status === 429) limiter.throttled = true;
        if (res.ok) return { ok: true, status: res.status, body: body as T };
        return { ok: false, status: res.status, code: errorCode(res.status, body), throttled: res.status === 429, stopped: false, body };
      } catch (err) {
        return { ok: false, status: 0, code: err instanceof Error && err.name === "AbortError" ? "timeout" : "network", throttled: false, stopped: false };
      } finally {
        clearTimeout(timer);
      }
    });
    return result ?? { ok: false, status: 0, code: limiter.throttled ? "throttled" : "deadline", throttled: limiter.throttled, stopped: true };
  }

  private pgHeaders(account: Account, extra: Record<string, string> = {}): Record<string, string> {
    return {
      "x-client-id": account.clientId,
      "x-client-secret": account.clientSecret,
      "x-api-version": this.opts.apiVersion,
      ...extra,
    };
  }

  /**
   * Finds a mandate by its numeric id in each account in turn (the preferred one first). `notFound`
   * only when every account answered that it does not exist.
   */
  async lookupByCfId(
    cfSubscriptionId: string,
    preferred?: string | null,
  ): Promise<{ account: Account; mandate: CfMandate } | { notFound: true } | { error: Extract<CfCall<unknown>, { ok: false }> }> {
    const ordered = [...this.opts.accounts].sort((a, b) => Number(b.name === preferred) - Number(a.name === preferred));
    for (const account of ordered) {
      const res = await this.call<Record<string, unknown>>(
        `${this.v2Base}/subscriptions/${encodeURIComponent(cfSubscriptionId)}`,
        { method: "GET", headers: { "X-Client-Id": account.clientId, "X-Client-Secret": account.clientSecret } },
        this.opts.lookupLimiter,
      );
      if (res.ok) {
        const mandate = mandateFromV2(res.body);
        if (mandate?.merchantSubscriptionId) return { account, mandate };
        return { error: { ok: false, status: res.status, code: "unreadable_mandate", throttled: false, stopped: false } };
      }
      if (!isNotFound(res.status, res.body)) return { error: res };
    }
    return { notFound: true };
  }

  /** A mandate by merchant id (from a webhook), in each account in turn, the preferred one first. */
  async mandateInAccounts(
    subscriptionId: string,
    preferred?: string | null,
  ): Promise<{ account: Account; mandate: CfMandate } | { notFound: true } | { error: Extract<CfCall<unknown>, { ok: false }> }> {
    const ordered = [...this.opts.accounts].sort((a, b) => Number(b.name === preferred) - Number(a.name === preferred));
    for (const account of ordered) {
      const res = await this.mandate(account, subscriptionId);
      if (res.ok) return { account, mandate: res.body };
      if (!isNotFound(res.status, res.body)) return { error: res };
    }
    return { notFound: true };
  }

  async mandate(account: Account, subscriptionId: string): Promise<CfCall<CfMandate>> {
    const res = await this.call<Record<string, unknown>>(
      `${this.pgBase}/subscriptions/${encodeURIComponent(subscriptionId)}`,
      { method: "GET", headers: this.pgHeaders(account) },
    );
    return res.ok ? { ...res, body: mandateFromPg(res.body ?? {}) } : res;
  }

  async payments(account: Account, subscriptionId: string): Promise<CfCall<CfPayment[]>> {
    const res = await this.call<unknown>(
      `${this.pgBase}/subscriptions/${encodeURIComponent(subscriptionId)}/payments`,
      { method: "GET", headers: this.pgHeaders(account) },
    );
    return res.ok ? { ...res, body: Array.isArray(res.body) ? res.body as CfPayment[] : [] } : res;
  }

  async payment(account: Account, subscriptionId: string, paymentId: string): Promise<CfCall<CfPayment>> {
    return await this.call<CfPayment>(
      `${this.pgBase}/subscriptions/${encodeURIComponent(subscriptionId)}/payments/${encodeURIComponent(paymentId)}`,
      { method: "GET", headers: this.pgHeaders(account) },
    );
  }

  /** RETRY of a failed charge. No amount: Cashfree charges the mandate's own plan. */
  async retry(
    account: Account,
    subscriptionId: string,
    paymentId: string,
    day: string,
    idempotencyKey: string,
  ): Promise<CfCall<CfPayment>> {
    return await this.call<CfPayment>(
      `${this.pgBase}/subscriptions/${encodeURIComponent(subscriptionId)}/payments/${encodeURIComponent(paymentId)}/manage`,
      {
        method: "POST",
        headers: this.pgHeaders(account, { "Content-Type": "application/json", "x-idempotency-key": idempotencyKey }),
        body: JSON.stringify(retryBody(subscriptionId, paymentId, day)),
      },
    );
  }
}

/**
 * The app's Cashfree account plus any older ones in `cashfree_extra_accounts` (JSON list of
 * `{name, client_id, client_secret}`): mandates created under old credentials are retried there.
 */
export function cashfreeAccounts(config: Record<string, string | undefined>): Account[] {
  const accounts: Account[] = [];
  const clientId = text(config.cashfree_client_id);
  const clientSecret = text(config.cashfree_client_secret);
  if (clientId && clientSecret) accounts.push({ name: "primary", clientId, clientSecret });
  try {
    const extra = JSON.parse(config.cashfree_extra_accounts || "[]");
    if (Array.isArray(extra)) {
      for (const entry of extra) {
        const name = text(entry?.name).toLowerCase();
        const id = text(entry?.client_id);
        const secret = text(entry?.client_secret);
        if (!/^[a-z0-9_]{1,32}$/.test(name) || name === "primary" || !id || !secret) continue;
        if (accounts.some((a) => a.name === name || a.clientId === id)) continue;
        accounts.push({ name, clientId: id, clientSecret: secret });
      }
    }
  } catch {
    console.error("retry-failed-charges: cashfree_extra_accounts is not valid JSON; using the primary account only");
  }
  return accounts;
}
