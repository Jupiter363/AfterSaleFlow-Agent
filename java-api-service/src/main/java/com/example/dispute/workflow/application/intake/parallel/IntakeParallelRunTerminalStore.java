package com.example.dispute.workflow.application.intake.parallel;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import java.util.Objects;

/**
 * Atomically binds one immutable parallel Intake READY result to V4 FINAL and RESULT_READY.
 *
 * <p>This is a technical terminalization boundary only. It must not write a dossier, matrix,
 * public room message, formal manifest, target receipt, command completion, or assembly COMMITTED.
 */
public interface IntakeParallelRunTerminalStore {

    TerminalReceipt appendOrLoad(TerminalCommand command);

    record TerminalCommand(
            ExecuteAgentRunRequest request, GraphReconcileResponse reconciliation) {

        public TerminalCommand {
            request = Objects.requireNonNull(request, "request");
            reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
        }
    }

    record TerminalReceipt(
            ExecuteAgentRunResult result,
            String resultRef,
            String finalReceiptId,
            String eventId,
            String eventSha256,
            boolean inserted,
            long durableHighWatermark) {

        public TerminalReceipt {
            result = Objects.requireNonNull(result, "result");
            requireText(resultRef, "resultRef");
            requireText(finalReceiptId, "finalReceiptId");
            requireText(eventId, "eventId");
            requireSha256(eventSha256, "eventSha256");
            if (durableHighWatermark != result.lastSequenceNo()) {
                throw new IllegalArgumentException(
                        "durableHighWatermark must equal the terminal result sequence");
            }
        }
    }

    final class TerminalConflictException extends IllegalStateException {
        private final String code;

        public TerminalConflictException(String code, String message) {
            super(message);
            requireText(code, "code");
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
    }
}
