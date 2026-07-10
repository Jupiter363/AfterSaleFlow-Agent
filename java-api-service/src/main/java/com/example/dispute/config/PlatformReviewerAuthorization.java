package com.example.dispute.config;

import com.example.dispute.common.exception.ForbiddenException;

public final class PlatformReviewerAuthorization {

    public static final String SYSTEM_REVIEWER_ID = "reviewer-local";

    private PlatformReviewerAuthorization() {}

    public static void requireDecisionAccess(AuthenticatedActor actor) {
        if (actor.role() != ActorRole.PLATFORM_REVIEWER
                || !SYSTEM_REVIEWER_ID.equals(actor.actorId())) {
            throw new ForbiddenException(
                    "only the system platform reviewer can decide");
        }
    }
}
