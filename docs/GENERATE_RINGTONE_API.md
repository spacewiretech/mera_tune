# generate-ringtone API

Supabase Edge Function that turns a curated song plus a user's name into a personalized ringtone.
Gemini TTS speaks the name, the Cloud Run mixer (`services/ringtone-mixer`) mixes it into the song's
music bed at the name slot, and the result is uploaded to the public `generated-ringtones` bucket.

```
app ──POST──▶ generate-ringtone ──▶ Gemini TTS (PCM) ──▶ ringtone-mixer /mix (mp3 bytes) ──▶ Storage upload ──▶ 200 {ringtone_url}
```

## Request

`POST {SUPABASE_URL}/functions/v1/generate-ringtone`

Headers: `Authorization: Bearer <anon key>`, `apikey: <anon key>`, `Content-Type: application/json`.

```json
{
  "user_id": 123,
  "user_token": "<api_token from verify-otp / complete-signup, or null>",
  "tune_id": "b1162cd7-992b-470d-a036-6ff0bad9ff9f",
  "name": "Ayush",
  "language": "Hindi",
  "client_request_id": "0f6c1d2e-8f53-4a4e-9d57-3b8f2a1c9e40",
  "app_version": "1.3.0"
}
```

- `language` is `Languages.storageValue` (`Hindi`, `English`, …), never a localized label.
- `client_request_id` is a UUID the app keeps for the whole attempt, including retries. Re-posting the same id never creates a second generation.

## Success (200)

```json
{
  "generation_id": "…", "render_id": "…",
  "ringtone_url": "https://lltfhcsmojzoxpewjrfk.supabase.co/storage/v1/object/public/generated-ringtones/<tune_id>/<render_id>.mp3",
  "title": "Jai Shri Ram Ayush ji..",
  "tune_id": "…", "tune_name": "Ram Bhajan",
  "category": { "id": "…", "name": "Devotional", "image_url": "…" },
  "language": "Hindi", "voice": "Male",
  "duration_ms": 28400, "cached": false,
  "quota": { "used_today": 1, "daily_limit": 5 }
}
```

`cached: true` means the same song + name + voice was already rendered (by anyone) and the stored file was reused. Cache hits do not count toward quota.

`category.name` and `category.image_url` are always strings (`""` when the category has no image), never `null`: the app's `Category` model has non-null defaults and rejects an explicit `null`. `category` itself is `null` when the song has no category.

## Errors

Always JSON: `{ "error": "<message>", "error_code": "<CODE>", "retry_after_seconds"?: n, "quota"?: {…} }`.
The field is `error_code` because the Supabase gateway's own errors use a numeric `code`.

| HTTP | error_code | Meaning | App action |
|---|---|---|---|
| 400 | INVALID_REQUEST | Missing or malformed field | Generic error |
| 400 | INVALID_NAME | Name fails the rules below | Back to form |
| 400 | NAME_REJECTED | Blocklisted name or Gemini refused it | Back to form |
| 401 | UNAUTHORIZED | Token unknown/expired, token/user mismatch, or no token with the legacy flag off | Log in again |
| 403 | SUBSCRIPTION_REQUIRED | `generate_require_subscription=true` and the user is not trial/active | Open paywall |
| 404 | TUNE_NOT_FOUND | Tune inactive or missing | Pick another song |
| 409 | GENERATION_IN_PROGRESS | Same request or same render already running, or a parallel retry of the same `client_request_id` claimed it first | Auto re-post after `retry_after_seconds` |
| 422 | UNSUPPORTED_LANGUAGE | `generation_languages.tts_enabled` is false | Change language |
| 422 | TUNE_NOT_PERSONALIZABLE | Tune has no slot assets, its `tts_voice_name` gender does not match `tune.gender`, or the mixer rejected its authoring data (400 `BAD_REQUEST`, e.g. slot past the end of the bed) | Pick another song |
| 422 | NAME_TOO_LONG_FOR_SONG | Spoken name needs more than `generate_tts_max_tempo` speed-up to fit the slot. Once a render of the same cache key failed this way, later requests get it straight away, without a Gemini call | Pick another song |
| 429 | QUOTA_EXCEEDED | Per-user daily fresh-render limit or daily attempt cap reached (IST day) | Show limit; `retry_after_seconds` = time to IST midnight |
| 429 | SERVICE_BUSY | Global daily cap reached | Retry later |
| 502 | TTS_FAILED | Gemini failed, or the mixer found the clip unusable (`TTS_EMPTY` / `TTS_TOO_LONG`) | Retry (same `client_request_id`) |
| 502 | MIX_FAILED | Bed signing, mixer 5xx/timeout or an unexpected mixer response | Retry (same `client_request_id`) |
| 502 | UPLOAD_FAILED | Storage upload failed or took longer than 20 s | Retry (same `client_request_id`) |
| 503 | TTS_RATE_LIMITED | Gemini 429/503 | Auto re-post after `retry_after_seconds` |
| 503 | SERVICE_UNAVAILABLE | Kill switch off, secrets missing, unsupported `gemini_tts_endpoint`, or the mixer rejected the shared secret (401/403) | Retry later |
| 500 | INTERNAL | Unexpected, including a database error while checking `user_token` (not a 401, so a DB hiccup never forces a re-login) | Retry |

Messages are generic; configuration details (which secret, which endpoint value, the mixer's reason) go to the function logs and `ringtone_renders.error`, never to the client.

### Mixer error mapping

The mixer answers `{ error, code }` (see `services/ringtone-mixer/src/errors.ts`); `generate-ringtone/errors.ts#mixerFailure` maps it:

| Mixer response | API error | Why |
|---|---|---|
| 401 / 403 (any body) | 503 SERVICE_UNAVAILABLE, logged as error | Shared secret or IAM mismatch; ops must fix, retrying cannot help |
| 422 `NAME_TOO_LONG` | 422 NAME_TOO_LONG_FOR_SONG | Name does not fit this slot |
| 400 `BAD_REQUEST` | 422 TUNE_NOT_PERSONALIZABLE, logged as error | The song's authoring data is unusable |
| `TTS_EMPTY` / `TTS_TOO_LONG` | 502 TTS_FAILED | A fresh Gemini take may be fine |
| anything else (5xx, `FFMPEG_FAILED`, `BED_DOWNLOAD_FAILED`, 413, timeout) | 502 MIX_FAILED | Transient |

## Name rules (server `names.ts` and app `NameNormalizer` are kept identical by a shared fixture)

NFC; emoji, variation selectors and zero-width spaces stripped; control/format characters stripped except ZWJ/ZWNJ inside words; whitespace collapsed. Valid when 1–30 code points, at most 3 words, only letters, combining marks, space, apostrophes, `.` and `-`, and not Latin mixed with a non-Latin script (or two non-Latin scripts). Cache key = lower-cased display form.

Gemini is sent a canonical **spoken form** derived from the cache key alone (`names.ts#spokenName`: title-case at word starts, so `AYUSH`, `ayush` and `Ayush` are all spoken as `Ayush`, `o'brien` as `O'Brien`; caseless scripts such as Devanagari are unchanged). Renders are shared across users by key, so the first caller's casing must not decide the audio for everyone. The spoken form is stored in `ringtone_renders.name_display`; the per-user `title` keeps the casing the user typed. The default style prompt uses the **song's** language (`tune.language`), not the request's, for the same reason: the cache key has no language.

## Quota and retries

All limits use the IST calendar day.

| Limit | Counts | Over the limit |
|---|---|---|
| `generate_daily_limit` (token) / `generate_legacy_daily_limit` | This user's fresh renders: `cached=false` rows that are `ready`, or `processing` for less than 170 s | 429 QUOTA_EXCEEDED |
| `generate_daily_attempt_limit` | This user's fresh-render attempts that may have billed Gemini: in flight, ready, or failed after the Gemini call (`TTS_FAILED`, `MIX_FAILED`, `UPLOAD_FAILED`, `NAME_TOO_LONG_FOR_SONG`, `NAME_REJECTED`, …). Rate-limited re-posts (`TTS_RATE_LIMITED`), `GENERATION_IN_PROGRESS`, `INTERNAL` and admission rejections are not counted, so a Gemini outage cannot lock a user out. Never below the daily limit | 429 QUOTA_EXCEEDED |
| `generate_global_daily_limit` | All renders that are ready or fresh, plus failures after Gemini was billed (`NAME_TOO_LONG_FOR_SONG`, `MIX_FAILED`, `UPLOAD_FAILED`, `TTS_FAILED`, `TUNE_NOT_PERSONALIZABLE`, `SERVICE_UNAVAILABLE`, `DUPLICATE`, `STALE`) | 429 SERVICE_BUSY |

Counting and inserting are separate statements, so after inserting its rows a request re-reads the user's rows for today in creation order and continues only if its own row is among the first `limit`. Parallel requests therefore cannot exceed the per-user limits; the losers get 429 and their rows are marked `QUOTA_EXCEEDED`, which the attempt count ignores.

Retrying the same `client_request_id`:
- `ready` → the same response again (same `generation_id`, no new row).
- `processing` for less than 170 s (just over the 150 s Edge wall clock) → 409 GENERATION_IN_PROGRESS.
- `failed`, or `processing` for longer → the old row keeps its outcome and gives up the `client_request_id`, and the retry writes a new row (new `generation_id`). The hand-over is a conditional update, so of two concurrent retries only one proceeds; the other gets 409 and re-posts.

A `NAME_TOO_LONG_FOR_SONG` verdict sticks to its cache key (`tune_id`, `assets_version`, name, voice). After re-authoring the slot or raising `generate_tts_max_tempo`, bump `tune.assets_version` or `DELETE FROM ringtone_renders WHERE error_code = 'NAME_TOO_LONG_FOR_SONG' AND tune_id = '<id>';`.

## Config (`app_config`, service-role only)

| Key | Default | Purpose |
|---|---|---|
| generate_enabled | true | Kill switch |
| generate_daily_limit | 5 | Fresh renders per user per IST day (token users) |
| generate_legacy_daily_limit | 3 | Same, for callers without `user_token` |
| generate_daily_attempt_limit | 12 | Possibly-billed fresh-render attempts per user per IST day (billed failures included, rate-limited re-posts excluded); caps Gemini spend on retries. Values below the daily limit are raised to it |
| generate_global_daily_limit | 2000 | All users per IST day, billed failures included; keep below the Gemini RPD for your tier |
| generate_allow_legacy_user_id | true | Accept bare `user_id` during the token rollout; set false one release later |
| generate_require_subscription | false | Restrict to trial/active users |
| generate_max_duration_ms | 30000 | Output length cap |
| generate_tts_max_tempo | 1.3 | Max speed-up of the spoken name |
| gemini_tts_model | gemini-2.5-flash-preview-tts | Model id |
| gemini_tts_endpoint | generate_content | Only `generate_content` is implemented. Any other value (including the `interactions` stub) returns 503 SERVICE_UNAVAILABLE at config load, before quota or bookkeeping; the value is logged, not returned |
| gemini_tts_fallback_model | gemini-3.1-flash-tts-preview | Model for the third TTS attempt when the primary returned no usable audio (2.5 flash TTS often returns an empty answer for a one-word name). About 2× the price, used rarely. `none` disables it |
| gemini_api_key, mixer_url, mixer_shared_secret | empty | Credentials, kept here like the Cashfree and Fast2SMS keys (`app_config` is service-role only). Read on every request, so a change applies immediately. An Edge secret named `GEMINI_API_KEY` / `MIXER_URL` / `MIXER_SHARED_SECRET` would override the row |

Languages are gated by the anon-readable `generation_languages` table (default: Hindi and English enabled). The app reads it to disable unsupported pills on the form.

## Timeout budget

Gemini 25 s per attempt, 55 s total → mixer 60 s → upload 20 s. Must stay under the Edge wall clock (150 s Free, 400 s Paid). The app waits 90 s. A `processing` row older than 170 s cannot belong to a live request, so it no longer counts toward quota and its `client_request_id` can be retried.

## Deploy

1. **Link the CLI** (first time only; migrations so far were applied through the Supabase MCP):
   ```bash
   supabase link --project-ref lltfhcsmojzoxpewjrfk
   supabase migration list
   # mark the 10 pre-existing migrations as applied so db push does not replay them:
   supabase migration repair --status applied 20260725120000 20260725130000 20260725140000 20260728160000 \
     20260813140000 20260814201021 20260819184700 20260821110100 20260822153000 20260822153100
   ```
2. **Preflight** (SQL editor): the queries at the top of `20260923121000_add_personalized_ringtones.sql`.
3. **Migrate:** `supabase db push`. Both new migrations are additive and safe for installed app builds.
4. **Buckets:** create `generated-ringtones` (public, 5 MB, audio/mpeg) and `tune-beds` (private, 20 MB, mp3/wav) in Dashboard → Storage, or run the commented SQL in the migration.
5. **Gemini:** enable billing on the GCP project that owns the key (2.5 TTS previews have no free tier), restrict the key to the Generative Language API, and check the model's RPM/RPD in AI Studio.
6. **Pin the response shape** before first deploy:
   ```bash
   curl -s "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent" \
     -H "x-goog-api-key: $GEMINI_API_KEY" -H 'Content-Type: application/json' \
     -d '{"contents":[{"role":"user","parts":[{"text":"Say warmly: Ayush"}]}],
          "generationConfig":{"responseModalities":["AUDIO"],
            "speechConfig":{"voiceConfig":{"prebuiltVoiceConfig":{"voiceName":"Charon"}}}}}' \
     | python3 -c "import json,sys; p=json.load(sys.stdin)['candidates'][0]['content']['parts'][0]['inlineData']; print(p['mimeType'], len(p['data']))"
   # expect: audio/L16;codec=pcm;rate=24000 <base64 length>
   ```
   `tts.ts` reads `candidates[0].content.parts[].inlineData`. If Google has moved the model, switch `gemini_tts_model`.
7. **Mixer:** deploy per `services/ringtone-mixer/README.md`, note its URL.
8. **Credentials in `app_config`** (Dashboard → SQL editor, so the key stays out of shell history):
   ```sql
   UPDATE public.app_config SET value = '<gemini key>', updated_at = now() WHERE key = 'gemini_api_key';
   UPDATE public.app_config SET value = '<Cloud Run URL, no trailing slash>', updated_at = now() WHERE key = 'mixer_url';
   UPDATE public.app_config SET value = '<same value as the Cloud Run MIXER_SHARED_SECRET>', updated_at = now() WHERE key = 'mixer_shared_secret';
   ```
   Do not also set Edge secrets with these names; they would take precedence over the rows.
9. **Functions:** `supabase functions deploy verify-otp complete-signup generate-ringtone`
10. **Seed one tune** (see `PERSONALIZED_RINGTONE_AUTHORING.md`) and run the curl checks below.

## Verification

```bash
deno test --allow-read supabase/functions/tests          # name rules + spoken form, quota math, error mapping, Gemini parsing
bash services/ringtone-mixer/test/golden.sh              # ffmpeg mix golden test (Docker)

SB=https://lltfhcsmojzoxpewjrfk.supabase.co; ANON=<anon key>
H=(-H "Authorization: Bearer $ANON" -H "apikey: $ANON" -H 'Content-Type: application/json')
curl -s -X POST $SB/functions/v1/generate-ringtone "${H[@]}" \
  -d '{"user_id":1,"user_token":"<token>","tune_id":"<id>","name":"Ayush","language":"Hindi","client_request_id":"11111111-1111-4111-8111-111111111111","app_version":"1.3.0"}'
```

| Case | Expect |
|---|---|
| First call | 200, `cached:false`, URL ends `.mp3` |
| Same `client_request_id` again | Same `generation_id`, no new row |
| New id, same name + song | 200, `cached:true`, under 500 ms |
| New id, `"name":"AYUSH"` (same song) | 200, `cached:true` (same key; spoken as "Ayush"), title uses "AYUSH" |
| `"name":"Ayush 😀1"` | 400 INVALID_NAME |
| `"language":"Tamil"` (disabled) | 422 UNSUPPORTED_LANGUAGE |
| 6th distinct name today | 429 QUOTA_EXCEEDED |
| 13th possibly-billed attempt today, billed failures included (`generate_daily_attempt_limit=12`) | 429 QUOTA_EXCEEDED, even if fewer than 5 succeeded; `TTS_RATE_LIMITED` re-posts never count |
| 5 new ids fired in parallel with 4 renders already used | At most one proceeds, the rest 429 QUOTA_EXCEEDED |
| A name that got NAME_TOO_LONG_FOR_SONG, again with a new id | 422 NAME_TOO_LONG_FOR_SONG immediately, no new `ringtone_renders` row |
| Failed request re-posted with the same id | New `generation_id`; the old row stays `failed` with `client_request_id` NULL |
| No token, `generate_allow_legacy_user_id=false` | 401 UNAUTHORIZED |
| `generate_enabled=false` | 503 SERVICE_UNAVAILABLE |
| `gemini_tts_endpoint=interactions` | 503 SERVICE_UNAVAILABLE, generic message, no rows written |
| `MIXER_SHARED_SECRET` wrong on one side | 503 SERVICE_UNAVAILABLE (not MIX_FAILED), `console.error` in the function logs |

```bash
curl -sI "<ringtone_url>" | grep -iE 'HTTP/|content-type|cache-control'   # 200 audio/mpeg max-age=31536000
```
SQL health: `select status, cached, latency_ms, error_code from generated_ringtones order by created_at desc limit 10;` — no `ringtone_renders` should sit in `processing` for more than 10 minutes (enable the pg_cron sweeper in the migration).

## Privacy

The user's name is stored in `generated_ringtones.name_display` (as typed) and `ringtone_renders.name_display` (the canonical spoken form, needed for the cache) and is spoken in a public mp3. It is never logged and never sent to Mixpanel; logs carry ids, codes and timings only.
