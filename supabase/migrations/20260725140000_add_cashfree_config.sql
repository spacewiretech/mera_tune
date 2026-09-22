-- Cashfree payment gateway configuration
INSERT INTO app_config (key, value, description) VALUES
    ('cashfree_client_id', '', 'Cashfree App ID / Client ID'),
    ('cashfree_client_secret', '', 'Cashfree Secret Key'),
    ('cashfree_environment', 'sandbox', 'Cashfree environment: sandbox or production'),
    ('cashfree_api_version', '2025-01-01', 'Cashfree API version header'),
    ('cashfree_return_url', 'https://meratune.app/subscription/return', 'Return URL after subscription checkout'),
    ('subscription_auth_amount', '3', 'E-mandate authorization amount in INR'),
    ('subscription_recurring_amount', '249', 'Recurring autopay amount in INR'),
    ('subscription_interval_months', '3', 'Billing interval in months after first charge'),
    ('subscription_trial_days', '1', 'Free trial duration in days after e-mandate')
ON CONFLICT (key) DO NOTHING;
