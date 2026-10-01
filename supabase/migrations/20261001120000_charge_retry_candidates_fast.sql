-- Migration: charge_retry_candidates_fast
-- The sweep's "no open retry chain" step was planned as a nested loop that rescans
-- charge_retry_attempts for every candidate mandate (39 M row comparisons with 1,101 attempts).
-- The query took 14.7 s against the 8 s statement timeout PostgREST requests inherit from the
-- authenticator role, so every retry-failed-charges run from 2026-10-01 15:10 IST failed with 500.
-- With nested loops off it is a hash anti join: 0.66 s, and it stays linear as attempts grow.

ALTER FUNCTION public.charge_retry_candidates(DATE, INTEGER, TEXT[], INTEGER) SET enable_nestloop = off;

ANALYZE public.charge_retry_attempts;
ANALYZE public.charge_retry_checks;
ANALYZE public.charge_failures;
