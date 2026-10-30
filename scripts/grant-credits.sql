-- Run with psql -X -v email=client@example.com -v amount=25.50 -f scripts/grant-credits.sql
-- Optional: -v reason='Support compensation'. Every invocation is a NEW grant.
\set ON_ERROR_STOP on
\if :{?email}
\else
    \set email ''
\endif
\if :{?amount}
\else
    \set amount ''
\endif
\if :{?reason}
\else
    \set reason 'Manual credit grant'
\endif

BEGIN;
SET LOCAL lock_timeout = '10s';

-- SQL-literal quoting keeps psql inputs out of the executable DO body.
SELECT set_config('tovarika.manual_grant.email', lower(btrim(:'email')), true) AS email,
       set_config('tovarika.manual_grant.amount', btrim(:'amount'), true) AS amount,
       set_config('tovarika.manual_grant.reason', btrim(:'reason'), true) AS reason
\gset grant_

DO $$
DECLARE
    target_email text := current_setting('tovarika.manual_grant.email');
    grant_amount text := current_setting('tovarika.manual_grant.amount');
    grant_reason text := current_setting('tovarika.manual_grant.reason');
    amount_units numeric;
    target_user_id text;
    target_wallet_id text;
    wallet billing_wallets%ROWTYPE;
    posting_id text := 'wtx_' || replace(gen_random_uuid()::text, '-', '');
    grant_reference text := 'manual:' || replace(gen_random_uuid()::text, '-', '') || ':' || grant_reason;
    posted_at timestamptz;
BEGIN
    IF target_email = '' THEN
        RAISE EXCEPTION 'Pass the recipient email with -v email=client@example.com';
    END IF;
    IF grant_amount !~ '^[0-9]{1,14}([.][0-9]{1,2})?$' THEN
        RAISE EXCEPTION 'Pass a positive amount in Tovariks with -v amount=25.50 (up to 2 decimal places)';
    END IF;
    amount_units := grant_amount::numeric * 100;
    IF amount_units < 1 OR amount_units > 9007199254740991 THEN
        RAISE EXCEPTION 'Amount must be between 0.01 and 90071992547409.91 Tovariks';
    END IF;
    IF char_length(grant_reason) NOT BETWEEN 1 AND 210 THEN
        RAISE EXCEPTION 'Reason must contain between 1 and 210 characters';
    END IF;

    -- Match the application's auth -> topology -> owner -> wallet lock order.
    BEGIN
        SELECT id INTO STRICT target_user_id FROM users
        WHERE lower(email) = target_email AND status = 'ACTIVE'
        ORDER BY id FOR UPDATE;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE EXCEPTION 'No active user found for email %', target_email;
        WHEN TOO_MANY_ROWS THEN
            RAISE EXCEPTION 'Multiple active users found for email %; resolve the ambiguity first', target_email;
    END;
    PERFORM pg_advisory_xact_lock_shared(7204, 1);
    PERFORM pg_advisory_xact_lock(hashtextextended('user:' || target_user_id, 7202));

    SELECT l.canonical_wallet_id INTO target_wallet_id
    FROM billing_owners o JOIN billing_wallets w ON w.account_id = o.account_id
    JOIN billing_wallet_links l ON l.wallet_id = w.id
    WHERE o.kind = 'user' AND o.subject = target_user_id;
    IF target_wallet_id IS NULL THEN
        RAISE EXCEPTION 'Billing wallet is not initialized; the user must sign in and open their balance first';
    END IF;
    PERFORM w.id FROM billing_wallets w JOIN billing_wallet_links l ON l.wallet_id = w.id
    WHERE l.canonical_wallet_id = target_wallet_id ORDER BY w.id FOR UPDATE OF w;
    SELECT * INTO STRICT wallet FROM billing_wallets WHERE id = target_wallet_id;
    IF wallet.total_units::numeric + amount_units > 9007199254740991 THEN
        RAISE EXCEPTION 'The resulting balance exceeds the supported maximum';
    END IF;

    posted_at := greatest(coalesce(wallet.last_posted_at, '-infinity'::timestamptz), clock_timestamp());
    UPDATE billing_wallets
    SET total_units = total_units + amount_units::bigint,
        ledger_sequence = ledger_sequence + 1,
        last_posted_at = posted_at,
        updated_at = greatest(updated_at, posted_at)
    WHERE id = target_wallet_id RETURNING * INTO wallet;

    -- trial_grant is already visible in credit history. Do not touch billing_trial_grants:
    -- that table controls the separate, one-time initial allowance.
    INSERT INTO billing_ledger(id, wallet_id, sequence, kind, amount_units,
                               balance_after_units, reference, occurred_at)
    VALUES (posting_id, target_wallet_id, wallet.ledger_sequence, 'trial_grant', amount_units::bigint,
            wallet.total_units, grant_reference, posted_at);
    INSERT INTO billing_ledger_order(posting_id, wallet_id, sequence)
    VALUES (posting_id, target_wallet_id, wallet.ledger_sequence);
    INSERT INTO billing_credit_lots(id, wallet_id, reference, original_units, remaining_units, created_at, expires_at)
    VALUES ('lot_' || replace(gen_random_uuid()::text, '-', ''), target_wallet_id, grant_reference,
            amount_units::bigint, amount_units::bigint, posted_at, NULL);
    PERFORM set_config('tovarika.manual_grant.posting_id', posting_id, true);
END $$;

SELECT current_setting('tovarika.manual_grant.posting_id') AS posting_id
\gset grant_
COMMIT;
\echo 'Granted' :grant_amount 'T to' :grant_email 'Posting:' :grant_posting_id
