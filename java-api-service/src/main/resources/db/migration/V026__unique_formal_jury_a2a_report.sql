create unique index uq_agent_a2a_jury_review_report
    on agent_a2a_message (
        case_id,
        round_no,
        from_agent,
        to_agent,
        message_type
    )
    where message_type = 'JURY_REVIEW_REPORT';
