/** Strings, never null: the app's `Category` model has non-null defaults and rejects explicit null. */
export type TuneCategory = { id: string; name: string; image_url: string };

/** A `category:category_id(id, name, image_url)` embed (object, one-element array or null). */
export function normalizeCategory(raw: unknown): TuneCategory | null {
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (!value || typeof value !== "object") return null;
  const record = value as Record<string, unknown>;
  if (record.id === undefined || record.id === null) return null;
  return {
    id: String(record.id),
    name: record.name === undefined || record.name === null ? "" : String(record.name),
    image_url: typeof record.image_url === "string" ? record.image_url : "",
  };
}
