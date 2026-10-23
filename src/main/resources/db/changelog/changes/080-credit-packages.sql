-- Paid packages are separate lots; existing/trial funds retain their original lifetime.
CREATE TABLE billing_credit_packages (
    id varchar(50) PRIMARY KEY,
    price_kopecks bigint NOT NULL CHECK(price_kopecks > 0),
    credit_units bigint NOT NULL CHECK(credit_units BETWEEN 1 AND 9007199254740991),
    validity_months integer NOT NULL CHECK(validity_months = 1)
);
INSERT INTO billing_credit_packages VALUES
    ('credits_140_month',99000,14000,1),
    ('credits_400_month',249000,40000,1),
    ('credits_1200_month',649000,120000,1);
CREATE TRIGGER billing_credit_package_immutable BEFORE UPDATE OR DELETE ON billing_credit_packages
    FOR EACH ROW EXECUTE FUNCTION billing_reject_mutation();

CREATE TABLE billing_credit_lots (
    id varchar(40) PRIMARY KEY,
    wallet_id varchar(40) NOT NULL REFERENCES billing_wallets(id),
    reference varchar(250) NOT NULL UNIQUE,
    original_units bigint NOT NULL CHECK(original_units BETWEEN 1 AND 9007199254740991),
    remaining_units bigint NOT NULL CHECK(remaining_units BETWEEN 0 AND original_units),
    reserved_units bigint NOT NULL DEFAULT 0 CHECK(reserved_units BETWEEN 0 AND remaining_units),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz,
    CHECK(expires_at IS NULL OR expires_at > created_at)
);
CREATE INDEX billing_lots_wallet ON billing_credit_lots(wallet_id) WHERE remaining_units > 0;
CREATE INDEX billing_lots_expiration ON billing_credit_lots(expires_at,wallet_id)
    WHERE expires_at IS NOT NULL AND remaining_units > reserved_units;
CREATE TABLE billing_lot_allocations (
    operation_id varchar(40) NOT NULL REFERENCES billing_operations(id),
    lot_id varchar(40) NOT NULL REFERENCES billing_credit_lots(id),
    amount_units bigint NOT NULL CHECK(amount_units > 0),
    refunded_units bigint NOT NULL DEFAULT 0 CHECK(refunded_units BETWEEN 0 AND amount_units),
    PRIMARY KEY(operation_id,lot_id)
);
-- Allocate legacy reservations without changing existing immutable receipts or balances.
INSERT INTO billing_credit_lots(id,wallet_id,reference,original_units,remaining_units,reserved_units)
SELECT 'lot_'||substr(md5(id),1,32),id,'legacy:'||id,total_units,total_units,reserved_units
FROM billing_wallets WHERE total_units > 0;
INSERT INTO billing_lot_allocations(operation_id,lot_id,amount_units)
SELECT r.operation_id,'lot_'||substr(md5(l.canonical_wallet_id),1,32),r.amount_units
FROM billing_reservations r JOIN billing_wallet_links l ON l.wallet_id=r.wallet_id WHERE r.state='reserved';

ALTER TABLE billing_ledger DROP CONSTRAINT billing_ledger_kind_check;
ALTER TABLE billing_ledger ADD CONSTRAINT billing_ledger_kind_check
    CHECK(kind IN ('trial_grant','ai_debit','compensation','free_operation','package_purchase','credit_expiry'));

CREATE SEQUENCE billing_robokassa_invoice_seq MAXVALUE 9007199254740991;
CREATE TABLE billing_credit_purchases (
    id varchar(40) PRIMARY KEY,
    invoice_id bigint NOT NULL UNIQUE DEFAULT nextval('billing_robokassa_invoice_seq'),
    user_id varchar(100) NOT NULL,
    wallet_id varchar(40) NOT NULL REFERENCES billing_wallets(id),
    idempotency_key varchar(200) NOT NULL,
    package_id varchar(50) NOT NULL REFERENCES billing_credit_packages(id),
    price_kopecks bigint NOT NULL CHECK(price_kopecks > 0),
    credit_units bigint NOT NULL CHECK(credit_units BETWEEN 1 AND 9007199254740991),
    status varchar(20) NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','succeeded')),
    test_mode boolean NOT NULL CHECK(test_mode),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    paid_at timestamptz,
    expires_at timestamptz,
    UNIQUE(user_id,idempotency_key),
    CHECK((status='succeeded')=(paid_at IS NOT NULL)),
    CHECK((status='succeeded')=(expires_at IS NOT NULL)),
    CHECK(expires_at IS NULL OR expires_at > paid_at)
);
CREATE FUNCTION billing_guard_purchase() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Purchase cannot be deleted'; END IF;
    IF (to_jsonb(NEW)-ARRAY['status','paid_at','expires_at']) IS DISTINCT FROM
       (to_jsonb(OLD)-ARRAY['status','paid_at','expires_at']) OR
       (OLD.status='succeeded' AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Purchase snapshot is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER billing_credit_purchase_immutable BEFORE UPDATE OR DELETE ON billing_credit_purchases
    FOR EACH ROW EXECUTE FUNCTION billing_guard_purchase();
