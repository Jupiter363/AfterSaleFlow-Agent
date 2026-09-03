package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;

/** Durable boundary that terminalizes a nonretryable formal-finalization rejection. */
@FunctionalInterface
public interface AgentRunFinalizationFailureRecorder {

    Receipt record(Command command);

    record Command(
            String agentRunId,
            String logicalRunId,
            String attemptId,
            long attemptNo,
            String commandId,
            String commandRequestHash,
            String resultHash,
            long finalSequenceNo,
            boolean publicOutputEmitted,
            String safeErrorCode) {

        public Command {
            agentRunId = identifier(agentRunId, "agentRunId");
            logicalRunId = identifier(logicalRunId, "logicalRunId");
            attemptId = identifier(attemptId, "attemptId");
            if (attemptNo < 1) {
                throw new IllegalArgumentException("attemptNo must be positive");
            }
            commandId = identifier(commandId, "commandId");
            commandRequestHash = sha256(commandRequestHash, "commandRequestHash");
            resultHash = sha256(resultHash, "resultHash");
            if (finalSequenceNo < 0 || finalSequenceNo == Long.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "finalSequenceNo must be non-negative and leave room for a terminal error");
            }
            safeErrorCode = safeCode(safeErrorCode);
        }
    }

    record Receipt(
            String agentRunId,
            String attemptId,
            String resultHash,
            long terminalSequenceNo,
            AgentRunAttemptStatus attemptStatus,
            String safeErrorCode,
            boolean replayed) {

        public Receipt {
            agentRunId = identifier(agentRunId, "agentRunId");
            attemptId = identifier(attemptId, "attemptId");
            resultHash = sha256(resultHash, "resultHash");
            if (terminalSequenceNo < 1) {
                throw new IllegalArgumentException("terminalSequenceNo must be positive");
            }
            if (attemptStatus != AgentRunAttemptStatus.FAILED
                    && attemptStatus != AgentRunAttemptStatus.ABORTED) {
                throw new IllegalArgumentException(
                        "attemptStatus must be FAILED or ABORTED");
            }
            safeErrorCode = safeCode(safeErrorCode);
        }
    }

    private static String identifier(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(
                    field + " must be a nonblank identifier of at most 128 characters");
        }
        return value;
    }

    private static String sha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return value;
    }

    private static String safeCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException(
                    "safeErrorCode must be an uppercase bounded fixed code");
        }
        return value;
    }
}
