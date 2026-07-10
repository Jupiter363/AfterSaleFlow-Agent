package com.example.dispute.casecore.application;

import com.example.dispute.config.ActorRole;

/** Fixed demo accounts accepted by external import boundaries. */
public final class DemoImportActors {

    public static final String USER_ID = "user-local";
    public static final String MERCHANT_ID = "merchant-local";

    private DemoImportActors() {}

    public static void requireImportedParties(String userId, String merchantId) {
        if (!USER_ID.equals(userId)) {
            throw new IllegalArgumentException("userId must be user-local");
        }
        if (!MERCHANT_ID.equals(merchantId)) {
            throw new IllegalArgumentException("merchantId must be merchant-local");
        }
    }

    public static void requireSimulationParties(
            ActorRole initiatorRole,
            String currentActorId,
            String counterpartyActorId) {
        String expectedCurrent =
                initiatorRole == ActorRole.USER ? USER_ID : MERCHANT_ID;
        String expectedCounterparty =
                initiatorRole == ActorRole.USER ? MERCHANT_ID : USER_ID;
        if (!expectedCurrent.equals(currentActorId)) {
            throw new IllegalArgumentException(
                    "currentActorId must be " + expectedCurrent);
        }
        if (!expectedCounterparty.equals(counterpartyActorId)) {
            throw new IllegalArgumentException(
                    "counterpartyActorId must be " + expectedCounterparty);
        }
    }
}
