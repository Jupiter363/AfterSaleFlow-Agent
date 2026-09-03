drop index if exists uq_room_turn_memory_case_room_turn_actor;

create unique index uq_room_turn_memory_session_turn_actor
    on room_turn_memory(
        agent_session_id,
        turn_no,
        actor_id,
        coalesce(answer_role, ''),
        coalesce(agent_role, '')
    )
    where agent_session_id is not null;

create unique index uq_room_turn_memory_legacy_case_room_turn_actor
    on room_turn_memory(
        case_id,
        room_type,
        turn_no,
        actor_id,
        coalesce(answer_role, ''),
        coalesce(agent_role, '')
    )
    where agent_session_id is null;
