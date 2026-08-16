package com.example.dispute.workflow.temporal.caseprocess;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.temporal.room.intake.TargetIntakeSourceEventRef;
import com.example.dispute.workflow.targete2e.temporal.room.TargetRoomAgentRunTerminalNoCommit;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.time.Instant;
import java.util.List;

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

    @ActivityMethod(name = "ConvergeTargetEvidenceTerminalNoCommit")
    default ConvergeTargetEvidenceTerminalNoCommitResult convergeTargetEvidenceTerminalNoCommit(
            ConvergeTargetEvidenceTerminalNoCommit request) {
        throw new UnsupportedOperationException(
                "target Evidence terminal-no-commit convergence is unavailable");
    }

    @ActivityMethod(name = "ResolveTargetEvidenceTerminalNoCommit")
    default ResolveTargetEvidenceTerminalNoCommitResult resolveTargetEvidenceTerminalNoCommit(
            ResolveTargetEvidenceTerminalNoCommit request) {
        throw new UnsupportedOperationException(
                "target Evidence terminal-no-commit resolution is unavailable");
    }

    @ActivityMethod(name = "RecoverExpiredTargetEvidenceTerminalNoCommit")
    default RecoverExpiredTargetEvidenceTerminalNoCommitResult
            recoverExpiredTargetEvidenceTerminalNoCommit(
                    RecoverExpiredTargetEvidenceTerminalNoCommit request) {
        throw new UnsupportedOperationException(
                "expired target Evidence terminal recovery is unavailable");
    }

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

    enum ExpiredTargetEvidenceTerminalRecoveryOutcome {
        RECOVERED,
        IDEMPOTENT_REPLAY
    }

    record RecoverExpiredTargetEvidenceTerminalNoCommit(
            String schemaVersion,
            CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest recovery,
            long roomEpoch,
            long roomFencingToken,
            String roomWorkflowId,
            String roomWorkflowRunId,
            String roomWorkflowBuildId,
            String caseWorkflowBuildId) {

        public static final String SCHEMA_VERSION =
                "recover-expired-target-evidence-terminal-no-commit.v1";

        public RecoverExpiredTargetEvidenceTerminalNoCommit {
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be recover-expired-target-evidence-terminal-no-commit.v1");
            }
            if (recovery == null) {
                throw new IllegalArgumentException("recovery must not be null");
            }
            if (roomEpoch < 0 || roomFencingToken < 1) {
                throw new IllegalArgumentException("target Evidence room authority is invalid");
            }
            requireText(roomWorkflowId, "roomWorkflowId");
            requireText(roomWorkflowRunId, "roomWorkflowRunId");
            requireText(roomWorkflowBuildId, "roomWorkflowBuildId");
            requireText(caseWorkflowBuildId, "caseWorkflowBuildId");
        }
    }

    record RecoverExpiredTargetEvidenceTerminalNoCommitResult(
            String schemaVersion,
            ExpiredTargetEvidenceTerminalRecoveryOutcome outcome,
            String recoveryId,
            String requestSha256,
            TargetRoomAgentRunTerminalNoCommit authority,
            String receiptUri,
            String receiptSha256,
            Instant actualExpiredAt,
            long processRevision,
            long roomRevision,
            long lastCommandSequence,
            long lastCaseEventSequence) {

        public static final String SCHEMA_VERSION =
                "recover-expired-target-evidence-terminal-no-commit-result.v1";

        public RecoverExpiredTargetEvidenceTerminalNoCommitResult {
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be recover-expired-target-evidence-terminal-no-commit-result.v1");
            }
            if (outcome == null || authority == null || actualExpiredAt == null) {
                throw new IllegalArgumentException(
                        "expired target Evidence recovery result is incomplete");
            }
            requireText(recoveryId, "recoveryId");
            if (requestSha256 == null || !requestSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("requestSha256 must be a lowercase SHA-256");
            }
            if (!authority.receiptUri().equals(receiptUri)
                    || !authority.receiptSha256().equals(receiptSha256)
                    || !authority.terminalAt().isBefore(authority.command().deadlineAt())
                    || authority.command().deadlineAt().isAfter(actualExpiredAt)
                    || processRevision != authority.command().expectedProcessRevision()
                    || roomRevision != authority.expectedRoomRevision()
                    || lastCommandSequence != authority.command().caseCommandSequence()
                    || lastCaseEventSequence != authority.expectedLastCaseEventSequence()) {
                throw new IllegalArgumentException(
                        "expired target Evidence recovery result conflicts with terminal authority");
            }
        }
    }

    record ConvergeTargetEvidenceTerminalNoCommit(
            String schemaVersion,
            TargetRoomAgentRunTerminalNoCommit authority,
            String caseWorkflowId,
            String caseWorkflowRunId,
            String caseWorkflowBuildId) {

        public ConvergeTargetEvidenceTerminalNoCommit {
            if (!"converge-target-evidence-terminal-no-commit.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be converge-target-evidence-terminal-no-commit.v1");
            }
            if (authority == null) {
                throw new IllegalArgumentException("authority must not be null");
            }
            requireText(caseWorkflowId, "caseWorkflowId");
            requireText(caseWorkflowRunId, "caseWorkflowRunId");
            requireText(caseWorkflowBuildId, "caseWorkflowBuildId");
        }
    }

    record ConvergeTargetEvidenceTerminalNoCommitResult(
            String schemaVersion,
            TerminalNoCommitOutcome outcome,
            TargetRoomAgentRunTerminalNoCommit authority,
            String receiptUri,
            String receiptSha256,
            long processRevision,
            long roomRevision,
            long lastCommandSequence,
            long lastCaseEventSequence) {

        public ConvergeTargetEvidenceTerminalNoCommitResult {
            if (!"converge-target-evidence-terminal-no-commit-result.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be converge-target-evidence-terminal-no-commit-result.v1");
            }
            if (outcome == null || authority == null) {
                throw new IllegalArgumentException("terminal-no-commit result is incomplete");
            }
            if (!authority.receiptUri().equals(receiptUri)
                    || !authority.receiptSha256().equals(receiptSha256)) {
                throw new IllegalArgumentException(
                        "terminal-no-commit result receipt conflicts with its authority");
            }
            if (processRevision < authority.command().expectedProcessRevision()
                    || roomRevision < authority.expectedRoomRevision()
                    || lastCommandSequence < authority.command().caseCommandSequence()
                    || lastCaseEventSequence < 0) {
                throw new IllegalArgumentException(
                        "terminal-no-commit result moved durable authority backward");
            }
            if (outcome == TerminalNoCommitOutcome.TERMINALIZED
                    && (processRevision != authority.command().expectedProcessRevision()
                            || roomRevision != authority.expectedRoomRevision()
                            || lastCommandSequence != authority.command().caseCommandSequence())) {
                throw new IllegalArgumentException(
                        "new terminal-no-commit convergence must preserve revisions and advance one cursor");
            }
        }
    }

    record ResolveTargetEvidenceTerminalNoCommit(
            String schemaVersion,
            CaseCommandRef command,
            long roomFencingToken,
            long expectedRoomRevision,
            long expectedLastCaseEventSequence,
            String roomWorkflowId,
            String roomWorkflowRunId,
            String roomWorkflowBuildId,
            String commandHash,
            String commandEnvelopeHash,
            ExecuteAgentRunRequest rootRequest,
            String caseWorkflowId,
            String caseWorkflowRunId,
            String caseWorkflowBuildId) {

        public ResolveTargetEvidenceTerminalNoCommit {
            if (!"resolve-target-evidence-terminal-no-commit.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be resolve-target-evidence-terminal-no-commit.v1");
            }
            if (command == null
                    || rootRequest == null
                    || command.roomType() != RoomType.EVIDENCE
                    || (command.commandType() != CommandType.EVIDENCE_OPENING
                            && command.commandType() != CommandType.EVIDENCE_SUBMIT)
                    || command.caseCommandSequence() < 1
                    || command.roomEpoch() < 0
                    || command.expectedProcessRevision() < 0
                    || roomFencingToken < 1
                    || expectedRoomRevision < 0
                    || expectedLastCaseEventSequence < 0
                    || rootRequest.attemptNo() != 1
                    || !command.commandId().equals(rootRequest.command().commandId())
                    || !command.tenantSurrogate().equals(rootRequest.command().tenantSurrogate())
                    || !command.caseId().equals(rootRequest.command().caseId())
                    || rootRequest.command().roomType() != RoomType.EVIDENCE
                    || command.roomEpoch() != rootRequest.command().roomEpoch()
                    || command.expectedProcessRevision()
                            != rootRequest.command().processRevision()) {
                throw new IllegalArgumentException(
                        "target Evidence terminal-no-commit source is invalid");
            }
            requireText(roomWorkflowId, "roomWorkflowId");
            requireText(roomWorkflowRunId, "roomWorkflowRunId");
            requireText(roomWorkflowBuildId, "roomWorkflowBuildId");
            requireText(caseWorkflowId, "caseWorkflowId");
            requireText(caseWorkflowRunId, "caseWorkflowRunId");
            requireText(caseWorkflowBuildId, "caseWorkflowBuildId");
            if (commandHash == null
                    || !commandHash.matches("[0-9a-f]{64}")
                    || commandEnvelopeHash == null
                    || !commandEnvelopeHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "target Evidence terminal-no-commit source hashes are invalid");
            }
        }
    }

    record ResolveTargetEvidenceTerminalNoCommitResult(
            String schemaVersion,
            TargetRoomAgentRunTerminalNoCommit authority,
            String receiptUri,
            String receiptSha256) {

        public ResolveTargetEvidenceTerminalNoCommitResult {
            if (!"resolve-target-evidence-terminal-no-commit-result.v1".equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "schemaVersion must be resolve-target-evidence-terminal-no-commit-result.v1");
            }
            if (authority == null
                    || !authority.receiptUri().equals(receiptUri)
                    || !authority.receiptSha256().equals(receiptSha256)) {
                throw new IllegalArgumentException(
                        "resolved target Evidence terminal authority is incomplete");
            }
        }
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
            String schemaVersion,
            TargetIntakeCommandTerminalNoCommit authority,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    List<TargetIntakeSourceEventRef> observedCaseEvents) {

        public static final String SCHEMA_VERSION =
                "resolve-target-intake-terminal-no-commit.v1";
        public static final String V2_SCHEMA_VERSION =
                "resolve-target-intake-terminal-no-commit.v2";

        public ResolveTargetIntakeTerminalNoCommit(
                String schemaVersion, TargetIntakeCommandTerminalNoCommit authority) {
            this(schemaVersion, authority, null);
        }

        public ResolveTargetIntakeTerminalNoCommit {
            boolean v2 = V2_SCHEMA_VERSION.equals(schemaVersion);
            if (!SCHEMA_VERSION.equals(schemaVersion) && !v2) {
                throw new IllegalArgumentException(
                        "schemaVersion must be resolve-target-intake-terminal-no-commit.v1 or .v2");
            }
            if (authority == null) {
                throw new IllegalArgumentException("authority must not be null");
            }
            if (!v2) {
                if (observedCaseEvents != null) {
                    throw new IllegalArgumentException("v1 resolve request must omit observed events");
                }
            } else {
                if (!TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION.equals(
                                authority.schemaVersion())
                        || observedCaseEvents == null) {
                    throw new IllegalArgumentException(
                            "v2 resolve request requires strict v2 observed authority");
                }
                observedCaseEvents = List.copyOf(observedCaseEvents);
                long previousSequence = -1;
                for (TargetIntakeSourceEventRef event : observedCaseEvents) {
                    if (event == null
                            || !authority.tenantSurrogate().equals(event.tenantSurrogate())
                            || !authority.caseId().equals(event.caseId())
                            || event.roomType() != authority.roomType()
                            || event.roomEpoch() != authority.roomEpoch()
                            || event.fencingToken() != authority.fencingToken()
                            || event.eventSequence() <= previousSequence
                            || event.eventSequence() > authority.lastCaseEventSequence()
                            || !TargetIntakeSourceEventRef.isCursorOnlyEventType(
                                    event.eventType())) {
                        throw new IllegalArgumentException(
                                "v2 resolve request observed event lineage is invalid");
                    }
                    previousSequence = event.eventSequence();
                }
            }
        }
    }

    record ResolveTargetIntakeTerminalNoCommitResult(
            String schemaVersion,
            TargetIntakeCommandTerminalNoCommit authority,
            String receiptUri,
            String receiptSha256,
            @JsonInclude(JsonInclude.Include.NON_NULL) String caseWorkflowId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String caseWorkflowRunId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String caseWorkflowBuildId) {

        public static final String SCHEMA_VERSION =
                "resolve-target-intake-terminal-no-commit-result.v1";
        public static final String V2_SCHEMA_VERSION =
                "resolve-target-intake-terminal-no-commit-result.v2";

        public ResolveTargetIntakeTerminalNoCommitResult(
                String schemaVersion,
                TargetIntakeCommandTerminalNoCommit authority,
                String receiptUri,
                String receiptSha256) {
            this(schemaVersion, authority, receiptUri, receiptSha256, null, null, null);
        }

        public ResolveTargetIntakeTerminalNoCommitResult {
            boolean v2 = V2_SCHEMA_VERSION.equals(schemaVersion);
            if (!SCHEMA_VERSION.equals(schemaVersion) && !v2) {
                throw new IllegalArgumentException(
                        "schemaVersion must be resolve-target-intake-terminal-no-commit-result.v1 or .v2");
            }
            if (authority == null) {
                throw new IllegalArgumentException("authority must not be null");
            }
            requireText(receiptUri, "receiptUri");
            if (receiptSha256 == null || !receiptSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("receiptSha256 must be a lowercase SHA-256");
            }
            if (!v2) {
                if (caseWorkflowId != null
                        || caseWorkflowRunId != null
                        || caseWorkflowBuildId != null) {
                    throw new IllegalArgumentException("v1 resolve result must omit parent binding");
                }
            } else {
                if (!TargetIntakeCommandTerminalNoCommit.V3_SCHEMA_VERSION.equals(
                        authority.schemaVersion())) {
                    throw new IllegalArgumentException("v2 resolve result requires strict v3 authority");
                }
                requireText(caseWorkflowId, "caseWorkflowId");
                requireText(caseWorkflowRunId, "caseWorkflowRunId");
                requireText(caseWorkflowBuildId, "caseWorkflowBuildId");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
