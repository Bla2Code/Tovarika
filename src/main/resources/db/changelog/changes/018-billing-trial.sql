-- Preserve original financial streams; only the mutable balance guard and history projection merge.
CREATE TABLE billing_wallet_links (
    wallet_id varchar(40) PRIMARY KEY REFERENCES billing_wallets(id),
    canonical_wallet_id varchar(40) NOT NULL REFERENCES billing_wallets(id)
);
CREATE INDEX billing_wallet_group ON billing_wallet_links(canonical_wallet_id);
INSERT INTO billing_wallet_links SELECT id,id FROM billing_wallets;
CREATE TABLE billing_ledger_order (
    posting_id varchar(40) PRIMARY KEY REFERENCES billing_ledger(id),
    wallet_id varchar(40) NOT NULL REFERENCES billing_wallets(id),
    sequence bigint NOT NULL CHECK(sequence > 0),
    UNIQUE(wallet_id, sequence) DEFERRABLE INITIALLY DEFERRED
);
INSERT INTO billing_ledger_order SELECT id,wallet_id,sequence FROM billing_ledger;
-- No balances, grants or auth/workspace data are imported or deleted by this migration.

-- Domain replay retains trial owner scopes; index the converted alias lookup.
CREATE INDEX ix_trial_sessions_converted_owner ON trial_sessions(owner_user_id,id) WHERE owner_user_id IS NOT NULL;
