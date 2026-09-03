create table case_intake_dossier (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    room_type varchar(32) not null,
    dossier_version integer not null,
    dossier_json jsonb not null default '{}'::jsonb,
    quality_score integer not null default 0,
    ready_for_next_step boolean not null default false,
    admission_recommendation varchar(32) not null,
    source_turn_no integer not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by varchar(128) not null,
    updated_by varchar(128) not null,
    constraint fk_case_intake_dossier_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint ck_case_intake_dossier_room_type
        check (room_type in ('INTAKE')),
    constraint ck_case_intake_dossier_version
        check (dossier_version >= 1),
    constraint ck_case_intake_dossier_quality_score
        check (quality_score between 0 and 100),
    constraint ck_case_intake_dossier_source_turn
        check (source_turn_no >= 1)
);

create unique index uq_case_intake_dossier_case_room
    on case_intake_dossier(case_id, room_type);

create index idx_case_intake_dossier_ready
    on case_intake_dossier(case_id, ready_for_next_step, quality_score);

