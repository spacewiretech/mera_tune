/**
 * Cashfree webhook signature: Base64(HMAC-SHA256(x-webhook-timestamp + raw body)) keyed with the PG
 * client secret. Pure, so the verdict matrix is unit tested in tests/cashfree_webhook_test.ts.
 */
export type SignatureVerdict = "valid" | "invalid" | "malformed" | "missing_header" | "missing_secret";
export type SignatureMode = "enforce" | "log_only";

/** `app_config.cashfree_webhook_signature_mode`; anything but `log_only` (including missing) enforces. */
export function parseSignatureMode(value: string | null | undefined): SignatureMode {
  return value?.trim().toLowerCase() === "log_only" ? "log_only" : "enforce";
}

function decodeBase64(value: string): Uint8Array<ArrayBuffer> | null {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value)) return null;
  try {
    return Uint8Array.from(atob(value), (char) => char.charCodeAt(0));
  } catch {
    return null;
  }
}

/** Uses `crypto.subtle.verify`, which compares in constant time. */
export async function verifyCashfreeSignature(
  rawBody: string,
  timestamp: string,
  signature: string,
  secret: string,
): Promise<SignatureVerdict> {
  if (!secret) return "missing_secret";
  const ts = timestamp.trim();
  const sig = signature.trim();
  if (!ts || !sig) return "missing_header";

  const signatureBytes = decodeBase64(sig);
  if (!signatureBytes) return "malformed";
  if (signatureBytes.length !== 32) return "invalid";

  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const valid = await crypto.subtle.verify("HMAC", key, signatureBytes, encoder.encode(ts + rawBody));
  return valid ? "valid" : "invalid";
}

/**
 * A bad signature is rejected in every mode. A missing header or secret is rejected when
 * enforcing and let through (with an error log by the caller) during the `log_only` rollout.
 */
export function signatureDecision(verdict: SignatureVerdict, mode: SignatureMode): { accept: boolean } {
  if (verdict === "valid") return { accept: true };
  if (verdict === "missing_header" || verdict === "missing_secret") return { accept: mode === "log_only" };
  return { accept: false };
}

/**
 * Receive time minus the signed timestamp (epoch ms; seconds are normalised). Logged only: it is
 * unverified whether Cashfree re-signs its 2/10/30 min retries, so a window could drop real deliveries.
 */
export function timestampSkewMs(timestamp: string, nowMs = Date.now()): number | null {
  const trimmed = timestamp.trim();
  if (!trimmed) return null;
  const value = Number(trimmed);
  if (!Number.isFinite(value) || value <= 0) return null;
  return Math.round(nowMs - (value < 1e12 ? value * 1000 : value));
}
