package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.time.Instant;

@ActivityInterface
public interface CaseCommandLifecycleActivities {

    @ActivityMethod(name = "ExpireCaseCommand")
    ExpireCaseCommandResult expireCaseCommand(ExpireCaseCommand request);

    @ActivityMethod(name = "RecordCaseCommandRouted")
    RecordCaseCommandRoutedResult recordCaseCommandRouted(
            RecordCaseCommandRouted request);

    @ActivityMethod(name = "CompleteCaseCommandRouting")
    RecordCaseCommandRoutedResult completeCaseCommandRouting(
            RecordCaseCommandRouted request);

    enum CommandLifecycleOutcome {
        ORCHESTRATION_ACCEPTED,
        SHADOW_COMPLETED,
        EXPIRED,
        ALREADY_APPLIED,
        ALREADY_SHADOW_COMPLETED,
        ALREADY_REJECTED,
        ALREADY_FAILED,
        ALREADY_EXPIRED
    }

    record ExpireCaseCommand(
            String schemaVersion,
            String tenantSurrogate,
            String caseId,
            String commandId,
            long caseCommandSequence,
            String requestHash,
            Instant deadlineAt,
            Instant expiredAt,
            String workflowId,
            String workflowRunId) {

        public ExpireCaseCommand {
            if (!"expire-case-command.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be expire-case-command.v1");
            }
            requireText(tenantSurrogate, "tenantSurrogate");
            requireText(caseId, "caseId");
            requireText(commandId, "commandId");
            requireText(requestHash, "requestHash");
            requireText(workflowId, "workflowId");
            requireText(workflowRunId, "workflowRunId");
            if (caseCommandSequence < 1) {
                throw new IllegalArgumentException("caseCommandSequence must be positive");
            }
            if (deadlineAt == null || expiredAt == null || expiredAt.isBefore(deadlineAt)) {
                throw new IllegalArgumentException("expiration time is invalid");
            }
        }
    }

    record ExpireCaseCommandResult(
            String schemaVersion, CommandLifecycleOutcome outcome) {

        public ExpireCaseCommandResult {
            if (!"expire-case-command-result.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be expire-case-command-result.v1");
            }
            if (outcome == null) {
                throw new IllegalArgumentException("outcome must not be null");
            }
        }
    }

    record RecordCaseCommandRouted(
            String schemaVersion,
            String tenantSurrogate,
            String caseId,
            String commandId,
            long caseCommandSequence,
            String requestHash,
            RoomType roomType,
            long roomEpoch,
            Instant routedAt,
            String workflowId,
            String workflowRunId) {

        public RecordCaseCommandRouted {
            if (!"record-case-command-routed.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be record-case-command-routed.v1");
            }
            requireText(tenantSurrogate, "tenantSurrogate");
            requireText(caseId, "caseId");
            requireText(commandId, "commandId");
            requireText(requestHash, "requestHash");
            requireText(workflowId, "workflowId");
            requireText(workflowRunId, "workflowRunId");
            if (caseCommandSequence < 1 || roomEpoch < 0) {
                throw new IllegalArgumentException("command routing sequence is invalid");
            }
            if (roomType == null || routedAt == null) {
                throw new IllegalArgumentException("command routing scope is incomplete");
            }
        }
    }

    record RecordCaseCommandRoutedResult(
            String schemaVersion, CommandLifecycleOutcome outcome) {

        public RecordCaseCommandRoutedResult {
            if (!"record-case-command-routed-result.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be record-case-command-routed-result.v1");
            }
            if (outcome == null) {
                throw new IllegalArgumentException("outcome must not be null");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
