-- A generation reset is a durable, non-terminal v3 control event. It invalidates
-- provisional visible deltas before the same Python request is generated again.

alter table agent_run_stream_event
    drop constraint if exists ck_agent_run_stream_event_type_v3,
    add constraint ck_agent_run_stream_event_type_v3
        check (event_type in (
            'start', 'attempt_started', 'visible_delta', 'generation_reset',
            'public_frame_start', 'public_text_delta', 'active_frame_snapshot',
            'public_frame_committed', 'public_frame_interrupted', 'usage',
            'attempt_aborted', 'attempt_reset', 'final', 'error'
        ));

alter table agent_run_stream_event_delivery
    drop constraint if exists ck_stream_event_delivery_type_v3,
    add constraint ck_stream_event_delivery_type_v3
        check (event_type in (
            'start', 'attempt_started', 'visible_delta', 'generation_reset',
            'public_frame_start', 'public_text_delta', 'active_frame_snapshot',
            'public_frame_committed', 'public_frame_interrupted', 'usage',
            'attempt_aborted', 'attempt_reset', 'final', 'error'
        ));
