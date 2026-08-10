package com.example.dispute.workflow.activity.domain;

import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationScope.COMMAND;
import static com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ReconciliationSeverity.ERROR;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.ALREADY_APPLIED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.ALREADY_EXPIRED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.ALREADY_FAILED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.ALREADY_REJECTED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.ALREADY_SHADOW_COMPLETED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.EXPIRED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.ORCHESTRATION_ACCEPTED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome.SHADOW_COMPLETED;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.TerminalNoCommitOutcome.IDEMPOTENT_REPLAY;
import static com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.TerminalNoCommitOutcome.TERMINALIZED;

import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.workflow.application.command.CaseCommandReferenceMapper;
import com.example.dispute.workflow.application.epoch.RoomEpochReadiness;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.CommandStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.ProcessReconciliationIssueEntity;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.ProcessReconciliationIssueRepository;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommand;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommandResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRoutedResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetIntakeTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerEntry;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.example.dispute.workflow.temporal.caseprocess.TargetIntakeCommandTerminalNoCommit;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmissionSnapshot;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore.CommandLookup;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore.MaterialSnapshot;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.temporal.failure.ApplicationFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CaseProcessLedgerActivitiesImpl
        implements CaseProcessLedgerActivities, CaseCommandLifecycleActivities {

    private final CaseCommandRepository commandRepository;
    private final CaseTimelineEventRepository eventRepository;
    private final CaseRoomRepository roomRepository;
    private final CaseRoomEpochRepository roomEpochRepository;
    private final CaseProcessProjectionRepository projectionRepository;
    private final ProcessReconciliationIssueRepository issueRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentRunAttemptRepository agentRunAttemptRepository;
    private final TargetIntakeCommandMaterialStore targetIntakeCommandMaterialStore;
    private final TargetE2EActivationLedger targetE2EActivationLedger;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CaseProcessLedgerActivitiesImpl(
            CaseCommandRepository commandRepository,
            CaseTimelineEventRepository eventRepository,
            CaseRoomRepository roomRepository,
            CaseRoomEpochRepository roomEpochRepository,
            CaseProcessProjectionRepository projectionRepository,
            ProcessReconciliationIssueRepository issueRepository,
            AgentRunRepository agentRunRepository,
            AgentRunAttemptRepository agentRunAttemptRepository,
            @Nullable TargetIntakeCommandMaterialStore targetIntakeCommandMaterialStore,
            @Nullable TargetE2EActivationLedger targetE2EActivationLedger,
            ObjectMapper objectMapper,
            Clock clock) {
        this.commandRepository = commandRepository;
        this.eventRepository = eventRepository;
        this.roomRepository = roomRepository;
        this.roomEpochRepository = roomEpochRepository;
        this.projectionRepository = projectionRepository;
        this.issueRepository = issueRepository;
        this.agentRunRepository = agentRunRepository;
        this.agentRunAttemptRepository = agentRunAttemptRepository;
        this.targetIntakeCommandMaterialStore = targetIntakeCommandMaterialStore;
        this.targetE2EActivationLedger = targetE2EActivationLedger;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseCommandRef> loadCaseCommands(LoadSequenceRange request) {
        requireProjectionScope(request.tenantSurrogate(), request.caseId());
        return commandRepository
                .findByTenantSurrogateAndCaseIdAndCaseCommandSequenceBetweenOrderByCaseCommandSequenceAsc(
                        request.tenantSurrogate(),
                        request.caseId(),
                        request.fromSequenceInclusive(),
                        request.toSequenceInclusive())
                .stream()
                .limit(request.limit())
                .map(command -> CaseCommandReferenceMapper.fromEntity(command, objectMapper))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseCommandLedgerEntry> loadCaseCommandLedgerEntries(
            LoadSequenceRange request) {
        requireProjectionScope(request.tenantSurrogate(), request.caseId());
        return commandRepository
                .findByTenantSurrogateAndCaseIdAndCaseCommandSequenceBetweenOrderByCaseCommandSequenceAsc(
                        request.tenantSurrogate(),
                        request.caseId(),
                        request.fromSequenceInclusive(),
                        request.toSequenceInclusive())
                .stream()
                .limit(request.limit())
                .map(
                        command ->
                                new CaseCommandLedgerEntry(
                                        "case-command-ledger-entry.v1",
                                        CaseCommandReferenceMapper.fromEntity(
                                                command, objectMapper),
                                        CaseCommandLedgerState.valueOf(
                                                command.getCommandStatus().name())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseDomainEventRef> loadDomainEvents(LoadSequenceRange request) {
        requireProjectionScope(request.tenantSurrogate(), request.caseId());
        return eventRepository
                .findByCaseIdAndSequenceNoBetweenOrderBySequenceNoAsc(
                        request.caseId(),
                        request.fromSequenceInclusive(),
                        request.toSequenceInclusive())
                .stream()
                .limit(request.limit())
                .map(event -> eventRef(request, event))
                .toList();
    }

    @Override
    @Transactional
    public void reportSequenceGap(SequenceGapReport report) {
        CaseProcessProjectionEntity projection =
                requireProjectionScope(report.tenantSurrogate(), report.caseId());
        String canonical =
                String.join(
                        "|",
                        "case-process-sequence-gap.v1",
                        report.tenantSurrogate(),
                        report.caseId(),
                        report.workflowId(),
                        report.stream().name(),
                        Long.toString(report.expectedSequence()),
                        Long.toString(report.highestObservedSequence()),
                        report.reasonCode());
        String digest = sha256(canonical);
        String issueKey = "sequence-gap:" + digest;
        issueRepository.lockTenantIssueKey(report.tenantSurrogate(), issueKey);
        ProcessReconciliationIssueEntity issue =
                issueRepository
                        .findByTenantSurrogateAndIssueKey(
                                report.tenantSurrogate(), issueKey)
                        .orElseGet(
                                () ->
                                        ProcessReconciliationIssueEntity.detected(
                                                "PRI_" + digest.substring(0, 60),
                                                issueKey,
                                                report.tenantSurrogate(),
                                                report.caseId(),
                                                report.stream().name() + "_SEQUENCE_GAP",
                                                COMMAND,
                                                ERROR,
                                                roomType(projection.getCurrentRoom()),
                                                projection.getRoomEpoch(),
                                                projection.getProcessRevision(),
                                                projection.getFencingToken(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                gapDetails(report),
                                                now()));
        issue.reopenIfResolved(now());
        issueRepository.saveAndFlush(issue);
    }

    @Override
    @Transactional
    public ExpireCaseCommandResult expireCaseCommand(ExpireCaseCommand request) {
        CaseCommandEntity command = lockedCommand(request.tenantSurrogate(), request.commandId());
        if (!command.getCaseId().equals(request.caseId())
                || command.getCaseCommandSequence() != request.caseCommandSequence()
                || !command.getRequestHash().equals(request.requestHash())
                || !command.getDeadlineAt().toInstant().equals(request.deadlineAt())) {
            throw permanentFailure(
                    "CASE_COMMAND_EXPIRATION_SCOPE_MISMATCH",
                    "case command expiration scope mismatch");
        }
        CommandStatus status = command.getCommandStatus();
        if (status == CommandStatus.APPLIED) {
            return expirationResult(ALREADY_APPLIED);
        }
        if (status == CommandStatus.SHADOW_COMPLETED) {
            return expirationResult(ALREADY_SHADOW_COMPLETED);
        }
        if (status == CommandStatus.REJECTED) {
            return expirationResult(ALREADY_REJECTED);
        }
        if (status == CommandStatus.FAILED) {
            return expirationResult(ALREADY_FAILED);
        }
        if (status == CommandStatus.EXPIRED) {
            return expirationResult(ALREADY_EXPIRED);
        }
        command.markExpired(
                "COMMAND_DEADLINE_EXPIRED",
                OffsetDateTime.ofInstant(request.expiredAt(), ZoneOffset.UTC));
        return expirationResult(EXPIRED);
    }

    @Override
    @Transactional
    public RecordCaseCommandRoutedResult recordCaseCommandRouted(
            RecordCaseCommandRouted request) {
        CaseCommandEntity command = lockedRoutingCommand(request);
        CommandStatus status = command.getCommandStatus();
        RecordCaseCommandRoutedResult tombstone = routingTombstone(status);
        if (tombstone != null) {
            return tombstone;
        }
        if (status == CommandStatus.ORCHESTRATION_ACCEPTED) {
            return routingResult(ORCHESTRATION_ACCEPTED);
        }

        CaseRoomEpochEntity epoch = activeRoutingEpoch(request);
        OffsetDateTime routedAt = routingTime(request);
        if (!command.getDeadlineAt().isAfter(routedAt)) {
            command.markExpired("COMMAND_DEADLINE_EXPIRED", routedAt);
            return routingResult(EXPIRED);
        }
        if (epoch.getWriterMode() == WriterMode.SHADOW
                || epoch.getWriterMode() == WriterMode.TEMPORAL) {
            command.markOrchestrationAccepted(routedAt);
            return routingResult(ORCHESTRATION_ACCEPTED);
        }
        throw permanentFailure(
                "CASE_COMMAND_ROUTING_WRITER_REJECTED",
                "LEGACY epochs cannot accept Temporal commands");
    }

    @Override
    @Transactional
    public RecordCaseCommandRoutedResult completeCaseCommandRouting(
            RecordCaseCommandRouted request) {
        CaseCommandEntity command = lockedRoutingCommand(request);
        CommandStatus status = command.getCommandStatus();
        RecordCaseCommandRoutedResult tombstone = routingTombstone(status);
        if (tombstone != null) {
            return tombstone;
        }

        CaseRoomEpochEntity epoch = routingEpoch(request);
        if (status == CommandStatus.ORCHESTRATION_ACCEPTED
                && epoch.getWriterMode() == WriterMode.TEMPORAL) {
            return routingResult(ORCHESTRATION_ACCEPTED);
        }
        requireActiveEpoch(epoch);
        OffsetDateTime routedAt = routingTime(request);
        if (epoch.getWriterMode() == WriterMode.SHADOW) {
            command.markShadowCompleted(routedAt);
            return routingResult(SHADOW_COMPLETED);
        }
        if (epoch.getWriterMode() == WriterMode.TEMPORAL) {
            command.markOrchestrationAccepted(routedAt);
            return routingResult(ORCHESTRATION_ACCEPTED);
        }
        throw permanentFailure(
                "CASE_COMMAND_ROUTING_WRITER_REJECTED",
                "LEGACY epochs cannot accept Temporal commands");
    }

    @Override
    @Transactional
    public ResolveTargetIntakeTerminalNoCommitResult resolveTargetIntakeTerminalNoCommit(
            ResolveTargetIntakeTerminalNoCommit request) {
        TargetIntakeCommandTerminalNoCommit authority = request.authority();
        requireTerminalNoCommitEvidence(authority);
        CaseCommandEntity command = lockedCommand(authority.tenantSurrogate(), authority.commandId());
        requireTerminalNoCommitCommand(command, authority);
        ReceiptIdentity receipt = terminalNoCommitReceipt(authority);
        CaseRoomEpochEntity epoch = terminalNoCommitEpoch(authority);
        CaseProcessProjectionEntity projection = terminalNoCommitProjection(authority);
        if (command.getCommandStatus() == CommandStatus.FAILED) {
            requireExactTerminalFailure(command, authority, receipt);
            requireConvergedTerminalCoordinates(epoch, projection, authority);
        } else if (command.getCommandStatus() != CommandStatus.ORCHESTRATION_ACCEPTED) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_COMMAND_CONFLICT",
                    "case command is not an accepted terminal-no-commit candidate");
        } else {
            requireSourceTerminalCoordinates(epoch, projection, authority);
        }
        return new ResolveTargetIntakeTerminalNoCommitResult(
                "resolve-target-intake-terminal-no-commit-result.v1",
                authority,
                receipt.uri(),
                receipt.sha256());
    }

    @Override
    @Transactional
    public ConvergeTargetIntakeTerminalNoCommitResult convergeTargetIntakeTerminalNoCommit(
            ConvergeTargetIntakeTerminalNoCommit request) {
        TargetIntakeCommandTerminalNoCommit authority = request.authority();
        String expectedCaseWorkflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId(
                        authority.tenantSurrogate(), authority.caseId());
        if (!expectedCaseWorkflowId.equals(request.caseWorkflowId())
                || !authority.caseBuildId().equals(request.caseWorkflowBuildId())) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_PARENT_MISMATCH",
                    "CaseProcess workflow authority does not match the terminal receipt");
        }

        requireTerminalNoCommitEvidence(authority);
        CaseCommandEntity command = lockedCommand(authority.tenantSurrogate(), authority.commandId());
        requireTerminalNoCommitCommand(command, authority);
        ReceiptIdentity receipt = terminalNoCommitReceipt(authority);
        CaseRoomEpochEntity epoch = terminalNoCommitEpoch(authority);
        CaseProcessProjectionEntity projection = terminalNoCommitProjection(authority);

        if (command.getCommandStatus() == CommandStatus.FAILED) {
            requireExactTerminalFailure(command, authority, receipt);
            requireParentBinding(epoch, projection, request);
            requireConvergedTerminalCoordinates(epoch, projection, authority);
            return terminalNoCommitResult(
                    IDEMPOTENT_REPLAY, authority, receipt, epoch, projection);
        }
        if (command.getCommandStatus() != CommandStatus.ORCHESTRATION_ACCEPTED) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_COMMAND_CONFLICT",
                    "case command reached another terminal state before no-commit convergence");
        }

        requireParentBinding(epoch, projection, request);
        requireSourceTerminalCoordinates(epoch, projection, authority);
        OffsetDateTime terminalAt =
                OffsetDateTime.ofInstant(authority.terminalAt(), ZoneOffset.UTC);
        int epochUpdated =
                roomEpochRepository.advanceFencedEpoch(
                        authority.tenantSurrogate(),
                        authority.caseId(),
                        RoomType.INTAKE.name(),
                        authority.roomEpoch(),
                        authority.fencingToken(),
                        authority.expectedProcessRevision(),
                        authority.newProcessRevision(),
                        authority.expectedRoomRevision(),
                        authority.newRoomRevision(),
                        request.caseWorkflowId(),
                        request.caseWorkflowRunId(),
                        request.caseWorkflowBuildId(),
                        terminalAt);
        if (epochUpdated != 1) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_EPOCH_CAS_REJECTED",
                    "room epoch changed before terminal-no-commit convergence");
        }
        int projectionUpdated =
                projectionRepository.advanceFencedProjection(
                        authority.tenantSurrogate(),
                        authority.caseId(),
                        authority.roomEpoch(),
                        authority.fencingToken(),
                        authority.expectedProcessRevision(),
                        authority.newProcessRevision(),
                        projection.getMacroPhase(),
                        projection.getCurrentRoom(),
                        projection.getRoomPhase(),
                        authority.caseCommandSequence(),
                        authority.lastCaseEventSequence(),
                        projection.getProjectedDeadlineAt(),
                        request.caseWorkflowId(),
                        request.caseWorkflowRunId(),
                        request.caseWorkflowBuildId(),
                        projection.getProjectionRef(),
                        projection.getProjectionSha256(),
                        terminalAt);
        if (projectionUpdated != 1) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_PROJECTION_CAS_REJECTED",
                    "process projection changed before terminal-no-commit convergence");
        }
        command.markAcceptedOrchestrationTerminalNoCommit(
                authority.errorCode(), receipt.uri(), receipt.sha256(), terminalAt);
        return new ConvergeTargetIntakeTerminalNoCommitResult(
                "converge-target-intake-terminal-no-commit-result.v1",
                TERMINALIZED,
                authority,
                receipt.uri(),
                receipt.sha256(),
                authority.newProcessRevision(),
                authority.newRoomRevision(),
                authority.caseCommandSequence(),
                authority.lastCaseEventSequence());
    }

    private void requireTerminalNoCommitEvidence(
            TargetIntakeCommandTerminalNoCommit authority) {
        if (targetIntakeCommandMaterialStore == null) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_MATERIAL_STORE_UNAVAILABLE",
                    "target Intake command material store is unavailable");
        }
        if (targetE2EActivationLedger == null) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_ACTIVATION_LEDGER_UNAVAILABLE",
                    "target E2E activation ledger is unavailable");
        }
        String expectedExecutionRequestHash =
                TargetIntakeCommandTerminalNoCommit.LEGACY_SCHEMA_VERSION.equals(
                                authority.schemaVersion())
                        ? authority.commandEnvelopeHash()
                        : authority.agentRunExecutionRequestHash();
        MaterialSnapshot material =
                targetIntakeCommandMaterialStore
                        .readByRoute(
                                new CommandLookup(
                                        authority.tenantSurrogate(),
                                        authority.caseId(),
                                        authority.commandId(),
                                        authority.roomEpoch(),
                                        authority.fencingToken()))
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "TARGET_INTAKE_TERMINAL_NO_COMMIT_MATERIAL_MISSING",
                                                "target Intake command material is unavailable"));
        var admission = material.admission();
        IntakeCommandExecutionContext context = material.context();
        IntakeTargetAgentRunContext target =
                context == null ? null : context.targetAgentRun();
        var targetRequest = target == null ? null : target.request();
        RoomGraphCommand materialCommand = targetRequest == null ? null : targetRequest.command();
        if (!admission.activationId().equals(authority.activationId())
                || !admission.manifestHash().equals(authority.activationManifestHash())
                || !admission.tenantSurrogate().equals(authority.tenantSurrogate())
                || !admission.caseId().equals(authority.caseId())
                || !admission.commandId().equals(authority.commandId())
                || !admission.commandHash().equals(authority.commandHash())
                || !admission.commandEnvelopeHash().equals(authority.commandEnvelopeHash())
                || admission.roomEpoch() != authority.roomEpoch()
                || admission.roomFencingToken() != authority.fencingToken()
                || target == null
                || !target.activationId().equals(authority.activationId())
                || !target.activationManifestHash().equals(authority.activationManifestHash())
                || !target.caseBuildId().equals(authority.caseBuildId())
                || !target.controlBuildId().equals(authority.controlBuildId())
                || !target.agentBuildId().equals(authority.agentBuildId())
                || !target.graphBindingHash().equals(authority.graphBindingHash())
                || !target.graphCodeBuildId().equals(authority.graphCodeBuildId())
                || !target.commandHash().equals(authority.commandHash())
                || !target.commandEnvelopeHash().equals(authority.commandEnvelopeHash())
                || targetRequest == null
                || materialCommand == null
                || !targetRequest.logicalInputHash().equals(authority.logicalInputHash())
                || !materialCommand.requestHash().equals(expectedExecutionRequestHash)
                || !targetRequest.logicalRunId().equals(authority.logicalRunId())
                || !targetRequest.attemptId().equals(authority.rootAttemptId())
                || target.expectedProcessRevision() != authority.expectedProcessRevision()
                || target.expectedRoomRevision() != authority.expectedRoomRevision()) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_MATERIAL_MISMATCH",
                    "target Intake command material conflicts with terminal authority");
        }
        CommandAdmissionSnapshot admissionSnapshot =
                targetE2EActivationLedger
                        .queryCommandAdmission(authority.activationId(), authority.commandId())
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "TARGET_INTAKE_TERMINAL_NO_COMMIT_ADMISSION_MISSING",
                                                "target Intake command admission is unavailable"));
        if (!material.admissionId().equals(admissionSnapshot.admissionId())
                || !authority.activationManifestHash()
                        .equals(admissionSnapshot.activationManifestHash())
                || !authority.commandHash().equals(admissionSnapshot.commandHash())
                || !authority.commandEnvelopeHash()
                        .equals(admissionSnapshot.commandEnvelopeHash())
                || admissionSnapshot.completed()) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_ADMISSION_CONFLICT",
                    "target Intake command admission has conflicting completion authority");
        }

        AgentRunEntity run =
                agentRunRepository
                        .findByIdForUpdate(authority.logicalRunId())
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "TARGET_INTAKE_TERMINAL_NO_COMMIT_RUN_MISSING",
                                                "terminal AgentRun is unavailable"));
        List<AgentRunAttemptEntity> attempts =
                agentRunAttemptRepository.findAllByAgentRunIdOrderByAttemptNoAsc(
                        authority.logicalRunId());
        if (attempts.size() != authority.terminalAttemptNo()) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_LINEAGE_INVALID",
                    "terminal AgentRun attempt lineage is not exact");
        }
        for (int index = 0; index < attempts.size(); index++) {
            AgentRunAttemptEntity attempt = attempts.get(index);
            String expectedPrevious = index == 0 ? null : attempts.get(index - 1).getId();
            if (attempt.getAttemptNo() != index + 1L
                    || !authority.logicalRunId().equals(attempt.getAgentRunId())
                    || !Objects.equals(expectedPrevious, attempt.getPreviousAttemptId())) {
                throw permanentFailure(
                        "TARGET_INTAKE_TERMINAL_NO_COMMIT_LINEAGE_INVALID",
                        "terminal AgentRun attempt lineage is not contiguous");
            }
        }
        AgentRunAttemptEntity root = attempts.getFirst();
        AgentRunAttemptEntity terminal = attempts.getLast();
        RoomGraphCommand rootCommand = decodeGraphCommand(root);
        RoomGraphCommand terminalCommand = decodeGraphCommand(terminal);
        ExecuteAgentRunResult expectedResult = terminalResult(authority);
        ExecuteAgentRunResult storedResult = decodeTerminalResult(terminal);
        boolean finalizationRejected =
                "FINALIZATION_REJECTED".equals(run.getStopReason())
                        && storedResult.outcome() == ExecuteAgentRunResult.Outcome.COMPLETED
                        && authority.terminalAttemptId().equals(run.getResultReadyAttemptId())
                        && Objects.equals(storedResult.resultHash(), run.getFinalResultHash());
        if (finalizationRejected) {
            requireFinalizationRejectedCompletedAudit(
                    authority, run, terminal, terminalCommand, storedResult);
        } else {
            try {
                terminal.requireDurableFailureResult(expectedResult);
            } catch (IllegalArgumentException | IllegalStateException mismatch) {
                throw permanentFailure(
                        "TARGET_INTAKE_TERMINAL_NO_COMMIT_RESULT_INVALID",
                        "terminal AgentRun result conflicts with its durable attempt");
            }
            if (!expectedResult.equals(storedResult)) {
                throw permanentFailure(
                        "TARGET_INTAKE_TERMINAL_NO_COMMIT_RESULT_INVALID",
                        "terminal AgentRun result conflicts with its durable attempt");
            }
        }
        if (!authority.rootAttemptId().equals(root.getId())
                || !authority.terminalAttemptId().equals(terminal.getId())
                || terminal.getAttemptNo() != authority.terminalAttemptNo()
                || terminal.getAttemptStatus() != authority.terminalAttemptStatus()
                || !authority.commandId().equals(root.getCommandId())
                || !expectedExecutionRequestHash.equals(root.getRequestHash())
                || !expectedExecutionRequestHash.equals(rootCommand.requestHash())
                || !root.getCommandRequestHash().equals(rootCommand.requestHash())
                || !terminal.getCommandRequestHash().equals(terminalCommand.requestHash())
                || !authority.logicalRunId().equals(rootCommand.logicalRunId())
                || !authority.logicalRunId().equals(terminalCommand.logicalRunId())
                || !authority.rootAttemptId().equals(rootCommand.attemptId())
                || !authority.terminalAttemptId().equals(terminalCommand.attemptId())
                || !authority.tenantSurrogate().equals(rootCommand.tenantSurrogate())
                || !authority.tenantSurrogate().equals(terminalCommand.tenantSurrogate())
                || !authority.caseId().equals(rootCommand.caseId())
                || !authority.caseId().equals(terminalCommand.caseId())
                || rootCommand.roomType() != RoomType.INTAKE
                || terminalCommand.roomType() != RoomType.INTAKE
                || rootCommand.roomEpoch() != authority.roomEpoch()
                || terminalCommand.roomEpoch() != authority.roomEpoch()
                || rootCommand.processRevision() != authority.expectedProcessRevision()
                || terminalCommand.processRevision() != authority.expectedProcessRevision()
                || rootCommand.eventRef() == null
                || !authority.messageId().equals(rootCommand.eventRef().artifactId())
                || !authority.messageRef().equals(rootCommand.eventRef().uri())
                || !authority.messageHash().equals(rootCommand.eventRef().sha256())) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_LINEAGE_INVALID",
                    "terminal AgentRun identity does not bind the original command");
        }
        if (!authority.tenantSurrogate().equals(run.getTenantSurrogate())
                || !authority.caseId().equals(run.getCaseId())
                || !AgentRunProtocol.V2.wireValue().equals(run.getProtocol())
                || run.getExecutorKind() != AgentRunExecutorKind.TEMPORAL_ACTIVITY
                || run.getRoomType() != RoomType.INTAKE
                || run.getRoomEpoch() != authority.roomEpoch()
                || run.getFencingToken() != authority.fencingToken()
                || run.getProcessRevision() != authority.expectedProcessRevision()
                || !expectedExecutionRequestHash.equals(run.getRequestHash())
                || !authority.logicalInputHash().equals(run.getLogicalInputHash())
                || !authority.terminalAttemptStatus().name().equals(run.getRunStatus())
                || !"UNCOMMITTED".equals(run.getFinalizationStatus())
                || run.getCommittedAttemptId() != null
                || run.getCommittedManifestId() != null
                || run.getCommittedManifestHash() != null
                || run.getFinalStreamSequenceNo() != null
                || run.getFinalizedAt() != null
                || (finalizationRejected
                    ? (!authority.terminalAttemptId().equals(run.getResultReadyAttemptId())
                        || !storedResult.resultHash().equals(run.getFinalResultHash()))
                    : (run.getResultReadyAttemptId() != null
                        || run.getFinalResultHash() != null))) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_RUN_INVALID",
                    "logical AgentRun is not terminal without a finalization");
        }
    }

    private static void requireFinalizationRejectedCompletedAudit(
            TargetIntakeCommandTerminalNoCommit authority,
            AgentRunEntity run,
            AgentRunAttemptEntity terminal,
            RoomGraphCommand terminalCommand,
            ExecuteAgentRunResult storedResult) {
        Instant completedAt = terminal.getCompletedAt() == null
                ? null
                : terminal.getCompletedAt().toInstant();
        Instant runCompletedAt = run.getCompletedAt() == null
                ? null
                : run.getCompletedAt().toInstant();
        boolean valid = TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION.equals(
                        authority.schemaVersion())
                && storedResult.outcome() == ExecuteAgentRunResult.Outcome.COMPLETED
                && storedResult.graphResult() != null
                && authority.logicalRunId().equals(storedResult.agentRunId())
                && authority.logicalRunId().equals(storedResult.logicalRunId())
                && authority.terminalAttemptId().equals(storedResult.attemptId())
                && authority.terminalAttemptNo() == storedResult.attemptNo()
                && terminalCommand.commandId().equals(storedResult.graphResult().commandId())
                && Objects.equals(terminal.getResultHash(), storedResult.resultHash())
                && storedResult.resultHash().equals(storedResult.graphResult().outputHash())
                && terminal.getLastSequenceNo()
                        == Math.incrementExact(storedResult.lastSequenceNo())
                && authority.lastSequenceNo() == terminal.getLastSequenceNo()
                && terminal.isPublicOutputEmitted() == storedResult.publicOutputEmitted()
                && authority.publicOutputEmitted() == storedResult.publicOutputEmitted()
                && terminal.isFinalFrameObserved()
                && Objects.equals(terminal.getErrorCode(), authority.errorCode())
                && Boolean.FALSE.equals(terminal.getErrorRetryable())
                && com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction
                        .FAIL_LOGICAL_RUN
                        .name()
                        .equals(terminal.getTerminationCode())
                && Objects.equals(run.getErrorCode(), authority.errorCode())
                && Boolean.FALSE.equals(run.getErrorRetryable())
                && completedAt != null
                && completedAt.equals(storedResult.completedAt())
                && completedAt.equals(authority.terminalAt())
                && completedAt.equals(runCompletedAt);
        if (!valid) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_RESULT_INVALID",
                    "finalization-rejected completed audit conflicts with terminal authority");
        }
    }

    private RoomGraphCommand decodeGraphCommand(AgentRunAttemptEntity attempt) {
        try {
            return objectMapper.readValue(attempt.getCommandJson(), RoomGraphCommand.class);
        } catch (RuntimeException | java.io.IOException invalid) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_LINEAGE_INVALID",
                    "AgentRun command lineage cannot be decoded");
        }
    }

    private ExecuteAgentRunResult decodeTerminalResult(AgentRunAttemptEntity attempt) {
        try {
            return objectMapper.readValue(attempt.getResultJson(), ExecuteAgentRunResult.class);
        } catch (RuntimeException | java.io.IOException invalid) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_RESULT_INVALID",
                    "terminal AgentRun result cannot be decoded");
        }
    }

    private static ExecuteAgentRunResult terminalResult(
            TargetIntakeCommandTerminalNoCommit authority) {
        return new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                authority.logicalRunId(),
                authority.logicalRunId(),
                authority.terminalAttemptId(),
                authority.terminalAttemptNo(),
                authority.agentRunOutcome(),
                null,
                null,
                authority.lastSequenceNo(),
                authority.publicOutputEmitted(),
                authority.errorCode(),
                authority.retryable(),
                authority.recoveryAction(),
                authority.terminalAt());
    }

    private static void requireTerminalNoCommitCommand(
            CaseCommandEntity command, TargetIntakeCommandTerminalNoCommit authority) {
        if (!authority.caseId().equals(command.getCaseId())
                || command.getCaseCommandSequence() != authority.caseCommandSequence()
                || !authority.commandRequestHash().equals(command.getRequestHash())
                || command.getRoomType() != RoomType.INTAKE
                || command.getRoomEpoch() != authority.roomEpoch()
                || command.getExpectedProcessRevision() != authority.expectedProcessRevision()
                || !authority.messageRef().equals(command.getPayloadUri())
                || !authority.messageHash().equals(command.getPayloadSha256())) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_COMMAND_MISMATCH",
                    "case command conflicts with terminal-no-commit authority");
        }
    }

    private CaseRoomEpochEntity terminalNoCommitEpoch(
            TargetIntakeCommandTerminalNoCommit authority) {
        CaseRoomEpochEntity epoch =
                roomEpochRepository
                        .findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                                authority.caseId(), RoomType.INTAKE, authority.roomEpoch())
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "TARGET_INTAKE_TERMINAL_NO_COMMIT_EPOCH_MISSING",
                                                "target Intake epoch is unavailable"));
        if (!authority.tenantSurrogate().equals(epoch.getTenantSurrogate())
                || epoch.getWriterMode() != WriterMode.TEMPORAL
                || epoch.getLifecycleStatus() != EpochLifecycleStatus.ACTIVE
                || epoch.getFencingToken() != authority.fencingToken()
                || !authority.roomWorkflowId().equals(epoch.getRoomTemporalWorkflowId())
                || !authority.roomWorkflowRunId().equals(epoch.getRoomTemporalRunId())
                || !authority.roomWorkflowBuildId().equals(epoch.getRoomWorkflowBuildId())) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_EPOCH_MISMATCH",
                    "target Intake epoch binding conflicts with terminal authority");
        }
        return epoch;
    }

    private CaseProcessProjectionEntity terminalNoCommitProjection(
            TargetIntakeCommandTerminalNoCommit authority) {
        CaseProcessProjectionEntity projection =
                projectionRepository
                        .findByIdForUpdate(authority.caseId())
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "TARGET_INTAKE_TERMINAL_NO_COMMIT_PROJECTION_MISSING",
                                                "case process projection is unavailable"));
        if (!authority.tenantSurrogate().equals(projection.getTenantSurrogate())
                || projection.getWriterMode() != WriterMode.TEMPORAL
                || projection.getRoomEpoch() != authority.roomEpoch()
                || projection.getFencingToken() != authority.fencingToken()
                || !RoomType.INTAKE.name().equals(projection.getCurrentRoom())) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_PROJECTION_MISMATCH",
                    "case process projection conflicts with terminal authority");
        }
        return projection;
    }

    private static void requireSourceTerminalCoordinates(
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            TargetIntakeCommandTerminalNoCommit authority) {
        long expectedLastCaseEventSequence =
                TargetIntakeCommandTerminalNoCommit.LEGACY_SCHEMA_VERSION.equals(
                                authority.schemaVersion())
                        ? authority.lastCaseEventSequence()
                        : authority.expectedLastCaseEventSequence();
        if (!RoomEpochReadiness.isTemporalReady(epoch, projection)
                || epoch.getProcessRevision() != authority.expectedProcessRevision()
                || epoch.getRoomRevision() != authority.expectedRoomRevision()
                || projection.getProcessRevision() != authority.expectedProcessRevision()
                || projection.getLastCommandSequence() != authority.caseCommandSequence() - 1
                || projection.getLastCaseEventSequence()
                        != expectedLastCaseEventSequence) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_SOURCE_STALE",
                    "terminal-no-commit source coordinates are stale");
        }
    }

    private static void requireConvergedTerminalCoordinates(
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            TargetIntakeCommandTerminalNoCommit authority) {
        if (epoch.getProcessRevision() < authority.newProcessRevision()
                || epoch.getRoomRevision() < authority.newRoomRevision()
                || projection.getProcessRevision() < authority.newProcessRevision()
                || projection.getLastCommandSequence() < authority.caseCommandSequence()
                || projection.getLastCaseEventSequence() < authority.lastCaseEventSequence()) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_REPLAY_STALE",
                    "terminal-no-commit replay cannot prove converged coordinates");
        }
    }

    private static void requireParentBinding(
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            ConvergeTargetIntakeTerminalNoCommit request) {
        if (!request.caseWorkflowId().equals(epoch.getTemporalWorkflowId())
                || !request.caseWorkflowRunId().equals(epoch.getTemporalRunId())
                || !request.caseWorkflowBuildId().equals(epoch.getTemporalBuildId())
                || !request.caseWorkflowId().equals(projection.getTemporalWorkflowId())
                || !request.caseWorkflowRunId().equals(projection.getTemporalRunId())
                || !request.caseWorkflowBuildId().equals(projection.getTemporalBuildId())) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_PARENT_MISMATCH",
                    "CaseProcess persistence binding conflicts with terminal authority");
        }
    }

    private static void requireExactTerminalFailure(
            CaseCommandEntity command,
            TargetIntakeCommandTerminalNoCommit authority,
            ReceiptIdentity receipt) {
        if (!authority.errorCode().equals(command.getStatusReasonCode())
                || !receipt.uri().equals(command.getResultUri())
                || !receipt.sha256().equals(command.getResultSha256())
                || command.getAppliedAt() != null) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_REPLAY_CONFLICT",
                    "failed case command is bound to another terminal receipt");
        }
    }

    private ReceiptIdentity terminalNoCommitReceipt(
            TargetIntakeCommandTerminalNoCommit authority) {
        return new ReceiptIdentity(authority.receiptUri(), authority.receiptSha256());
    }

    private static ConvergeTargetIntakeTerminalNoCommitResult terminalNoCommitResult(
            CaseCommandLifecycleActivities.TerminalNoCommitOutcome outcome,
            TargetIntakeCommandTerminalNoCommit authority,
            ReceiptIdentity receipt,
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection) {
        return new ConvergeTargetIntakeTerminalNoCommitResult(
                "converge-target-intake-terminal-no-commit-result.v1",
                outcome,
                authority,
                receipt.uri(),
                receipt.sha256(),
                projection.getProcessRevision(),
                epoch.getRoomRevision(),
                projection.getLastCommandSequence(),
                projection.getLastCaseEventSequence());
    }

    private record ReceiptIdentity(String uri, String sha256) {}

    private CaseCommandEntity lockedRoutingCommand(RecordCaseCommandRouted request) {
        CaseCommandEntity command = lockedCommand(request.tenantSurrogate(), request.commandId());
        String expectedWorkflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId(
                        request.tenantSurrogate(), request.caseId());
        if (!command.getCaseId().equals(request.caseId())
                || command.getCaseCommandSequence() != request.caseCommandSequence()
                || !command.getRequestHash().equals(request.requestHash())
                || command.getRoomType() != request.roomType()
                || command.getRoomEpoch() != request.roomEpoch()
                || !request.workflowId().equals(expectedWorkflowId)) {
            throw permanentFailure(
                    "CASE_COMMAND_ROUTING_SCOPE_MISMATCH",
                    "case command routing scope mismatch");
        }
        return command;
    }

    private CaseRoomEpochEntity activeRoutingEpoch(RecordCaseCommandRouted request) {
        CaseRoomEpochEntity epoch = routingEpoch(request);
        requireActiveEpoch(epoch);
        return epoch;
    }

    private CaseRoomEpochEntity routingEpoch(RecordCaseCommandRouted request) {
        CaseRoomEpochEntity epoch =
                roomEpochRepository
                        .findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                                request.caseId(), request.roomType(), request.roomEpoch())
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "CASE_COMMAND_ROUTING_EPOCH_MISSING",
                                                "case room epoch is unavailable"));
        String expectedWorkflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId(
                        request.tenantSurrogate(), request.caseId());
        if (!request.tenantSurrogate().equals(epoch.getTenantSurrogate())
                || (epoch.getTemporalWorkflowId() != null
                        && !epoch.getTemporalWorkflowId().equals(expectedWorkflowId))) {
            throw permanentFailure(
                    "CASE_COMMAND_ROUTING_EPOCH_MISMATCH",
                    "case command routing epoch mismatch");
        }
        CaseProcessProjectionEntity projection =
                projectionRepository
                        .findByIdForUpdate(request.caseId())
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "CASE_COMMAND_ROUTING_PROJECTION_MISSING",
                                                "case process projection is unavailable"));
        if (epoch.getWriterMode() != WriterMode.LEGACY
                && epoch.getLifecycleStatus() != EpochLifecycleStatus.TERMINAL
                && !RoomEpochReadiness.isTemporalReady(epoch, projection)) {
            throw permanentFailure(
                    "CASE_COMMAND_ROUTING_EPOCH_NOT_READY",
                    "room epoch provisioning is not ready for command routing");
        }
        return epoch;
    }

    private static void requireActiveEpoch(CaseRoomEpochEntity epoch) {
        if (epoch.getLifecycleStatus() != EpochLifecycleStatus.ACTIVE) {
            throw permanentFailure(
                    "CASE_COMMAND_ROUTING_EPOCH_MISMATCH",
                    "case command routing epoch mismatch");
        }
    }

    private static RecordCaseCommandRoutedResult routingTombstone(CommandStatus status) {
        return switch (status) {
            case APPLIED -> routingResult(ALREADY_APPLIED);
            case SHADOW_COMPLETED -> routingResult(ALREADY_SHADOW_COMPLETED);
            case REJECTED -> routingResult(ALREADY_REJECTED);
            case FAILED -> routingResult(ALREADY_FAILED);
            case EXPIRED -> routingResult(ALREADY_EXPIRED);
            case PENDING_ORCHESTRATION, ORCHESTRATION_ACCEPTED -> null;
        };
    }

    private static OffsetDateTime routingTime(RecordCaseCommandRouted request) {
        return OffsetDateTime.ofInstant(request.routedAt(), ZoneOffset.UTC);
    }

    private CaseCommandEntity lockedCommand(String tenantSurrogate, String commandId) {
        return commandRepository
                .findByTenantSurrogateAndCommandIdForUpdate(tenantSurrogate, commandId)
                .orElseThrow(
                        () ->
                                permanentFailure(
                                        "CASE_COMMAND_LEDGER_MISSING",
                                        "case command is unavailable"));
    }

    private static ExpireCaseCommandResult expirationResult(
            CaseCommandLifecycleActivities.CommandLifecycleOutcome outcome) {
        return new ExpireCaseCommandResult("expire-case-command-result.v1", outcome);
    }

    private static RecordCaseCommandRoutedResult routingResult(
            CaseCommandLifecycleActivities.CommandLifecycleOutcome outcome) {
        return new RecordCaseCommandRoutedResult(
                "record-case-command-routed-result.v1", outcome);
    }

    private static ApplicationFailure permanentFailure(
            String type, String message) {
        return ApplicationFailure.newNonRetryableFailure(message, type);
    }

    private CaseDomainEventRef eventRef(
            LoadSequenceRange request, CaseTimelineEventEntity event) {
        EventRoomEpoch eventRoomEpoch =
                roomEpoch(request.tenantSurrogate(), request.caseId(), event);
        byte[] payload = event.getEventJson().getBytes(StandardCharsets.UTF_8);
        String payloadHash = sha256(payload);
        return new CaseDomainEventRef(
                "case-domain-event-ref.v1",
                event.getId(),
                request.tenantSurrogate(),
                request.caseId(),
                event.getSequenceNo(),
                event.getEventType(),
                eventRoomEpoch.roomType(),
                eventRoomEpoch.roomEpoch(),
                new PayloadRef(
                        "case-timeline-event.v1",
                        "urn:case-timeline-event:" + event.getId(),
                        payloadHash,
                        payload.length),
                event.getEventTime(),
                traceparent(event.getId()));
    }

    private EventRoomEpoch roomEpoch(
            String tenantSurrogate, String caseId, CaseTimelineEventEntity event) {
        if (event.getRoomId() == null) {
            return new EventRoomEpoch(null, 0);
        }
        CaseRoomEntity room =
                roomRepository
                        .findById(event.getRoomId())
                        .filter(candidate -> candidate.getCaseId().equals(caseId))
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "timeline event room binding is invalid"));
        RoomType roomType;
        try {
            roomType = RoomType.valueOf(room.getRoomType().name());
        } catch (IllegalArgumentException unsupportedRoom) {
            return new EventRoomEpoch(null, 0);
        }
        List<CaseRoomEpochEntity> epochs =
                roomEpochRepository.findEpochAt(
                        tenantSurrogate,
                        caseId,
                        roomType,
                        OffsetDateTime.ofInstant(event.getEventTime(), ZoneOffset.UTC),
                        PageRequest.of(0, 2));
        if (epochs.size() != 1) {
            throw new IllegalStateException(
                    "timeline event does not resolve to exactly one room epoch");
        }
        return new EventRoomEpoch(roomType, epochs.getFirst().getRoomEpoch());
    }

    private CaseProcessProjectionEntity requireProjectionScope(
            String tenantSurrogate, String caseId) {
        CaseProcessProjectionEntity projection =
                projectionRepository
                        .findById(caseId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "case process projection is unavailable"));
        if (!projection.getTenantSurrogate().equals(tenantSurrogate)) {
            throw new IllegalArgumentException("case process tenant scope mismatch");
        }
        return projection;
    }

    private String gapDetails(SequenceGapReport report) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("schemaVersion", "case-process-sequence-gap.v1");
        details.put("workflowId", report.workflowId());
        details.put("workflowRunId", report.workflowRunId());
        details.put("stream", report.stream().name());
        details.put("expectedSequence", report.expectedSequence());
        details.put("highestObservedSequence", report.highestObservedSequence());
        details.put("recoveryAttempts", report.recoveryAttempts());
        details.put("reasonCode", report.reasonCode());
        return details.toString();
    }

    private static RoomType roomType(String currentRoom) {
        if (currentRoom == null) {
            return null;
        }
        try {
            return RoomType.valueOf(currentRoom);
        } catch (IllegalArgumentException unsupportedRoom) {
            return null;
        }
    }

    private static String traceparent(String eventId) {
        String digest = sha256("case-timeline-event:" + eventId);
        return "00-" + digest.substring(0, 32) + "-" + digest.substring(32, 48) + "-01";
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private record EventRoomEpoch(RoomType roomType, long roomEpoch) {}
}
