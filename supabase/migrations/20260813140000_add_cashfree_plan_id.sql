-- Cashfree pre-created subscription plan (dashboard plan ID)
INSERT INTO app_config (key, value, description) VALUES
    ('cashfree_plan_id', 'meratune_trial_1d', 'Cashfree subscription plan ID from merchant dashboard'),
    ('cashfree_use_plan_id', 'false', 'Use cashfree_plan_id instead of inline plan_details (true/false)')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;

UPDATE app_config SET value = '299' WHERE key = 'subscription_recurring_amount';
UPDATE app_config SET value = 'false' WHERE key = 'cashfree_use_plan_id';
