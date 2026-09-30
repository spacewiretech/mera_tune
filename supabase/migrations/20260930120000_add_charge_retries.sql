-- Migration: add_charge_retries
-- Retries of ₹299 autopay charges that failed for insufficient funds (Edge Function
-- retry-failed-charges). Adds the attempts table, the failed charges cashfree-webhook records, the
-- per-mandate sweep bookkeeping, the sweep query, the config switches (retries ship OFF) and an
-- hourly pg_cron job that calls the function.
--
-- Preflight (SQL editor, before `supabase db push`):
--   SELECT to_regclass('public.subscriptions'), to_regclass('public.subscription_payments');  -- both exist
--   SELECT extname FROM pg_extension WHERE extname IN ('pg_cron', 'pg_net');                   -- enabled here if missing
--
-- DEPLOYMENT ORDER: apply this migration first, then deploy cashfree-webhook (it writes
-- charge_failures; before the migration its write only logs an error) and retry-failed-charges
-- (verify_jwt = false). Nothing is sent while charge_retry_mode is 'off' (the cron job does not even call the
-- function then). To try it:
--   UPDATE app_config SET value = '2026-10-01' WHERE key = 'charge_retry_failures_since';  -- first failure day retried (IST)
--   UPDATE app_config SET value = 'mt_123_1790000000000' WHERE key = 'charge_retry_only';  -- one mandate first
--   UPDATE app_config SET value = 'dry_run' WHERE key = 'charge_retry_mode';               -- reads only, then 'live'
-- Off again: UPDATE app_config SET value = 'off' WHERE key = 'charge_retry_mode';
--
-- Safe to re-run: IF NOT EXISTS / ON CONFLICT DO NOTHING / CREATE OR REPLACE / cron.schedule by name.

CREATE EXTENSION IF NOT EXISTS pg_cron;
CREATE EXTENSION IF NOT EXISTS pg_net WITH SCHEMA extensions;

-- One row per failed charge and attempt number. failed_payment_id is Cashfree's string payment_id
-- (e.g. 1353204_355_1790408562302) of the mandate's latest recurring charge that failed.
CREATE TABLE IF NOT EXISTS public.charge_retry_attempts (
    id BIGSERIAL PRIMARY KEY,
    subscription_row_id BIGINT NOT NULL REFERENCES public.subscriptions(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    account TEXT NOT NULL DEFAULT 'primary',          -- Cashfree account the mandate lives in
    merchant_subscription_id TEXT NOT NULL,            -- mt_<user>_<ms>, the 2025-01-01 API id
    cf_subscription_id TEXT,
    failed_payment_id TEXT NOT NULL,
    failed_cf_payment_id TEXT,
    failed_amount NUMERIC(10, 2),
    failed_on DATE NOT NULL,                           -- IST day of the failed charge
    attempt SMALLINT NOT NULL CHECK (attempt BETWEEN 1 AND 3),
    scheduled_for DATE NOT NULL,                       -- IST day asked for in next_scheduled_time
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'requested', 'succeeded', 'failed', 'ended', 'rejected', 'skipped')),
    status_reason TEXT,
    idempotency_key TEXT NOT NULL UNIQUE,
    retry_of_payment_id TEXT,                          -- the failed charge this attempt retried
    retry_payment_id TEXT,                             -- the new charge Cashfree created
    retry_cf_payment_id TEXT,
    retry_amount NUMERIC(10, 2),
    retry_scheduled_for DATE,
    requested_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    last_error TEXT,
    send_errors INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (failed_payment_id, attempt)
);

CREATE INDEX IF NOT EXISTS idx_charge_retry_attempts_open
    ON public.charge_retry_attempts (subscription_row_id)
    WHERE status IN ('pending', 'requested');
CREATE INDEX IF NOT EXISTS idx_charge_retry_attempts_open_scheduled
    ON public.charge_retry_attempts (scheduled_for)
    WHERE status IN ('pending', 'requested');
CREATE UNIQUE INDEX IF NOT EXISTS idx_charge_retry_attempts_retry_cf_payment
    ON public.charge_retry_attempts (retry_cf_payment_id)
    WHERE retry_cf_payment_id IS NOT NULL;

-- Failed / cancelled ₹299 charges as cashfree-webhook receives them (SUBSCRIPTION_PAYMENT_FAILED /
-- _CANCELLED): the job's fast path for new failures, and the outcome of its own retries. The
-- failure text is scrubbed (no VPAs, no digit runs). processed_at: the job has looked at it.
CREATE TABLE IF NOT EXISTS public.charge_failures (
    payment_id TEXT PRIMARY KEY,                       -- Cashfree string payment_id
    cf_payment_id TEXT,
    merchant_subscription_id TEXT NOT NULL,
    cf_subscription_id TEXT,
    subscription_row_id BIGINT REFERENCES public.subscriptions(id) ON DELETE CASCADE,
    user_id BIGINT,
    payment_status TEXT NOT NULL,
    failure_reason TEXT,
    failure_bucket TEXT,
    retry_attempts INT NOT NULL DEFAULT 0,
    amount NUMERIC(10, 2),
    scheduled_on DATE,                                 -- IST day of the charge
    failed_at TIMESTAMPTZ NOT NULL,                    -- webhook event_time
    processed_at TIMESTAMPTZ,
    processed_outcome TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_charge_failures_unprocessed
    ON public.charge_failures (failed_at DESC)
    WHERE processed_at IS NULL;

-- What the sweep last saw per mandate, so it asks Cashfree once per charge, not every hour.
CREATE TABLE IF NOT EXISTS public.charge_retry_checks (
    subscription_row_id BIGINT PRIMARY KEY REFERENCES public.subscriptions(id) ON DELETE CASCADE,
    merchant_subscription_id TEXT,
    account TEXT,
    cashfree_status TEXT,
    next_charge_on DATE,                               -- IST day of Cashfree's next regular charge
    latest_charge_payment_id TEXT,
    latest_charge_status TEXT,
    outcome TEXT NOT NULL,                             -- planned, latest_paid, not_insufficient_funds, mandate_customer_paused, …
    checked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    next_check_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_subscription_payments_row_recurring
    ON public.subscription_payments (subscription_row_id, paid_at)
    WHERE payment_type = 'recurring';

ALTER TABLE public.charge_retry_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.charge_retry_checks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.charge_failures ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.charge_retry_attempts FROM anon, authenticated;
REVOKE ALL ON public.charge_retry_checks FROM anon, authenticated;
REVOKE ALL ON public.charge_failures FROM anon, authenticated;

-- Mandates to look at: active rows whose charge came due (looked at from p_check_after_hours IST on
-- the due day: failures settle late in the day) with no ₹299 payment recorded since, and no open
-- retry chain. A mandate the sweep has seen
-- is due again at its next_check_at, for Cashfree's own next charge day; one it has not seen (or
-- that has paid since) uses the estimate from the DB: the first charge day, or the last payment
-- day, plus whole months. Newest due first. p_only: merchant ids (mt_…) or cf_subscription_ids.
CREATE OR REPLACE FUNCTION public.charge_retry_candidates(
    p_since DATE,
    p_limit INTEGER,
    p_only TEXT[] DEFAULT NULL,
    p_check_after_hours INTEGER DEFAULT 22)
RETURNS TABLE (
    subscription_row_id BIGINT,
    user_id BIGINT,
    cf_subscription_id TEXT,
    merchant_subscription_id TEXT,
    account TEXT,
    due_on DATE
)
LANGUAGE sql
STABLE
SET search_path = public
AS $$
with paid as (
  select p.subscription_row_id, max(p.paid_at) as last_paid_at
  from public.subscription_payments p
  where p.payment_type = 'recurring' and p.subscription_row_id is not null
  group by p.subscription_row_id
), base as (
  select s.id, s.user_id, s.cashfree_subscription_id::text as cf_id,
         c.merchant_subscription_id, c.account, c.next_check_at, c.next_charge_on,
         ((coalesce(s.next_billing_date, s.start_date + interval '1 day') at time zone 'UTC') at time zone 'Asia/Kolkata')::date as first_due_on,
         (pd.last_paid_at at time zone 'Asia/Kolkata')::date as last_paid_on,
         (now() at time zone 'Asia/Kolkata')::date as today
  from public.subscriptions s
  left join paid pd on pd.subscription_row_id = s.id
  left join public.charge_retry_checks c on c.subscription_row_id = s.id
  where s.status = 'active'
    and s.start_date is not null
    and s.cashfree_subscription_id is not null
    and (p_only is null
         or s.cashfree_subscription_id::text = any(p_only)
         or c.merchant_subscription_id = any(p_only)
         or s.user_id::text in (select split_part(o, '_', 2) from unnest(p_only) o where o like 'mt\_%'))
    and not exists (
      select 1 from public.charge_retry_attempts a
      where a.subscription_row_id = s.id and a.status in ('pending', 'requested'))
), due as (
  select b.*,
         -- seen before, and nothing paid since the charge it was waiting for: Cashfree's own day
         (b.next_check_at is not null and b.next_charge_on is not null
          and (b.last_paid_on is null or b.last_paid_on < b.next_charge_on - 2)) as use_check,
         coalesce(b.last_paid_on, b.first_due_on) as anchor,
         (extract(year from age(b.today, coalesce(b.last_paid_on, b.first_due_on))) * 12
          + extract(month from age(b.today, coalesce(b.last_paid_on, b.first_due_on))))::int as k
  from base b
), picked as (
  select d.*,
         case when d.use_check then d.next_charge_on
              else (d.anchor + make_interval(months => d.k))::date end as due_day
  from due d
)
select p.id, p.user_id, p.cf_id, p.merchant_subscription_id, p.account, p.due_day
from picked p
where p.due_day >= p_since
  and p.due_day <= p.today
  and (p.next_check_at is null or p.next_check_at <= now())
  and (p.use_check or p.last_paid_on is null or p.k >= 1)
  and now() >= (p.due_day::timestamp at time zone 'Asia/Kolkata') + make_interval(hours => p_check_after_hours)
order by p.due_day desc, p.id desc
limit greatest(p_limit, 0);
$$;

REVOKE ALL ON FUNCTION public.charge_retry_candidates(DATE, INTEGER, TEXT[], INTEGER) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.charge_retry_candidates(DATE, INTEGER, TEXT[], INTEGER) TO service_role;

INSERT INTO public.app_config (key, value, description) VALUES
    ('charge_retry_mode', 'off',
     'Failed-charge retries: off (default), dry_run (reads Cashfree, writes and sends nothing), live'),
    ('charge_retry_failures_since', '',
     'IST day (YYYY-MM-DD) of the earliest failed charge that is retried. Empty: nothing new is planned'),
    ('charge_retry_only', '',
     'Comma-separated mandates (mt_… or cf_subscription_id) the job is limited to. Empty: all'),
    ('charge_retry_max_checks_per_run', '150', 'Mandates the hourly sweep looks up in Cashfree per run'),
    ('charge_retry_max_sends_per_run', '300', 'RETRY requests per run'),
    ('charge_retry_min_interval_ms', '250', 'Spacing of Cashfree 2025-01-01 API calls (3 at a time)'),
    ('charge_retry_lookup_interval_ms', '1100', 'Spacing of legacy /api/v2 lookups (about 60 a minute per IP)'),
    ('charge_retry_send_lead_hours', '26',
     'A retry for day D is requested at least this many hours before 00:00 IST of D (Cashfree notice: 25 h)'),
    ('charge_retry_check_after_hours', '22',
     'Hours after 00:00 IST of a charge day when its outcome is read (failures settle late in the day)'),
    ('cashfree_extra_accounts', '',
     'JSON [{"name","client_id","client_secret"}] of older Cashfree accounts whose mandates are retried there'),
    ('charge_retry_function_url', 'https://lltfhcsmojzoxpewjrfk.supabase.co/functions/v1/retry-failed-charges',
     'URL the hourly pg_cron job posts to'),
    ('charge_retry_job_secret', replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''),
     'x-job-secret the pg_cron job sends to retry-failed-charges')
ON CONFLICT (key) DO NOTHING;

-- Hourly at :17. The command itself skips the call while the mode is off.
SELECT cron.schedule(
    'charge-retry-hourly',
    '17 * * * *',
    $cron$
    SELECT net.http_post(
        url := (SELECT value FROM public.app_config WHERE key = 'charge_retry_function_url'),
        headers := jsonb_build_object(
            'Content-Type', 'application/json',
            'x-job-secret', (SELECT value FROM public.app_config WHERE key = 'charge_retry_job_secret')),
        body := '{}'::jsonb,
        timeout_milliseconds := 140000)
    WHERE EXISTS (
        SELECT 1 FROM public.app_config WHERE key = 'charge_retry_mode' AND value IN ('dry_run', 'live'));
    $cron$
);

NOTIFY pgrst, 'reload schema';
