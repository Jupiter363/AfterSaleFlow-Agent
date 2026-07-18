-- Add a terminal state for shadow orchestration without claiming a formal domain mutation.
alter table case_command
    drop constraint ck_case_command_status;

alter table case_command
    add constraint ck_case_command_status
        check (command_status in (
            'PENDING_ORCHESTRATION', 'ORCHESTRATION_ACCEPTED', 'APPLIED',
            'SHADOW_COMPLETED', 'REJECTED', 'FAILED', 'EXPIRED'
        ));

alter table case_command_outbox
    drop constraint ck_case_command_outbox_status;

alter table case_command_outbox
    add constraint ck_case_command_outbox_status
        check (outbox_status in (
            'PENDING', 'CLAIMED', 'RETRY', 'DELIVERED', 'RECONCILED',
            'DEAD_LETTER'
        ));
