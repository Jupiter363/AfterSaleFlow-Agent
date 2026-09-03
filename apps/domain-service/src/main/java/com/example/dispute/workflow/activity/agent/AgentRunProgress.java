package com.example.dispute.workflow.activity.agent;

/** Public-only progress metadata safe for the Temporal heartbeat payload. */
public record AgentRunProgress(
        long lastSequenceNo,
        boolean publicOutputEmitted,
        boolean finalFrameObserved) {

    public AgentRunProgress {
        if (lastSequenceNo < -1) {
            throw new IllegalArgumentException("lastSequenceNo is below the empty stream baseline");
        }
        if (lastSequenceNo == -1 && (publicOutputEmitted || finalFrameObserved)) {
            throw new IllegalArgumentException(
                    "empty stream baseline cannot carry public or final progress");
        }
    }
}
