package com.example.dispute.workflow.runtime.ingress.rooms;

import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;

/**
 * Production-only pre-admission boundary for non-Intake graph commands. Implementations append their
 * immutable graph hand-off before the normal command service accepts and outboxes the command.
 */
@FunctionalInterface
public interface TargetRoomCommandIngress {
    void materialize(
            String caseId,
            String commandId,
            AcceptCaseCommand command,
            AuthenticatedActor actor,
            String traceId);

    default EvidenceSubmissionRunReceipt materializeEvidenceSubmission(
            String caseId,
            String commandId,
            AcceptCaseCommand command,
            AuthenticatedActor actor,
            String traceId,
            EvidenceAgentTurnCommand evidenceAgentTurnCommand) {
        throw new IllegalStateException(
                "target Evidence submission materialization is unavailable");
    }

    default EvidenceOpeningRunReceipt materializeEvidenceOpening(
            String caseId,
            String commandId,
            AcceptCaseCommand command,
            AuthenticatedActor actor,
            String traceId,
            EvidenceAgentTurnCommand evidenceAgentTurnCommand) {
        throw new IllegalStateException(
                "target Evidence opening materialization is unavailable");
    }

    record EvidenceSubmissionRunReceipt(String logicalRunId) {
        public EvidenceSubmissionRunReceipt {
            if (logicalRunId == null || logicalRunId.isBlank()) {
                throw new IllegalArgumentException("logicalRunId must not be blank");
            }
        }
    }

    record EvidenceOpeningRunReceipt(String logicalRunId, String rootAttemptId) {
        public EvidenceOpeningRunReceipt {
            if (logicalRunId == null || logicalRunId.isBlank()
                    || rootAttemptId == null
                    || !rootAttemptId.equals(logicalRunId + ":1")) {
                throw new IllegalArgumentException(
                        "target Evidence opening run receipt is invalid");
            }
        }
    }
}
