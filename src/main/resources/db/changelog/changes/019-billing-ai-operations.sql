-- Domain-to-finance link only; no financial FK to workspace and no legacy backfill.
ALTER TABLE project_jobs ADD COLUMN billing_operation_id varchar(100),
    ADD COLUMN billing_fence bigint NOT NULL DEFAULT 0 CHECK (billing_fence >= 0);
CREATE UNIQUE INDEX project_jobs_billing_operation ON project_jobs(billing_operation_id)
    WHERE billing_operation_id IS NOT NULL;
