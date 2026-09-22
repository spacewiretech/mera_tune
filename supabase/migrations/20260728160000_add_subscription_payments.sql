-- Records each Cashfree subscription charge for deduplication and renewal counting.
CREATE TABLE IF NOT EXISTS subscription_payments (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subscription_row_id BIGINT REFERENCES subscriptions(id) ON DELETE SET NULL,
    cf_payment_id TEXT NOT NULL UNIQUE,
    cashfree_subscription_id TEXT,
    amount NUMERIC(10, 2) NOT NULL,
    payment_type TEXT NOT NULL CHECK (payment_type IN ('auth', 'recurring')),
    billing_month TEXT,
    renewal_number INT,
    paid_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_subscription_payments_user_id
    ON subscription_payments(user_id);

CREATE INDEX IF NOT EXISTS idx_subscription_payments_user_recurring
    ON subscription_payments(user_id, payment_type)
    WHERE payment_type = 'recurring';

ALTER TABLE subscription_payments ENABLE ROW LEVEL SECURITY;
