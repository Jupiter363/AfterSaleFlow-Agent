-- Preserve immutable Intake event history while allowing a terminal, uncommitted
-- Graph attempt to be replaced at the same logical sequence.

alter table case_intake_snapshot_binding
    add column binding_generation bigint not null default 1,
    add column supersedes_binding_id varchar(128),
    add constraint ck_intake_event_binding_generation
        check (
            (
                binding_type = 'INITIAL'
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
        ),
    add constraint fk_intake_event_superseded_binding
        foreign key (supersedes_binding_id)
        references case_intake_snapshot_binding(binding_id)
        deferrable initially deferred,
    add constraint uq_intake_event_binding_slot_identity
        unique (
            binding_id,
            thread_registration_id,
            event_sequence,
            binding_generation
        );

drop index uq_intake_event_sequence;

create unique index uq_intake_event_sequence_generation
    on case_intake_snapshot_binding(
        thread_registration_id,
        event_sequence,
        binding_generation
    )
    where binding_type = 'EVENT';

create unique index uq_intake_event_superseded_once
    on case_intake_snapshot_binding(supersedes_binding_id)
    where binding_type = 'EVENT' and supersedes_binding_id is not null;

create table case_intake_event_slot_authority (
    thread_registration_id varchar(128) not null,
    logical_sequence bigint not null,
    current_binding_id varchar(128) not null,
    current_generation bigint not null,
    authority_version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (thread_registration_id, logical_sequence),
    constraint uq_intake_event_slot_current_binding
        unique (current_binding_id),
    constraint fk_intake_event_slot_thread
        foreign key (thread_registration_id)
        references case_intake_graph_thread_binding(registration_id),
    constraint fk_intake_event_slot_current_binding
        foreign key (
            current_binding_id,
            thread_registration_id,
            logical_sequence,
            current_generation
        ) references case_intake_snapshot_binding(
            binding_id,
            thread_registration_id,
            event_sequence,
            binding_generation
        )
        deferrable initially deferred,
    constraint ck_intake_event_slot_numbers
        check (
            logical_sequence > 0
            and current_generation > 0
            and authority_version >= 0
        ),
    constraint ck_intake_event_slot_time
        check (updated_at >= created_at)
);

insert into case_intake_event_slot_authority (
    thread_registration_id,
    logical_sequence,
    current_binding_id,
    current_generation,
    authority_version,
    created_at,
    updated_at
)
select
    thread_registration_id,
    event_sequence,
    binding_id,
    binding_generation,
    0,
    created_at,
    created_at
from case_intake_snapshot_binding
where binding_type = 'EVENT';

create function enforce_intake_event_slot_authority_transition()
returns trigger
language plpgsql
as $$
declare
    bound_generation bigint;
    bound_supersedes varchar(128);
begin
    select binding_generation, supersedes_binding_id
      into bound_generation, bound_supersedes
      from case_intake_snapshot_binding
     where binding_id = new.current_binding_id
       and binding_type = 'EVENT'
       and thread_registration_id = new.thread_registration_id
       and event_sequence = new.logical_sequence;

    if not found or bound_generation <> new.current_generation then
        raise exception using
            errcode = '23514',
            message = 'Intake event slot current binding is not the exact immutable generation';
    end if;

    if tg_op = 'INSERT' then
        if new.current_generation <> 1
            or new.authority_version <> 0
            or bound_supersedes is not null
        then
            raise exception using
                errcode = '23514',
                message = 'Intake event slot must start at generation one';
        end if;
        return new;
    end if;

    if new.thread_registration_id is distinct from old.thread_registration_id
        or new.logical_sequence is distinct from old.logical_sequence
        or new.created_at is distinct from old.created_at
        or new.current_generation <> old.current_generation + 1
        or new.authority_version <> old.authority_version + 1
        or new.updated_at < old.updated_at
        or bound_supersedes is distinct from old.current_binding_id
    then
        raise exception using
            errcode = '23514',
            message = 'Intake event slot authority can only advance one exact generation';
    end if;
    return new;
end
$$;

create trigger trg_intake_event_slot_authority_transition
    before insert or update on case_intake_event_slot_authority
    for each row execute function enforce_intake_event_slot_authority_transition();

create trigger trg_intake_event_slot_authority_no_delete
    before delete on case_intake_event_slot_authority
    for each row execute function reject_append_only_mutation();

create trigger trg_intake_event_slot_authority_no_truncate
    before truncate on case_intake_event_slot_authority
    for each statement execute function reject_append_only_mutation();

create function require_intake_event_history_current_authority()
returns trigger
language plpgsql
as $$
begin
    if new.binding_type = 'EVENT'
        and not exists (
            select 1
              from case_intake_event_slot_authority slot
             where slot.thread_registration_id = new.thread_registration_id
               and slot.logical_sequence = new.event_sequence
               and slot.current_binding_id = new.binding_id
               and slot.current_generation = new.binding_generation
        )
    then
        raise exception using
            errcode = '23514',
            message = 'Intake event history generation lacks current slot authority';
    end if;
    return null;
end
$$;

create constraint trigger trg_intake_event_history_current_authority
    after insert on case_intake_snapshot_binding
    deferrable initially deferred
    for each row
    when (new.binding_type = 'EVENT')
    execute function require_intake_event_history_current_authority();
