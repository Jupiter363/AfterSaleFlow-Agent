-- Preserve the one INITIAL snapshot per private thread while allowing an authenticated
-- parallel ROOM_MESSAGE command to bind its own immutable, current-dossier snapshot.

alter table case_intake_snapshot_binding
    drop constraint ck_intake_snapshot_reference,
    drop constraint ck_intake_snapshot_shape,
    drop constraint ck_intake_event_source_authority,
    drop constraint ck_intake_event_binding_generation;

alter table case_intake_snapshot_binding
    add constraint ck_intake_snapshot_reference
        check (
            object_uri ~ '^(s3|minio|urn):'
            and length(btrim(object_version)) between 1 and 128
            and content_sha256 ~ '^[0-9a-f]{64}$'
            and (
                (binding_type in ('INITIAL', 'TURN') and size_bytes between 1 and 262144)
                or (binding_type = 'EVENT' and size_bytes between 1 and 32768)
            )
        ),
    add constraint ck_intake_snapshot_shape
        check (
            (
                binding_type = 'INITIAL'
                and schema_version = 'intake-domain-snapshot.v2'
                and initialization_marker
                and room_revision is not null
                and projection_revision is not null
                and initial_last_sequence is not null
                and initial_last_sequence >= 0
                and event_id is null
                and message_id is null
                and event_sequence is null
                and audience is null
                and occurred_at is null
            )
            or
            (
                binding_type = 'TURN'
                and schema_version = 'intake-domain-snapshot.v2'
                and not initialization_marker
                and room_revision is not null
                and projection_revision is not null
                and initial_last_sequence is not null
                and initial_last_sequence >= 0
                and event_id is null
                and message_id is null
                and event_sequence is null
                and audience is null
                and occurred_at is null
            )
            or
            (
                binding_type = 'EVENT'
                and schema_version = 'intake-turn-event.v2'
                and not initialization_marker
                and room_revision is null
                and projection_revision is null
                and initial_last_sequence is null
                and event_id is not null
                and message_id is not null
                and event_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
                and message_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
                and event_sequence > 0
                and audience in ('USER', 'MERCHANT')
                and audience = actor_audience
                and occurred_at is not null
            )
        ),
    add constraint ck_intake_event_source_authority
        check (
            (binding_type in ('INITIAL', 'TURN') and event_source_type is null)
            or
            (
                binding_type = 'EVENT'
                and (
                    event_source_type is null
                    or event_source_type in (
                        'INITIAL_FORM',
                        'ROOM_MESSAGE',
                        'FORMAL_EVENT',
                        'RESPONDENT_OPENING'
                    )
                )
            )
        ),
    add constraint ck_intake_event_binding_generation
        check (
            (
                binding_type in ('INITIAL', 'TURN')
                and binding_generation = 1
                and supersedes_binding_id is null
            )
            or
            (
                binding_type = 'EVENT'
                and binding_generation >= 1
                and (
                    (binding_generation = 1 and supersedes_binding_id is null)
                    or (binding_generation > 1 and supersedes_binding_id is not null)
                )
            )
        );

create index ix_intake_turn_snapshot_artifact
    on case_intake_snapshot_binding(thread_registration_id, artifact_id)
    where binding_type = 'TURN';
