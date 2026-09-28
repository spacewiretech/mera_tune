-- Migration: add_plan_generation_limits
-- Plan-based creation quotas for generate-ringtone (generate-ringtone/quota.ts planQuotaFor), also
-- reported by name-ringtones mine mode: users.status trial gets generate_trial_daily_limit fresh
-- renders per IST day, active gets generate_member_monthly_limit per IST calendar month, and any
-- other status (token auth) gets generate_daily_limit, capped at the trial limit (only trial and
-- active users may create, so a non-member never gets more than a trial). Cached ringtones never
-- count. The per-day
-- attempt cap (generate_daily_attempt_limit), the legacy user_id limit and the global cap are
-- unchanged.
--
-- DEPLOYMENT ORDER: none required. The functions fall back to the same defaults (2 / 50) when these
-- rows are missing, so applying before or after deploying generate-ringtone / name-ringtones is safe.
--
-- Safe to re-run: ON CONFLICT DO NOTHING; the description update is idempotent.

INSERT INTO public.app_config (key, value, description) VALUES
    ('generate_trial_daily_limit', '2', 'Fresh renders per IST day for users.status trial'),
    ('generate_member_monthly_limit', '50', 'Fresh renders per IST calendar month for users.status active')
ON CONFLICT (key) DO NOTHING;

UPDATE public.app_config
SET description = 'Fresh renders per user per IST day (token auth) for users whose status is not trial or active; never above generate_trial_daily_limit'
WHERE key = 'generate_daily_limit';
