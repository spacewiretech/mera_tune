import { sha256Hex } from "./user-sessions.ts";

export type MixpanelValue = string | number | boolean;
export type MixpanelProps = Record<string, MixpanelValue | null | undefined>;
export type MixpanelResult = { ok: boolean; error?: string };
/** Profile operations. No `$add`: /engage has no dedupe, so totals are `$set` from the DB. */
export type PeopleOps = { set?: MixpanelProps; setOnce?: MixpanelProps; unset?: string[] };
export type MixpanelRequestOptions = { timeoutMs?: number; fetchImpl?: typeof fetch };
export type TrackOptions = MixpanelRequestOptions & { insertId?: string; timeMs?: number };

export const MIXPANEL_TIMEOUT_MS = 4000;
export const IST_OFFSET_MS = 330 * 60 * 1000;

const MIXPANEL_API = "https://api.mixpanel.com";
const PROP_KEY = /^[a-z][a-z0-9_]{0,63}$/;
const RESERVED_KEYS = new Set(["token", "distinct_id", "time", "platform", "ip"]);
/** Defence in depth behind the per-event allowlists in subscription-analytics.ts. */
const DENIED_KEY = /(phone|email|upi_id|vpa|customer|refund_note|remark|address|ifsc|account_number|otp)/;
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;
const NAIVE_DATE_TIME = /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/;

/** Env secret first, then app_config. `||` so an empty env value still falls back. */
export function resolveMixpanelToken(
  config: Record<string, string> = {},
  envToken: string | undefined = Deno.env.get("MIXPANEL_TOKEN"),
): string {
  return (envToken ?? "").trim() || (config.mixpanel_token ?? "").trim();
}

/** Deterministic `$insert_id`: 32 hex chars of SHA-256 over the JSON of the parts. */
export async function mixpanelInsertId(
  ...parts: Array<string | number | boolean | null | undefined>
): Promise<string> {
  const hex = await sha256Hex(JSON.stringify(parts.map((part) => part ?? null)));
  return hex.slice(0, 32);
}

/**
 * Epoch ms of a Cashfree time. Cashfree mixes ISO with an offset and naive local times
 * (`2025-08-07T10:31:36`, `authorization_time`, `next_schedule_date`); naive values are IST.
 */
export function parseCashfreeTimeMs(value: unknown): number | null {
  if (typeof value === "number") {
    if (!Number.isFinite(value) || value <= 0) return null;
    return value < 1e12 ? value * 1000 : value;
  }
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (!trimmed) return null;
  let iso = trimmed;
  if (DATE_ONLY.test(trimmed)) iso = `${trimmed}T00:00:00+05:30`;
  else if (NAIVE_DATE_TIME.test(trimmed)) iso = `${trimmed.replace(" ", "T")}+05:30`;
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? null : ms;
}

function istParts(ms: number | null | undefined): [string, string, string] | null {
  if (ms == null || !Number.isFinite(ms)) return null;
  const shifted = new Date(ms + IST_OFFSET_MS);
  return [
    String(shifted.getUTCFullYear()),
    String(shifted.getUTCMonth() + 1).padStart(2, "0"),
    String(shifted.getUTCDate()).padStart(2, "0"),
  ];
}

/** `YYYY-MM` of the IST calendar month, or "" when unknown. */
export function istMonth(ms: number | null | undefined): string {
  const parts = istParts(ms);
  return parts ? `${parts[0]}-${parts[1]}` : "";
}

/** `YYYY-MM-DD` of the IST calendar day, or "" when unknown. */
export function istDate(ms: number | null | undefined): string {
  const parts = istParts(ms);
  return parts ? parts.join("-") : "";
}

export function billingMonthFromDate(isoDate: string): string {
  return istMonth(parseCashfreeTimeMs(isoDate));
}

/** Mixpanel's profile date format (UTC, no zone suffix). */
export function mixpanelDate(ms: number): string {
  return new Date(ms).toISOString().slice(0, 19);
}

/** Drops blank values, non-finite numbers, malformed/reserved keys and PII-looking keys. */
export function cleanProps(props: MixpanelProps): Record<string, MixpanelValue> {
  const cleaned: Record<string, MixpanelValue> = {};
  for (const [key, value] of Object.entries(props)) {
    if (!PROP_KEY.test(key) || RESERVED_KEYS.has(key) || DENIED_KEY.test(key)) continue;
    if (typeof value === "string") {
      const trimmed = value.trim();
      if (trimmed) cleaned[key] = trimmed.slice(0, 255);
    } else if (typeof value === "number") {
      if (Number.isFinite(value)) cleaned[key] = value;
    } else if (typeof value === "boolean") {
      cleaned[key] = value;
    }
  }
  return cleaned;
}

/** POSTs a JSON array and parses Mixpanel's verbose `{status, error}`. Never throws. */
async function postMixpanel(
  url: string,
  body: unknown,
  opts: MixpanelRequestOptions,
): Promise<MixpanelResult> {
  try {
    const response = await (opts.fetchImpl ?? fetch)(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(opts.timeoutMs ?? MIXPANEL_TIMEOUT_MS),
    });
    const text = await response.text();
    let status = 0;
    let error = "";
    try {
      const parsed = JSON.parse(text) as { status?: number; error?: string | null };
      status = Number(parsed.status ?? 0);
      error = parsed.error ?? "";
    } catch {
      status = text.trim() === "1" ? 1 : 0;
    }
    if (!response.ok || status !== 1) return { ok: false, error: error || `http_${response.status}` };
    return { ok: true };
  } catch (err) {
    const timedOut = err instanceof DOMException && err.name === "TimeoutError";
    return { ok: false, error: timedOut ? "timeout" : "network" };
  }
}

/**
 * Sends one server event. `time` is epoch ms (pass the Cashfree event time so retries dedupe on
 * `$insert_id`); `platform` is always "server". Never throws; properties are never logged.
 */
export async function trackMixpanelEvent(
  token: string,
  distinctId: string,
  event: string,
  properties: MixpanelProps,
  opts: TrackOptions = {},
): Promise<MixpanelResult> {
  if (!token) {
    console.warn("Mixpanel token missing, skipping event:", event);
    return { ok: false, error: "token_missing" };
  }
  if (!distinctId) {
    console.warn("Mixpanel distinct_id missing, skipping event:", event);
    return { ok: false, error: "distinct_id_missing" };
  }

  try {
    const time = Math.round(opts.timeMs ?? Date.now());
    const insertId = opts.insertId ?? await mixpanelInsertId(event, distinctId, time);
    const result = await postMixpanel(`${MIXPANEL_API}/track?ip=0&verbose=1`, [{
      event,
      properties: {
        ...cleanProps(properties),
        token,
        distinct_id: distinctId,
        time,
        platform: "server",
        $insert_id: insertId,
      },
    }], opts);
    if (!result.ok) console.error("Mixpanel track failed:", event, result.error);
    return result;
  } catch (err) {
    console.error("Mixpanel track failed:", event, err instanceof Error ? err.name : "unknown");
    return { ok: false, error: "internal" };
  }
}

/**
 * Profile update with `$ignore_time` (a 3 a.m. webhook must not bump `$last_seen`) and `ip=0`
 * (the edge datacenter must not overwrite the user's city). Never throws.
 */
export async function updateMixpanelPeople(
  token: string,
  distinctId: string,
  ops: PeopleOps,
  opts: MixpanelRequestOptions = {},
): Promise<MixpanelResult> {
  if (!token) return { ok: false, error: "token_missing" };
  if (!distinctId) return { ok: false, error: "distinct_id_missing" };

  const base = { $token: token, $distinct_id: distinctId, $ignore_time: true };
  const updates: Record<string, unknown>[] = [];
  const set = cleanProps(ops.set ?? {});
  if (Object.keys(set).length > 0) updates.push({ ...base, $set: set });
  const setOnce = cleanProps(ops.setOnce ?? {});
  if (Object.keys(setOnce).length > 0) updates.push({ ...base, $set_once: setOnce });
  const unset = (ops.unset ?? []).filter((key) => PROP_KEY.test(key));
  if (unset.length > 0) updates.push({ ...base, $unset: unset });
  if (updates.length === 0) return { ok: true };

  const result = await postMixpanel(`${MIXPANEL_API}/engage?ip=0&verbose=1`, updates, opts);
  if (!result.ok) console.error("Mixpanel engage failed:", result.error);
  return result;
}
