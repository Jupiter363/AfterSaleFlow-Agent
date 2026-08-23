alter table human_review_record
    add column if not exists ai_decision_action varchar(64),
    add column if not exists reviewer_decision_action varchar(64);

alter table human_review_record
    add constraint ck_human_review_ai_decision_action
        check (
            ai_decision_action is null
            or ai_decision_action in (
                'CANCEL_ORDER',
                'RETURN_AND_REFUND',
                'REFUND_ONLY',
                'RESHIP',
                'REPLACE',
                'REPAIR',
                'COMPENSATE',
                'CONTINUE_FULFILLMENT',
                'REJECT_CLAIM'
            )
        ),
    add constraint ck_human_review_reviewer_decision_action
        check (
            reviewer_decision_action is null
            or reviewer_decision_action in (
                'CANCEL_ORDER',
                'RETURN_AND_REFUND',
                'REFUND_ONLY',
                'RESHIP',
                'REPLACE',
                'REPAIR',
                'COMPENSATE',
                'CONTINUE_FULFILLMENT',
                'REJECT_CLAIM',
                'ESCALATE_MANUAL'
            )
        ),
    add constraint ck_human_review_decision_action_binding
        check (
            (ai_decision_action is null and reviewer_decision_action is null)
            or (
                ai_decision_action is not null
                and reviewer_decision_action is not null
                and (
                    (decision_type = 'APPROVE'
                        and reviewer_decision_action = ai_decision_action)
                    or (decision_type = 'MODIFY_AND_APPROVE'
                        and reviewer_decision_action <> ai_decision_action
                        and reviewer_decision_action <> 'ESCALATE_MANUAL')
                    or (decision_type = 'ESCALATE_MANUAL'
                        and reviewer_decision_action = 'ESCALATE_MANUAL')
                )
            )
        );
