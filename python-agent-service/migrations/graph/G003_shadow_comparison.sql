-- Bounded shadow-only parity evidence. This table is never a formal result source.

create table agent_graph_shadow_comparison (
    comparison_id varchar(64) primary key,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    thread_id varchar(39) not null,
    command_id varchar(128) not null,
    graph_key varchar(128) not null,
    graph_version varchar(128) not null,
    checkpoint_schema_version varchar(128) not null,
    execution_mode varchar(32) not null default 'SHADOW',
    input_ref varchar(512) not null,
    input_hash varchar(64) not null,
    legacy_output_ref varchar(512) not null,
    legacy_output_hash varchar(64) not null,
    candidate_output_ref varchar(512) not null,
    candidate_output_hash varchar(64) not null,
    comparator_version varchar(128) not null,
    schema_status varchar(32) not null,
    privacy_status varchar(32) not null,
    guardrail_status varchar(32) not null,
    formal_fields_status varchar(32) not null,
    reference_hash_status varchar(32) not null,
    transition_status varchar(32) not null,
    terminal_classification_status varchar(32) not null,
    invariant_status varchar(32) not null,
    detail_json jsonb not null default '{}'::jsonb,
    comparison_hash varchar(64) not null,
    formal_eligible boolean generated always as (false) stored,
    evidence_manifest_ref varchar(512),
    created_at timestamptz not null default statement_timestamp(),
    expires_at timestamptz not null default (statement_timestamp() + interval '30 days'),
    constraint fk_agent_graph_shadow_command
        foreign key (thread_id, command_id)
        references agent_graph_command(thread_id, command_id) on delete restrict,
    constraint ck_agent_graph_shadow_mode check (execution_mode = 'SHADOW'),
    constraint ck_agent_graph_shadow_hashes
        check (
            input_hash ~ '^[0-9a-f]{64}$'
            and legacy_output_hash ~ '^[0-9a-f]{64}$'
            and candidate_output_hash ~ '^[0-9a-f]{64}$'
            and comparison_hash ~ '^[0-9a-f]{64}$'
        ),
    constraint ck_agent_graph_shadow_schema
        check (schema_status in ('PASS', 'DIFF', 'NOT_EVALUATED')),
    constraint ck_agent_graph_shadow_privacy
        check (privacy_status in ('PASS', 'DIFF', 'NOT_EVALUATED')),
    constraint ck_agent_graph_shadow_guardrail
        check (guardrail_status in ('PASS', 'DIFF', 'NOT_EVALUATED')),
    constraint ck_agent_graph_shadow_formal_fields
        check (formal_fields_status in ('PASS', 'DIFF', 'NOT_EVALUATED')),
    constraint ck_agent_graph_shadow_reference_hash
        check (reference_hash_status in ('PASS', 'DIFF', 'NOT_EVALUATED')),
    constraint ck_agent_graph_shadow_transition
        check (transition_status in ('PASS', 'DIFF', 'NOT_EVALUATED')),
    constraint ck_agent_graph_shadow_terminal
        check (terminal_classification_status in ('PASS', 'DIFF', 'NOT_EVALUATED')),
    constraint ck_agent_graph_shadow_invariant
        check (invariant_status in ('PASS', 'DIFF', 'NOT_EVALUATED')),
    constraint ck_agent_graph_shadow_detail
        check (
            jsonb_typeof(detail_json) = 'object'
            and octet_length(detail_json::text) <= 65536
        ),
    constraint ck_agent_graph_shadow_evidence_ref
        check (evidence_manifest_ref is null or length(evidence_manifest_ref) between 1 and 512),
    constraint ck_agent_graph_shadow_expiry
        check (expires_at > created_at and expires_at <= created_at + interval '30 days')
);

create index idx_agent_graph_shadow_expiry
    on agent_graph_shadow_comparison(expires_at)
    where evidence_manifest_ref is null;
create index idx_agent_graph_shadow_version_time
    on agent_graph_shadow_comparison(
        graph_key, graph_version, checkpoint_schema_version, created_at desc
    );

create table agent_graph_shadow_cleanup_receipt (
    cleanup_receipt_id varchar(64) primary key,
    comparison_id varchar(64) not null unique,
    comparison_hash varchar(64) not null,
    expired_at timestamptz not null,
    deleted_at timestamptz not null default clock_timestamp(),
    deleted_by varchar(128) not null,
    constraint ck_agent_graph_shadow_cleanup_hash
        check (comparison_hash ~ '^[0-9a-f]{64}$')
);

create function reject_agent_graph_shadow_mutation()
returns trigger
language plpgsql
as $function$
begin
    if new is not distinct from old then
        return new;
    end if;
    if old.evidence_manifest_ref is null
        and new.evidence_manifest_ref is not null
        and (to_jsonb(new) - 'evidence_manifest_ref')
            = (to_jsonb(old) - 'evidence_manifest_ref') then
        return new;
    end if;
    raise exception using errcode = '23514', message = 'shadow comparisons are immutable';
end;
$function$;

create function guard_agent_graph_shadow_insert()
returns trigger
language plpgsql
as $function$
begin
    if not exists (
        select 1
          from graph_thread_registry thread
          join agent_graph_command command
            on command.thread_id = thread.thread_id
           and command.command_id = new.command_id
         where thread.tenant_surrogate = new.tenant_surrogate
           and thread.case_id = new.case_id
           and thread.thread_id = new.thread_id
           and thread.graph_key = new.graph_key
           and thread.graph_version = new.graph_version
           and thread.checkpoint_schema_version = new.checkpoint_schema_version
           and command.graph_key = new.graph_key
           and command.graph_version = new.graph_version
           and command.checkpoint_schema_version = new.checkpoint_schema_version
           and command.execution_mode = 'SHADOW'
    ) then
        raise exception using errcode = '23514',
            message = 'shadow comparison identity conflicts with thread or command';
    end if;
    return new;
end;
$function$;

create function guard_agent_graph_shadow_delete()
returns trigger
language plpgsql
as $function$
begin
    if old.expires_at > statement_timestamp() or old.evidence_manifest_ref is not null then
        raise exception using errcode = '23514',
            message = 'shadow comparison is retained';
    end if;
    insert into agent_graph_shadow_cleanup_receipt (
        cleanup_receipt_id, comparison_id, comparison_hash,
        expired_at, deleted_by
    ) values (
        old.comparison_id, old.comparison_id, old.comparison_hash,
        old.expires_at, current_user
    );
    return old;
end;
$function$;

create function reject_agent_graph_shadow_cleanup_receipt_mutation()
returns trigger
language plpgsql
as $function$
begin
    raise exception using errcode = '23514',
        message = 'shadow cleanup receipts are append-only';
end;
$function$;

create trigger trg_reject_agent_graph_shadow_mutation
before update on agent_graph_shadow_comparison
for each row execute function reject_agent_graph_shadow_mutation();

create trigger trg_guard_agent_graph_shadow_insert
before insert on agent_graph_shadow_comparison
for each row execute function guard_agent_graph_shadow_insert();

create trigger trg_guard_agent_graph_shadow_delete
before delete on agent_graph_shadow_comparison
for each row execute function guard_agent_graph_shadow_delete();

create trigger trg_reject_agent_graph_shadow_cleanup_receipt_mutation
before update or delete on agent_graph_shadow_cleanup_receipt
for each row execute function reject_agent_graph_shadow_cleanup_receipt_mutation();
