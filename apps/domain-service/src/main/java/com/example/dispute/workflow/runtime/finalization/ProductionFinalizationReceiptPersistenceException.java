package com.example.dispute.workflow.runtime.finalization;

import com.example.dispute.agentstream.application.AgentRunFinalizationFailure;

/** Classified JDBC failure that forces rollback of the caller-owned formal transaction. */
public final class ProductionFinalizationReceiptPersistenceException extends RuntimeException
        implements AgentRunFinalizationFailure {

    private final boolean retryable;

    public ProductionFinalizationReceiptPersistenceException(
            String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    @Override
    public String code() {
        return "ProductionFinalizationReceiptPersistence";
    }

    @Override
    public boolean retryable() {
        return retryable;
    }
}
