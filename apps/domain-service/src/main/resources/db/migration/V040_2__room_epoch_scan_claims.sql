-- Persist scan fairness and leases independently from room ownership revisions.
alter table case_room_epoch
    add column domain_event_recovery_next_scan_at timestamp with time zone,
    add column domain_event_recovery_claim_token varchar(64),
    add column domain_event_recovery_claimed_until timestamp with time zone,
    add column projection_reconciliation_next_scan_at timestamp with time zone,
    add column projection_reconciliation_claim_token varchar(64),
    add column projection_reconciliation_claimed_until timestamp with time zone;

update case_room_epoch
   set domain_event_recovery_next_scan_at = coalesce(updated_at, current_timestamp),
       projection_reconciliation_next_scan_at = coalesce(updated_at, current_timestamp);

alter table case_room_epoch
    alter column domain_event_recovery_next_scan_at set not null,
    alter column domain_event_recovery_next_scan_at set default current_timestamp,
    alter column projection_reconciliation_next_scan_at set not null,
    alter column projection_reconciliation_next_scan_at set default current_timestamp,
    add constraint ck_case_room_epoch_event_recovery_claim
        check (
            (domain_event_recovery_claim_token is null
                and domain_event_recovery_claimed_until is null)
            or
            (domain_event_recovery_claim_token is not null
                and domain_event_recovery_claimed_until is not null)
        ),
    add constraint ck_case_room_epoch_reconciliation_claim
        check (
            (projection_reconciliation_claim_token is null
                and projection_reconciliation_claimed_until is null)
            or
            (projection_reconciliation_claim_token is not null
                and projection_reconciliation_claimed_until is not null)
        );

create index idx_case_room_epoch_event_recovery_due
    on case_room_epoch(
        domain_event_recovery_next_scan_at,
        domain_event_recovery_claimed_until,
        updated_at,
        id
    )
    where lifecycle_status = 'ACTIVE'
      and writer_mode in ('SHADOW', 'TEMPORAL')
      and temporal_workflow_id is not null;

create index idx_case_room_epoch_reconciliation_due
    on case_room_epoch(
        projection_reconciliation_next_scan_at,
        projection_reconciliation_claimed_until,
        updated_at,
        id
    )
    where lifecycle_status = 'ACTIVE'
      and writer_mode in ('SHADOW', 'TEMPORAL')
      and temporal_workflow_id is not null;
