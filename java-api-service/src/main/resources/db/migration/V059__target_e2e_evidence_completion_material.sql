-- Immutable non-Graph command authority for target Evidence party completion.
create table target_e2e_evidence_completion_command_material (
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
    room_type varchar(32) not null,
    room_epoch bigint not null,
    room_fencing_token bigint not null,
    case_command_request_hash varchar(64) not null,
    expected_process_revision bigint not null,
    expected_room_revision bigint not null,
    actor_id varchar(128) not null,
    actor_role varchar(32) not null,
    actor_scopes_json jsonb not null,
    payload_schema_version varchar(96) not null,
    payload_uri varchar(1024) not null,
    payload_sha256 varchar(64) not null,
    payload_size_bytes bigint not null,
    deadline_at timestamptz not null,
    trace_id varchar(32) not null,
    material_schema_version varchar(96) not null,
    material_canonical_json text not null,
    material_sha256 varchar(64) not null,
    stored_at timestamptz not null default current_timestamp,
    constraint uq_target_e2e_evidence_completion_material_command unique (activation_id, command_id),
    constraint fk_target_e2e_evidence_completion_material_admission
        foreign key (admission_id) references target_e2e_command_admission(admission_id),
    constraint fk_target_e2e_evidence_completion_material_activation
        foreign key (activation_id, activation_manifest_hash, execution_lane, isolated_domain_db_binding_hash)
        references target_e2e_activation(activation_id, manifest_hash, execution_lane, isolated_domain_db_binding_hash),
    constraint fk_target_e2e_evidence_completion_material_case
        foreign key (activation_id, tenant_surrogate, case_id)
        references target_e2e_case_reservation(activation_id, tenant_surrogate, case_id),
    constraint fk_target_e2e_evidence_completion_material_epoch
        foreign key (activation_id, tenant_surrogate, case_id, room_type, room_epoch, room_fencing_token)
        references target_e2e_room_epoch_binding(activation_id, tenant_surrogate, case_id, room_type, room_epoch, room_fencing_token),
    constraint ck_target_e2e_evidence_completion_material_shape check (
        execution_lane = 'TARGET_E2E_CANDIDATE' and room_type = 'EVIDENCE'
        and material_schema_version = 'target-e2e-evidence-completion-command-material.v1'
        and activation_manifest_hash ~ '^[0-9a-f]{64}$'
        and isolated_domain_db_binding_hash ~ '^[0-9a-f]{64}$'
        and command_hash ~ '^[0-9a-f]{64}$' and command_envelope_hash ~ '^[0-9a-f]{64}$'
        and case_command_request_hash ~ '^[0-9a-f]{64}$'
        and payload_sha256 ~ '^[0-9a-f]{64}$' and material_sha256 ~ '^[0-9a-f]{64}$'
        and trace_id ~ '^[0-9a-f]{32}$' and trace_id <> repeat('0', 32)
        and actor_role in ('USER', 'MERCHANT')
        and room_epoch between 0 and 9007199254740991
        and room_fencing_token between 1 and 9007199254740991
        and expected_process_revision between 0 and 9007199254740991
        and expected_room_revision between 0 and 9007199254740991
        and payload_size_bytes between 1 and 1048576
        and jsonb_typeof(actor_scopes_json) = 'array'
        and octet_length(material_canonical_json) between 2 and 65536
        and material_canonical_json::jsonb is not null
    )
);

create or replace function enforce_target_e2e_evidence_completion_command_material()
returns trigger language plpgsql as $$
declare admission_row target_e2e_command_admission%rowtype; material jsonb;
begin
  select * into admission_row from target_e2e_command_admission
   where admission_id = new.admission_id for share;
  if not found or admission_row.activation_id is distinct from new.activation_id
     or admission_row.activation_manifest_hash is distinct from new.activation_manifest_hash
     or admission_row.execution_lane is distinct from new.execution_lane
     or admission_row.isolated_domain_db_binding_hash is distinct from new.isolated_domain_db_binding_hash
     or admission_row.tenant_surrogate is distinct from new.tenant_surrogate
     or admission_row.case_id is distinct from new.case_id
     or admission_row.command_id is distinct from new.command_id
     or admission_row.command_hash is distinct from new.command_hash
     or admission_row.command_envelope_hash is distinct from new.command_envelope_hash
     or admission_row.room_epoch is distinct from new.room_epoch
     or admission_row.room_fencing_token is distinct from new.room_fencing_token then
    raise exception using errcode = '23514', message = 'target Evidence completion material must exactly bind admission';
  end if;
  material := new.material_canonical_json::jsonb;
  if material #>> '{schema_version}' is distinct from new.material_schema_version
     or material #>> '{execution_lane}' is distinct from new.execution_lane
     or material #>> '{activation_id}' is distinct from new.activation_id
     or material #>> '{activation_manifest_hash}' is distinct from new.activation_manifest_hash
     or material #>> '{isolated_domain_db_binding_hash}' is distinct from new.isolated_domain_db_binding_hash
     or material #>> '{tenant_surrogate}' is distinct from new.tenant_surrogate
     or material #>> '{case_id}' is distinct from new.case_id
     or material #>> '{command_id}' is distinct from new.command_id
     or material #>> '{command_type}' is distinct from 'PARTY_EVIDENCE_COMPLETE'
     or material #>> '{room_type}' is distinct from 'EVIDENCE'
     or material #>> '{room_epoch}' is distinct from new.room_epoch::text
     or material #>> '{room_fencing_token}' is distinct from new.room_fencing_token::text
     or material #>> '{case_command_request_hash}' is distinct from new.case_command_request_hash
     or material #>> '{expected_process_revision}' is distinct from new.expected_process_revision::text
     or material #>> '{expected_room_revision}' is distinct from new.expected_room_revision::text
     or material #>> '{actor_ref,actor_id}' is distinct from new.actor_id
     or material #>> '{actor_ref,actor_role}' is distinct from new.actor_role
     or material #> '{actor_ref,actor_scopes}' is distinct from new.actor_scopes_json
     or material #>> '{payload_ref,schema_version}' is distinct from new.payload_schema_version
     or material #>> '{payload_ref,uri}' is distinct from new.payload_uri
     or material #>> '{payload_ref,sha256}' is distinct from new.payload_sha256
     or material #>> '{payload_ref,size_bytes}' is distinct from new.payload_size_bytes::text
     or (material #>> '{deadline_at}')::timestamptz is distinct from new.deadline_at
     or material #>> '{trace_id}' is distinct from new.trace_id
     or material #>> '{command_hash}' is distinct from new.command_hash
     or material #>> '{command_envelope_hash}' is distinct from new.command_envelope_hash then
    raise exception using errcode = '23514', message = 'target Evidence completion material is not canonical admission authority';
  end if;
  return new;
end $$;

create trigger trg_target_e2e_evidence_completion_material_binding
before insert on target_e2e_evidence_completion_command_material
for each row execute function enforce_target_e2e_evidence_completion_command_material();
create trigger trg_target_e2e_evidence_completion_material_immutable
before update or delete on target_e2e_evidence_completion_command_material
for each row execute function reject_target_e2e_append_only_mutation();
create trigger trg_target_e2e_evidence_completion_material_no_truncate
before truncate on target_e2e_evidence_completion_command_material
for each statement execute function reject_target_e2e_append_only_mutation();
revoke all on target_e2e_evidence_completion_command_material from public;
