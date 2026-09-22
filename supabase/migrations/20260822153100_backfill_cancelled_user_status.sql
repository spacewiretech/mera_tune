-- Keep users.status in sync with the latest subscription row.
-- Skip users who cancelled then started a new pending/active subscription.

UPDATE public.users u
SET
    status = 'cancelled',
    updated_at = now()
FROM (
    SELECT DISTINCT ON (user_id)
        user_id,
        status
    FROM public.subscriptions
    ORDER BY user_id, created_at DESC
) latest
WHERE latest.user_id = u.id
  AND latest.status = 'cancelled'
  AND u.status IN ('trial', 'active');
