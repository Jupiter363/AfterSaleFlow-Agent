-- Target-architecture isolated preproduction activation authority. This migration is
-- expand-only: existing SHADOW rows retain their exact R1.5 constants and no runtime
-- role or Graph principal receives a grant here.

create table production_runtime_activation (
    activation_id varchar(64) primary key,
    contract_version varchar(64) not null,
    manifest_hash varchar(64) not null,
    execution_lane varchar(32) not null,
    environment_id varchar(128) not null,
    environment_generation bigint not null,
    candidate_sha varchar(40) not null,
    nonce varchar(128) not null,
    tenant_surrogate varchar(128) not null,
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    lifecycle_status varchar(24) not null,
    lifecycle_changed_at timestamptz not null,
    activated_at timestamptz,
    drain_only_at timestamptz,
    drained_at timestamptz,
    revoked_at timestamptz,
    all_replicas_detached boolean not null default false,
    evidence_sealed boolean not null default false,
    case_scope_mode varchar(40) not null,
    case_scope_hash varchar(64) not null,
    explicit_case_count integer,
    synthetic_case_id_prefix varchar(32),
    synthetic_max_cases integer,
    synthetic_fixture_set_id varchar(128),
    synthetic_fixture_set_hash varchar(64),
    synthetic_fixture_bytes_canonical_hash varchar(64),
    contains_real_case_or_party_data boolean not null,
    case_external_effects_allowed boolean not null,
    allowed_room_types varchar(32)[] not null,
    case_build_id varchar(128) not null,
    control_build_id varchar(128) not null,
    agent_build_id varchar(128) not null,
    graph_key varchar(128) not null,
    graph_version varchar(128) not null,
    graph_checkpoint_schema_version varchar(128) not null,
    graph_binding_hash varchar(64) not null,
    graph_code_build_id varchar(128) not null,
    java_api_image_digest varchar(71) not null,
    temporal_control_worker_image_digest varchar(71) not null,
    temporal_agent_worker_image_digest varchar(71) not null,
    python_agent_image_digest varchar(71) not null,
    frontend_image_digest varchar(71) not null,
    temporal_namespace varchar(128) not null,
    domain_cluster_identity varchar(128) not null,
    domain_database_identity varchar(128) not null,
    domain_runtime_principal_identity varchar(128) not null,
    isolated_domain_db_binding_hash varchar(64) not null,
    graph_cluster_identity varchar(128) not null,
    graph_database_identity varchar(128) not null,
    graph_runtime_principal_identity varchar(128) not null,
    isolated_graph_db_binding_hash varchar(64) not null,
    binding_set_hash varchar(64) not null,
    graph_output_authority varchar(32) not null,
    graph_domain_credentials_present boolean not null,
    graph_domain_write_allowed boolean not null,
    formal_writer varchar(32) not null,
    java_domain_commit_allowed boolean not null,
    external_effects_allowed boolean not null,
    production_traffic_allowed boolean not null,
    production_promotion_authority boolean not null,
    migration_promotion_authority boolean not null,
    production_formal_selector varchar(16) not null,
    production_production_runtime_activation varchar(16) not null,
    registered_at timestamptz not null default current_timestamp,
    constraint uq_production_runtime_activation_nonce unique (nonce),
    constraint uq_production_runtime_activation_identity unique (
        environment_id, environment_generation, activation_id, nonce, manifest_hash
    ),
    constraint uq_production_runtime_activation_epoch_binding unique (
        activation_id, manifest_hash, execution_lane, isolated_domain_db_binding_hash
    ),
    constraint ck_production_runtime_activation_identity check (
        activation_id ~ '^p9act[.]v1[.][0-9a-f]{32}$'
        and contract_version = 'production-runtime-activation.v1'
        and manifest_hash ~ '^[0-9a-f]{64}$'
        and execution_lane = 'PRODUCTION'
        and environment_id ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'
        and environment_generation between 1 and 9007199254740991
        and candidate_sha ~ '^[0-9a-f]{40}$'
        and nonce ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{31,127}$'
        and tenant_surrogate ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'
    ),
    constraint ck_production_runtime_activation_time check (
        expires_at > issued_at
        and expires_at <= issued_at + interval '7200 seconds'
        and lifecycle_changed_at >= registered_at
    ),
    constraint ck_production_runtime_activation_lifecycle check (
        (
            lifecycle_status = 'REGISTERED'
            and activated_at is null and drain_only_at is null
            and drained_at is null and revoked_at is null
            and all_replicas_detached = false and evidence_sealed = false
        ) or (
            lifecycle_status = 'ACTIVE'
            and activated_at is not null and drain_only_at is null
            and drained_at is null and revoked_at is null
            and all_replicas_detached = false and evidence_sealed = false
        ) or (
            lifecycle_status = 'DRAIN_ONLY'
            and activated_at is not null and drain_only_at is not null
            and drain_only_at >= expires_at
            and drained_at is null and revoked_at is null
            and all_replicas_detached = false and evidence_sealed = false
        ) or (
            lifecycle_status = 'DRAINED'
            and activated_at is not null and drain_only_at is not null
            and drained_at is not null and drained_at >= drain_only_at
            and revoked_at is null
            and all_replicas_detached = false and evidence_sealed = false
        ) or (
            lifecycle_status = 'REVOKED_TERMINAL'
            and activated_at is not null and drain_only_at is not null
            and drained_at is not null and revoked_at is not null
            and revoked_at > drained_at
            and all_replicas_detached = true and evidence_sealed = true
        )
    ),
    constraint ck_production_runtime_activation_scope check (
        (
            case_scope_mode = 'EXPLICIT_CASE_IDS'
            and explicit_case_count between 1 and 100
            and synthetic_case_id_prefix is null
            and synthetic_max_cases is null
            and synthetic_fixture_set_id is null
            and synthetic_fixture_set_hash is null
            and synthetic_fixture_bytes_canonical_hash is null
        ) or (
            case_scope_mode = 'ISOLATED_SYNTHETIC_NEW_CASES'
            and explicit_case_count is null
            and synthetic_case_id_prefix ~ '^[A-Z][A-Z0-9_]{2,31}$'
            and synthetic_max_cases between 1 and 16
            and synthetic_fixture_set_id ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'
            and synthetic_fixture_set_hash ~ '^[0-9a-f]{64}$'
            and synthetic_fixture_bytes_canonical_hash = synthetic_fixture_set_hash
        )
        and case_scope_hash ~ '^[0-9a-f]{64}$'
        and contains_real_case_or_party_data = false
        and case_external_effects_allowed = false
    ),
    constraint ck_production_runtime_activation_rooms check (
        cardinality(allowed_room_types) between 1 and 4
        and allowed_room_types <@ array['INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW']::varchar[]
        and cardinality(allowed_room_types) = (
            (case when 'INTAKE' = any(allowed_room_types) then 1 else 0 end)
            + (case when 'EVIDENCE' = any(allowed_room_types) then 1 else 0 end)
            + (case when 'HEARING' = any(allowed_room_types) then 1 else 0 end)
            + (case when 'REVIEW' = any(allowed_room_types) then 1 else 0 end)
        )
        and array_position(allowed_room_types, null) is null
    ),
    constraint ck_production_runtime_activation_bindings check (
        length(btrim(case_build_id)) between 1 and 128
        and length(btrim(control_build_id)) between 1 and 128
        and length(btrim(agent_build_id)) between 1 and 128
        and graph_key = 'all-rooms.production-runtime.v1'
        and length(btrim(graph_version)) between 1 and 128
        and length(btrim(graph_checkpoint_schema_version)) between 1 and 128
        and graph_binding_hash ~ '^[0-9a-f]{64}$'
        and length(btrim(graph_code_build_id)) between 1 and 128
        and temporal_namespace ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'
        and isolated_domain_db_binding_hash ~ '^[0-9a-f]{64}$'
        and isolated_graph_db_binding_hash ~ '^[0-9a-f]{64}$'
        and binding_set_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint ck_production_runtime_activation_images check (
        java_api_image_digest ~ '^sha256:[0-9a-f]{64}$'
        and temporal_control_worker_image_digest ~ '^sha256:[0-9a-f]{64}$'
        and temporal_agent_worker_image_digest ~ '^sha256:[0-9a-f]{64}$'
        and python_agent_image_digest ~ '^sha256:[0-9a-f]{64}$'
        and frontend_image_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    constraint ck_production_runtime_activation_database_separation check (
        domain_cluster_identity <> graph_cluster_identity
        and domain_database_identity <> graph_database_identity
        and domain_runtime_principal_identity <> graph_runtime_principal_identity
        and isolated_domain_db_binding_hash <> isolated_graph_db_binding_hash
    ),
    constraint ck_production_runtime_activation_authority check (
        graph_output_authority = 'PROPOSAL_ONLY'
        and graph_domain_credentials_present = false
        and graph_domain_write_allowed = false
        and formal_writer = 'JAVA_FINALIZER_ONLY'
        and java_domain_commit_allowed = true
        and external_effects_allowed = false
        and production_traffic_allowed = false
        and production_promotion_authority = false
        and migration_promotion_authority = false
        and production_formal_selector = 'LEGACY'
        and production_production_runtime_activation = 'DISABLED'
    )
);

create index idx_production_runtime_activation_environment
    on production_runtime_activation(environment_id, environment_generation, lifecycle_status);

create table production_runtime_environment_generation_watermark (
    environment_id varchar(128) primary key,
    highest_generation bigint not null,
    highest_activation_id varchar(64) not null,
    advanced_at timestamptz not null default current_timestamp,
    constraint ck_production_runtime_environment_watermark check (
        environment_id ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'
        and highest_generation between 1 and 9007199254740991
        and highest_activation_id ~ '^p9act[.]v1[.][0-9a-f]{32}$'
    )
);

create or replace function enforce_production_runtime_environment_generation()
returns trigger
language plpgsql
as $$
declare
    existing_activation production_runtime_activation%rowtype;
    watermark_row production_runtime_environment_generation_watermark%rowtype;
    watermark_found boolean;
begin
    -- Activation registration is infrequent. One transaction-scoped lock makes the
    -- activationId/nonce checks and environment watermark one linearization point.
    perform pg_advisory_xact_lock(9047);
    select * into watermark_row
      from production_runtime_environment_generation_watermark
     where environment_id = new.environment_id
     for update;
    watermark_found := found;
    select * into existing_activation
      from production_runtime_activation
     where activation_id = new.activation_id or nonce = new.nonce
     limit 1;
    if found then
        if existing_activation.activation_id = new.activation_id
           and existing_activation.nonce = new.nonce
           and existing_activation.environment_id = new.environment_id
           and existing_activation.environment_generation = new.environment_generation
           and existing_activation.manifest_hash = new.manifest_hash
           and existing_activation.candidate_sha = new.candidate_sha
           and existing_activation.tenant_surrogate = new.tenant_surrogate
           and existing_activation.case_scope_hash = new.case_scope_hash
           and existing_activation.binding_set_hash = new.binding_set_hash then
            if not watermark_found
               or watermark_row.highest_generation is distinct from new.environment_generation
               or watermark_row.highest_activation_id is distinct from new.activation_id then
                raise exception using errcode = '23514',
                    message = 'production runtime exact attach is stale below the durable environment generation high-water mark';
            end if;
            return new;
        end if;
        raise exception using errcode = '23505',
            message = 'production runtime activationId or nonce replay conflicts with a durable grant';
    end if;
    if new.issued_at > clock_timestamp() or new.expires_at <= clock_timestamp() then
        raise exception using errcode = '23514',
            message = 'new production runtime activation must be current at registration';
    end if;

    if not watermark_found then
        insert into production_runtime_environment_generation_watermark (
            environment_id, highest_generation, highest_activation_id
        ) values (new.environment_id, new.environment_generation, new.activation_id);
    elsif new.environment_generation <= watermark_row.highest_generation then
        raise exception using errcode = '23514',
            message = 'production runtime environment generation must advance its durable high-water mark';
    else
        update production_runtime_environment_generation_watermark
           set highest_generation = new.environment_generation,
               highest_activation_id = new.activation_id,
               advanced_at = current_timestamp
         where environment_id = new.environment_id;
    end if;
    return new;
end
$$;

create trigger trg_production_runtime_activation_generation
before insert on production_runtime_activation
for each row execute function enforce_production_runtime_environment_generation();

create or replace function guard_production_runtime_environment_watermark()
returns trigger
language plpgsql
as $$
begin
    if new.environment_id is distinct from old.environment_id
       or new.highest_generation <= old.highest_generation
       or new.highest_activation_id is not distinct from old.highest_activation_id
       or new.advanced_at < old.advanced_at then
        raise exception using errcode = '23514',
            message = 'production runtime environment generation high-water mark cannot regress';
    end if;
    return new;
end
$$;

create trigger trg_production_runtime_environment_watermark_guard
before update on production_runtime_environment_generation_watermark
for each row execute function guard_production_runtime_environment_watermark();

-- A single unique claim is the cross-activation linearization point for every case ID.
-- Unlike a read after an advisory-lock wait, the unique index resolves concurrent
-- inserts against the winning transaction even under READ COMMITTED snapshots.
create table production_runtime_case_id_claim (
    case_id varchar(128) primary key,
    reservation_id varchar(64) not null unique,
    activation_id varchar(64) not null,
    reservation_kind varchar(32) not null,
    claimed_at timestamptz not null,
    constraint fk_production_runtime_case_claim_activation
        foreign key (activation_id) references production_runtime_activation(activation_id),
    constraint ck_production_runtime_case_claim check (
        case_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        and reservation_id ~ '^p9case[.]v1[.][0-9a-f]{32}$'
        and reservation_kind in ('EXPLICIT_CASE_ID', 'ISOLATED_SYNTHETIC_NEW_CASE')
    )
);

create table production_runtime_case_reservation (
    reservation_id varchar(64) not null,
    activation_id varchar(64) not null,
    environment_id varchar(128) not null,
    environment_generation bigint not null,
    tenant_surrogate varchar(128) not null,
    reservation_kind varchar(32) not null,
    slot_number integer not null,
    case_id varchar(128) not null,
    case_scope_hash varchar(64) not null,
    fixture_set_id varchar(128),
    fixture_set_hash varchar(64),
    fixture_bytes_canonical_hash varchar(64),
    contains_real_case_or_party_data boolean not null,
    external_effects_allowed boolean not null,
    reserved_at timestamptz not null default current_timestamp,
    constraint pk_production_runtime_case_reservation primary key (activation_id, slot_number),
    constraint uq_production_runtime_case_reservation_id unique (reservation_id),
    constraint uq_production_runtime_case_reservation_exact unique (
        activation_id, slot_number, reservation_id
    ),
    constraint uq_production_runtime_case_reservation_route unique (
        activation_id, tenant_surrogate, case_id
    ),
    constraint fk_production_runtime_case_reservation_activation
        foreign key (activation_id) references production_runtime_activation(activation_id),
    constraint ck_production_runtime_case_reservation_identity check (
        reservation_id ~ '^p9case[.]v1[.][0-9a-f]{32}$'
        and environment_id ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'
        and environment_generation between 1 and 9007199254740991
        and tenant_surrogate ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'
        and case_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        and case_scope_hash ~ '^[0-9a-f]{64}$'
        and slot_number between 1 and 100
    ),
    constraint ck_production_runtime_case_reservation_shape check (
        (
            reservation_kind = 'EXPLICIT_CASE_ID'
            and fixture_set_id is null
            and fixture_set_hash is null
            and fixture_bytes_canonical_hash is null
        ) or (
            reservation_kind = 'ISOLATED_SYNTHETIC_NEW_CASE'
            and fixture_set_id is not null
            and fixture_set_hash ~ '^[0-9a-f]{64}$'
            and fixture_bytes_canonical_hash = fixture_set_hash
            and slot_number <= 16
        )
        and contains_real_case_or_party_data = false
        and external_effects_allowed = false
    )
);

create or replace function enforce_production_runtime_case_reservation()
returns trigger
language plpgsql
as $$
declare
    activation_row production_runtime_activation%rowtype;
begin
    insert into production_runtime_case_id_claim (
        case_id, reservation_id, activation_id, reservation_kind, claimed_at
    ) values (
        new.case_id, new.reservation_id, new.activation_id,
        new.reservation_kind, new.reserved_at
    ) on conflict do nothing;
    if not found then
        raise exception using errcode = '23505',
            message = 'production runtime case ID is already claimed by another reservation';
    end if;
    select * into activation_row
      from production_runtime_activation
     where activation_id = new.activation_id
     for share;
    if not found then
        raise exception using errcode = '23503',
            message = 'production runtime case reservation requires an activation';
    end if;
    if activation_row.expires_at <= clock_timestamp()
       or (
            (new.reservation_kind = 'EXPLICIT_CASE_ID'
                and activation_row.lifecycle_status not in ('REGISTERED', 'ACTIVE'))
            or (new.reservation_kind = 'ISOLATED_SYNTHETIC_NEW_CASE'
                and activation_row.lifecycle_status <> 'ACTIVE')
       ) then
        raise exception using errcode = '23514',
            message = 'production runtime case reservation requires a live activation in its allowed state';
    end if;
    if new.environment_id is distinct from activation_row.environment_id
       or new.environment_generation is distinct from activation_row.environment_generation
       or new.tenant_surrogate is distinct from activation_row.tenant_surrogate
       or new.case_scope_hash is distinct from activation_row.case_scope_hash then
        raise exception using errcode = '23514',
            message = 'production runtime case reservation binding mismatch';
    end if;
    if activation_row.case_scope_mode = 'EXPLICIT_CASE_IDS' then
        if new.reservation_kind <> 'EXPLICIT_CASE_ID'
           or new.slot_number > activation_row.explicit_case_count then
            raise exception using errcode = '23514',
                message = 'production runtime explicit case reservation exceeds signed scope';
        end if;
    else
        if new.reservation_kind <> 'ISOLATED_SYNTHETIC_NEW_CASE'
           or new.slot_number > activation_row.synthetic_max_cases
           or left(new.case_id, length(activation_row.synthetic_case_id_prefix))
                <> activation_row.synthetic_case_id_prefix
           or new.fixture_set_id is distinct from activation_row.synthetic_fixture_set_id
           or new.fixture_set_hash is distinct from activation_row.synthetic_fixture_set_hash
           or new.fixture_bytes_canonical_hash is distinct from
                activation_row.synthetic_fixture_bytes_canonical_hash then
            raise exception using errcode = '23514',
                message = 'production runtime synthetic case reservation exceeds signed scope';
        end if;
    end if;
    if new.reservation_kind = 'ISOLATED_SYNTHETIC_NEW_CASE' and exists (
        select 1 from production_runtime_case_reservation reservation
         where reservation.case_id = new.case_id
    ) then
        raise exception using errcode = '23505',
            message = 'production runtime generated case ID conflicts with a durable reservation';
    end if;
    if new.reservation_kind = 'EXPLICIT_CASE_ID' and exists (
        select 1 from production_runtime_generated_case_tombstone tombstone
         where tombstone.generated_case_id = new.case_id
    ) then
        raise exception using errcode = '23505',
            message = 'production runtime explicit case ID conflicts with a global generated-ID tombstone';
    end if;
    return new;
end
$$;

create trigger trg_production_runtime_case_reservation_scope
before insert on production_runtime_case_reservation
for each row execute function enforce_production_runtime_case_reservation();

create or replace function reject_production_runtime_append_only_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception using errcode = '55000',
        message = tg_table_name || ' is append-only';
end
$$;

create trigger trg_production_runtime_case_reservation_immutable
before update or delete on production_runtime_case_reservation
for each row execute function reject_production_runtime_append_only_mutation();
create trigger trg_production_runtime_case_reservation_no_truncate
before truncate on production_runtime_case_reservation
for each statement execute function reject_production_runtime_append_only_mutation();
create trigger trg_production_runtime_case_claim_immutable
before update or delete on production_runtime_case_id_claim
for each row execute function reject_production_runtime_append_only_mutation();
create trigger trg_production_runtime_case_claim_no_truncate
before truncate on production_runtime_case_id_claim
for each statement execute function reject_production_runtime_append_only_mutation();

create table production_runtime_generated_case_tombstone (
    generated_case_id varchar(128) primary key,
    activation_id varchar(64) not null,
    slot_number integer not null,
    reservation_id varchar(64) not null,
    environment_id varchar(128) not null,
    environment_generation bigint not null,
    tombstoned_at timestamptz not null,
    constraint uq_production_runtime_generated_tombstone_reservation unique (reservation_id),
    constraint fk_production_runtime_generated_tombstone_reservation
        foreign key (activation_id, slot_number, reservation_id)
        references production_runtime_case_reservation(activation_id, slot_number, reservation_id),
    constraint ck_production_runtime_generated_tombstone check (
        generated_case_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        and environment_generation between 1 and 9007199254740991
    )
);

create or replace function tombstone_production_runtime_generated_case()
returns trigger
language plpgsql
as $$
begin
    if new.reservation_kind = 'ISOLATED_SYNTHETIC_NEW_CASE' then
        insert into production_runtime_generated_case_tombstone (
            generated_case_id, activation_id, slot_number, reservation_id,
            environment_id, environment_generation, tombstoned_at
        ) values (
            new.case_id, new.activation_id, new.slot_number, new.reservation_id,
            new.environment_id, new.environment_generation, new.reserved_at
        );
    end if;
    return new;
end
$$;

create trigger trg_production_runtime_generated_case_tombstone
after insert on production_runtime_case_reservation
for each row execute function tombstone_production_runtime_generated_case();
create index idx_production_runtime_case_reservation_case
    on production_runtime_case_reservation(case_id);
create trigger trg_production_runtime_generated_tombstone_immutable
before update or delete on production_runtime_generated_case_tombstone
for each row execute function reject_production_runtime_append_only_mutation();
create trigger trg_production_runtime_generated_tombstone_no_truncate
before truncate on production_runtime_generated_case_tombstone
for each statement execute function reject_production_runtime_append_only_mutation();

create or replace function assert_production_runtime_explicit_scope_complete()
returns trigger
language plpgsql
as $$
declare
    persisted_count integer;
begin
    if new.case_scope_mode <> 'EXPLICIT_CASE_IDS' then
        return new;
    end if;
    select count(*) into persisted_count
      from production_runtime_case_reservation
     where activation_id = new.activation_id
       and reservation_kind = 'EXPLICIT_CASE_ID';
    if persisted_count <> new.explicit_case_count then
        raise exception using errcode = '23514',
            message = 'production runtime explicit case scope must be persisted atomically and exactly';
    end if;
    return new;
end
$$;

create constraint trigger trg_production_runtime_explicit_scope_complete
after insert on production_runtime_activation
deferrable initially deferred
for each row execute function assert_production_runtime_explicit_scope_complete();

-- Exact activation authority for every target room epoch. Intake keeps its richer
-- selection record as well; Evidence, Hearing, and Review bind through this common row.
create table production_runtime_room_epoch_binding (
    epoch_id varchar(64) primary key,
    activation_id varchar(64) not null,
    activation_manifest_hash varchar(64) not null,
    execution_lane varchar(32) not null,
    isolated_domain_db_binding_hash varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    room_fencing_token bigint not null,
    bound_at timestamptz not null default current_timestamp,
    constraint fk_production_runtime_room_epoch
        foreign key (epoch_id) references case_room_epoch(id),
    constraint fk_production_runtime_room_epoch_activation
        foreign key (
            activation_id, activation_manifest_hash, execution_lane,
            isolated_domain_db_binding_hash
        ) references production_runtime_activation(
            activation_id, manifest_hash, execution_lane,
            isolated_domain_db_binding_hash
        ),
    constraint fk_production_runtime_room_epoch_case
        foreign key (activation_id, tenant_surrogate, case_id)
        references production_runtime_case_reservation(activation_id, tenant_surrogate, case_id),
    constraint uq_production_runtime_room_epoch_route unique (
        activation_id, tenant_surrogate, case_id, room_type, room_epoch
    ),
    constraint ck_production_runtime_room_epoch_binding check (
        execution_lane = 'PRODUCTION'
        and activation_manifest_hash ~ '^[0-9a-f]{64}$'
        and isolated_domain_db_binding_hash ~ '^[0-9a-f]{64}$'
        and room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')
        and room_epoch between 0 and 9007199254740991
        and room_fencing_token between 1 and 9007199254740991
    )
);

create or replace function enforce_production_runtime_room_epoch_binding()
returns trigger
language plpgsql
as $$
declare
    epoch_row case_room_epoch%rowtype;
    activation_row production_runtime_activation%rowtype;
begin
    select * into epoch_row from case_room_epoch
     where id = new.epoch_id for share;
    if not found then
        raise exception using errcode = '23503',
            message = 'production runtime room binding requires a durable room epoch';
    end if;
    select * into activation_row from production_runtime_activation
     where activation_id = new.activation_id
       and manifest_hash = new.activation_manifest_hash
       and execution_lane = new.execution_lane
       and isolated_domain_db_binding_hash = new.isolated_domain_db_binding_hash
     for share;
    if not found
       or activation_row.lifecycle_status <> 'ACTIVE'
       or activation_row.expires_at <= clock_timestamp() then
        raise exception using errcode = '23514',
            message = 'production runtime room binding requires a live ACTIVE activation';
    end if;
    if epoch_row.tenant_surrogate is distinct from new.tenant_surrogate
       or epoch_row.case_id is distinct from new.case_id
       or epoch_row.room_type is distinct from new.room_type
       or epoch_row.room_epoch is distinct from new.room_epoch
       or epoch_row.fencing_token is distinct from new.room_fencing_token
       or epoch_row.writer_mode <> 'TEMPORAL'
       or epoch_row.graph_key is distinct from activation_row.graph_key
       or epoch_row.graph_version is distinct from activation_row.graph_version
       or epoch_row.checkpoint_schema_version is distinct from
            activation_row.graph_checkpoint_schema_version
       or epoch_row.temporal_build_id is distinct from activation_row.case_build_id
       or epoch_row.room_workflow_build_id is distinct from activation_row.control_build_id
       or epoch_row.stream_protocol <> 'agent-stream.v2'
       or activation_row.tenant_surrogate is distinct from new.tenant_surrogate
       or not (new.room_type = any(activation_row.allowed_room_types)) then
        raise exception using errcode = '23514',
            message = 'production runtime room epoch binding does not match activation authority';
    end if;
    return new;
end
$$;

create trigger trg_production_runtime_room_epoch_binding_guard
before insert on production_runtime_room_epoch_binding
for each row execute function enforce_production_runtime_room_epoch_binding();
create trigger trg_production_runtime_room_epoch_binding_immutable
before update or delete on production_runtime_room_epoch_binding
for each row execute function reject_production_runtime_append_only_mutation();
create trigger trg_production_runtime_room_epoch_binding_no_truncate
before truncate on production_runtime_room_epoch_binding
for each statement execute function reject_production_runtime_append_only_mutation();

create or replace function guard_production_runtime_activation_mutation()
returns trigger
language plpgsql
as $$
begin
    if new.activation_id is distinct from old.activation_id
       or new.contract_version is distinct from old.contract_version
       or new.manifest_hash is distinct from old.manifest_hash
       or new.execution_lane is distinct from old.execution_lane
       or new.environment_id is distinct from old.environment_id
       or new.environment_generation is distinct from old.environment_generation
       or new.candidate_sha is distinct from old.candidate_sha
       or new.nonce is distinct from old.nonce
       or new.tenant_surrogate is distinct from old.tenant_surrogate
       or new.issued_at is distinct from old.issued_at
       or new.expires_at is distinct from old.expires_at
       or new.case_scope_mode is distinct from old.case_scope_mode
       or new.case_scope_hash is distinct from old.case_scope_hash
       or new.explicit_case_count is distinct from old.explicit_case_count
       or new.synthetic_case_id_prefix is distinct from old.synthetic_case_id_prefix
       or new.synthetic_max_cases is distinct from old.synthetic_max_cases
       or new.synthetic_fixture_set_id is distinct from old.synthetic_fixture_set_id
       or new.synthetic_fixture_set_hash is distinct from old.synthetic_fixture_set_hash
       or new.synthetic_fixture_bytes_canonical_hash is distinct from old.synthetic_fixture_bytes_canonical_hash
       or new.contains_real_case_or_party_data is distinct from old.contains_real_case_or_party_data
       or new.case_external_effects_allowed is distinct from old.case_external_effects_allowed
       or new.allowed_room_types is distinct from old.allowed_room_types
       or new.case_build_id is distinct from old.case_build_id
       or new.control_build_id is distinct from old.control_build_id
       or new.agent_build_id is distinct from old.agent_build_id
       or new.graph_key is distinct from old.graph_key
       or new.graph_version is distinct from old.graph_version
       or new.graph_checkpoint_schema_version is distinct from old.graph_checkpoint_schema_version
       or new.graph_binding_hash is distinct from old.graph_binding_hash
       or new.graph_code_build_id is distinct from old.graph_code_build_id
       or new.java_api_image_digest is distinct from old.java_api_image_digest
       or new.temporal_control_worker_image_digest is distinct from old.temporal_control_worker_image_digest
       or new.temporal_agent_worker_image_digest is distinct from old.temporal_agent_worker_image_digest
       or new.python_agent_image_digest is distinct from old.python_agent_image_digest
       or new.frontend_image_digest is distinct from old.frontend_image_digest
       or new.temporal_namespace is distinct from old.temporal_namespace
       or new.domain_cluster_identity is distinct from old.domain_cluster_identity
       or new.domain_database_identity is distinct from old.domain_database_identity
       or new.domain_runtime_principal_identity is distinct from old.domain_runtime_principal_identity
       or new.isolated_domain_db_binding_hash is distinct from old.isolated_domain_db_binding_hash
       or new.graph_cluster_identity is distinct from old.graph_cluster_identity
       or new.graph_database_identity is distinct from old.graph_database_identity
       or new.graph_runtime_principal_identity is distinct from old.graph_runtime_principal_identity
       or new.isolated_graph_db_binding_hash is distinct from old.isolated_graph_db_binding_hash
       or new.binding_set_hash is distinct from old.binding_set_hash
       or new.graph_output_authority is distinct from old.graph_output_authority
       or new.graph_domain_credentials_present is distinct from old.graph_domain_credentials_present
       or new.graph_domain_write_allowed is distinct from old.graph_domain_write_allowed
       or new.formal_writer is distinct from old.formal_writer
       or new.java_domain_commit_allowed is distinct from old.java_domain_commit_allowed
       or new.external_effects_allowed is distinct from old.external_effects_allowed
       or new.production_traffic_allowed is distinct from old.production_traffic_allowed
       or new.production_promotion_authority is distinct from old.production_promotion_authority
       or new.migration_promotion_authority is distinct from old.migration_promotion_authority
       or new.production_formal_selector is distinct from old.production_formal_selector
       or new.production_production_runtime_activation is distinct from old.production_production_runtime_activation
       or new.registered_at is distinct from old.registered_at then
        raise exception using errcode = '55000',
            message = 'production_runtime_activation immutable binding cannot be rewritten';
    end if;

    if new.lifecycle_changed_at < old.lifecycle_changed_at
       or not (
           (old.lifecycle_status = 'REGISTERED'
                and new.lifecycle_status = 'ACTIVE'
                and new.activated_at is not null
                and new.activated_at < new.expires_at
                and new.drain_only_at is null and new.drained_at is null and new.revoked_at is null)
           or (old.lifecycle_status = 'ACTIVE'
                and new.lifecycle_status = 'DRAIN_ONLY'
                and new.activated_at is not distinct from old.activated_at
                and new.drain_only_at >= new.expires_at
                and new.drained_at is null and new.revoked_at is null)
           or (old.lifecycle_status = 'DRAIN_ONLY'
                and new.lifecycle_status = 'DRAINED'
                and new.activated_at is not distinct from old.activated_at
                and new.drain_only_at is not distinct from old.drain_only_at
                and new.drained_at >= new.drain_only_at
                and new.revoked_at is null)
           or (old.lifecycle_status = 'DRAINED'
                and new.lifecycle_status = 'REVOKED_TERMINAL'
                and new.activated_at is not distinct from old.activated_at
                and new.drain_only_at is not distinct from old.drain_only_at
                and new.drained_at is not distinct from old.drained_at
                and new.revoked_at > new.drained_at
                and new.all_replicas_detached = true
                and new.evidence_sealed = true)
       ) then
        raise exception using errcode = '23514',
            message = 'production runtime activation lifecycle transition is invalid';
    end if;
    if new.lifecycle_status = 'DRAINED' and exists (
        select 1
          from production_runtime_command_admission admission
          left join production_runtime_command_completion completion
            on completion.admission_id = admission.admission_id
         where admission.activation_id = new.activation_id
           and completion.admission_id is null
    ) then
        raise exception using errcode = '23514',
            message = 'production runtime activation cannot be DRAINED with unresolved accepted work';
    end if;
    return new;
end
$$;

create trigger trg_production_runtime_activation_guard
before update on production_runtime_activation
for each row execute function guard_production_runtime_activation_mutation();
create trigger trg_production_runtime_activation_no_delete
before delete on production_runtime_activation
for each row execute function reject_production_runtime_append_only_mutation();
create trigger trg_production_runtime_activation_no_truncate
before truncate on production_runtime_activation
for each statement execute function reject_production_runtime_append_only_mutation();

create trigger trg_production_runtime_environment_watermark_no_delete
before delete on production_runtime_environment_generation_watermark
for each row execute function reject_production_runtime_append_only_mutation();
create trigger trg_production_runtime_environment_watermark_no_truncate
before truncate on production_runtime_environment_generation_watermark
for each statement execute function reject_production_runtime_append_only_mutation();

create table production_runtime_command_admission (
    admission_id varchar(64) primary key,
    activation_id varchar(64) not null,
    activation_manifest_hash varchar(64) not null,
    execution_lane varchar(32) not null,
    isolated_domain_db_binding_hash varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    command_id varchar(128) not null,
    command_hash varchar(64) not null,
    command_envelope_hash varchar(64) not null,
    room_epoch bigint not null,
    room_fencing_token bigint not null,
    admitted_at timestamptz not null default current_timestamp,
    constraint uq_production_runtime_command_admission unique (activation_id, command_id),
    constraint fk_production_runtime_command_activation
        foreign key (
            activation_id, activation_manifest_hash, execution_lane,
            isolated_domain_db_binding_hash
        ) references production_runtime_activation(
            activation_id, manifest_hash, execution_lane,
            isolated_domain_db_binding_hash
        ),
    constraint fk_production_runtime_command_case
        foreign key (activation_id, tenant_surrogate, case_id)
        references production_runtime_case_reservation(activation_id, tenant_surrogate, case_id),
    constraint ck_production_runtime_command_admission check (
        admission_id ~ '^p9cmd[.]v1[.][0-9a-f]{32}$'
        and execution_lane = 'PRODUCTION'
        and activation_manifest_hash ~ '^[0-9a-f]{64}$'
        and isolated_domain_db_binding_hash ~ '^[0-9a-f]{64}$'
        and command_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        and command_hash ~ '^[0-9a-f]{64}$'
        and command_envelope_hash ~ '^[0-9a-f]{64}$'
        and room_epoch between 0 and 9007199254740991
        and room_fencing_token between 1 and 9007199254740991
    )
);

create or replace function enforce_production_runtime_command_admission()
returns trigger
language plpgsql
as $$
declare
    activation_row production_runtime_activation%rowtype;
begin
    select * into activation_row
      from production_runtime_activation
     where activation_id = new.activation_id
       and manifest_hash = new.activation_manifest_hash
       and execution_lane = new.execution_lane
       and isolated_domain_db_binding_hash = new.isolated_domain_db_binding_hash
     for share;
    if not found
       or activation_row.lifecycle_status <> 'ACTIVE'
       or new.admitted_at >= activation_row.expires_at
       or clock_timestamp() >= activation_row.expires_at then
        raise exception using errcode = '23514',
            message = 'production runtime command requires pre-expiry ACTIVE admission';
    end if;
    return new;
end
$$;

create trigger trg_production_runtime_command_admission_guard
before insert on production_runtime_command_admission
for each row execute function enforce_production_runtime_command_admission();
create trigger trg_production_runtime_command_admission_immutable
before update or delete on production_runtime_command_admission
for each row execute function reject_production_runtime_append_only_mutation();
create trigger trg_production_runtime_command_admission_no_truncate
before truncate on production_runtime_command_admission
for each statement execute function reject_production_runtime_append_only_mutation();

create table production_runtime_command_completion (
    admission_id varchar(64) primary key,
    activation_id varchar(64) not null,
    command_id varchar(128) not null,
    command_hash varchar(64) not null,
    command_envelope_hash varchar(64) not null,
    completion_hash varchar(64) not null,
    completed_at timestamptz not null default current_timestamp,
    constraint fk_production_runtime_command_completion
        foreign key (admission_id) references production_runtime_command_admission(admission_id),
    constraint ck_production_runtime_command_completion_hashes check (
        command_hash ~ '^[0-9a-f]{64}$'
        and command_envelope_hash ~ '^[0-9a-f]{64}$'
        and completion_hash ~ '^[0-9a-f]{64}$'
    )
);

create or replace function enforce_production_runtime_command_completion()
returns trigger
language plpgsql
as $$
declare
    admission_row production_runtime_command_admission%rowtype;
begin
    select * into admission_row from production_runtime_command_admission
     where admission_id = new.admission_id;
    if not found
       or admission_row.activation_id is distinct from new.activation_id
       or admission_row.command_id is distinct from new.command_id
       or admission_row.command_hash is distinct from new.command_hash
       or admission_row.command_envelope_hash is distinct from new.command_envelope_hash
       or new.completed_at < admission_row.admitted_at then
        raise exception using errcode = '23514',
            message = 'production runtime command completion binding mismatch';
    end if;
    return new;
end
$$;

create trigger trg_production_runtime_command_completion_guard
before insert on production_runtime_command_completion
for each row execute function enforce_production_runtime_command_completion();
create trigger trg_production_runtime_command_completion_immutable
before update or delete on production_runtime_command_completion
for each row execute function reject_production_runtime_append_only_mutation();
create trigger trg_production_runtime_command_completion_no_truncate
before truncate on production_runtime_command_completion
for each statement execute function reject_production_runtime_append_only_mutation();

alter table case_intake_epoch_selection_binding
    add column activation_id varchar(64),
    add column activation_manifest_hash varchar(64),
    add column execution_lane varchar(32) not null default 'SIGNED_SYNTHETIC_SHADOW',
    add column isolated_domain_db_binding_hash varchar(64),
    drop constraint ck_r15_selection_constants,
    add constraint fk_production_runtime_intake_selection_activation
        foreign key (
            activation_id, activation_manifest_hash, execution_lane,
            isolated_domain_db_binding_hash
        ) references production_runtime_activation(
            activation_id, manifest_hash, execution_lane,
            isolated_domain_db_binding_hash
        ),
    add constraint ck_r15_selection_constants check (
        (
            writer_mode = 'SHADOW'
            and execution_lane = 'SIGNED_SYNTHETIC_SHADOW'
            and activation_id is null
            and activation_manifest_hash is null
            and isolated_domain_db_binding_hash is null
            and room_type = 'INTAKE'
            and room_workflow_type = 'IntakeRoomWorkflow'
            and graph_key = 'intake.v2'
            and state_schema_version = 'intake-graph-state.v2'
            and output_schema_version = 'intake-turn-proposal.v2'
            and agent_key = 'DISPUTE_INTAKE_OFFICER'
            and agent_session_profile_version = 'agent-session-profile.v1'
            and memory_policy_id = 'GRAPH_PRIVATE_NO_MEMORY_FRAME_V1'
        ) or (
            writer_mode = 'TEMPORAL'
            and execution_lane = 'PRODUCTION'
            and activation_id is not null
            and activation_manifest_hash ~ '^[0-9a-f]{64}$'
            and isolated_domain_db_binding_hash ~ '^[0-9a-f]{64}$'
            and room_type = 'INTAKE'
            and case_workflow_type = 'CaseProcessWorkflow'
            and room_workflow_type = 'IntakeRoomWorkflow'
            and graph_key = 'all-rooms.production-runtime.v1'
            and stream_protocol = 'agent-stream.v2'
            and agent_key = 'DISPUTE_INTAKE_OFFICER'
        )
    );

create or replace function enforce_production_runtime_intake_selection()
returns trigger
language plpgsql
as $$
declare
    activation_row production_runtime_activation%rowtype;
begin
    if new.writer_mode <> 'TEMPORAL' then
        return new;
    end if;
    select * into activation_row
      from production_runtime_activation
     where activation_id = new.activation_id
       and manifest_hash = new.activation_manifest_hash
       and execution_lane = new.execution_lane
       and isolated_domain_db_binding_hash = new.isolated_domain_db_binding_hash
     for share;
    if not found
       or activation_row.lifecycle_status <> 'ACTIVE'
       or activation_row.expires_at <= clock_timestamp() then
        raise exception using errcode = '23514',
            message = 'production runtime TEMPORAL selection requires a live ACTIVE activation';
    end if;
    if activation_row.tenant_surrogate is distinct from new.tenant_surrogate
       or activation_row.case_build_id is distinct from new.case_workflow_build_id
       or activation_row.control_build_id is distinct from new.room_workflow_build_id
       or activation_row.graph_key is distinct from new.graph_key
       or activation_row.graph_version is distinct from new.graph_version
       or activation_row.graph_checkpoint_schema_version is distinct from new.checkpoint_schema_version
       or not ('INTAKE' = any(activation_row.allowed_room_types))
       or not exists (
            select 1 from production_runtime_case_reservation reservation
             where reservation.activation_id = new.activation_id
               and reservation.tenant_surrogate = new.tenant_surrogate
               and reservation.case_id = new.case_id
               and reservation.reserved_at <= new.created_at
       ) then
        raise exception using errcode = '23514',
            message = 'production runtime TEMPORAL selection binding mismatch or case is not reserved';
    end if;
    return new;
end
$$;

create trigger trg_production_runtime_intake_selection_guard
before insert on case_intake_epoch_selection_binding
for each row execute function enforce_production_runtime_intake_selection();

alter table agent_execution_manifest
    add constraint uq_production_runtime_agent_manifest_hash unique (id, manifest_sha256);

create table production_runtime_finalization_receipt (
    receipt_id varchar(128) primary key,
    schema_version varchar(64) not null,
    execution_lane varchar(32) not null,
    activation_id varchar(64) not null,
    activation_manifest_hash varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    room_epoch bigint not null,
    room_fencing_token bigint not null,
    process_revision bigint not null,
    stage_sequence bigint not null,
    logical_run_id varchar(128) not null,
    attempt_id varchar(128) not null,
    command_hash varchar(64) not null,
    command_envelope_hash varchar(64) not null,
    graph_key varchar(128) not null,
    graph_version varchar(128) not null,
    checkpoint_schema_version varchar(128) not null,
    checkpoint_id varchar(128) not null,
    result_hash varchar(64) not null,
    proposal_hash varchar(64) not null,
    result_envelope_hash varchar(64) not null,
    agent_run_manifest_id varchar(64) not null,
    agent_run_manifest_hash varchar(64) not null,
    isolated_domain_db_binding_hash varchar(64) not null,
    committed_at timestamptz not null,
    receipt_hash varchar(64) not null,
    receipt_canonical_bytes bytea not null,
    formal_writer varchar(32) not null,
    domain_commit_status varchar(32) not null,
    recorded_at timestamptz not null default current_timestamp,
    constraint uq_production_runtime_finalization_receipt_hash unique (receipt_hash),
    constraint uq_production_runtime_finalization_logical_run unique (activation_id, logical_run_id),
    constraint fk_production_runtime_finalization_activation
        foreign key (
            activation_id, activation_manifest_hash, execution_lane,
            isolated_domain_db_binding_hash
        ) references production_runtime_activation(
            activation_id, manifest_hash, execution_lane,
            isolated_domain_db_binding_hash
        ),
    constraint fk_production_runtime_finalization_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint fk_production_runtime_finalization_manifest
        foreign key (agent_run_manifest_id, agent_run_manifest_hash)
        references agent_execution_manifest(id, manifest_sha256),
    constraint ck_production_runtime_finalization_identity check (
        schema_version = 'production-runtime-finalization-receipt.v1'
        and execution_lane = 'PRODUCTION'
        and activation_id ~ '^p9act[.]v1[.][0-9a-f]{32}$'
        and activation_manifest_hash ~ '^[0-9a-f]{64}$'
        and tenant_surrogate ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'
        and case_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        and room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')
        and room_epoch between 0 and 9007199254740991
        and room_fencing_token between 1 and 9007199254740991
        and process_revision between 0 and 9007199254740991
        and stage_sequence between 0 and 9007199254740991
    ),
    constraint ck_production_runtime_finalization_hashes check (
        command_hash ~ '^[0-9a-f]{64}$'
        and command_envelope_hash ~ '^[0-9a-f]{64}$'
        and result_hash ~ '^[0-9a-f]{64}$'
        and proposal_hash ~ '^[0-9a-f]{64}$'
        and result_envelope_hash ~ '^[0-9a-f]{64}$'
        and agent_run_manifest_hash ~ '^[0-9a-f]{64}$'
        and isolated_domain_db_binding_hash ~ '^[0-9a-f]{64}$'
        and receipt_hash ~ '^[0-9a-f]{64}$'
        and octet_length(receipt_canonical_bytes) between 2 and 65536
    ),
    constraint ck_production_runtime_finalization_authority check (
        formal_writer = 'JAVA_FINALIZER_ONLY'
        and domain_commit_status = 'COMMITTED'
        and recorded_at >= committed_at
    )
);

create or replace function enforce_production_runtime_finalization_receipt()
returns trigger
language plpgsql
as $$
declare
    manifest_row agent_execution_manifest%rowtype;
begin
    select * into manifest_row
      from agent_execution_manifest
     where id = new.agent_run_manifest_id
       and manifest_sha256 = new.agent_run_manifest_hash;
    if not found
       or manifest_row.tenant_surrogate is distinct from new.tenant_surrogate
       or manifest_row.case_id is distinct from new.case_id
       or manifest_row.room_type is distinct from new.room_type
       or manifest_row.room_epoch is distinct from new.room_epoch
       or manifest_row.process_revision is distinct from new.process_revision
       or manifest_row.fencing_token is distinct from new.room_fencing_token
       or manifest_row.logical_agent_run_id is distinct from new.logical_run_id
       or manifest_row.attempt_id is distinct from new.attempt_id
       or manifest_row.graph_key is distinct from new.graph_key
       or manifest_row.graph_version is distinct from new.graph_version
       or manifest_row.checkpoint_schema_version is distinct from new.checkpoint_schema_version
       or manifest_row.checkpoint_id is distinct from new.checkpoint_id then
        raise exception using errcode = '23514',
            message = 'production runtime finalization receipt manifest binding mismatch';
    end if;
    if not exists (
        select 1 from production_runtime_case_reservation reservation
         where reservation.activation_id = new.activation_id
           and reservation.tenant_surrogate = new.tenant_surrogate
           and reservation.case_id = new.case_id
    ) then
        raise exception using errcode = '23514',
            message = 'production runtime finalization receipt case is outside activation scope';
    end if;
    return new;
end
$$;

create trigger trg_production_runtime_finalization_binding
before insert on production_runtime_finalization_receipt
for each row execute function enforce_production_runtime_finalization_receipt();
create trigger trg_production_runtime_finalization_immutable
before update or delete on production_runtime_finalization_receipt
for each row execute function reject_production_runtime_append_only_mutation();
create trigger trg_production_runtime_finalization_no_truncate
before truncate on production_runtime_finalization_receipt
for each statement execute function reject_production_runtime_append_only_mutation();

-- PostgreSQL grants no table access to PUBLIC by default; make the boundary explicit.
-- Deployment-owned Java roles receive only the table operations they need outside Flyway.
revoke all on production_runtime_activation from public;
revoke all on production_runtime_environment_generation_watermark from public;
revoke all on production_runtime_case_id_claim from public;
revoke all on production_runtime_case_reservation from public;
revoke all on production_runtime_generated_case_tombstone from public;
revoke all on production_runtime_room_epoch_binding from public;
revoke all on production_runtime_command_admission from public;
revoke all on production_runtime_command_completion from public;
revoke all on production_runtime_finalization_receipt from public;
