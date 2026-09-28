/**
 * Name sanitising for personalized ringtones.
 *
 * Mirrored by Kotlin `util/NameNormalizer.kt`; both implementations are driven by the shared
 * fixture `supabase/functions/tests/fixtures/name_normalization_cases.json`. Change the pipeline
 * here, add a fixture case, then update the Kotlin mirror.
 *
 * displayName(raw):
 *   1. NFC normalise
 *   2. collapse whitespace runs (tabs, newlines, ...) to one space
 *   3. strip emoji: \p{Extended_Pictographic}, \p{Regional_Indicator}, \p{Emoji_Modifier},
 *      VS16 (U+FE0F) and ZWSP (U+200B)
 *   4. pass 1: strip control chars (\p{Cc})
 *   5. pass 2: strip format chars (\p{Cf}) except ZWJ (U+200D) / ZWNJ (U+200C), which Indic
 *      scripts need inside a cluster. Two `u`-flag passes; no `v`-flag set subtraction.
 *   6. drop ZWJ/ZWNJ that are not between two letters/marks (left over from emoji sequences)
 *   7. collapse whitespace again, trim
 *
 * validateName(raw), checked in this order:
 *   EMPTY, TOO_LONG (> 30 code points), TOO_MANY_WORDS (> 3), INVALID_CHARS (anything outside
 *   [\p{L}\p{M} '’.-] + ZWJ/ZWNJ, or no letter at all), MIXED_SCRIPT (Latin mixed with any
 *   other script, or two different non-Latin scripts).
 *
 * normalizeForKey(display) = display.toLocaleLowerCase("en"); used for the render-cache key,
 * the blocklist and the sample-name short-circuit.
 *
 * spokenName(normalized) = what Gemini is asked to say (server only, no Kotlin mirror).
 */
import { isBlockedName } from "../_shared/name-blocklist.ts";

export const NAME_RULES = Object.freeze({
  maxCodePoints: 30,
  maxWords: 3,
  /** Letters, marks, space, straight/curly apostrophe, dot, hyphen, ZWNJ, ZWJ. */
  allowedChars: /^[\p{L}\p{M} '’.\-‌‍]+$/u,
  /** Scripts recognised for the single-script rule; anything else is one "Other" bucket. */
  scripts: Object.freeze([
    "Latin",
    "Devanagari",
    "Bengali",
    "Gurmukhi",
    "Gujarati",
    "Oriya",
    "Tamil",
    "Telugu",
    "Kannada",
    "Malayalam",
    "Sinhala",
    "Arabic",
    "Cyrillic",
    "Greek",
    "Hebrew",
    "Thai",
    "Han",
    "Hiragana",
    "Katakana",
    "Hangul",
    "Armenian",
    "Georgian",
    "Ethiopic",
    "Tibetan",
    "Myanmar",
    "Khmer",
    "Lao",
  ] as const),
});

export type NameRejectReason =
  | "EMPTY"
  | "TOO_LONG"
  | "TOO_MANY_WORDS"
  | "INVALID_CHARS"
  | "MIXED_SCRIPT";

export type NameValidation =
  | { valid: true; display: string; normalized: string }
  | { valid: false; reason: NameRejectReason };

const ZWNJ = "‌";
const ZWJ = "‍";

const WHITESPACE_RUN = /\s+/g;
const EMOJI = /[\p{Extended_Pictographic}\p{Regional_Indicator}\p{Emoji_Modifier}️​]/gu;
const CONTROL_CHARS = /\p{Cc}/gu;
const FORMAT_CHARS = /\p{Cf}/gu;
const EDGE_JOINERS = /(?<![\p{L}\p{M}])[‌‍]+|[‌‍]+(?![\p{L}\p{M}])/gu;
const HAS_LETTER = /\p{L}/u;
const LETTER = /\p{L}/u;

const SCRIPT_MATCHERS: ReadonlyArray<{ name: string; re: RegExp }> = NAME_RULES.scripts.map(
  (name) => ({ name, re: new RegExp(`\\p{Script=${name}}`, "u") }),
);

function stripControlAndFormat(value: string): string {
  const withoutControls = value.replace(CONTROL_CHARS, "");
  return withoutControls.replace(FORMAT_CHARS, (m) => (m === ZWNJ || m === ZWJ) ? m : "");
}

/** Cleaned form shown to the user and spoken by TTS. Empty string when nothing survives. */
export function displayName(raw: string): string {
  const stripped = stripControlAndFormat(
    raw.normalize("NFC").replace(WHITESPACE_RUN, " ").replace(EMOJI, ""),
  );
  return stripped.replace(EDGE_JOINERS, "").replace(WHITESPACE_RUN, " ").trim();
}

/** Cache / blocklist key. Locale pinned so results do not depend on the host locale. */
export function normalizeForKey(display: string): string {
  return display.toLocaleLowerCase("en");
}

const WORD_START_LOWER = /(^|[\s'’.\-])(\p{Ll})/gu;

/**
 * Canonical form sent to Gemini and stored in ringtone_renders.name_display. Renders are cached
 * across users by the lower-cased key, so the spoken text must be a function of that key alone;
 * otherwise the first caller's casing ("AYUSH") decides the audio for everyone ("Ayush").
 * Title-cases the key at word starts (after a space, apostrophe, dot or hyphen): "o'brien" ->
 * "O'Brien". Caseless scripts (Devanagari, Tamil, ...) have no \p{Ll}, so for them this returns
 * the key unchanged, which equals the display form. The per-user title keeps the display form.
 */
export function spokenName(normalized: string): string {
  return normalized.replace(
    WORD_START_LOWER,
    (_match, prefix: string, letter: string) => prefix + letter.toLocaleUpperCase("en"),
  );
}

export function codePointLength(value: string): number {
  let count = 0;
  for (const _ of value) count++;
  return count;
}

function scriptOf(char: string): string {
  for (const matcher of SCRIPT_MATCHERS) {
    if (matcher.re.test(char)) return matcher.name;
  }
  return "Other";
}

/** True when letters come from more than one script, or Latin is mixed with anything else. */
export function hasMixedScripts(display: string): boolean {
  const scripts = new Set<string>();
  for (const char of display) {
    if (!LETTER.test(char)) continue;
    scripts.add(scriptOf(char));
    if (scripts.size > 1) return true;
  }
  return false;
}

export function validateName(raw: string): NameValidation {
  const display = displayName(raw);
  if (!display) return { valid: false, reason: "EMPTY" };
  if (codePointLength(display) > NAME_RULES.maxCodePoints) return { valid: false, reason: "TOO_LONG" };
  if (display.split(" ").length > NAME_RULES.maxWords) return { valid: false, reason: "TOO_MANY_WORDS" };
  if (!NAME_RULES.allowedChars.test(display) || !HAS_LETTER.test(display)) {
    return { valid: false, reason: "INVALID_CHARS" };
  }
  if (hasMixedScripts(display)) return { valid: false, reason: "MIXED_SCRIPT" };
  return { valid: true, display, normalized: normalizeForKey(display) };
}

export type SanitizedName =
  | { ok: true; display: string; normalized: string }
  | {
    ok: false;
    code: "INVALID_NAME" | "NAME_REJECTED";
    reason: NameRejectReason | "BLOCKLISTED";
    message: string;
  };

const REASON_MESSAGES: Record<NameRejectReason, string> = {
  EMPTY: "Please enter a name",
  TOO_LONG: `Name must be at most ${NAME_RULES.maxCodePoints} characters`,
  TOO_MANY_WORDS: `Name must be at most ${NAME_RULES.maxWords} words`,
  INVALID_CHARS: "Name can only contain letters",
  MIXED_SCRIPT: "Please write the name in a single script",
};

/**
 * Request-level entry point: validation plus blocklist. Never log the input or the display form.
 */
export function sanitizeName(raw: unknown): SanitizedName {
  if (typeof raw !== "string") {
    return { ok: false, code: "INVALID_NAME", reason: "EMPTY", message: REASON_MESSAGES.EMPTY };
  }
  const result = validateName(raw);
  if (!result.valid) {
    return { ok: false, code: "INVALID_NAME", reason: result.reason, message: REASON_MESSAGES[result.reason] };
  }
  if (isBlockedName(result.normalized)) {
    return { ok: false, code: "NAME_REJECTED", reason: "BLOCKLISTED", message: "This name cannot be used" };
  }
  return { ok: true, display: result.display, normalized: result.normalized };
}

/** Same pipeline applied to an authored value such as tune.sample_name. */
export function normalizeAuthoredName(raw: string | null | undefined): string {
  if (!raw) return "";
  return normalizeForKey(displayName(raw));
}

/**
 * Per-user ringtone title from tune.title_template: every `{name}` is replaced by the display
 * form, a template without the placeholder gets the name appended. Null without a template.
 */
export function buildTitle(template: string | null | undefined, display: string): string | null {
  const trimmed = template?.trim();
  if (!trimmed) return null;
  return trimmed.includes("{name}") ? trimmed.split("{name}").join(display) : `${trimmed} ${display}`;
}
