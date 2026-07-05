alter table fulfillment_dispute_case
    add column if not exists initiator_role varchar(32);

update fulfillment_dispute_case
set initiator_role = case
    when created_by = user_id then 'USER'
    when created_by = merchant_id then 'MERCHANT'
    else 'USER'
end
where initiator_role is null;

alter table fulfillment_dispute_case
    alter column initiator_role set not null;

alter table fulfillment_dispute_case
    add constraint chk_fulfillment_case_initiator_role
    check (initiator_role in ('USER', 'MERCHANT'));
