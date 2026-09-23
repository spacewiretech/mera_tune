// deno test --allow-read supabase/functions/tests
import { assert, assertEquals, assertStrictEquals } from "jsr:@std/assert@1";
import {
  codePointLength,
  displayName,
  NAME_RULES,
  normalizeAuthoredName,
  normalizeForKey,
  sanitizeName,
  spokenName,
  validateName,
} from "../generate-ringtone/names.ts";
import { isBlockedName, NAME_BLOCKLIST } from "../_shared/name-blocklist.ts";
import {
  normalizeVoiceGender,
  TTS_VOICE_NAMES,
  TTS_VOICES,
  ttsVoiceGender,
  voicesForGender,
} from "../_shared/tts-voices.ts";

type FixtureCase = {
  id: string;
  input: string;
  valid: boolean;
  reason?: string;
  display?: string;
  normalized?: string;
};

type Fixture = { _notes: string[]; cases: FixtureCase[] };

const fixture: Fixture = JSON.parse(
  await Deno.readTextFile(new URL("./fixtures/name_normalization_cases.json", import.meta.url)),
);

Deno.test("fixture has enough coverage and unique ids", () => {
  assert(fixture.cases.length >= 25, `expected >= 25 cases, got ${fixture.cases.length}`);
  const ids = new Set(fixture.cases.map((c) => c.id));
  assertEquals(ids.size, fixture.cases.length, "duplicate case ids");
  for (const c of fixture.cases) {
    if (c.valid) {
      assert(typeof c.display === "string", `${c.id}: valid case needs display`);
      assert(typeof c.normalized === "string", `${c.id}: valid case needs normalized`);
    } else {
      assert(typeof c.reason === "string", `${c.id}: invalid case needs reason`);
    }
  }
});

for (const c of fixture.cases) {
  Deno.test(`names fixture: ${c.id}`, () => {
    const result = validateName(c.input);
    assertStrictEquals(result.valid, c.valid, `${c.id}: valid mismatch (${JSON.stringify(result)})`);
    if (result.valid) {
      assertEquals(result.display, c.display, `${c.id}: display`);
      assertEquals(result.normalized, c.normalized, `${c.id}: normalized`);
      // The normalized form is idempotent and equals normalizing the display form.
      assertEquals(normalizeForKey(result.display), result.normalized, `${c.id}: normalizeForKey(display)`);
      assertEquals(displayName(result.display), result.display, `${c.id}: displayName idempotent`);
    } else {
      assertEquals(result.reason, c.reason, `${c.id}: reason`);
    }
  });
}

Deno.test("NFD and NFC inputs share one cache key", () => {
  const nfd = validateName("José");
  const nfc = validateName("José");
  assert(nfd.valid && nfc.valid);
  assertEquals(nfd.normalized, nfc.normalized);
  assertEquals(nfd.display, nfc.display);
});

Deno.test("valid display forms never exceed the code point and word limits", () => {
  for (const c of fixture.cases) {
    if (!c.valid) continue;
    assert(codePointLength(c.display!) <= NAME_RULES.maxCodePoints, c.id);
    assert(c.display!.split(" ").length <= NAME_RULES.maxWords, c.id);
  }
});

Deno.test("sanitizeName maps validation failures to INVALID_NAME", () => {
  const result = sanitizeName("Ayush 😀1");
  assert(!result.ok);
  assertEquals(result.code, "INVALID_NAME");
  assertEquals(result.reason, "INVALID_CHARS");
});

Deno.test("sanitizeName rejects non-string input", () => {
  for (const input of [undefined, null, 42, { name: "x" }]) {
    const result = sanitizeName(input);
    assert(!result.ok);
    assertEquals(result.code, "INVALID_NAME");
  }
});

Deno.test("sanitizeName returns display and normalized for a good name", () => {
  const result = sanitizeName("  Ayush   KUMAR ");
  assert(result.ok);
  assertEquals(result.display, "Ayush KUMAR");
  assertEquals(result.normalized, "ayush kumar");
});

Deno.test("blocklist hits become NAME_REJECTED, spaces and case ignored", () => {
  const [term] = [...NAME_BLOCKLIST].filter((t) => /^[a-z]+$/.test(t));
  assert(term, "need one ascii term in the blocklist");
  const upper = term.toUpperCase();
  const split = term.slice(0, 2) + " " + term.slice(2);
  for (const input of [term, upper, split]) {
    const result = sanitizeName(input);
    assert(!result.ok, `expected rejection for a blocklisted term`);
    assertEquals(result.code, "NAME_REJECTED");
  }
  assert(isBlockedName(term));
  assert(!isBlockedName("assam"), "substring matches must not reject ordinary names");
  assert(!isBlockedName("ayush"));
});

Deno.test("sample name normalisation matches request normalisation", () => {
  assertEquals(normalizeAuthoredName(" Ram "), "ram");
  assertEquals(normalizeAuthoredName(null), "");
  const request = sanitizeName("RAM");
  assert(request.ok);
  assertEquals(request.normalized, normalizeAuthoredName("Ram"));
});

/** Spoken form for a raw input, via the same path generate-ringtone uses. */
function spokenFor(raw: string): string {
  const result = sanitizeName(raw);
  assert(result.ok, "expected a valid name");
  return spokenName(result.normalized);
}

Deno.test("spoken name: every casing of one name is spoken the same way", () => {
  for (const input of ["AYUSH", "ayush", "Ayush", "aYuSh", "  ayush "]) {
    assertEquals(spokenFor(input), "Ayush");
  }
  assertEquals(spokenFor("AYUSH KUMAR"), "Ayush Kumar");
  assertEquals(spokenFor("ayush kumar sharma"), "Ayush Kumar Sharma");
});

Deno.test("spoken name: word starts after apostrophes, dots and hyphens", () => {
  assertEquals(spokenName("o'brien"), "O'Brien");
  assertEquals(spokenName("d’souza"), "D’Souza");
  assertEquals(spokenName("mary-jane"), "Mary-Jane");
  assertEquals(spokenName("j.k. rowling"), "J.K. Rowling");
  assertEquals(spokenFor("ÉLODIE"), "Élodie");
  assertEquals(spokenFor("josé"), "José");
});

Deno.test("spoken name: caseless scripts keep the display form", () => {
  for (const input of ["आयुष", "आयुष कुमार", "ஆயுஷ்", "ఆయుష్", "আয়ুষ", "ಆಯುಷ್", "ആയുഷ്", "ଆୟୁଷ"]) {
    const result = sanitizeName(input);
    assert(result.ok, "expected a valid name");
    assertEquals(spokenName(result.normalized), result.display);
  }
});

Deno.test("spoken name: cased non-Latin scripts are canonical too", () => {
  assertEquals(spokenFor("ИВАН"), "Иван");
  assertEquals(spokenFor("иван"), "Иван");
  assertEquals(spokenFor("ΣΟΦΙΑ"), spokenFor("σοφια"));
});

Deno.test("spoken name is a pure function of the cache key for every fixture name", () => {
  for (const c of fixture.cases) {
    if (!c.valid) continue;
    const spoken = spokenName(c.normalized!);
    assertEquals(normalizeForKey(spoken), c.normalized, `${c.id}: lower-casing the spoken form gives the key back`);
    assertEquals(spokenName(normalizeForKey(spoken)), spoken, `${c.id}: idempotent`);
    assertEquals(spokenName(normalizeForKey(c.display!.toLocaleUpperCase("en"))), spoken, `${c.id}: casing-independent`);
  }
});

Deno.test("tts voices table has the 30 official voices with valid genders", () => {
  assertEquals(TTS_VOICE_NAMES.length, 30);
  for (const name of TTS_VOICE_NAMES) {
    const gender = TTS_VOICES[name];
    assert(gender === "male" || gender === "female", name);
  }
  assertEquals(ttsVoiceGender("Sulafat"), "female");
  assertEquals(ttsVoiceGender("Puck"), "male");
  assertEquals(ttsVoiceGender("NotAVoice"), null);
  assertEquals(ttsVoiceGender(null), null);
  assertEquals(voicesForGender("female").length + voicesForGender("male").length, 30);
});

Deno.test("tune.gender values map onto voice genders", () => {
  assertEquals(normalizeVoiceGender("Female"), "female");
  assertEquals(normalizeVoiceGender("MALE"), "male");
  assertEquals(normalizeVoiceGender(" f "), "female");
  assertEquals(normalizeVoiceGender(""), null);
  assertEquals(normalizeVoiceGender(undefined), null);
});
