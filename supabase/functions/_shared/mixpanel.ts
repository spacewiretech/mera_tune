export type MixpanelTrackResult = {
  ok: boolean;
  error?: string;
};

export async function trackMixpanelEvent(
  token: string,
  distinctId: string,
  event: string,
  properties: Record<string, unknown>,
  insertId?: string,
): Promise<MixpanelTrackResult> {
  if (!token) {
    console.warn("Mixpanel token missing, skipping event:", event);
    return { ok: false, error: "token_missing" };
  }

  const props: Record<string, unknown> = {
    token,
    distinct_id: distinctId,
    time: Math.floor(Date.now() / 1000),
    platform: "server",
    ...properties,
  };

  if (insertId) {
    props.$insert_id = insertId;
  }

  const response = await fetch("https://api.mixpanel.com/track?verbose=1", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify([{ event, properties: props }]),
  });

  const body = await response.text();
  let mixpanelError = "";
  let status = 0;
  try {
    const parsed = JSON.parse(body) as { status?: number; error?: string | null };
    status = parsed.status ?? 0;
    mixpanelError = parsed.error ?? "";
  } catch {
    status = body.trim() === "1" ? 1 : 0;
    mixpanelError = body;
  }

  if (!response.ok || status !== 1) {
    const error = mixpanelError || `http_${response.status}`;
    console.error("Mixpanel track failed:", event, error);
    return { ok: false, error };
  }

  return { ok: true };
}

export async function setMixpanelPeople(
  token: string,
  distinctId: string,
  set: Record<string, unknown>,
): Promise<void> {
  if (!token) return;

  const response = await fetch("https://api.mixpanel.com/engage", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "text/plain",
    },
    body: JSON.stringify([{
      $token: token,
      $distinct_id: distinctId,
      $set: set,
    }]),
  });

  if (!response.ok) {
    console.error("Mixpanel people.set failed:", await response.text());
  }
}

export function billingMonthFromDate(isoDate: string): string {
  const date = new Date(isoDate);
  if (Number.isNaN(date.getTime())) return "";
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  return `${year}-${month}`;
}
