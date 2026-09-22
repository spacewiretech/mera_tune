export async function sha256(value: string): Promise<string> {
  const normalized = value.trim().toLowerCase();
  const hashBuffer = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(normalized),
  );
  return Array.from(new Uint8Array(hashBuffer))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

export function metaCredentials(config: Record<string, string>): {
  datasetId: string;
  accessToken: string;
} {
  return {
    datasetId: Deno.env.get("META_DATASET_ID")?.trim()
      ?? config.meta_dataset_id?.trim()
      ?? "",
    accessToken: Deno.env.get("META_CONVERSIONS_API_ACCESS_TOKEN")?.trim()
      ?? config.meta_conversions_api_access_token?.trim()
      ?? "",
  };
}

export function eventTimeFromIso(isoDate: string): number {
  const ms = Date.parse(isoDate);
  return Number.isNaN(ms) ? Math.floor(Date.now() / 1000) : Math.floor(ms / 1000);
}

export type MetaConversionEvent = {
  eventName: string;
  eventTime: number;
  eventId: string;
  externalId: string;
  customData?: Record<string, string | number>;
};

export async function trackMetaConversion(
  datasetId: string,
  accessToken: string,
  event: MetaConversionEvent,
): Promise<void> {
  if (!datasetId || !accessToken) {
    console.warn("Meta Conversions API credentials missing, skipping event:", event.eventName);
    return;
  }

  const hashedExternalId = await sha256(event.externalId);

  const response = await fetch(
    `https://graph.facebook.com/v21.0/${datasetId}/events?access_token=${accessToken}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        data: [{
          event_name: event.eventName,
          event_time: event.eventTime,
          event_id: event.eventId,
          action_source: "system_generated",
          user_data: {
            external_id: [hashedExternalId],
          },
          custom_data: event.customData,
        }],
      }),
    },
  );

  if (!response.ok) {
    console.error("Meta Conversions API failed:", event.eventName, await response.text());
  }
}
