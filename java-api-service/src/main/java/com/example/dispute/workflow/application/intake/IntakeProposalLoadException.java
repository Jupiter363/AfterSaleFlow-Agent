package com.example.dispute.workflow.application.intake;

import com.example.dispute.agentstream.application.AgentRunFinalizationFailure;

/**
 * Explicit retryable immutable-object access failure.
 *
 * <p>Only a reader adapter may create this exception after classifying a timeout or a transient
 * object-store/server failure. Unknown reader failures must be rejected fail-closed instead.
 */
public final class IntakeProposalLoadException extends RuntimeException
        implements AgentRunFinalizationFailure {

    public static final String CODE = "IntakeProposalAccessRetryable";

    public IntakeProposalLoadException(String message, Throwable cause) {
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
