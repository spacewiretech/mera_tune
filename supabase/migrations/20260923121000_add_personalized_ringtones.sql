-- Migration: add_personalized_ringtones
-- Authoring columns on tune, the app-readable generation_languages gate, the shared render cache
-- (ringtone_renders), the per-user generation log (generated_ringtones) and the app_config keys
-- read by the generate-ringtone Edge Function.
--
-- Preflight (run in the SQL editor before `supabase db push`):
--   SELECT data_type FROM information_schema.columns
--     WHERE table_schema = 'public' AND table_name = 'tune' AND column_name = 'id';    -- expect uuid; if text, change tune_id below
--   SELECT DISTINCT gender FROM public.tune;                                            -- expect Male / Female (compared case-insensitively)
--   SELECT DISTINCT language FROM public.tune;                                          -- must match generation_languages.language spelling
--   SELECT to_regclass('public.app_config'), to_regclass('public.users');               -- both must exist
--
-- DEPLOYMENT ORDER: purely additive; safe to apply before the new app build ships. Installed
-- builds keep working (they ignore the new columns). See section 2 for why tune privileges are
-- left unchanged.
--
-- Safe to re-run: IF NOT EXISTS / DROP IF EXISTS / ON CONFLICT DO NOTHING. The tune CHECK
-- constraint is dropped and re-created, so a re-run always applies the current definition.

-- ---------------------------------------------------------------------------------------------
-- 1. tune: authoring columns for the personalized (name-slot) flow
-- ---------------------------------------------------------------------------------------------
ALTER TABLE public.tune
    ADD COLUMN IF NOT EXISTS is_personalizable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS featured_rank INTEGER,
    ADD COLUMN IF NOT EXISTS bed_path TEXT,                       -- object path in the PRIVATE bucket tune-beds
    ADD COLUMN IF NOT EXISTS name_slot_start_ms INTEGER,
    ADD COLUMN IF NOT EXISTS name_slot_end_ms INTEGER,
    ADD COLUMN IF NOT EXISTS sample_name TEXT,                    -- the name currently sung in tune_url
    ADD COLUMN IF NOT EXISTS tts_voice_name TEXT,                 -- Gemini prebuilt voice, see _shared/tts-voices.ts
    ADD COLUMN IF NOT EXISTS tts_style_prompt TEXT,
    ADD COLUMN IF NOT EXISTS slot_gain_db NUMERIC(4,1) NOT NULL DEFAULT 3.0,
    ADD COLUMN IF NOT EXISTS duck_db NUMERIC(4,1) NOT NULL DEFAULT -6.0,
    ADD COLUMN IF NOT EXISTS title_template TEXT,                 -- e.g. 'Jai Shri Ram {name} ji..'
    ADD COLUMN IF NOT EXISTS assets_version INTEGER NOT NULL DEFAULT 1;

-- Every column is tested with IS NOT NULL explicitly: a CHECK passes when it evaluates to NULL,
-- so `name_slot_start_ms >= 0` alone would let a personalizable row keep NULL slot times.
ALTER TABLE public.tune DROP CONSTRAINT IF EXISTS tune_personalizable_assets_check;
ALTER TABLE public.tune ADD CONSTRAINT tune_personalizable_assets_check CHECK (
    NOT is_personalizable OR (
        bed_path IS NOT NULL AND btrim(bed_path) <> ''
        AND name_slot_start_ms IS NOT NULL
        AND name_slot_end_ms IS NOT NULL
        AND name_slot_start_ms >= 0
        AND name_slot_end_ms > name_slot_start_ms
        AND (name_slot_end_ms - name_slot_start_ms) BETWEEN 300 AND 6000
        AND tts_voice_name IS NOT NULL AND btrim(tts_voice_name) <> ''
        AND sample_name IS NOT NULL AND btrim(sample_name) <> ''
    )
);

CREATE INDEX IF NOT EXISTS idx_tune_personalizable
    ON public.tune(language, featured_rank)
    WHERE is_personalizable AND is_active;

-- ---------------------------------------------------------------------------------------------
-- 2. tune column privileges: intentionally NOT restricted.
--    The authoring columns (bed_path, tts_voice_name, tts_style_prompt, slot_gain_db, duck_db) stay
--    readable by anon. That is safe: bed_path points into the PRIVATE tune-beds bucket and is useless
--    without a signed URL minted by the Edge Function; the other columns are tuning numbers.
--    Revoking table-level SELECT would break every installed app build that still requests
--    `select=*` on tune (Play Store v1.2.3 and earlier). Newer builds use explicit column lists
--    (HomeRepository.TUNE_COLUMNS), so a column-level lockdown can be added in a later migration
--    once old builds are gone:
--      REVOKE SELECT ON public.tune FROM anon, authenticated;
--      GRANT SELECT (<every column except the five above>) ON public.tune TO anon, authenticated;
-- ---------------------------------------------------------------------------------------------

-- ---------------------------------------------------------------------------------------------
-- 3. generation_languages: app-readable TTS gate (like subscription_videos)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.generation_languages (
    language TEXT PRIMARY KEY,
    tts_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.generation_languages ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "anon read generation_languages" ON public.generation_languages;
CREATE POLICY "anon read generation_languages"
    ON public.generation_languages
    FOR SELECT
    TO anon, authenticated
    USING (true);

REVOKE ALL ON public.generation_languages FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.generation_languages TO anon, authenticated;

INSERT INTO public.generation_languages (language, tts_enabled) VALUES
    ('English', TRUE),
    ('Hindi', TRUE),
    ('Telugu', FALSE),
    ('Tamil', FALSE),
    ('Kannada', FALSE),
    ('Malayalam', FALSE),
    ('Marathi', FALSE),
    ('Odia', FALSE),
    ('Bengali', FALSE)
ON CONFLICT (language) DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- 4. ringtone_renders: shared cache, one mp3 per (tune, assets_version, normalized name, voice)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.ringtone_renders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tune_id UUID NOT NULL REFERENCES public.tune(id) ON DELETE CASCADE,
    assets_version INTEGER NOT NULL,
    name_normalized TEXT NOT NULL,
    name_display TEXT NOT NULL,
    language TEXT NOT NULL,
    voice TEXT NOT NULL,                                          -- tune.gender at render time
    tts_model TEXT,
    tts_voice TEXT NOT NULL,                                      -- Gemini prebuilt voice name
    storage_path TEXT,
    public_url TEXT,
    duration_ms INTEGER,
    tts_duration_ms INTEGER,
    tempo_applied NUMERIC(4,2),
    status TEXT NOT NULL DEFAULT 'processing' CHECK (status IN ('processing', 'ready', 'failed')),
    error_code TEXT,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS ringtone_renders_ready_key
    ON public.ringtone_renders(tune_id, assets_version, name_normalized, tts_voice)
    WHERE status = 'ready';

CREATE INDEX IF NOT EXISTS idx_ringtone_renders_inflight
    ON public.ringtone_renders(tune_id, assets_version, name_normalized, tts_voice, created_at)
    WHERE status = 'processing';

CREATE INDEX IF NOT EXISTS idx_ringtone_renders_created
    ON public.ringtone_renders(created_at);

-- A key that already failed NAME_TOO_LONG_FOR_SONG is answered 422 before calling Gemini again.
CREATE INDEX IF NOT EXISTS idx_ringtone_renders_name_too_long
    ON public.ringtone_renders(tune_id, assets_version, name_normalized, tts_voice)
    WHERE error_code = 'NAME_TOO_LONG_FOR_SONG';

-- ---------------------------------------------------------------------------------------------
-- 5. generated_ringtones: per-user log for quota, idempotency and ops/cost reporting
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.generated_ringtones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    tune_id UUID NOT NULL REFERENCES public.tune(id) ON DELETE CASCADE,
    render_id UUID REFERENCES public.ringtone_renders(id) ON DELETE SET NULL,
    client_request_id TEXT,
    name_display TEXT NOT NULL,
    name_normalized TEXT NOT NULL,
    language TEXT NOT NULL,
    voice TEXT NOT NULL,
    title TEXT,
    cached BOOLEAN NOT NULL DEFAULT FALSE,
    status TEXT NOT NULL DEFAULT 'processing' CHECK (status IN ('processing', 'ready', 'failed')),
    error_code TEXT,
    auth_mode TEXT NOT NULL DEFAULT 'token' CHECK (auth_mode IN ('token', 'legacy_user_id')),
    latency_ms INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_generated_ringtones_request
    ON public.generated_ringtones(user_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_generated_ringtones_user_created
    ON public.generated_ringtones(user_id, created_at DESC);

ALTER TABLE public.ringtone_renders ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.generated_ringtones ENABLE ROW LEVEL SECURITY;

-- Service-role only.
REVOKE ALL ON public.ringtone_renders, public.generated_ringtones FROM PUBLIC, anon, authenticated;

-- ---------------------------------------------------------------------------------------------
-- 6. app_config keys read by generate-ringtone (env secrets win over app_config)
-- ---------------------------------------------------------------------------------------------
INSERT INTO public.app_config (key, value, description) VALUES
    ('generate_enabled', 'true', 'Kill switch for generate-ringtone (false -> 503 SERVICE_UNAVAILABLE)'),
    ('generate_daily_limit', '5', 'Fresh renders per user per IST day (token auth)'),
    ('generate_legacy_daily_limit', '3', 'Daily limit when the caller has no user_token'),
    ('generate_daily_attempt_limit', '12', 'Fresh-render attempts per user per IST day, failed ones included (never below the daily limit)'),
    ('generate_global_daily_limit', '2000', 'Fresh renders per IST day across all users (keep below the Gemini RPD)'),
    ('generate_allow_legacy_user_id', 'true', 'Accept user_id without token during the app update cycle'),
    ('generate_require_subscription', 'false', 'Restrict to users.status in (trial, active)'),
    ('generate_max_duration_ms', '30000', 'Ringtone length cap passed to the mixer'),
    ('generate_tts_max_tempo', '1.3', 'Max atempo before NAME_TOO_LONG_FOR_SONG'),
    ('gemini_tts_model', 'gemini-2.5-flash-preview-tts', 'Gemini TTS model id'),
    ('gemini_tts_endpoint', 'generate_content', 'generate_content | interactions'),
    ('gemini_api_key', '', 'Fallback only; prefer Edge secret GEMINI_API_KEY'),
    ('mixer_url', '', 'Fallback only; prefer Edge secret MIXER_URL'),
    ('mixer_shared_secret', '', 'Fallback only; prefer Edge secret MIXER_SHARED_SECRET')
ON CONFLICT (key) DO NOTHING;

-- Let PostgREST pick up the new tables/columns/grants without waiting for its cache timer.
NOTIFY pgrst, 'reload schema';

-- ---------------------------------------------------------------------------------------------
-- 7. Storage buckets (run ONCE in the SQL editor or create them in Dashboard -> Storage).
--    generated-ringtones: PUBLIC, 5 MB, audio/mpeg only. Output path <tune_id>/<render_id>.mp3.
--    tune-beds:           PRIVATE, 20 MB, mp3/wav. Read through 300 s signed URLs only.
--    No storage.objects policies: the Edge Function uses the service-role key.
-- ---------------------------------------------------------------------------------------------
-- INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
-- VALUES ('generated-ringtones', 'generated-ringtones', TRUE, 5242880, ARRAY['audio/mpeg'])
-- ON CONFLICT (id) DO UPDATE SET public = EXCLUDED.public,
--                                file_size_limit = EXCLUDED.file_size_limit,
--                                allowed_mime_types = EXCLUDED.allowed_mime_types;
--
-- INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
-- VALUES ('tune-beds', 'tune-beds', FALSE, 20971520,
--         ARRAY['audio/mpeg', 'audio/mp3', 'audio/wav', 'audio/x-wav', 'audio/wave'])
-- ON CONFLICT (id) DO UPDATE SET public = EXCLUDED.public,
--                                file_size_limit = EXCLUDED.file_size_limit,
--                                allowed_mime_types = EXCLUDED.allowed_mime_types;

-- ---------------------------------------------------------------------------------------------
-- 8. Stale-row sweeper (pg_cron; enable the extension in Dashboard -> Database -> Extensions
--    first, then run ONCE in the SQL editor). Rows stuck in 'processing' for 10+ minutes mean
--    the isolate died mid-render. The function already ignores processing rows older than 170 s;
--    the sweeper makes them show up as failed/STALE in ops SQL and in the global daily cap.
-- ---------------------------------------------------------------------------------------------
-- CREATE EXTENSION IF NOT EXISTS pg_cron WITH SCHEMA extensions;
--
-- SELECT cron.schedule(
--     'sweep_stale_ringtone_renders',
--     '*/10 * * * *',
--     $sweep$
--         UPDATE public.ringtone_renders
--            SET status = 'failed', error_code = 'STALE', updated_at = NOW(), completed_at = NOW()
--          WHERE status = 'processing' AND created_at < NOW() - INTERVAL '10 minutes';
--         UPDATE public.generated_ringtones
--            SET status = 'failed', error_code = 'STALE', completed_at = NOW()
--          WHERE status = 'processing' AND created_at < NOW() - INTERVAL '10 minutes';
--     $sweep$
-- );
--
-- To remove: SELECT cron.unschedule('sweep_stale_ringtone_renders');
