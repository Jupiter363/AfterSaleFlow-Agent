-- Keep the persisted case navigation stage aligned with the post-hearing UI.
alter table fulfillment_dispute_case
    drop constraint if exists ck_dispute_current_room;

alter table fulfillment_dispute_case
    add constraint ck_dispute_current_room
        check (
            current_room is null
            or current_room in (
                'INTAKE',
                'EVIDENCE',
                'HEARING',
                'DRAFT',
                'REVIEW',
                'OUTCOME'
            )
        );
