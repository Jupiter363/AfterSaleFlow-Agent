alter table fulfillment_dispute_case
    add column initiator_id varchar(128),
    add column respondent_id varchar(128),
    add column respondent_role varchar(32);

update fulfillment_dispute_case
set initiator_id = case initiator_role
        when 'USER' then user_id
        when 'MERCHANT' then merchant_id
    end,
    respondent_id = case initiator_role
        when 'USER' then merchant_id
        when 'MERCHANT' then user_id
    end,
    respondent_role = case initiator_role
        when 'USER' then 'MERCHANT'
        when 'MERCHANT' then 'USER'
    end;

alter table fulfillment_dispute_case
    alter column initiator_id set not null,
    alter column respondent_id set not null,
    alter column respondent_role set not null;

alter table fulfillment_dispute_case
    add constraint chk_fulfillment_case_respondent_role
        check (respondent_role in ('USER', 'MERCHANT')),
    add constraint chk_fulfillment_case_party_assignment
        check (
            (initiator_role = 'USER'
                and initiator_id = user_id
                and respondent_role = 'MERCHANT'
                and respondent_id = merchant_id)
            or
            (initiator_role = 'MERCHANT'
                and initiator_id = merchant_id
                and respondent_role = 'USER'
                and respondent_id = user_id)
        );

create index idx_fulfillment_case_initiator_identity
    on fulfillment_dispute_case(initiator_id, initiator_role);

create index idx_fulfillment_case_respondent_identity
    on fulfillment_dispute_case(respondent_id, respondent_role);
