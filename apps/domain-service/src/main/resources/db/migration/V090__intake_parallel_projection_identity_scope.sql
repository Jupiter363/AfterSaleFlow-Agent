-- Provider projection identifiers are local to one Frame generation.
--
-- V081 accidentally made them unique across the whole exact-three Frame set.
-- That rejects both legitimate cross-Frame identifier reuse and a generation-2
-- retry that deterministically re-emits its generation-1 identifiers.

alter table intake_parallel_frame_projection_item
    drop constraint uq_intake_parallel_projection_item_id;

alter table intake_parallel_frame_projection_item
    add constraint uq_intake_parallel_projection_item_generation
        unique (frame_set_id, frame_type, frame_generation, canonical_item_id);

