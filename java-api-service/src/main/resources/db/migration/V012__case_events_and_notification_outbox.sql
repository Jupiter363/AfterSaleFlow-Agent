-- Replayable role-filtered case events and reliable in-platform summons inbox.

alter table case_timeline_event
    add column sequence_no bigint,
    add column room_id varchar(64),
    add column audience_json jsonb not null default '[]'::jsonb,
    add column event_key varchar(128);

with ranked as (
    select
        id,
        row_number() over (
            partition by case_id
            order by event_time, created_at, id
        ) as generated_sequence
    from case_timeline_event
)
update case_timeline_event timeline
set sequence_no = ranked.generated_sequence
from ranked
where timeline.id = ranked.id;

alter table case_timeline_event
    alter column sequence_no set not null,
    add constraint fk_timeline_room
        foreign key (room_id) references case_room(id);

create unique index uq_case_timeline_sequence
    on case_timeline_event(case_id, sequence_no);
create unique index uq_case_timeline_event_key
    on case_timeline_event(case_id, event_key)
    where event_key is not null;
create index idx_case_timeline_room_sequence
    on case_timeline_event(room_id, sequence_no)
    where room_id is not null;

create table notification (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    business_event_key varchar(128) not null,
    recipient_id varchar(128) not null,
    recipient_role varchar(32) not null,
    notification_type varchar(64) not null,
    title varchar(256) not null,
    body text not null,
    deep_link varchar(512) not null,
    payload_json jsonb not null default '{}'::jsonb,
    read_at timestamptz,
    created_at timestamptz not null default now(),
    expires_at timestamptz,
    constraint fk_notification_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint ck_notification_recipient_role
        check (
            recipient_role in (
                'USER',
                'MERCHANT',
                'CUSTOMER_SERVICE',
                'PLATFORM_REVIEWER',
                'ADMIN'
            )
        ),
    constraint ck_notification_type
        check (
            notification_type in (
                'INTAKE_ACCEPTED',
                'DISPUTE_SUMMONS',
                'EVIDENCE_ROOM_OPENED',
                'EVIDENCE_DEADLINE_WARNING',
                'HEARING_OPENED',
                'SUPPLEMENT_REQUESTED',
                'SETTLEMENT_CONFIRMATION_REQUIRED',
                'REVIEW_PENDING',
                'FINAL_DECISION',
                'EXECUTION_COMPLETED',
                'MANUAL_HANDOFF'
            )
        )
);

create unique index uq_notification_business_recipient
    on notification(business_event_key, recipient_id);
create index idx_notification_recipient_unread
    on notification(recipient_id, created_at desc)
    where read_at is null;

create table notification_outbox (
    id varchar(64) primary key,
    case_id varchar(64) not null,
    business_event_key varchar(128) not null,
    event_type varchar(64) not null,
    event_payload_json jsonb not null default '{}'::jsonb,
    outbox_status varchar(32) not null default 'PENDING',
    attempt_count integer not null default 0,
    available_at timestamptz not null default now(),
    published_at timestamptz,
    last_error text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_notification_outbox_case
        foreign key (case_id) references fulfillment_dispute_case(id),
    constraint uq_notification_outbox_event unique (business_event_key),
    constraint ck_notification_outbox_status
        check (outbox_status in ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    constraint ck_notification_outbox_attempts check (attempt_count >= 0)
);

create index idx_notification_outbox_delivery
    on notification_outbox(outbox_status, available_at, created_at);
