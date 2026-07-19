package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import java.util.List;
import java.util.Objects;

/** Durable, hash-bound append port for attempt-scoped public stream events. */
public interface AgentRunV2StreamStore {

    AppendReceipt append(AgentStreamEvent event);

    BatchAppendReceipt appendBatch(List<AgentStreamEvent> events);

    record AppendReceipt(boolean inserted, long durableHighWatermark) {
        public AppendReceipt {
            if (durableHighWatermark < 0) {
                throw new IllegalArgumentException(
                        "durableHighWatermark must not be negative");
            }
        }
    }

    record BatchAppendReceipt(List<Boolean> inserted, long durableHighWatermark) {
        public BatchAppendReceipt {
            if (inserted == null || inserted.isEmpty()) {
                throw new IllegalArgumentException("inserted must describe every batch event");
            }
            inserted = List.copyOf(inserted);
            if (durableHighWatermark < 0) {
                throw new IllegalArgumentException(
                        "durableHighWatermark must not be negative after append");
            }
        }

        public int insertedCount() {
            return Math.toIntExact(inserted.stream().filter(Boolean::booleanValue).count());
        }
    }

    /** A new event cannot be appended because the durable attempt is no longer writable. */
    final class NonRunningAttemptException extends IllegalStateException {
        private final AgentRunAttemptStatus attemptStatus;

        public NonRunningAttemptException(AgentRunAttemptStatus attemptStatus) {
            super("new durable stream events require a RUNNING attempt; status is "
                    + Objects.requireNonNull(attemptStatus, "attemptStatus"));
            this.attemptStatus = attemptStatus;
        }

        public AgentRunAttemptStatus attemptStatus() {
            return attemptStatus;
        }
    }
}
