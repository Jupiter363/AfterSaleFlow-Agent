package com.example.dispute.workflow.activity.agent;

/** Public-only progress metadata safe for the Temporal heartbeat payload. */
public record AgentRunProgress(
        long lastSequenceNo,
        boolean publicOutputEmitted,
        boolean finalFrameObserved) {

    public AgentRunProgress {
        if (lastSequenceNo < 0) {
            throw new IllegalArgumentException("lastSequenceNo must not be negative");
        }
    }
}
