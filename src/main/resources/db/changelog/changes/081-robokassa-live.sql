-- Allow new LIVE purchases. Existing purchase rows and their immutable snapshots are unchanged.
ALTER TABLE billing_credit_purchases DROP CONSTRAINT billing_credit_purchases_test_mode_check;
