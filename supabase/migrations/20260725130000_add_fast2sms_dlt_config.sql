-- Add DLT config keys for Fast2SMS
INSERT INTO app_config (key, value, description) VALUES
    ('fast2sms_sender_id', '', 'DLT-approved 3-6 letter Sender ID (e.g. MRATNE). Must be added in Fast2SMS DLT Manager first.'),
    ('fast2sms_message_id', '', 'DLT Message ID from Fast2SMS DLT Manager for your OTP template. Required when route is dlt.')
ON CONFLICT (key) DO NOTHING;
