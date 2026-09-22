-- Migration: create_app_config_and_otp_sessions
-- Applied via Supabase MCP

CREATE TABLE IF NOT EXISTS app_config (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL DEFAULT '',
    description TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE app_config ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS otp_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone VARCHAR(15) NOT NULL,
    otp_hash TEXT NOT NULL,
    session_token TEXT UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_otp_sessions_phone ON otp_sessions (phone);
CREATE INDEX IF NOT EXISTS idx_otp_sessions_token ON otp_sessions (session_token) WHERE session_token IS NOT NULL;

ALTER TABLE otp_sessions ENABLE ROW LEVEL SECURITY;

INSERT INTO app_config (key, value, description) VALUES
    ('fast2sms_api_key', '', 'Fast2SMS API authorization key'),
    ('fast2sms_route', 'otp', 'Fast2SMS route: otp or dlt'),
    ('fast2sms_url', 'https://www.fast2sms.com/dev/bulkV2', 'Fast2SMS bulk API endpoint'),
    ('otp_length', '4', 'Number of digits in OTP code'),
    ('otp_expiry_minutes', '5', 'OTP validity in minutes'),
    ('default_country_code', '91', 'Default phone country code (India)')
ON CONFLICT (key) DO NOTHING;

REVOKE ALL ON app_config FROM anon, authenticated;
REVOKE ALL ON otp_sessions FROM anon, authenticated;
