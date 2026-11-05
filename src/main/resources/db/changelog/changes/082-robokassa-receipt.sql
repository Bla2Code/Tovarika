-- Preserve the exact receipt for new checkouts. Legacy orders keep NULL and their original payment parameters.
ALTER TABLE billing_credit_purchases ADD COLUMN receipt_json text
    CHECK(receipt_json IS NULL OR jsonb_typeof(receipt_json::jsonb) = 'object');
-- billing_guard_purchase already protects every snapshot column, including receipt_json, from updates.
