/**
 * Pure helpers of name-ringtones: request parsing, row normalization, filtering, dedupe and the
 * response row shape. No I/O, so they are unit tested in supabase/functions/tests/name_ringtones_test.ts.
 *
 * Name mode lists what generate-ringtone would hand this caller from cache for the typed name:
 *   - "catalog": active personalizable tunes whose stock recording already sings the name
 *     (normalizeAuthoredName(sample_name) === key, generate-ringtone's sample-name short-circuit);
 *   - "render": READY ringtone_renders of the name on the tune's current cache key (same
 *     assets_version and tts_voice as the tune now), for active personalizable tunes.
 * So posting generate-ringtone with a row's tune id and the same name is a cached hit (no Gemini
 * call, no quota), which is how the app records the choice in the caller's own list.
 * Mine mode lists the caller's READY generated_ringtones.
 *
 * Output never carries a user id; generation_id is only ever one of the caller's own rows.
 */
import { ApiError } from "../generate-ringtone/errors.ts";
import { buildTitle, normalizeAuthoredName } from "../generate-ringtone/names.ts";
import type { AuthMode } from "../generate-ringtone/quota.ts";
import { type Credentials, parseCredentials } from "../generate-ringtone/request.ts";
import { normalizeVoiceGender } from "../_shared/tts-voices.ts";
import { normalizeCategory, type TuneCategory } from "../_shared/tune-category.ts";

export const NAME_MODE_MAX_ROWS = 20;
export const MINE_MODE_MAX_ROWS = 50;

/** Columns read from tune. tts_voice_name is only used for the cache-key check, never returned. */
export const TUNE_COLUMNS = [
  "id",
  "name",
  "category_id",
  "gender",
  "language",
  "tune_url",
  "is_active",
  "is_personalizable",
  "featured_rank",
  "sample_name",
  "title_template",
  "assets_version",
  "tts_voice_name",
  "likes_count",
  "views_count",
  "category:category_id(id, name, image_url)",
].join(", ");

export const RENDER_COLUMNS = "id, tune_id, assets_version, tts_voice, voice, language, public_url, created_at";
export const GENERATION_COLUMNS = "id, tune_id, render_id, name_display, name_normalized, language, title, created_at";

export type Mode = "name" | "mine";

export type NameRingtonesRequest = {
  credentials: Credentials;
  mode: Mode;
  /** Raw `name`; validated with generate-ringtone's sanitizeName in name mode. */
  name: unknown;
  limit: number;
};

export type TuneRow = {
  id: string;
  name: string;
  category_id: string;
  gender: string;
  language: string;
  tune_url: string;
  is_active: boolean;
  is_personalizable: boolean;
  featured_rank: number | null;
  sample_name: string | null;
  title_template: string | null;
  assets_version: number;
  tts_voice_name: string | null;
  likes_count: number;
  views_count: number;
  category: TuneCategory | null;
};

/** The tune as the app's `Tune` model decodes it: strings never null, no authoring columns. */
export type PublicTune = Omit<TuneRow, "tts_voice_name">;

export type RenderRow = {
  id: string;
  tune_id: string;
  assets_version: number | null;
  tts_voice: string;
  voice: string;
  language: string;
  public_url: string;
  created_at: string | null;
};

export type GenerationRow = {
  id: string;
  tune_id: string;
  render_id: string | null;
  name_display: string;
  name_normalized: string;
  language: string;
  title: string | null;
  created_at: string | null;
};

export type RingtoneSource = "catalog" | "render";

export type RingtoneOut = {
  tune: PublicTune;
  /** Display title of the personalized ringtone. */
  title: string;
  ringtone_url: string;
  render_id: string | null;
  /** The caller's own generated_ringtones.id for this ringtone, else null. */
  generation_id: string | null;
  /** `male` / `female`, `""` when the tune gender is neither. */
  voice: string;
  language: string;
  source: RingtoneSource;
  created_at: string | null;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function text(value: unknown): string {
  return value === undefined || value === null ? "" : String(value);
}

function nullableText(value: unknown): string | null {
  if (typeof value !== "string") return null;
  return value.trim() ? value : null;
}

function integer(value: unknown): number | null {
  const parsed = typeof value === "number" ? value : typeof value === "string" ? Number(value) : Number.NaN;
  return Number.isInteger(parsed) ? parsed : null;
}

/** `limit` clamped to 1..max; missing or invalid -> max. */
export function clampLimit(raw: unknown, max: number): number {
  const parsed = integer(raw);
  if (parsed === null || parsed <= 0) return max;
  return Math.min(parsed, max);
}

export function parseRequest(raw: unknown): NameRingtonesRequest {
  if (!isRecord(raw)) throw new ApiError(400, "INVALID_REQUEST", "Request body must be a JSON object");
  const credentials = parseCredentials(raw);
  if (raw.mine !== undefined && raw.mine !== null && typeof raw.mine !== "boolean") {
    throw new ApiError(400, "INVALID_REQUEST", "mine must be a boolean");
  }
  const mode: Mode = raw.mine === true ? "mine" : "name";
  return {
    credentials,
    mode,
    name: raw.name,
    limit: clampLimit(raw.limit, mode === "mine" ? MINE_MODE_MAX_ROWS : NAME_MODE_MAX_ROWS),
  };
}

/**
 * Whether this caller may read their own generated_ringtones (which carry the names they typed,
 * generation ids and file URLs). Only a session token proves who the caller is: a bare legacy
 * `user_id` (generate_allow_legacy_user_id) is guessable and the anon key ships in the APK. So a
 * legacy caller gets 401 in mine mode, and in name mode only the shared catalog / render rows,
 * with no generation ids. generate-ringtone keeps accepting it (it acts as a user, reads nothing).
 */
export function readsOwnRows(mode: Mode, authMode: AuthMode): boolean {
  if (authMode === "token") return true;
  if (mode === "mine") throw new ApiError(401, "UNAUTHORIZED", "Please log in again");
  return false;
}

export function toTuneRow(raw: unknown): TuneRow | null {
  if (!isRecord(raw) || raw.id === undefined || raw.id === null) return null;
  return {
    id: String(raw.id),
    name: text(raw.name),
    category_id: text(raw.category_id),
    gender: text(raw.gender),
    language: text(raw.language),
    tune_url: text(raw.tune_url),
    is_active: raw.is_active !== false,
    is_personalizable: raw.is_personalizable === true,
    featured_rank: integer(raw.featured_rank),
    sample_name: nullableText(raw.sample_name),
    title_template: nullableText(raw.title_template),
    assets_version: integer(raw.assets_version) ?? 1,
    tts_voice_name: nullableText(raw.tts_voice_name),
    likes_count: integer(raw.likes_count) ?? 0,
    views_count: integer(raw.views_count) ?? 0,
    category: normalizeCategory(raw.category),
  };
}

export function toRenderRow(raw: unknown): RenderRow | null {
  if (!isRecord(raw) || !raw.id || !raw.tune_id) return null;
  const publicUrl = text(raw.public_url).trim();
  if (!publicUrl) return null;
  return {
    id: String(raw.id),
    tune_id: String(raw.tune_id),
    assets_version: integer(raw.assets_version),
    tts_voice: text(raw.tts_voice),
    voice: text(raw.voice),
    language: text(raw.language),
    public_url: publicUrl,
    created_at: nullableText(raw.created_at),
  };
}

export function toGenerationRow(raw: unknown): GenerationRow | null {
  if (!isRecord(raw) || !raw.id || !raw.tune_id) return null;
  return {
    id: String(raw.id),
    tune_id: String(raw.tune_id),
    render_id: raw.render_id ? String(raw.render_id) : null,
    name_display: text(raw.name_display),
    name_normalized: text(raw.name_normalized),
    language: text(raw.language),
    title: nullableText(raw.title),
    created_at: nullableText(raw.created_at),
  };
}

/** Keeps the entries that normalize; drops malformed ones. */
export function normalizeRows<T>(rows: unknown, map: (raw: unknown) => T | null): T[] {
  if (!Array.isArray(rows)) return [];
  return rows.map(map).filter((row): row is T => row !== null);
}

export function publicTune(tune: TuneRow): PublicTune {
  const { tts_voice_name: _voiceName, ...rest } = tune;
  return rest;
}

export function voiceKey(gender: string | null | undefined): string {
  return normalizeVoiceGender(gender) ?? "";
}

/** The render is what generate-ringtone's cache lookup would find for this tune today. */
export function isCurrentRender(render: RenderRow, tune: TuneRow): boolean {
  return render.assets_version === tune.assets_version &&
    tune.tts_voice_name !== null &&
    render.tts_voice === tune.tts_voice_name;
}

/** Tunes whose stock recording sings `nameKey` (the sanitizeName `normalized` key). */
export function sampleMatchIds(rows: ReadonlyArray<{ id: unknown; sample_name: unknown }>, nameKey: string): string[] {
  if (!nameKey) return [];
  return rows
    .filter((row) => typeof row.sample_name === "string" && normalizeAuthoredName(row.sample_name) === nameKey)
    .map((row) => String(row.id));
}

/** featured_rank ascending, unranked last, then title; the order of the catalog rows. */
function compareCatalog(a: TuneRow, b: TuneRow): number {
  const rankA = a.featured_rank ?? Number.POSITIVE_INFINITY;
  const rankB = b.featured_rank ?? Number.POSITIVE_INFINITY;
  if (rankA !== rankB) return rankA < rankB ? -1 : 1;
  return a.name.localeCompare(b.name);
}

function timeOf(iso: string | null): number {
  const parsed = iso === null ? Number.NaN : Date.parse(iso);
  return Number.isNaN(parsed) ? Number.NEGATIVE_INFINITY : parsed;
}

/** created_at descending, missing or unparsable last. Stable for ties. */
function newestFirst<T extends { created_at: string | null }>(a: T, b: T): number {
  const ta = timeOf(a.created_at);
  const tb = timeOf(b.created_at);
  return ta === tb ? 0 : ta < tb ? 1 : -1;
}

/** Newest row per key; `rows` in any order. */
function newestBy<T extends { created_at: string | null }>(
  rows: ReadonlyArray<T>,
  key: (row: T) => string | null,
): Map<string, T> {
  const out = new Map<string, T>();
  for (const row of [...rows].sort(newestFirst)) {
    const k = key(row);
    if (k !== null && !out.has(k)) out.set(k, row);
  }
  return out;
}

export type NameModeInput = {
  /** sanitizeName(...).display: the caller's cleaned name, used for built titles. */
  display: string;
  /** Output of sampleMatchIds. */
  sampleTuneIds: ReadonlyArray<string>;
  renders: ReadonlyArray<RenderRow>;
  tunesById: ReadonlyMap<string, TuneRow>;
  /** The caller's own READY generated_ringtones for this name key. */
  ownGenerations: ReadonlyArray<GenerationRow>;
  limit: number;
};

/**
 * Catalog rows first (featured_rank, then title), then renders newest first; one row per
 * (tune_id, voice). Title: the caller's own stored title, else generate-ringtone's title for the
 * caller's display name, else the display name.
 */
export function nameModeRows(input: NameModeInput): RingtoneOut[] {
  const ownByRender = newestBy(input.ownGenerations, (row) => row.render_id);
  const ownSampleByTune = newestBy(input.ownGenerations, (row) => row.render_id === null ? row.tune_id : null);
  const eligible = (tuneId: string): TuneRow | null => {
    const tune = input.tunesById.get(tuneId);
    return tune && tune.is_active && tune.is_personalizable ? tune : null;
  };
  const titleFor = (own: GenerationRow | undefined, tune: TuneRow): string =>
    own?.title?.trim() || buildTitle(tune.title_template, input.display) || input.display;

  const out: RingtoneOut[] = [];
  const seen = new Set<string>();
  const push = (row: RingtoneOut) => {
    const key = `${row.tune.id}|${row.voice}`;
    if (seen.has(key)) return;
    seen.add(key);
    out.push(row);
  };

  const catalog = [...new Set(input.sampleTuneIds)]
    .map(eligible)
    .filter((tune): tune is TuneRow => tune !== null && tune.tune_url.trim() !== "")
    .sort(compareCatalog);
  for (const tune of catalog) {
    const own = ownSampleByTune.get(tune.id);
    push({
      tune: publicTune(tune),
      title: titleFor(own, tune),
      ringtone_url: tune.tune_url,
      render_id: null,
      generation_id: own?.id ?? null,
      voice: voiceKey(tune.gender),
      language: tune.language,
      source: "catalog",
      created_at: own?.created_at ?? null,
    });
  }

  for (const render of [...input.renders].sort(newestFirst)) {
    const tune = eligible(render.tune_id);
    if (!tune || !isCurrentRender(render, tune)) continue;
    const own = ownByRender.get(render.id);
    push({
      tune: publicTune(tune),
      title: titleFor(own, tune),
      ringtone_url: render.public_url,
      render_id: render.id,
      generation_id: own?.id ?? null,
      voice: voiceKey(render.voice || tune.gender),
      language: render.language || tune.language,
      source: "render",
      created_at: render.created_at,
    });
  }

  return out.slice(0, Math.max(0, input.limit));
}

export type MineModeInput = {
  /** The caller's READY generated_ringtones. */
  generations: ReadonlyArray<GenerationRow>;
  /** READY renders by id (public_url set). */
  rendersById: ReadonlyMap<string, RenderRow>;
  tunesById: ReadonlyMap<string, TuneRow>;
  limit: number;
};

/**
 * Newest first, one row per (tune_id, name key): repeat generations of the same name on the same
 * song (retries, cache hits) are one ringtone. Sample-name rows (no render) play the stock tune_url.
 * Rows of inactive tunes, or whose render is gone, are skipped.
 */
export function mineModeRows(input: MineModeInput): RingtoneOut[] {
  const out: RingtoneOut[] = [];
  const seen = new Set<string>();
  for (const generation of [...input.generations].sort(newestFirst)) {
    if (out.length >= input.limit) break;
    const tune = input.tunesById.get(generation.tune_id);
    if (!tune || !tune.is_active) continue;
    const render = generation.render_id ? input.rendersById.get(generation.render_id) : undefined;
    const url = generation.render_id ? render?.public_url ?? "" : tune.tune_url;
    if (!url.trim()) continue;
    const key = `${generation.tune_id}|${generation.name_normalized}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({
      tune: publicTune(tune),
      title: generation.title?.trim() || buildTitle(tune.title_template, generation.name_display) ||
        generation.name_display || tune.name,
      ringtone_url: url,
      render_id: generation.render_id,
      generation_id: generation.id,
      voice: voiceKey(render?.voice || tune.gender),
      language: generation.language || tune.language,
      source: generation.render_id ? "render" : "catalog",
      created_at: generation.created_at,
    });
  }
  return out;
}

export function uniqueIds(ids: ReadonlyArray<string | null | undefined>): string[] {
  return [...new Set(ids.filter((id): id is string => typeof id === "string" && id !== ""))];
}
