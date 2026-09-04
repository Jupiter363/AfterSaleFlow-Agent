-- Room epochs are zero-based per room type. The first Review epoch therefore has room_epoch=0,
-- while its fencing token remains strictly positive. Keep the exact composite foreign key and
-- append-only guards from V060; only align this local coordinate check with case_room_epoch.
alter table production_runtime_review_epoch_task_binding
    drop constraint ck_review_epoch_task_coordinates;

alter table production_runtime_review_epoch_task_binding
    add constraint ck_review_epoch_task_coordinates
        check (room_epoch >= 0 and room_fencing_token > 0);
