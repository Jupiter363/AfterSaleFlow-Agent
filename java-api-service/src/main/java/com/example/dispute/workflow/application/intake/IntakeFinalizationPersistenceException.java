package com.example.dispute.workflow.application.intake;

import com.example.dispute.agentstream.application.AgentRunFinalizationFailure;

/** Retryable database/resource failure while committing an Intake formal result. */
public final class IntakeFinalizationPersistenceException extends RuntimeException
        implements AgentRunFinalizationFailure {

    public static final String CODE = "IntakeFinalizationPersistenceRetryable";

    public IntakeFinalizationPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public boolean retryable() {
        return true;
    }
}
