-- Independent technical accounting. No workspace/financial FK; no prompts or raw responses.
create table billing_ai_attempts (
    id varchar(36) primary key,
    job_namespace varchar(40) not null,
    job_id varchar(200) not null,
    job_attempt integer not null check(job_attempt>0),
    operation_id varchar(40),
    funding varchar(40) not null check(funding in ('wallet','free_entitlement','free_operation','legacy','unknown')),
    provider varchar(40) not null,
    is_stub boolean not null,
    provider_outcome varchar(40) not null check(provider_outcome in ('successful','error','timeout','stub','unknown')),
    job_outcome varchar(40) not null check(job_outcome in ('completed','failed','stale','unknown')),
    http_status integer,
    requested_main_model varchar(160), requested_image_model varchar(160),
    requested_size varchar(20), requested_quality varchar(20),
    started_at timestamptz not null, finished_at timestamptz,
    response_id varchar(160), actual_main_model varchar(160),
    provider_created_at bigint, provider_completed_at bigint,
    usage_schema_version integer not null
);
create index billing_ai_attempts_job on billing_ai_attempts(job_namespace,job_id,job_attempt);
create index billing_ai_attempts_operation on billing_ai_attempts(operation_id);
-- response_id is deliberately not unique: correlation does not guarantee provider exactly-once.
create table billing_ai_usage (
    attempt_id varchar(36) not null references billing_ai_attempts(id),
    component_index integer not null,
    scope varchar(40) not null,
    provider_item_id varchar(160), actual_model varchar(160),
    actual_size varchar(20), actual_quality varchar(20), provider_status varchar(160),
    counters jsonb not null,
    classification varchar(40) not null,
    cost_state varchar(20) not null check(cost_state in ('unknown','estimated','stub')),
    estimated_amount numeric(30,12), currency varchar(3), rate_version varchar(100),
    primary key(attempt_id,component_index),
    check ((cost_state='estimated' and estimated_amount is not null and estimated_amount>=0 and currency is not null and rate_version is not null)
        or (cost_state<>'estimated' and estimated_amount is null and currency is null and rate_version is null))
);
-- Manually curated, exact actual model only. Deliberately no guessed production rates or FX.
create table billing_provider_rates (
    provider varchar(40) not null, model varchar(160) not null, scope varchar(40) not null,
    version varchar(100) not null, category varchar(40) not null,
    currency varchar(3) not null check(currency ~ '^[A-Z]{3}$'),
    amount_per_million numeric(24,12) not null check(amount_per_million>=0),
    effective_from timestamptz not null, source_url text not null,
    primary key(provider,model,scope,version,category)
);
create function billing_provider_rate_immutable() returns trigger language plpgsql as $$
begin raise exception 'Provider rates are immutable; insert a new version'; end; $$;
create trigger billing_provider_rate_immutable before update or delete on billing_provider_rates
    for each row execute function billing_provider_rate_immutable();
