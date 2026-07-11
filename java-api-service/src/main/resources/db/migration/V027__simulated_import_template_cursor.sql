create table simulated_import_template_cursor (
    id varchar(64) primary key,
    next_template_no integer not null,
    version bigint not null default 0,
    updated_at timestamptz not null default now(),
    constraint ck_simulated_import_template_cursor_range
        check (next_template_no between 1 and 20)
);

insert into simulated_import_template_cursor (id, next_template_no)
values ('external-case-template', 1);
