-- Keep room-flow hearing cases and configurable hearing rounds consistent.

update fulfillment_dispute_case
set hearing_route = 'FULL_HEARING',
    updated_by = coalesce(nullif(updated_by, ''), 'migration-v015')
where hearing_route is null
  and (
      current_room = 'HEARING'
      or case_status in (
          'HEARING',
          'HEARING_OPEN',
          'WAITING_EVIDENCE',
          'SETTLEMENT_PENDING',
          'DRAFT_READY',
          'DELIBERATION_RUNNING'
      )
  );

alter table hearing_round
    drop constraint if exists ck_hearing_round_no;

alter table hearing_round
    add constraint ck_hearing_round_no
        check (round_no between 1 and 5);

alter table hearing_round_party_submission
    drop constraint if exists ck_hearing_round_party_submission_round_no;

alter table hearing_round_party_submission
    add constraint ck_hearing_round_party_submission_round_no
        check (round_no between 1 and 5);

alter table room_message
    drop constraint if exists ck_room_message_round;

alter table room_message
    add constraint ck_room_message_round
        check (hearing_round is null or hearing_round between 1 and 5);
