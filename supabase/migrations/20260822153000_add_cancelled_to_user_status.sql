-- users.status is user_status (none/trial/active/expired).
-- cashfree-webhook writes "cancelled" on cancel; that value was missing.

ALTER TYPE public.user_status ADD VALUE IF NOT EXISTS 'cancelled';
