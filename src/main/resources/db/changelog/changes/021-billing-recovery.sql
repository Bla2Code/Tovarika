-- Bounded reconciliation scans, without financial/workspace foreign keys.
CREATE INDEX billing_pending_recovery ON billing_operations(job_namespace, created_at, id) WHERE state='reserved';
