-- Forward-only semantic cutover from the legacy fulfillment collaboration model
-- to the final AI Native fulfillment-dispute hearing model.

alter table fulfillment_case rename to fulfillment_dispute_case;
alter table fulfillment_dispute_case rename column after_sale_id to after_sales_id;
alter table fulfillment_dispute_case rename column route_type to hearing_route;
alter table fulfillment_dispute_case
    add column initiator_role varchar(32) not null default 'SYSTEM',
    add column logistics_id varchar(64),
    add column current_stage varchar(64);

update fulfillment_dispute_case
set hearing_route = case hearing_route
    when 'REGULAR_FULFILLMENT' then 'TRANSFERRED'
    when 'RULE_BASED_RESOLUTION' then 'SIMPLE_HEARING'
    when 'DISPUTE_HEARING' then 'FULL_HEARING'
    else hearing_route
end
where hearing_route is not null;

alter table party_submission rename to dispute_submission;
alter table claim_issue_evidence_matrix rename to claim_issue_evidence_link;
alter table hearing_record rename to hearing_stage_record;
alter table approval_record rename to human_review_record;
alter table human_review_record rename column approval_hash to action_hash;
alter table human_review_record
    add column packet_version integer,
    add column expires_at timestamptz;
alter table evaluation_trace rename to evaluation_record;

alter table evidence_dossier
    drop constraint uq_evidence_dossier_case;
alter table evidence_dossier
    add constraint uq_evidence_dossier_case_version
        unique (case_id, dossier_version);

update route_decision
set route_type = case route_type
    when 'REGULAR_FULFILLMENT' then 'TRANSFERRED'
    when 'RULE_BASED_RESOLUTION' then 'SIMPLE_HEARING'
    when 'DISPUTE_HEARING' then 'FULL_HEARING'
    else route_type
end;

alter table route_decision drop constraint ck_route_decision_type;
alter table route_decision
    add constraint ck_route_decision_final_type
        check (route_type in (
            'TRANSFERRED',
            'SIMPLE_HEARING',
            'FULL_HEARING'
        ));
alter table route_decision
    add column terminal_in_dispute_system boolean not null default false,
    add column requires_deliberation boolean not null default false;

create index idx_fulfillment_dispute_case_status
    on fulfillment_dispute_case(case_status);
create index idx_fulfillment_dispute_case_route
    on fulfillment_dispute_case(hearing_route);
