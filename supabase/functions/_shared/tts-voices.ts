/**
 * Gemini TTS prebuilt voices with the official gender labels from the Cloud Text-to-Speech
 * "Gemini-TTS" voice table. `tune.tts_voice_name` must be one of these keys and its gender must
 * match `tune.gender`; generate-ringtone logs a mismatch with console.error and answers
 * 422 TUNE_NOT_PERSONALIZABLE (an authoring error the user cannot fix by retrying).
 */
export type TtsVoiceGender = "male" | "female";

export const TTS_VOICES: Readonly<Record<string, TtsVoiceGender>> = Object.freeze({
  Achernar: "female",
  Achird: "male",
  Algenib: "male",
  Algieba: "male",
  Alnilam: "male",
  Aoede: "female",
  Autonoe: "female",
  Callirrhoe: "female",
  Charon: "male",
  Despina: "female",
  Enceladus: "male",
  Erinome: "female",
  Fenrir: "male",
  Gacrux: "female",
  Iapetus: "male",
  Kore: "female",
  Laomedeia: "female",
  Leda: "female",
  Orus: "male",
  Pulcherrima: "female",
  Puck: "male",
  Rasalgethi: "male",
  Sadachbia: "male",
  Sadaltager: "male",
  Schedar: "male",
  Sulafat: "female",
  Umbriel: "male",
  Vindemiatrix: "female",
  Zephyr: "female",
  Zubenelgenubi: "male",
});

export const TTS_VOICE_NAMES: readonly string[] = Object.freeze(Object.keys(TTS_VOICES));

/** Gender of a prebuilt voice, or null when the name is not in the table (case-sensitive). */
export function ttsVoiceGender(voiceName: string | null | undefined): TtsVoiceGender | null {
  if (!voiceName) return null;
  return TTS_VOICES[voiceName.trim()] ?? null;
}

export function isKnownTtsVoice(voiceName: string | null | undefined): boolean {
  return ttsVoiceGender(voiceName) !== null;
}

/** Maps tune.gender values (Male / Female / M / F, any case) onto the voice-table labels. */
export function normalizeVoiceGender(raw: string | null | undefined): TtsVoiceGender | null {
  const value = (raw ?? "").trim().toLowerCase();
  if (value === "m" || value === "male") return "male";
  if (value === "f" || value === "female") return "female";
  return null;
}

export function voicesForGender(gender: TtsVoiceGender): string[] {
  return TTS_VOICE_NAMES.filter((name) => TTS_VOICES[name] === gender);
}
