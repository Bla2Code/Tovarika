-- Successful activity receipts are independent of workspace lifetime and monetary admission.
CREATE TABLE billing_activity_outbox (
    reference varchar(250) PRIMARY KEY,
    owner_kind varchar(20) NOT NULL CHECK(owner_kind IN ('user','trial')),
    owner_subject varchar(100) NOT NULL,
    operation_code varchar(40) NOT NULL CHECK(operation_code IN ('image_generation','image_entire_edit',
        'image_region_edit','image_erase','image_remove_background','image_outpaint','product_analysis','cpu_only')),
    billing_operation_id varchar(40) REFERENCES billing_operations(id),
    occurred_at timestamptz NOT NULL,
    posting_id varchar(40) REFERENCES billing_ledger(id)
);
CREATE INDEX billing_activity_pending ON billing_activity_outbox(owner_kind,owner_subject,occurred_at,reference) WHERE posting_id IS NULL;
CREATE FUNCTION billing_guard_activity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR (to_jsonb(NEW)-'posting_id') IS DISTINCT FROM (to_jsonb(OLD)-'posting_id')
       OR (OLD.posting_id IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Successful activity receipt is immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER billing_activity_immutable BEFORE UPDATE OR DELETE ON billing_activity_outbox FOR EACH ROW EXECUTE FUNCTION billing_guard_activity();

-- Zero events are journal entries, never debits or compensations. Existing money stays immutable.
ALTER TABLE billing_ledger DROP CONSTRAINT billing_ledger_kind_check,
    DROP CONSTRAINT billing_ledger_amount_units_check,
    DROP CONSTRAINT billing_ledger_check1,
    DROP CONSTRAINT billing_ledger_operation_code_check;
ALTER TABLE billing_ledger ADD CONSTRAINT billing_ledger_kind_check CHECK(kind IN ('trial_grant','ai_debit','compensation','free_operation')),
    ADD CONSTRAINT billing_ledger_amount_units_check CHECK(amount_units BETWEEN 0 AND 9007199254740991 AND ((kind='free_operation')=(amount_units=0))),
    ADD CONSTRAINT billing_ledger_activity_code_check CHECK((kind IN ('ai_debit','free_operation'))=(operation_code IS NOT NULL)),
    ADD CONSTRAINT billing_ledger_operation_code_check CHECK(operation_code IS NULL OR operation_code IN ('image_generation','image_entire_edit',
        'image_region_edit','image_erase','image_remove_background','image_outpaint','product_analysis','cpu_only')),
    ADD CONSTRAINT billing_ledger_paid_code_check CHECK(kind<>'ai_debit' OR operation_code NOT IN ('product_analysis','cpu_only'));

-- Backfill only durable successful results; no charges/grants, no workspace FK or names/prompts.
INSERT INTO billing_activity_outbox(reference,owner_kind,owner_subject,operation_code,billing_operation_id,occurred_at)
SELECT 'project_jobs:'||q.id,CASE WHEN p.owner_user_id IS NULL THEN 'trial' ELSE 'user' END,
       coalesce(p.owner_user_id,p.owner_trial_session_id),
       coalesce(o.operation_code,CASE WHEN q.type='card_generation' THEN 'image_generation'
         WHEN q.type='card_region_edit' THEN 'image_region_edit'
         WHEN q.image_edit_payload->>'kind'='entire' THEN 'image_entire_edit'
         WHEN q.image_edit_payload->>'kind'='region' THEN 'image_region_edit'
         WHEN q.image_edit_payload->>'kind'='erase' THEN 'image_erase'
         WHEN q.image_edit_payload->>'kind'='remove_background' THEN 'image_remove_background'
         WHEN q.image_edit_payload->>'kind'='resize' AND q.image_edit_payload->>'mode'='outpaint' THEN 'image_outpaint'
         ELSE 'cpu_only' END),q.billing_operation_id,q.finished_at
FROM project_jobs q JOIN projects j ON j.id=q.project_id JOIN products p ON p.id=j.product_id
LEFT JOIN billing_operations o ON o.id=q.billing_operation_id
WHERE q.status='completed' AND q.finished_at IS NOT NULL AND coalesce(p.owner_user_id,p.owner_trial_session_id) IS NOT NULL
  AND q.type IN ('card_generation','card_image_edit','card_region_edit');
INSERT INTO billing_activity_outbox(reference,owner_kind,owner_subject,operation_code,occurred_at)
SELECT 'analysis_jobs:'||q.id,CASE WHEN p.owner_user_id IS NULL THEN 'trial' ELSE 'user' END,
       coalesce(p.owner_user_id,p.owner_trial_session_id),'product_analysis',q.finished_at
FROM analysis_jobs q JOIN products p ON p.id=q.product_id
WHERE q.status='completed' AND q.finished_at IS NOT NULL AND coalesce(p.owner_user_id,p.owner_trial_session_id) IS NOT NULL;
