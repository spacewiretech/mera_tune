-- Keep one auth row per subscription: prefer a real Cashfree payment ID (CH_…)
-- over a row that reused cashfree_subscription_id as cf_payment_id.

DELETE FROM subscription_payments sp
WHERE sp.id IN (
  SELECT id FROM (
    SELECT
      id,
      ROW_NUMBER() OVER (
        PARTITION BY subscription_row_id
        ORDER BY
          CASE WHEN cf_payment_id LIKE 'CH_%' THEN 0 ELSE 1 END,
          CASE WHEN cf_payment_id IS DISTINCT FROM cashfree_subscription_id THEN 0 ELSE 1 END,
          id ASC
      ) AS rn
    FROM subscription_payments
    WHERE payment_type = 'auth'
      AND subscription_row_id IS NOT NULL
  ) ranked
  WHERE rn > 1
);

CREATE UNIQUE INDEX IF NOT EXISTS subscription_payments_one_auth_per_sub
  ON subscription_payments (subscription_row_id)
  WHERE payment_type = 'auth' AND subscription_row_id IS NOT NULL;
