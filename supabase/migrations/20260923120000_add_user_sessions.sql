-- Migration: add_user_sessions
-- Long-lived per-device API tokens for app users. Issued by verify-otp (returning users) and
-- complete-signup (new users); consumed by generate-ringtone. Only the SHA-256 hex of the random
-- token is stored (see supabase/functions/_shared/user-sessions.ts). Issuing a token revokes all
-- but the newest 5 sessions of that user and deletes the verified otp_sessions row.
--
-- Preflight (run in the SQL editor before `supabase db push`):
--   SELECT data_type FROM information_schema.columns
--     WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'id';   -- expect bigint
--   SELECT to_regclass('public.user_sessions');                                        -- NULL = not yet created
--
-- Safe to re-run: every statement is IF NOT EXISTS / idempotent.

CREATE TABLE IF NOT EXISTS public.user_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    app_version TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '365 days'),
    revoked_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_user_sessions_user_id ON public.user_sessions(user_id);

ALTER TABLE public.user_sessions ENABLE ROW LEVEL SECURITY;

-- Service-role only: the anon key must never read or write tokens.
REVOKE ALL ON public.user_sessions FROM PUBLIC, anon, authenticated;
