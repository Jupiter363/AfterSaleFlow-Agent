package com.example.dispute.hearing.domain;

import java.util.Optional;

/** Atomic boundary between Java formal mutations and Workflow-visible durable receipts. */
public interface HearingAuthorityLedger {

    HearingDomainReceipt commitOrReplay(
            HearingAuthorityCommit command, FormalCommitAction formalCommitAction);

    /**
     * Completes the room-authority half of a CLOSE receipt in the same surrounding transaction.
     * The callback must move the case from the source Hearing epoch to its successor using the
     * exact process and room revisions carried by the committed receipt.
     */
    HearingDomainReceipt completeCloseTransition(
            HearingAuthorityCommit command, CloseTransitionAction closeTransitionAction);

    Optional<HearingDomainReceipt> findCommitted(String tenantSurrogate, String operationKey);

    @FunctionalInterface
    interface FormalCommitAction {
        HearingFormalCommitResult commit();
    }

    @FunctionalInterface
    interface CloseTransitionAction {
        void transition(HearingDomainReceipt receipt);
    }
}
