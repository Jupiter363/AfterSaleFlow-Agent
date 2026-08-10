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

    @ActivityMethod(name = "ConvergeTargetIntakeTerminalNoCommit")
    ConvergeTargetIntakeTerminalNoCommitResult convergeTargetIntakeTerminalNoCommit(
            ConvergeTargetIntakeTerminalNoCommit request);

    @ActivityMethod(name = "ResolveTargetIntakeTerminalNoCommit")
    ResolveTargetIntakeTerminalNoCommitResult resolveTargetIntakeTerminalNoCommit(
            ResolveTargetIntakeTerminalNoCommit request);

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

    enum TerminalNoCommitOutcome {
        TERMINALIZED,
        IDEMPOTENT_REPLAY
    }

    record ConvergeTargetIntakeTerminalNoCommit(
            String schemaVersion,
            TargetIntakeCommandTerminalNoCommit authority,
            String caseWorkflowId,
            String caseWorkflowRunId,
            String caseWorkflowBuildId) {

        public ConvergeTargetIntakeTerminalNoCommit {
            if (!"converge-target-intake-terminal-no-commit.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be converge-target-intake-terminal-no-commit.v1");
            }
            if (authority == null) {
                throw new IllegalArgumentException("authority must not be null");
            }
            requireText(caseWorkflowId, "caseWorkflowId");
            requireText(caseWorkflowRunId, "caseWorkflowRunId");
            requireText(caseWorkflowBuildId, "caseWorkflowBuildId");
        }
    }

    record ConvergeTargetIntakeTerminalNoCommitResult(
            String schemaVersion,
            TerminalNoCommitOutcome outcome,
            TargetIntakeCommandTerminalNoCommit authority,
            String receiptUri,
            String receiptSha256,
            long processRevision,
            long roomRevision,
            long lastCommandSequence,
            long lastCaseEventSequence) {

        public ConvergeTargetIntakeTerminalNoCommitResult {
            if (!"converge-target-intake-terminal-no-commit-result.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be converge-target-intake-terminal-no-commit-result.v1");
            }
            if (outcome == null || authority == null) {
                throw new IllegalArgumentException("terminal-no-commit result is incomplete");
            }
            requireText(receiptUri, "receiptUri");
            if (receiptSha256 == null || !receiptSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("receiptSha256 must be a lowercase SHA-256");
            }
            if (processRevision < authority.newProcessRevision()
                    || roomRevision < authority.newRoomRevision()
                    || lastCommandSequence < authority.caseCommandSequence()
                    || lastCaseEventSequence < authority.lastCaseEventSequence()) {
                throw new IllegalArgumentException(
                        "terminal-no-commit result moved durable authority backward");
            }
            if (outcome == TerminalNoCommitOutcome.TERMINALIZED
                    && (processRevision != authority.newProcessRevision()
                            || roomRevision != authority.newRoomRevision()
                            || lastCommandSequence != authority.caseCommandSequence()
                            || lastCaseEventSequence != authority.lastCaseEventSequence())) {
                throw new IllegalArgumentException(
                        "new terminal-no-commit convergence must advance exactly once");
            }
        }
    }

    record ResolveTargetIntakeTerminalNoCommit(
            String schemaVersion, TargetIntakeCommandTerminalNoCommit authority) {

        public ResolveTargetIntakeTerminalNoCommit {
            if (!"resolve-target-intake-terminal-no-commit.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be resolve-target-intake-terminal-no-commit.v1");
            }
            if (authority == null) {
                throw new IllegalArgumentException("authority must not be null");
            }
        }
    }

    record ResolveTargetIntakeTerminalNoCommitResult(
            String schemaVersion,
            TargetIntakeCommandTerminalNoCommit authority,
            String receiptUri,
            String receiptSha256) {

        public ResolveTargetIntakeTerminalNoCommitResult {
            if (!"resolve-target-intake-terminal-no-commit-result.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be resolve-target-intake-terminal-no-commit-result.v1");
            }
            if (authority == null) {
                throw new IllegalArgumentException("authority must not be null");
            }
            requireText(receiptUri, "receiptUri");
            if (receiptSha256 == null || !receiptSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("receiptSha256 must be a lowercase SHA-256");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
