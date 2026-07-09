alter table room_message
    drop constraint if exists ck_room_message_type;

alter table room_message
    add constraint ck_room_message_type
        check (
            message_type in (
                'PARTY_TEXT',
                'PARTY_EVIDENCE_REFERENCE',
                'PARTY_CONFIRMATION',
                'AGENT_MESSAGE',
                'JURY_REVIEW_REPORT',
                'SYSTEM_EVENT',
                'REVIEWER_NOTE'
            )
        );
