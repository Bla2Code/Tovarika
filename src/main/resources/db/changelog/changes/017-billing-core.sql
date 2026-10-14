CREATE TABLE billing_accounts (
    id varchar(40) PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- Opaque ownership aliases deliberately survive deletion/conversion of auth/workspace rows.
CREATE TABLE billing_owners (
    kind varchar(10) NOT NULL CHECK (kind IN ('user','trial')),
    subject varchar(100) NOT NULL,
    account_id varchar(40) NOT NULL REFERENCES billing_accounts(id),
    PRIMARY KEY(kind, subject)
);
CREATE TABLE billing_wallets (
    id varchar(40) PRIMARY KEY,
    account_id varchar(40) NOT NULL UNIQUE REFERENCES billing_accounts(id),
    total_units bigint NOT NULL DEFAULT 0 CHECK(total_units BETWEEN 0 AND 9007199254740991),
    reserved_units bigint NOT NULL DEFAULT 0 CHECK(reserved_units BETWEEN 0 AND total_units),
    used_units bigint NOT NULL DEFAULT 0 CHECK(used_units BETWEEN 0 AND 9007199254740991),
    ledger_sequence bigint NOT NULL DEFAULT 0 CHECK(ledger_sequence >= 0),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_posted_at timestamptz
);
CREATE TABLE billing_price_versions (
    id varchar(100) PRIMARY KEY,
    trial_allowance_units bigint NOT NULL CHECK(trial_allowance_units BETWEEN 0 AND 9007199254740991),
    free_entire_successes integer NOT NULL CHECK(free_entire_successes = 1),
    effective_from timestamptz NOT NULL,
    published_at timestamptz,
    created_by text NOT NULL DEFAULT current_user,
    change_reason text NOT NULL CHECK(length(change_reason) > 0)
);
CREATE TABLE billing_prices (
    version_id varchar(100) NOT NULL REFERENCES billing_price_versions(id),
    operation_code varchar(40) NOT NULL CHECK(operation_code IN ('image_generation','image_entire_edit',
        'image_region_edit','image_erase','image_remove_background','image_outpaint','product_analysis','cpu_only')),
    amount_units bigint NOT NULL CHECK(amount_units BETWEEN 0 AND 9007199254740991),
    PRIMARY KEY(version_id, operation_code),
    CHECK((operation_code IN ('product_analysis','cpu_only')) = (amount_units = 0))
);
CREATE TABLE billing_configuration (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    active_version varchar(100) NOT NULL REFERENCES billing_price_versions(id)
);
CREATE TABLE billing_operations (
    id varchar(40) PRIMARY KEY,
    wallet_id varchar(40) NOT NULL REFERENCES billing_wallets(id),
    namespace varchar(200) NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    digest_version integer NOT NULL CHECK(digest_version > 0),
    digest varchar(64) NOT NULL CHECK(digest ~ '^[0-9a-f]{64}$'),
    operation_code varchar(40) NOT NULL,
    price_version varchar(100) NOT NULL,
    amount_units bigint NOT NULL CHECK(amount_units BETWEEN 0 AND 9007199254740991),
    funding varchar(20) NOT NULL CHECK(funding IN ('wallet','free_entitlement','free_operation')),
    entitlement_subject varchar(200),
    job_namespace varchar(200) NOT NULL,
    job_id varchar(200) NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'reserved' CHECK(state IN ('reserved','captured','released')),
    fence bigint NOT NULL DEFAULT 0 CHECK(fence >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    UNIQUE(wallet_id, namespace, idempotency_key),
    UNIQUE(job_namespace, job_id),
    FOREIGN KEY(price_version, operation_code) REFERENCES billing_prices(version_id, operation_code),
    CHECK((funding = 'wallet') = (amount_units > 0)),
    CHECK((funding = 'free_entitlement') = (entitlement_subject IS NOT NULL)),
    CHECK(funding <> 'free_entitlement' OR operation_code = 'image_entire_edit'),
    CHECK(operation_code <> 'image_entire_edit' OR funding <> 'free_operation'),
    CHECK((state = 'reserved') = (finished_at IS NULL))
);
CREATE TABLE billing_reservations (
    operation_id varchar(40) PRIMARY KEY REFERENCES billing_operations(id),
    wallet_id varchar(40) NOT NULL REFERENCES billing_wallets(id),
    amount_units bigint NOT NULL CHECK(amount_units BETWEEN 1 AND 9007199254740991),
    state varchar(20) NOT NULL CHECK(state IN ('reserved','captured','released'))
);
CREATE TABLE billing_entitlements (
    code varchar(40) NOT NULL CHECK(code = 'free_entire'),
    subject varchar(200) NOT NULL,
    reserved_by varchar(40) UNIQUE REFERENCES billing_operations(id),
    consumed_by varchar(40) UNIQUE REFERENCES billing_operations(id),
    PRIMARY KEY(code, subject),
    CHECK(reserved_by IS NULL OR consumed_by IS NULL)
);
CREATE TABLE billing_ledger (
    id varchar(40) PRIMARY KEY,
    wallet_id varchar(40) NOT NULL REFERENCES billing_wallets(id),
    sequence bigint NOT NULL CHECK(sequence > 0),
    kind varchar(30) NOT NULL CHECK(kind IN ('trial_grant','ai_debit','compensation')),
    amount_units bigint NOT NULL CHECK(amount_units BETWEEN 1 AND 9007199254740991),
    balance_after_units bigint NOT NULL CHECK(balance_after_units BETWEEN 0 AND 9007199254740991),
    operation_id varchar(40) REFERENCES billing_operations(id),
    operation_code varchar(40),
    reference varchar(250) NOT NULL UNIQUE,
    original_debit_id varchar(40) REFERENCES billing_ledger(id),
    occurred_at timestamptz NOT NULL,
    UNIQUE(wallet_id, sequence),
    CHECK((kind = 'ai_debit') = (operation_id IS NOT NULL)),
    CHECK((kind = 'ai_debit') = (operation_code IS NOT NULL)),
    CHECK(operation_code IS NULL OR operation_code IN ('image_generation','image_entire_edit',
        'image_region_edit','image_erase','image_remove_background','image_outpaint')),
    CHECK((kind = 'compensation') = (original_debit_id IS NOT NULL))
);
CREATE UNIQUE INDEX billing_debit_once ON billing_ledger(operation_id) WHERE kind = 'ai_debit';
CREATE INDEX billing_history_order ON billing_ledger(wallet_id, sequence) WHERE kind IN ('trial_grant','ai_debit');
CREATE INDEX billing_refund_original ON billing_ledger(original_debit_id) WHERE kind = 'compensation';
CREATE TABLE billing_trial_grants (
    reference varchar(250) PRIMARY KEY,
    wallet_id varchar(40) NOT NULL UNIQUE REFERENCES billing_wallets(id),
    amount_units bigint NOT NULL CHECK(amount_units BETWEEN 0 AND 9007199254740991),
    posting_id varchar(40) UNIQUE REFERENCES billing_ledger(id)
);
-- Opaque, server-held cursors bind filters and a first-page snapshot without a signing secret.
CREATE TABLE billing_history_cursors (
    id varchar(40) PRIMARY KEY,
    wallet_id varchar(40) NOT NULL REFERENCES billing_wallets(id),
    from_date date,
    to_date date,
    sort varchar(10) NOT NULL CHECK(sort IN ('newest','oldest')),
    page_limit integer NOT NULL CHECK(page_limit BETWEEN 1 AND 100),
    last_sequence bigint NOT NULL,
    upper_sequence bigint NOT NULL,
    expires_at timestamptz NOT NULL DEFAULT (clock_timestamp() + interval '7 days')
);
CREATE INDEX billing_cursor_expiry ON billing_history_cursors(expires_at);

CREATE FUNCTION billing_reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Billing ledger is immutable'; END $$;
CREATE TRIGGER billing_ledger_immutable BEFORE UPDATE OR DELETE ON billing_ledger
    FOR EACH ROW EXECUTE FUNCTION billing_reject_mutation();
CREATE TRIGGER billing_trial_grant_immutable BEFORE UPDATE OR DELETE ON billing_trial_grants
    FOR EACH ROW EXECUTE FUNCTION billing_reject_mutation();
CREATE FUNCTION billing_validate_posting() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE debit billing_ledger%ROWTYPE; op billing_operations%ROWTYPE;
BEGIN
    -- Direct internal corrections also serialize on the same wallet guard.
    PERFORM 1 FROM billing_wallets WHERE id = NEW.wallet_id FOR UPDATE;
    IF NEW.kind = 'compensation' THEN
        SELECT * INTO debit FROM billing_ledger WHERE id = NEW.original_debit_id;
        IF debit.kind IS DISTINCT FROM 'ai_debit' OR debit.wallet_id IS DISTINCT FROM NEW.wallet_id
           OR NEW.amount_units > debit.amount_units - COALESCE(
               (SELECT sum(amount_units) FROM billing_ledger WHERE original_debit_id = debit.id),0) THEN
            RAISE EXCEPTION 'Compensation exceeds original debit or has invalid wallet';
        END IF;
    ELSIF NEW.kind = 'ai_debit' THEN
        SELECT * INTO op FROM billing_operations WHERE id = NEW.operation_id;
        IF op.funding IS DISTINCT FROM 'wallet' OR op.wallet_id IS DISTINCT FROM NEW.wallet_id
           OR op.amount_units IS DISTINCT FROM NEW.amount_units OR op.operation_code IS DISTINCT FROM NEW.operation_code THEN
            RAISE EXCEPTION 'Debit does not match accepted operation';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER billing_validate_ledger BEFORE INSERT ON billing_ledger
    FOR EACH ROW EXECUTE FUNCTION billing_validate_posting();
CREATE FUNCTION billing_guard_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Billing operation cannot be deleted'; END IF;
    IF (to_jsonb(NEW) - ARRAY['state','fence','finished_at']) IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['state','fence','finished_at']) THEN
        RAISE EXCEPTION 'Billing operation snapshot is immutable';
    END IF;
    IF NEW.fence < OLD.fence OR (OLD.state <> 'reserved' AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Terminal billing operation is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER billing_operation_snapshot BEFORE UPDATE OR DELETE ON billing_operations
    FOR EACH ROW EXECUTE FUNCTION billing_guard_snapshot();
CREATE FUNCTION billing_guard_entitlement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Free right cannot be deleted'; END IF;
    IF NEW.code IS DISTINCT FROM OLD.code OR NEW.subject IS DISTINCT FROM OLD.subject
       OR (OLD.consumed_by IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Consumed free right cannot be renewed';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER billing_entitlement_lifetime BEFORE UPDATE OR DELETE ON billing_entitlements
    FOR EACH ROW EXECUTE FUNCTION billing_guard_entitlement();
CREATE FUNCTION billing_guard_price() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE version_key text;
BEGIN
    IF TG_TABLE_NAME = 'billing_prices' THEN
        version_key := CASE WHEN TG_OP = 'DELETE' THEN OLD.version_id ELSE NEW.version_id END;
        -- Lock the version row so adding/updating prices cannot race publication.
        PERFORM 1 FROM billing_price_versions WHERE id = version_key FOR UPDATE;
        IF EXISTS(SELECT 1 FROM billing_price_versions WHERE id = version_key AND published_at IS NOT NULL)
           OR (TG_OP = 'UPDATE' AND OLD.version_id <> NEW.version_id) THEN
            RAISE EXCEPTION 'Published prices are immutable';
        END IF;
    ELSE
        IF TG_OP <> 'INSERT' AND OLD.published_at IS NOT NULL THEN
            RAISE EXCEPTION 'Published configuration is immutable';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER billing_prices_immutable BEFORE INSERT OR UPDATE OR DELETE ON billing_prices
    FOR EACH ROW EXECUTE FUNCTION billing_guard_price();
CREATE TRIGGER billing_versions_immutable BEFORE UPDATE OR DELETE ON billing_price_versions
    FOR EACH ROW EXECUTE FUNCTION billing_guard_price();
CREATE FUNCTION billing_validate_publication() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE version_key text;
BEGIN
    IF TG_TABLE_NAME = 'billing_configuration' THEN
        version_key := NEW.active_version;
    ELSE
        version_key := NEW.id;
    END IF;
    PERFORM 1 FROM billing_price_versions WHERE id = version_key FOR UPDATE;
    IF (SELECT count(*) FROM billing_prices WHERE version_id = version_key) <> 8
       OR (SELECT amount_units FROM billing_prices WHERE version_id = version_key AND operation_code = 'image_generation')
          IS DISTINCT FROM
          (SELECT amount_units FROM billing_prices WHERE version_id = version_key AND operation_code = 'image_entire_edit') THEN
        RAISE EXCEPTION 'Incomplete or inconsistent price catalog';
    END IF;
    IF TG_TABLE_NAME = 'billing_configuration' AND NOT EXISTS(
       SELECT 1 FROM billing_price_versions WHERE id = version_key AND published_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Only published configuration can be activated';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER billing_validate_version BEFORE UPDATE OF published_at ON billing_price_versions
    FOR EACH ROW WHEN (NEW.published_at IS NOT NULL) EXECUTE FUNCTION billing_validate_publication();
CREATE TRIGGER billing_validate_active BEFORE INSERT OR UPDATE ON billing_configuration
    FOR EACH ROW EXECUTE FUNCTION billing_validate_publication();

INSERT INTO billing_price_versions(id,trial_allowance_units,free_entire_successes,effective_from,change_reason)
VALUES ('billing-2026-10-14-v1',600,1,'2026-10-14T00:00:00Z','Initial agreed billing configuration');
INSERT INTO billing_prices(version_id,operation_code,amount_units) VALUES
('billing-2026-10-14-v1','image_generation',400),('billing-2026-10-14-v1','image_entire_edit',400),
('billing-2026-10-14-v1','image_region_edit',200),('billing-2026-10-14-v1','image_erase',200),
('billing-2026-10-14-v1','image_remove_background',200),('billing-2026-10-14-v1','image_outpaint',400),
('billing-2026-10-14-v1','product_analysis',0),('billing-2026-10-14-v1','cpu_only',0);
UPDATE billing_price_versions SET published_at = clock_timestamp() WHERE id = 'billing-2026-10-14-v1';
INSERT INTO billing_configuration(singleton,active_version) VALUES(true,'billing-2026-10-14-v1');
