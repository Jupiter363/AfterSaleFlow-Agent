-- Room conversations and their replay stream are legal/audit records.
-- Immutability is enforced below Hibernate as well so ad-hoc SQL cannot
-- rewrite or erase what a participant saw.

create function reject_append_only_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception '% is append-only', tg_table_name
        using errcode = '55000';
end;
$$;

create trigger trg_room_message_append_only
    before update or delete or truncate on room_message
    for each statement
    execute function reject_append_only_mutation();

create trigger trg_case_timeline_event_append_only
    before update or delete or truncate on case_timeline_event
    for each statement
    execute function reject_append_only_mutation();
