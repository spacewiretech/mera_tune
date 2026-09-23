# ringtone-mixer

Stateless Cloud Run service that mixes a spoken name (Gemini TTS PCM) into a song's music bed and
returns the mp3. Called only by the `generate-ringtone` Edge Function. It holds no Supabase key: the
edge passes a short-lived signed URL for the bed and uploads the returned bytes itself.

## API

`GET /health` → `{"ok":true}` (use `/health` on Cloud Run: its front end reserves paths ending in `z`, so `/healthz` gets Google's 404 there)

`POST /mix` with header `x-mixer-secret: <MIXER_SHARED_SECRET>`, JSON body (≤ 2 MB):

```json
{ "render_id": "uuid", "bed_url": "https://<allow-listed host>/…",
  "tts": { "pcm_base64": "…", "mime": "audio/L16;codec=pcm;rate=24000", "sample_rate": 24000 },
  "slot_start_ms": 6420, "slot_end_ms": 7380, "slot_gain_db": 3, "duck_db": -6,
  "max_tempo": 1.3, "max_duration_ms": 30000, "title": "Jai Shri Ram Ayush ji.." }
```

Success: `200 audio/mpeg` body with headers `X-Mix-Duration-Ms`, `X-Mix-Tts-Duration-Ms`, `X-Mix-Tempo`.
Errors: JSON `{ "error", "code" }` — `UNAUTHORIZED` (401), `BAD_REQUEST` (400), `TTS_EMPTY` (400), `TTS_TOO_LONG` (400), `NAME_TOO_LONG` (422), `BED_DOWNLOAD_FAILED` (502), `FFMPEG_FAILED` / `INTERNAL` (500), `DEADLINE_EXCEEDED` (504).

### Deadline and client disconnect

Each `POST /mix` has one wall-clock budget, `MIX_DEADLINE_MS` (default 55 000 ms), counted from when the handler starts. The budget covers the bed download and all ffmpeg/ffprobe calls, which run one after another (up to 7 per render). Each step's timeout is `min(its own cap, time left)`. The caps are 20 s for the bed download and 40 s for each ffmpeg/ffprobe call.

- **Budget runs out:** the running step is killed (SIGKILL) and the response is `504 DEADLINE_EXCEEDED`. If a step's own cap fires while the budget still has time, the error stays `FFMPEG_FAILED` or `BED_DOWNLOAD_FAILED`. A render that finishes just after the deadline is still sent.
- **Client disconnects** before the response is sent (`res` `close` with `!writableEnded`): the bed fetch is aborted or the running ffmpeg is killed, and the concurrency slot is freed straight away. Nothing is sent. The log line has `code: "CLIENT_CLOSED"` (`http_status` 499, WARNING).
- The temp dir is removed in `finally` in every case.

The default sits under the edge's 60 s mixer abort (`MIXER_TIMEOUT_MS` in `generate-ringtone`), so the edge receives a real 504 instead of timing out itself. The edge maps it to `MIX_FAILED`, which is retryable. Keep `MIX_DEADLINE_MS` below the edge abort and below Cloud Run `--timeout`.

## Pipeline

1. Decode the PCM (format from `mime`), trim leading/trailing silence, resample to 44.1 kHz mono.
2. Measure the name clip and the bed's slot window (RMS) and set the name gain to bed level + `slot_gain_db`.
3. Fit the name into the slot: speed up with `atempo` up to `max_tempo`, otherwise `NAME_TOO_LONG`; centre it.
4. Duck the bed by `duck_db` with 120 ms ramps, fade the name edges, upmix mono by channel duplication (not rematrix, which would cost 3 dB), `amix normalize=0`, limit at 0.95, fade the tail if the bed was cut.
5. Encode mp3 160 kbps, 44.1 kHz stereo, with title/artist tags.

## Environment

| Var | Example | Notes |
|---|---|---|
| `MIXER_SHARED_SECRET` | `openssl rand -base64 48` | Required; compared in constant time |
| `BED_HOST_ALLOWLIST` | `lltfhcsmojzoxpewjrfk.supabase.co` | Comma-separated; https only (SSRF guard) |
| `MIX_DEADLINE_MS` | `55000` | Optional. Whole-request budget, integer 1000–300000. An invalid value logs a WARNING and the default is used |
| `PORT` | `8080` | Set by Cloud Run |

## Test locally (Docker; no Node or ffmpeg needed on the host)

```bash
bash services/ringtone-mixer/test/golden.sh        # builds the image, runs 46 golden checks (incl. deadline + disconnect kill)
docker run --rm -p 18080:8080 -e MIXER_SHARED_SECRET=dev -e BED_HOST_ALLOWLIST=lltfhcsmojzoxpewjrfk.supabase.co meratune/ringtone-mixer:golden
curl -s localhost:18080/health
```

## Deploy (Cloud Run, asia-south1)

```bash
gcloud projects list                                   # confirm the project id (Firebase project is mera-tune)
PROJECT=mera-tune
gcloud config set project $PROJECT
gcloud services enable run.googleapis.com artifactregistry.googleapis.com cloudbuild.googleapis.com secretmanager.googleapis.com
gcloud artifacts repositories create meratune --repository-format=docker --location=asia-south1
openssl rand -base64 48 | tr -d '\n' | gcloud secrets create mixer-shared-secret --data-file=- --replication-policy=automatic
gcloud builds submit services/ringtone-mixer --tag asia-south1-docker.pkg.dev/$PROJECT/meratune/ringtone-mixer:v1
gcloud run deploy ringtone-mixer \
  --image asia-south1-docker.pkg.dev/$PROJECT/meratune/ringtone-mixer:v1 \
  --region asia-south1 --allow-unauthenticated \
  --memory 1Gi --cpu 1 --concurrency 4 --timeout 120 --min-instances 0 --max-instances 5 \
  --set-env-vars BED_HOST_ALLOWLIST=lltfhcsmojzoxpewjrfk.supabase.co \
  --update-secrets MIXER_SHARED_SECRET=mixer-shared-secret:latest
# The runtime service account needs roles/secretmanager.secretAccessor on the secret.
gcloud run services describe ringtone-mixer --region asia-south1 --format 'value(status.url)'   # → mixer_url
# Deployed 2026-09-23: https://ringtone-mixer-632001126202.asia-south1.run.app (project mera-tune)
```

Then give the edge the URL and the same secret through `app_config` (SQL editor): `UPDATE public.app_config SET value = '<url>' WHERE key = 'mixer_url';` and `UPDATE public.app_config SET value = '<same secret>' WHERE key = 'mixer_shared_secret';`.

`--min-instances 0` costs nothing idle; the first request after idle adds a 2–4 s cold start, which the app's processing screen absorbs. Use `--min-instances 1` if p95 latency matters.

## Hardening later

Replace the shared secret with Cloud Run IAM (`--no-allow-unauthenticated` + `roles/run.invoker`). The Deno edge would then mint a Google ID token by signing a service-account JWT and exchanging it at `oauth2.googleapis.com/token`.
