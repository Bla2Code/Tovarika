-- Run with psql -X -v email=client@example.com -v amount=25.50 -f scripts/debit-credits.sql
-- Optional: -v reason='Manual adjustment'. Every invocation is a NEW debit, unrelated to payments.
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
    \set reason 'Manual credit debit'
\endif

BEGIN;
SET LOCAL lock_timeout = '10s';

-- Quote inputs as SQL literals instead of interpolating them into the DO body.
SELECT set_config('tovarika.manual_debit.email', lower(btrim(:'email')), true) AS email,
       set_config('tovarika.manual_debit.amount', btrim(:'amount'), true) AS amount,
       set_config('tovarika.manual_debit.reason', btrim(:'reason'), true) AS reason
\gset debit_

DO $$
DECLARE
    target_email text := current_setting('tovarika.manual_debit.email');
    debit_amount text := current_setting('tovarika.manual_debit.amount');
    debit_reason text := current_setting('tovarika.manual_debit.reason');
    amount_units numeric;
    target_user_id text;
    target_wallet_id text;
    wallet billing_wallets%ROWTYPE;
    available_units numeric;
    remaining_to_debit bigint;
    take_units bigint;
    lot record;
    cutoff timestamptz;
    posting_id text := 'wtx_' || replace(gen_random_uuid()::text, '-', '');
    debit_reference text := 'manual_debit:' || replace(gen_random_uuid()::text, '-', '') || ':' || debit_reason;
    posted_at timestamptz;
BEGIN
    IF target_email = '' THEN
        RAISE EXCEPTION 'Pass the recipient email with -v email=client@example.com';
    END IF;
    IF debit_amount !~ '^[0-9]{1,14}([.][0-9]{1,2})?$' THEN
        RAISE EXCEPTION 'Pass a positive amount in Tovariks with -v amount=25.50 (up to 2 decimal places)';
    END IF;
    amount_units := debit_amount::numeric * 100;
    IF amount_units < 1 OR amount_units > 9007199254740991 THEN
        RAISE EXCEPTION 'Amount must be between 0.01 and 90071992547409.91 Tovariks';
    END IF;
    IF char_length(debit_reason) NOT BETWEEN 1 AND 200 THEN
        RAISE EXCEPTION 'Reason must contain between 1 and 200 characters';
    END IF;

    -- Use the same auth -> topology -> owner -> wallet lock order as the backend.
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

    -- The wallet may still contain expired lots awaiting the normal backend expiry pass.
    -- Exclude those lots and active reserves from a manual debit.
    cutoff := clock_timestamp();
    SELECT coalesce(sum(c.remaining_units - c.reserved_units), 0) INTO available_units
    FROM billing_credit_lots c JOIN billing_wallet_links l ON l.wallet_id = c.wallet_id
    WHERE l.canonical_wallet_id = target_wallet_id AND (c.expires_at IS NULL OR c.expires_at > cutoff);
    available_units := least(available_units, wallet.total_units - wallet.reserved_units);
    IF amount_units > available_units THEN
        RAISE EXCEPTION 'Insufficient available credits: requested % T, available % T (excluding expired and reserved credits)',
            amount_units / 100, available_units / 100;
    END IF;

    -- Match normal spending order: earliest expiration first, then non-expiring lots.
    remaining_to_debit := amount_units::bigint;
    FOR lot IN
        SELECT c.* FROM billing_credit_lots c JOIN billing_wallet_links l ON l.wallet_id = c.wallet_id
        WHERE l.canonical_wallet_id = target_wallet_id AND c.remaining_units > c.reserved_units
          AND (c.expires_at IS NULL OR c.expires_at > cutoff)
        ORDER BY c.expires_at ASC NULLS LAST, c.created_at, c.id
    LOOP
        take_units := least(remaining_to_debit, lot.remaining_units - lot.reserved_units);
        UPDATE billing_credit_lots SET remaining_units = remaining_units - take_units WHERE id = lot.id;
        remaining_to_debit := remaining_to_debit - take_units;
        EXIT WHEN remaining_to_debit = 0;
    END LOOP;
    IF remaining_to_debit <> 0 THEN
        RAISE EXCEPTION 'Wallet lot balance invariant failed';
    END IF;

    posted_at := greatest(coalesce(wallet.last_posted_at, '-infinity'::timestamptz), clock_timestamp());
    UPDATE billing_wallets
    SET total_units = total_units - amount_units::bigint,
        ledger_sequence = ledger_sequence + 1,
        last_posted_at = posted_at,
        updated_at = greatest(updated_at, posted_at)
    WHERE id = target_wallet_id RETURNING * INTO wallet;

    -- Reuse the existing non-AI debit kind without changing schema, API or used_units.
    INSERT INTO billing_ledger(id, wallet_id, sequence, kind, amount_units,
                               balance_after_units, reference, occurred_at)
    VALUES (posting_id, target_wallet_id, wallet.ledger_sequence, 'credit_expiry', amount_units::bigint,
            wallet.total_units, debit_reference, posted_at);
    INSERT INTO billing_ledger_order(posting_id, wallet_id, sequence)
    VALUES (posting_id, target_wallet_id, wallet.ledger_sequence);
    PERFORM set_config('tovarika.manual_debit.posting_id', posting_id, true);
END $$;

SELECT current_setting('tovarika.manual_debit.posting_id') AS posting_id
\gset debit_
COMMIT;
\echo 'Debited' :debit_amount 'T from' :debit_email 'Posting:' :debit_posting_id
