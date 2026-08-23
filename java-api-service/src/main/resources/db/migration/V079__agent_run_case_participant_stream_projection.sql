alter table agent_run
    add column if not exists stream_projection_mode varchar(32) not null default 'BOUND_AUDIENCE';

alter table agent_run
    drop constraint if exists chk_agent_run_stream_projection_mode;

alter table agent_run
    add constraint chk_agent_run_stream_projection_mode
    check (stream_projection_mode in ('BOUND_AUDIENCE', 'CASE_PARTICIPANTS'));
