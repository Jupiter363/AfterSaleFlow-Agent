alter table target_e2e_evidence_command_material
    drop constraint ck_target_e2e_evidence_material_shape;

alter table target_e2e_evidence_command_material
    add constraint ck_target_e2e_evidence_material_shape check (
        execution_lane = 'TARGET_E2E_CANDIDATE' and room_type = 'EVIDENCE'
        and material_schema_version in (
            'target-e2e-evidence-command-material.v1',
            'target-e2e-evidence-command-material.v2'
        )
        and activation_manifest_hash ~ '^[0-9a-f]{64}$'
        and isolated_domain_db_binding_hash ~ '^[0-9a-f]{64}$'
        and command_hash ~ '^[0-9a-f]{64}$' and command_envelope_hash ~ '^[0-9a-f]{64}$'
        and material_sha256 ~ '^[0-9a-f]{64}$'
        and room_epoch between 0 and 9007199254740991
        and room_fencing_token between 1 and 9007199254740991
        and octet_length(material_canonical_json) between 2 and 262144
        and material_canonical_json::jsonb is not null
    );
