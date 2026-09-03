package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import java.util.Objects;

/** Atomically returns an existing final or appends one immutable result-only final. */
public interface AgentRunReconciledFinalStore {

    Receipt appendOrLoad(Request request);

    record Request(
            String logicalRunId,
            String attemptId,
            Audience audience,
            String resultRef,
            String resultHash) {

        public Request {
            logicalRunId = required(logicalRunId, "logicalRunId");
            attemptId = required(attemptId, "attemptId");
            Objects.requireNonNull(audience, "audience");
            resultRef = required(resultRef, "resultRef");
            resultHash = required(resultHash, "resultHash");
            if (!resultHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("resultHash must be lowercase SHA-256");
            }
        }
    }

    record Receipt(
            AgentStreamEvent finalEvent,
            boolean inserted,
            long durableHighWatermark,
            boolean publicOutputEmitted) {

        public Receipt {
            Objects.requireNonNull(finalEvent, "finalEvent");
            AgentStreamEvent.Payload payload = finalEvent.payload();
            if (finalEvent.eventType() != StreamEventType.FINAL
                    || finalEvent.sequenceNo() < 0
                    || durableHighWatermark != finalEvent.sequenceNo()
                    || payload == null
                    || payload.node() != null
                    || payload.field() != null
                    || payload.delta() != null
                    || payload.usage() != null
                    || payload.reasonCode() != null
                    || payload.resetAttemptId() != null
                    || payload.finalResultRef() == null
                    || payload.finalResultRef().isBlank()
                    || payload.finalResultHash() == null
                    || !payload.finalResultHash().matches("[0-9a-f]{64}")
                    || payload.errorCode() != null
                    || payload.retryable() != null) {
                throw new ConflictException("reconciled final receipt is invalid");
            }
        }
    }

    final class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }

        public ConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
