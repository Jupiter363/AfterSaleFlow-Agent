alter table case_intake_snapshot_binding
    add column event_source_type varchar(32);

alter table case_intake_snapshot_binding
    add constraint ck_intake_event_source_authority
        check (
            (binding_type = 'INITIAL' and event_source_type is null)
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
        );
