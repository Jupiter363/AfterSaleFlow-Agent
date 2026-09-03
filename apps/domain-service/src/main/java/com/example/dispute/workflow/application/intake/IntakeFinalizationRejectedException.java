package com.example.dispute.workflow.application.intake;

import com.example.dispute.agentstream.application.AgentRunFinalizationFailure;

/** Non-retryable schema, authority, fencing, or domain rejection at the formal boundary. */
public final class IntakeFinalizationRejectedException extends RuntimeException
        implements AgentRunFinalizationFailure {

    private final String code;

    public IntakeFinalizationRejectedException(String code, String message) {
        super(message);
        this.code = IntakeContractSupport.identifier(code, "code");
    }

    public IntakeFinalizationRejectedException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = IntakeContractSupport.identifier(code, "code");
    }

    public String code() {
        return code;
    }

    @Override
    public boolean retryable() {
        return false;
    }
}
