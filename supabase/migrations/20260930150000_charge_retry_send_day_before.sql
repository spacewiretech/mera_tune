-- Migration: charge_retry_send_day_before
-- Cashfree debits a RETRY 24 h after the request and ignores next_scheduled_time (production,
-- 2026-09-30: requested 14:49 IST for 5 Oct, scheduled 1 Oct 14:49 IST). retry-failed-charges now
-- sends a retry for day D on the IST day before D; charge_retry_send_lead_hours is that 24 h delay
-- (it was the 26 h lead before a date that Cashfree does not use). Only an untouched 26 is changed.

UPDATE public.app_config
SET value = '24',
    description = 'Cashfree debits a retry this many hours after the request (it ignores next_scheduled_time), so a retry for day D is sent on the IST day before D',
    updated_at = NOW()
WHERE key = 'charge_retry_send_lead_hours' AND value = '26';
