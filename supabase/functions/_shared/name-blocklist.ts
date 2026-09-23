/**
 * Starter blocklist for personalized-ringtone names. Matched against the normalized name
 * (lower-cased, whitespace removed) and against each normalized word, exact match only, so
 * ordinary names that merely contain a blocked substring (e.g. "Assam") are not rejected.
 *
 * The team extends this list from moderation reports; keep entries lower-case and ASCII where
 * possible (Hinglish spellings, plus Devanagari where a term is usually typed that way).
 * Names hitting the list return 400 NAME_REJECTED. The name itself is never logged.
 */
export const NAME_BLOCKLIST: ReadonlySet<string> = new Set<string>([
  // English
  "fuck",
  "fucker",
  "motherfucker",
  "shit",
  "bitch",
  "asshole",
  "bastard",
  "cunt",
  "dick",
  "pussy",
  "nigger",
  "nigga",
  "slut",
  "whore",
  "rapist",
  // Hinglish
  "chutiya",
  "chutiye",
  "chutia",
  "madarchod",
  "maderchod",
  "behenchod",
  "bhenchod",
  "bhosdike",
  "bhosdi",
  "gandu",
  "gaandu",
  "gaand",
  "lodu",
  "loda",
  "lauda",
  "randi",
  "harami",
  "kutiya",
  "chinaal",
  // Devanagari spellings of the most common ones
  "चूतिया",
  "मादरचोद",
  "भेनचोद",
  "भोसड़ी",
  "गांडू",
  "रंडी",
  "हरामी",
]);

/**
 * @param normalized the normalized (lower-cased) display name, spaces allowed.
 */
export function isBlockedName(normalized: string): boolean {
  const joined = normalized.replace(/\s+/g, "");
  if (!joined) return false;
  if (NAME_BLOCKLIST.has(joined)) return true;
  return normalized.split(/\s+/).some((word) => word.length > 0 && NAME_BLOCKLIST.has(word));
}
