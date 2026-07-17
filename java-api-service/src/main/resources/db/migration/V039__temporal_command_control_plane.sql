-- Durable command intake and fenced process projections for the Temporal control plane.
-- Existing cases remain on the legacy writer. No row created here starts a Workflow.

create table case_command (
    id varchar(64) primary key,
    command_id varchar(128) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    case_command_sequence bigint not null,
    command_type varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    actor_id varchar(128) not null,
    actor_role varchar(32) not null,
    actor_scopes_json jsonb not null,
    payload_schema_version varchar(128) not null,
    payload_uri varchar(1024) not null,
    payload_sha256 varchar(64) not null,
    payload_size_bytes bigint not null,
    expected_process_revision bigint not null,
    occurred_at timestamptz not null,
    deadline_at timestamptz not null,
    traceparent varchar(55) not null,
    request_hash varchar(64) not null,
    command_status varchar(32) not null,
    status_reason_code varchar(64),
    result_uri varchar(1024),
    result_sha256 varchar(64),
    accepted_at timestamptz not null default now(),
    orchestrated_at timestamptz,
    applied_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint fk_case_command_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint uq_case_command_scope
        unique (id, tenant_surrogate, case_id),
    constraint uq_case_command_delivery_identity
        unique (id, tenant_surrogate, case_id, command_id),
    constraint ck_case_command_identity
        check (length(tenant_surrogate) > 0 and length(command_id) > 0),
    constraint ck_case_command_sequence
        check (case_command_sequence > 0),
    constraint ck_case_command_room
        check (
            room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')
            and room_epoch >= 0
        ),
    constraint ck_case_command_actor
        check (
            actor_role in ('USER', 'MERCHANT', 'PLATFORM_REVIEWER', 'ADMIN', 'SYSTEM')
            and jsonb_typeof(actor_scopes_json) = 'array'
            and jsonb_array_length(actor_scopes_json) between 1 and 32
        ),
    constraint ck_case_command_payload_ref
        check (
            payload_uri ~ '^(s3|minio|urn):'
            and payload_sha256 ~ '^[0-9a-f]{64}$'
            and payload_size_bytes between 0 and 1073741824
        ),
    constraint ck_case_command_revision
        check (expected_process_revision >= 0),
    constraint ck_case_command_time_budget
        check (deadline_at > occurred_at),
    constraint ck_case_command_traceparent
        check (traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$'),
    constraint ck_case_command_request_hash
        check (request_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_case_command_result
        check (
            (result_uri is null and result_sha256 is null)
            or
            (result_uri ~ '^(s3|minio|urn):' and result_sha256 ~ '^[0-9a-f]{64}$')
        ),
    constraint ck_case_command_status
        check (command_status in (
            'PENDING_ORCHESTRATION', 'ORCHESTRATION_ACCEPTED', 'APPLIED',
            'REJECTED', 'FAILED', 'EXPIRED'
        )),
    constraint ck_case_command_status_times
        check (
            (orchestrated_at is null or orchestrated_at >= accepted_at)
            and (applied_at is null or applied_at >= accepted_at)
        ),
    constraint ck_case_command_version
        check (version >= 0)
);

create unique index uq_case_command_tenant_command
    on case_command(tenant_surrogate, command_id);

create unique index uq_case_command_case_sequence
    on case_command(case_id, case_command_sequence);

create index idx_case_command_case_status
    on case_command(case_id, command_status, case_command_sequence);

create table case_command_outbox (
    id varchar(64) primary key,
    case_command_id varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    workflow_id varchar(128) not null,
    workflow_type varchar(128) not null,
    task_queue varchar(128) not null,
    delivery_kind varchar(32) not null,
    update_id varchar(128) not null,
    outbox_status varchar(32) not null,
    available_at timestamptz not null,
    attempt_count integer not null default 0,
    lease_owner varchar(128),
    lease_expires_at timestamptz,
    last_attempt_at timestamptz,
    delivered_at timestamptz,
    temporal_run_id varchar(128),
    last_error_code varchar(64),
    last_error_detail text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint fk_case_command_outbox_command
        foreign key (case_command_id, tenant_surrogate, case_id, update_id)
        references case_command(id, tenant_surrogate, case_id, command_id),
    constraint fk_case_command_outbox_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint uq_case_command_outbox_command unique (case_command_id),
    constraint uq_case_command_outbox_update unique (tenant_surrogate, update_id),
    constraint ck_case_command_outbox_delivery
        check (delivery_kind in ('UPDATE_WITH_START', 'UPDATE', 'SIGNAL')),
    constraint ck_case_command_outbox_status
        check (outbox_status in ('PENDING', 'CLAIMED', 'RETRY', 'DELIVERED', 'DEAD_LETTER')),
    constraint ck_case_command_outbox_attempt
        check (attempt_count >= 0),
    constraint ck_case_command_outbox_lease
        check (
            (lease_owner is null and lease_expires_at is null)
            or
            (lease_owner is not null and lease_expires_at is not null)
        ),
    constraint ck_case_command_outbox_delivery_time
        check (delivered_at is null or delivered_at >= created_at),
    constraint ck_case_command_outbox_version
        check (version >= 0)
);

create index idx_case_command_outbox_pending
    on case_command_outbox(available_at, created_at)
    where outbox_status in ('PENDING', 'RETRY');

create index idx_case_command_outbox_lease
    on case_command_outbox(lease_expires_at)
    where outbox_status = 'CLAIMED';

create table case_process_projection (
    case_id varchar(64) primary key,
    tenant_surrogate varchar(128) not null,
    macro_phase varchar(64) not null,
    current_room varchar(32),
    room_phase varchar(64) not null,
    writer_mode varchar(16) not null,
    process_revision bigint not null,
    room_epoch bigint not null,
    fencing_token bigint not null,
    last_command_sequence bigint not null default 0,
    last_case_event_sequence bigint not null default 0,
    projected_deadline_at timestamptz,
    temporal_workflow_id varchar(128),
    temporal_run_id varchar(128),
    temporal_build_id varchar(128),
    projection_ref varchar(1024),
    projection_sha256 varchar(64),
    projected_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint fk_case_process_projection_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint ck_case_process_projection_room
        check (current_room is null or current_room in (
            'INTAKE', 'EVIDENCE', 'HEARING', 'DRAFT', 'REVIEW', 'OUTCOME'
        )),
    constraint ck_case_process_projection_writer
        check (writer_mode in ('LEGACY', 'SHADOW', 'TEMPORAL')),
    constraint ck_case_process_projection_revision
        check (
            process_revision >= 0
            and room_epoch >= 0
            and fencing_token >= 0
            and last_command_sequence >= 0
            and last_case_event_sequence >= 0
        ),
    constraint ck_case_process_projection_temporal_binding
        check (
            writer_mode <> 'TEMPORAL'
            or (
                temporal_workflow_id is not null
                and temporal_run_id is not null
                and temporal_build_id is not null
                and fencing_token > 0
            )
        ),
    constraint ck_case_process_projection_ref
        check (
            (projection_ref is null and projection_sha256 is null)
            or
            (projection_ref ~ '^(s3|minio|urn):'
                and projection_sha256 ~ '^[0-9a-f]{64}$')
        ),
    constraint ck_case_process_projection_version
        check (version >= 0)
);

create unique index uq_case_process_projection_workflow
    on case_process_projection(temporal_workflow_id)
    where temporal_workflow_id is not null;

create index idx_case_process_projection_phase
    on case_process_projection(macro_phase, current_room, room_phase);

create unique index uq_case_room_scope
    on case_room(id, case_id, room_type);

create table case_room_epoch (
    id varchar(64) primary key,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_id varchar(64),
    room_type varchar(32) not null,
    room_epoch bigint not null,
    writer_mode varchar(16) not null,
    lifecycle_status varchar(16) not null,
    process_revision bigint not null,
    room_revision bigint not null,
    fencing_token bigint not null,
    temporal_workflow_id varchar(128),
    temporal_run_id varchar(128),
    temporal_build_id varchar(128),
    graph_key varchar(128),
    graph_version varchar(128),
    checkpoint_schema_version varchar(128),
    stream_protocol varchar(64),
    activated_at timestamptz not null,
    terminal_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint fk_case_room_epoch_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_case_room_epoch_room
        foreign key (room_id, case_id, room_type)
        references case_room(id, case_id, room_type),
    constraint ck_case_room_epoch_room
        check (room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')),
    constraint ck_case_room_epoch_writer
        check (writer_mode in ('LEGACY', 'SHADOW', 'TEMPORAL')),
    constraint ck_case_room_epoch_lifecycle
        check (lifecycle_status in ('ACTIVE', 'TERMINAL')),
    constraint ck_case_room_epoch_revision
        check (
            room_epoch >= 0
            and process_revision >= 0
            and room_revision >= 0
            and fencing_token >= 0
        ),
    constraint ck_case_room_epoch_temporal_binding
        check (
            writer_mode <> 'TEMPORAL'
            or (
                temporal_workflow_id is not null
                and temporal_run_id is not null
                and temporal_build_id is not null
                and fencing_token > 0
            )
        ),
    constraint ck_case_room_epoch_terminal
        check (
            (lifecycle_status = 'ACTIVE' and terminal_at is null)
            or
            (lifecycle_status = 'TERMINAL' and terminal_at is not null)
        ),
    constraint ck_case_room_epoch_version
        check (version >= 0)
);

create unique index uq_case_room_epoch_case_room_epoch
    on case_room_epoch(case_id, room_type, room_epoch);

create unique index uq_case_room_epoch_active
    on case_room_epoch(case_id, room_type)
    where lifecycle_status = 'ACTIVE';

create unique index uq_case_room_epoch_workflow
    on case_room_epoch(temporal_workflow_id)
    where temporal_workflow_id is not null;

create index idx_case_room_epoch_case_mode
    on case_room_epoch(case_id, writer_mode, lifecycle_status);

create table domain_operation (
    id varchar(64) primary key,
    operation_key varchar(128) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    case_command_id varchar(64),
    operation_type varchar(64) not null,
    room_type varchar(32),
    room_epoch bigint not null,
    process_revision bigint not null,
    fencing_token bigint not null,
    request_hash varchar(64) not null,
    operation_status varchar(32) not null,
    result_uri varchar(1024),
    result_sha256 varchar(64),
    failure_code varchar(64),
    failure_detail text,
    started_at timestamptz not null,
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint fk_domain_operation_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_domain_operation_command
        foreign key (case_command_id, tenant_surrogate, case_id)
        references case_command(id, tenant_surrogate, case_id),
    constraint ck_domain_operation_room
        check (room_type is null or room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')),
    constraint ck_domain_operation_revision
        check (room_epoch >= 0 and process_revision >= 0 and fencing_token >= 0),
    constraint ck_domain_operation_request_hash
        check (request_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_domain_operation_status
        check (operation_status in (
            'STARTED', 'COMPLETED', 'FAILED', 'COMPENSATION_REQUIRED', 'COMPENSATED'
        )),
    constraint ck_domain_operation_result
        check (
            (result_uri is null and result_sha256 is null)
            or
            (result_uri ~ '^(s3|minio|urn):' and result_sha256 ~ '^[0-9a-f]{64}$')
        ),
    constraint ck_domain_operation_completion
        check (
            (operation_status = 'STARTED' and completed_at is null)
            or
            (operation_status <> 'STARTED' and completed_at is not null)
        ),
    constraint ck_domain_operation_version
        check (version >= 0)
);

create unique index uq_domain_operation_tenant_key
    on domain_operation(tenant_surrogate, operation_key);

create index idx_domain_operation_case_status
    on domain_operation(case_id, operation_status, started_at);

create table process_reconciliation_issue (
    id varchar(64) primary key,
    issue_key varchar(128) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    issue_type varchar(64) not null,
    issue_scope varchar(32) not null,
    severity varchar(16) not null,
    issue_status varchar(16) not null,
    room_type varchar(32),
    room_epoch bigint not null,
    process_revision bigint not null,
    fencing_token bigint not null,
    expected_ref varchar(1024),
    expected_sha256 varchar(64),
    actual_ref varchar(1024),
    actual_sha256 varchar(64),
    details_json jsonb not null default '{}'::jsonb,
    detected_at timestamptz not null,
    acknowledged_at timestamptz,
    resolved_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint fk_process_reconciliation_issue_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint ck_process_reconciliation_issue_scope
        check (issue_scope in ('SHADOW', 'PROJECTION', 'COMMAND', 'OPERATION')),
    constraint ck_process_reconciliation_issue_severity
        check (severity in ('INFO', 'WARNING', 'ERROR', 'CRITICAL')),
    constraint ck_process_reconciliation_issue_status
        check (issue_status in ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    constraint ck_process_reconciliation_issue_room
        check (room_type is null or room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')),
    constraint ck_process_reconciliation_issue_revision
        check (room_epoch >= 0 and process_revision >= 0 and fencing_token >= 0),
    constraint ck_process_reconciliation_issue_expected
        check (
            (expected_ref is null and expected_sha256 is null)
            or
            (expected_ref ~ '^(s3|minio|urn):' and expected_sha256 ~ '^[0-9a-f]{64}$')
        ),
    constraint ck_process_reconciliation_issue_actual
        check (
            (actual_ref is null and actual_sha256 is null)
            or
            (actual_ref ~ '^(s3|minio|urn):' and actual_sha256 ~ '^[0-9a-f]{64}$')
        ),
    constraint ck_process_reconciliation_issue_details
        check (
            jsonb_typeof(details_json) = 'object'
            and octet_length(details_json::text) <= 65536
        ),
    constraint ck_process_reconciliation_issue_resolution
        check (
            (issue_status = 'RESOLVED' and resolved_at is not null)
            or
            (issue_status <> 'RESOLVED' and resolved_at is null)
        ),
    constraint ck_process_reconciliation_issue_version
        check (version >= 0)
);

create unique index uq_process_reconciliation_issue_tenant_key
    on process_reconciliation_issue(tenant_surrogate, issue_key);

create index idx_process_reconciliation_issue_open
    on process_reconciliation_issue(severity, detected_at)
    where issue_status <> 'RESOLVED';

-- Legacy backfill deliberately leaves all Temporal identity columns null.
insert into case_process_projection (
    case_id, tenant_surrogate, macro_phase, current_room, room_phase,
    writer_mode, process_revision, room_epoch, fencing_token,
    last_command_sequence, last_case_event_sequence,
    temporal_workflow_id, temporal_run_id, temporal_build_id,
    projected_at, updated_at
)
select
    dispute.id,
    'legacy-default',
    dispute.case_status,
    dispute.current_room,
    coalesce(room.room_status, 'NOT_CREATED'),
    'LEGACY',
    0,
    0,
    0,
    0,
    0,
    null,
    null,
    null,
    now(),
    now()
from fulfillment_dispute_case dispute
left join case_room room
    on room.case_id = dispute.id
   and room.room_type = dispute.current_room;

insert into case_room_epoch (
    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
    writer_mode, lifecycle_status, process_revision, room_revision,
    fencing_token, temporal_workflow_id, temporal_run_id, temporal_build_id,
    graph_key, graph_version, checkpoint_schema_version, stream_protocol,
    activated_at, terminal_at, created_at, updated_at
)
select
    'CRE_' || md5(room.case_id || ':' || room.room_type || ':0'),
    'legacy-default',
    room.case_id,
    room.id,
    room.room_type,
    0,
    'LEGACY',
    case when room.room_status in ('OPEN', 'WAITING') then 'ACTIVE' else 'TERMINAL' end,
    0,
    0,
    0,
    null,
    null,
    null,
    null,
    null,
    null,
    'agent_stream.v1',
    coalesce(room.opened_at, room.created_at),
    case
        when room.room_status in ('OPEN', 'WAITING') then null
        else coalesce(room.closed_at, room.sealed_at, room.updated_at)
    end,
    room.created_at,
    now()
from case_room room;

-- Defensive backfill for old cases whose current real room has no case_room row.
insert into case_room_epoch (
    id, tenant_surrogate, case_id, room_id, room_type, room_epoch,
    writer_mode, lifecycle_status, process_revision, room_revision,
    fencing_token, stream_protocol, activated_at, created_at, updated_at
)
select
    'CRE_' || md5(dispute.id || ':' || dispute.current_room || ':0'),
    'legacy-default',
    dispute.id,
    null,
    dispute.current_room,
    0,
    'LEGACY',
    'ACTIVE',
    0,
    0,
    0,
    'agent_stream.v1',
    dispute.created_at,
    dispute.created_at,
    now()
from fulfillment_dispute_case dispute
where dispute.current_room in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')
  and not exists (
      select 1
      from case_room_epoch epoch
      where epoch.case_id = dispute.id
        and epoch.room_type = dispute.current_room
        and epoch.room_epoch = 0
  );
