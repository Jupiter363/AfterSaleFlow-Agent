package com.example.dispute.workflow.targete2e.finalization;

import com.example.dispute.agentstream.application.AgentRunFinalizationFailure;

/** Classified JDBC failure that forces rollback of the caller-owned formal transaction. */
public final class TargetE2eFinalizationReceiptPersistenceException extends RuntimeException
        implements AgentRunFinalizationFailure {

    private final boolean retryable;

    public TargetE2eFinalizationReceiptPersistenceException(
            String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    @Override
    public String code() {
        return "TargetE2eFinalizationReceiptPersistence";
    }

    @Override
    public boolean retryable() {
        return retryable;
    }
}
