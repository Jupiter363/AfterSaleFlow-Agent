-- Freeze the ROOM_MESSAGE execution mode at the immutable Target room-epoch binding.
-- Historical rows remain on the pre-parallel V3 behavior. Only the epoch issuer may opt a
-- newly-created Intake epoch into PARALLEL_FRAMES_V1.
alter table target_e2e_room_epoch_binding
    add column intake_room_message_execution_profile_id varchar(32)
        not null default 'MONOLITHIC_V3';

alter table target_e2e_room_epoch_binding
    add constraint ck_target_e2e_intake_room_message_execution_profile
    check (
        (room_type = 'INTAKE'
            and intake_room_message_execution_profile_id in (
                'MONOLITHIC_V3',
                'PARALLEL_FRAMES_V1'
            ))
        or
        (room_type <> 'INTAKE'
            and intake_room_message_execution_profile_id = 'MONOLITHIC_V3')
    );

comment on column target_e2e_room_epoch_binding.intake_room_message_execution_profile_id is
    'Immutable epoch-scoped ROOM_MESSAGE execution profile; opening remains agent-stream.v3.';
