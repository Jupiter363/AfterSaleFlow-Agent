package com.example.dispute.hearing.domain;

import java.util.Set;

/** Closed business-decision vocabulary shared by Judge V1 and Judge V2. */
public enum HearingDecisionAction {
    CANCEL_ORDER,
    RETURN_AND_REFUND,
    REFUND_ONLY,
    RESHIP,
    REPLACE,
    REPAIR,
    COMPENSATE,
    CONTINUE_FULFILLMENT,
    REJECT_CLAIM;

    private static final Set<String> CODES =
            Set.of(
                    CANCEL_ORDER.name(),
                    RETURN_AND_REFUND.name(),
                    REFUND_ONLY.name(),
                    RESHIP.name(),
                    REPLACE.name(),
                    REPAIR.name(),
                    COMPENSATE.name(),
                    CONTINUE_FULFILLMENT.name(),
                    REJECT_CLAIM.name());

    public static boolean supports(String code) {
        return code != null && CODES.contains(code);
    }
}
