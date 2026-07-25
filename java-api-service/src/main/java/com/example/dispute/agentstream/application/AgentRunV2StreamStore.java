package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import java.util.List;
import java.util.Objects;

/** Durable, hash-bound append port for attempt-scoped public stream events. */
public interface AgentRunV2StreamStore {

    AppendReceipt append(AgentStreamEvent event);

    BatchAppendReceipt appendBatch(List<AgentStreamEvent> events);

    default List<AgentStreamEvent> replay(
            String runId, String attemptId, long afterSequence, int limit) {
        throw new UnsupportedOperationException(
                "this stream-store decorator does not expose authoritative replay");
    }

    default long durableHighWatermark(String runId, String attemptId) {
        throw new UnsupportedOperationException(
                "this stream-store decorator does not expose a durable high-watermark");
    }

    default CompatibilityReport validateCompatibility(
            String streamProtocol, String runId, String attemptId) {
        throw new UnsupportedOperationException(
                "this stream-store decorator cannot authorize a compatibility switch");
    }

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

    /** Exact old/target replay checks required before selecting a target reader. */
    record CompatibilityReport(
            String streamProtocol,
            String runId,
            String attemptId,
            long sourceCount,
            long targetCount,
            boolean countParity,
            boolean canonicalHashParity,
            boolean sequenceParity,
            boolean actorIdParity,
            boolean audienceParity,
            boolean visibilityParity,
            boolean resetParity,
            boolean terminalParity,
            boolean reconnectParity,
            boolean compositeCursorParity) {

        public CompatibilityReport {
            Objects.requireNonNull(streamProtocol, "streamProtocol");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(attemptId, "attemptId");
            if (sourceCount < 0 || targetCount < 0) {
                throw new IllegalArgumentException("compatibility counts must not be negative");
            }
        }

        public boolean compatible() {
            return countParity
                    && canonicalHashParity
                    && sequenceParity
                    && actorIdParity
                    && audienceParity
                    && visibilityParity
                    && resetParity
                    && terminalParity
                    && reconnectParity
                    && compositeCursorParity;
        }

        public CompatibilityReport requireCompatible() {
            if (!compatible()) {
                throw new CompatibilityMismatchException(this);
            }
            return this;
        }
    }

    /** Fail-closed signal: no reader switch or compatibility claim may ignore this mismatch. */
    final class CompatibilityMismatchException extends IllegalStateException {
        private final CompatibilityReport report;

        public CompatibilityMismatchException(CompatibilityReport report) {
            super("old/target stream compatibility validation failed for "
                    + Objects.requireNonNull(report, "report").streamProtocol()
                    + ":"
                    + report.runId()
                    + ":"
                    + report.attemptId());
            this.report = report;
        }

        public CompatibilityReport report() {
            return report;
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
