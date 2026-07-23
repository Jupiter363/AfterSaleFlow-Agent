package com.example.dispute.hearing.domain;

import java.util.Optional;

/** Atomic boundary between Java formal mutations and Workflow-visible durable receipts. */
public interface HearingAuthorityLedger {

    HearingDomainReceipt commitOrReplay(
            HearingAuthorityCommit command, FormalCommitAction formalCommitAction);

    Optional<HearingDomainReceipt> findCommitted(String tenantSurrogate, String operationKey);

    @FunctionalInterface
    interface FormalCommitAction {
        HearingFormalCommitResult commit();
    }
}
