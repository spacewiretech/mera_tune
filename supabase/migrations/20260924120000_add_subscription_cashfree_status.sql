-- Migration: add_subscription_cashfree_status
-- Raw Cashfree subscription status for server analytics (subscription_status_changed and the
-- previous_status props), the once-guard for trial_expired, and the rollout switch for mandatory
-- webhook signatures. Access still follows subscriptions.status / users.status; ON_HOLD, PAUSED,
-- EXPIRED, … are tracked only. Also an index for generate-ringtone's name_lookup_completed count.
--
-- Preflight (run in the SQL editor before `supabase db push`):
--   SELECT to_regclass('public.subscriptions'), to_regclass('public.app_config');       -- both must exist (subscriptions is out of band)
--   SELECT count(*) FROM public.ringtone_renders;                                        -- only if 20260923121000 is applied; size decides the index build (below)
--   SELECT key, value FROM public.app_config WHERE key = 'cashfree_webhook_signature_mode'; -- no row yet
--
-- DEPLOYMENT ORDER: apply BEFORE deploying cashfree-webhook, verify-subscription and
-- create-subscription (they write these columns, and the webhook selects them). Nullable columns
-- without a default: metadata-only. trial_expired_at starts NULL everywhere (no backfill); rows
-- cancelled before the deploy have no cashfree_status, so the webhook never reports them late.
-- After 24-72 h of webhook logs with verdict "valid", switch to enforce (no redeploy needed):
--   UPDATE public.app_config SET value = 'enforce' WHERE key = 'cashfree_webhook_signature_mode';
--
-- Safe to re-run: IF NOT EXISTS / ON CONFLICT DO NOTHING.

ALTER TABLE public.subscriptions
    ADD COLUMN IF NOT EXISTS cashfree_status TEXT,             -- e.g. ACTIVE, ON_HOLD, CUSTOMER_CANCELLED
    ADD COLUMN IF NOT EXISTS cashfree_status_at TIMESTAMPTZ,   -- Cashfree event_time of that status
    ADD COLUMN IF NOT EXISTS trial_expired_at TIMESTAMPTZ;     -- set once, when trial_expired was sent for this row

INSERT INTO public.app_config (key, value, description) VALUES
    ('cashfree_webhook_signature_mode', 'log_only',
     'log_only: unsigned webhooks are processed and logged; enforce: 401 unless signed with cashfree_client_secret. Bad signatures are rejected in both modes')
ON CONFLICT (key) DO NOTHING;

-- name_lookup_completed.match_count: ready renders of a name in a language, any sample. Every other
-- ringtone_renders index leads with tune_id. A plain (locking) build is fine while the table is small;
-- if it has grown, run this as CREATE INDEX CONCURRENTLY in the SQL editor first (outside a transaction).
CREATE INDEX IF NOT EXISTS idx_ringtone_renders_name_lang_ready
    ON public.ringtone_renders(name_normalized, language)
    WHERE status = 'ready';

NOTIFY pgrst, 'reload schema';
