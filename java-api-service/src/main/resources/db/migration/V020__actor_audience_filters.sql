alter table room_message
    add column audience_actor_ids_json jsonb not null default '[]'::jsonb;

alter table case_timeline_event
    add column audience_actor_ids_json jsonb not null default '[]'::jsonb;

create index idx_room_message_audience_actor_ids
    on room_message using gin (audience_actor_ids_json);

create index idx_case_timeline_event_audience_actor_ids
    on case_timeline_event using gin (audience_actor_ids_json);
