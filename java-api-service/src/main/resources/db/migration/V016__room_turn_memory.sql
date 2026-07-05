create table room_turn_memory (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    turn_no integer not null,
    actor_id varchar(128) not null,
    answer_role varchar(64),
    answer_content text,
    agent_role varchar(128),
    agent_response text,
    dossier_patch_json jsonb not null default '{}'::jsonb,
    scroll_snapshot_json jsonb not null default '{}'::jsonb,
    canvas_operations_json jsonb not null default '[]'::jsonb,
    agent_run_id varchar(64),
    created_at timestamptz not null default now(),
    created_by varchar(128) not null,
    constraint fk_room_turn_memory_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint ck_room_turn_memory_room_type
        check (room_type in ('INTAKE', 'EVIDENCE', 'HEARING', 'REVIEW')),
    constraint ck_room_turn_memory_turn_no
        check (turn_no >= 1),
    constraint ck_room_turn_memory_actor_kind
        check (
            (answer_role is not null and answer_content is not null)
            or (agent_role is not null and agent_response is not null)
        )
);

create unique index uq_room_turn_memory_case_room_turn_actor
    on room_turn_memory(case_id, room_type, turn_no, actor_id, coalesce(answer_role, ''), coalesce(agent_role, ''));

create index idx_room_turn_memory_case_room_turn
    on room_turn_memory(case_id, room_type, turn_no desc);

create index idx_room_turn_memory_latest_agent
    on room_turn_memory(case_id, room_type, turn_no desc)
    where agent_role is not null;

create trigger trg_room_turn_memory_append_only
    before update or delete or truncate on room_turn_memory
    for each statement
    execute function reject_append_only_mutation();
