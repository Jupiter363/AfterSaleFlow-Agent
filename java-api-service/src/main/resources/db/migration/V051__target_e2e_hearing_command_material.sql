-- Target-only Hearing graph commands. Admission is the authority; this table is immutable
-- material for the CONTROL-to-Agent boundary and cannot create a second command authority.
create table target_e2e_hearing_command_material (
    admission_id varchar(64) primary key,
    activation_id varchar(64) not null,
    activation_manifest_hash varchar(64) not null,
    isolated_domain_db_binding_hash varchar(64) not null,
    tenant_surrogate varchar(128) not null,
    case_id varchar(64) not null,
    command_id varchar(128) not null,
    command_hash varchar(64) not null,
    command_envelope_hash varchar(64) not null,
    room_epoch bigint not null,
    room_fencing_token bigint not null,
    material_canonical_json text not null,
    material_sha256 varchar(64) not null,
    stored_at timestamptz not null default current_timestamp,
    constraint uq_target_e2e_hearing_material_command unique (activation_id, command_id),
    constraint fk_target_e2e_hearing_material_admission
        foreign key (admission_id) references target_e2e_command_admission(admission_id),
    constraint ck_target_e2e_hearing_material_shape check (
        activation_manifest_hash ~ '^[0-9a-f]{64}$'
        and isolated_domain_db_binding_hash ~ '^[0-9a-f]{64}$'
        and command_hash ~ '^[0-9a-f]{64}$'
        and command_envelope_hash ~ '^[0-9a-f]{64}$'
        and material_sha256 ~ '^[0-9a-f]{64}$'
        and room_epoch between 0 and 9007199254740991
        and room_fencing_token between 1 and 9007199254740991
        and octet_length(material_canonical_json) between 2 and 262144
        and material_canonical_json::jsonb is not null
    )
);

create or replace function enforce_target_e2e_hearing_command_material()
returns trigger
language plpgsql
as $$
declare admitted target_e2e_command_admission%rowtype;
begin
    select * into admitted from target_e2e_command_admission
     where admission_id = new.admission_id for share;
    if not found
       or admitted.activation_id is distinct from new.activation_id
       or admitted.activation_manifest_hash is distinct from new.activation_manifest_hash
       or admitted.isolated_domain_db_binding_hash is distinct from new.isolated_domain_db_binding_hash
       or admitted.tenant_surrogate is distinct from new.tenant_surrogate
       or admitted.case_id is distinct from new.case_id
       or admitted.command_id is distinct from new.command_id
       or admitted.command_hash is distinct from new.command_hash
       or admitted.command_envelope_hash is distinct from new.command_envelope_hash
       or admitted.room_epoch is distinct from new.room_epoch
       or admitted.room_fencing_token is distinct from new.room_fencing_token
       or new.material_canonical_json::jsonb #>> '{request,command,room_type}' is distinct from 'HEARING'
    then
        raise exception using errcode = '23514',
            message = 'target E2E Hearing material must exactly bind an admitted Hearing command';
    end if;
    return new;
end
$$;

create trigger trg_target_e2e_hearing_material_binding
before insert on target_e2e_hearing_command_material
for each row execute function enforce_target_e2e_hearing_command_material();
create trigger trg_target_e2e_hearing_material_immutable
before update or delete on target_e2e_hearing_command_material
for each row execute function reject_target_e2e_append_only_mutation();
create trigger trg_target_e2e_hearing_material_no_truncate
before truncate on target_e2e_hearing_command_material
for each statement execute function reject_target_e2e_append_only_mutation();
revoke all on target_e2e_hearing_command_material from public;
