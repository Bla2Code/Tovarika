CREATE TABLE registration_onboarding (
    user_id varchar(100) PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    email varchar(320) NOT NULL,
    role varchar(40) CHECK (role IN ('marketplace_seller','aspiring_seller','client_designer','exploring_ai')),
    frequency varchar(40) CHECK (frequency IN ('more_than_20','from_6_to_20','from_1_to_5','rarely')),
    difficulty varchar(40) CHECK (difficulty IN ('time_consuming','expensive_designer','ai_quality','difficult_edits','no_problems')),
    completed_at timestamptz,
    CHECK ((completed_at IS NULL AND role IS NULL AND frequency IS NULL AND difficulty IS NULL)
        OR (completed_at IS NOT NULL AND role IS NOT NULL AND frequency IS NOT NULL AND difficulty IS NOT NULL))
);

-- Published prices and previous grants are immutable. Only future grants use 16 Tovariki.
INSERT INTO billing_price_versions(id,trial_allowance_units,free_entire_successes,effective_from,change_reason)
SELECT 'billing-2026-10-15-v2',1600,free_entire_successes,clock_timestamp(),'16 Tovariki for new registrations'
FROM billing_price_versions WHERE id=(SELECT active_version FROM billing_configuration WHERE singleton=true);
INSERT INTO billing_prices(version_id,operation_code,amount_units)
SELECT 'billing-2026-10-15-v2',operation_code,amount_units FROM billing_prices
WHERE version_id=(SELECT active_version FROM billing_configuration WHERE singleton=true);
UPDATE billing_price_versions SET published_at=clock_timestamp() WHERE id='billing-2026-10-15-v2';
UPDATE billing_configuration SET active_version='billing-2026-10-15-v2' WHERE singleton=true;
