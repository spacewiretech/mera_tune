# Authoring a personalized song

A song appears in the app's "Apna Gaana Chuniye" picker only when it has a music bed, a name slot,
a sample name and a Gemini voice. The database refuses `is_personalizable = true` without them.

## Per song

1. **Pick the song.** The sample name (for example "Ram") must be sung once, clearly, inside a hook of 30 seconds or less. Confirm the licence allows derivative versions.
2. **Find the slot.** Open the song in Audacity, set the selection toolbar to milliseconds, and select exactly the sung name plus about 40 ms on each side. Note the start and end in ms. The slot must be 300–6000 ms wide.
3. **Make the bed.** Silence or vocal-reduce only that region, with 20 ms fades at each edge so it does not click. Export as mp3 (or wav) and upload it to the **private** bucket `tune-beds` as `<tune_id>.mp3`. The object path goes in `bed_path`.
4. **Choose the voice.** In Google AI Studio → Generate speech, try the prebuilt voices with 3–4 names in the song's script. Pick one whose gender matches the song's `gender`; the server refuses a mismatch. Gender labels are in `supabase/functions/_shared/tts-voices.ts`.
5. **Mark it personalizable:**
   ```sql
   UPDATE public.tune SET
     is_personalizable  = TRUE,
     featured_rank      = 1,                 -- 1 shows first; NULL hides it from the picker
     bed_path           = '<tune_id>.mp3',
     name_slot_start_ms = 6420,
     name_slot_end_ms   = 7380,
     sample_name        = 'Ram',
     tts_voice_name     = 'Charon',
     tts_style_prompt   = NULL,              -- optional, e.g. 'softly, stretching the last syllable'
     slot_gain_db       = 3.0,               -- name loudness relative to the bed
     duck_db            = -6.0,              -- how far the bed dips under the name
     title_template     = 'Jai Shri Ram {name} ji..'
   WHERE id = '<tune_id>';
   ```
   After changing the bed or slot later, bump `assets_version` so cached renders are not reused.
6. **Listen test.** Generate with a short name ("Om"), a long one ("Lakshminarayan") and one in the song's script (see the curl in `GENERATE_RINGTONE_API.md`). Pass when the name is clear, there are no clicks and the bed dip is unobtrusive. Adjust `slot_gain_db`, `duck_db` or widen the slot. A long name that fails with `NAME_TOO_LONG_FOR_SONG` means the slot is too tight for common names.

## Per language

1. Run the listen test with three names on one song.
2. Enable the language: `UPDATE public.generation_languages SET tts_enabled = TRUE WHERE language = 'Tamil';`
3. Author 10 songs with `featured_rank` 1–10: at least 3 male, 3 female, and every category represented.

Launch order: Hindi first, then English, then the rest. Until a language is enabled the app shows it as "jaldi aa raha hai" on the form. A language with zero songs falls back to Hindi songs with a banner.
