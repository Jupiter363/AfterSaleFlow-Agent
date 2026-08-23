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

import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamEventEntity;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamEventRepository;
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
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
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
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommand;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpireCaseCommandResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetEvidenceTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetEvidenceTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRoutedResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecoverExpiredTargetEvidenceTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecoverExpiredTargetEvidenceTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetIntakeTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetEvidenceTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetEvidenceTerminalNoCommitResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerEntry;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.CaseCommandLedgerState;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.SequenceGapReport;
import com.example.dispute.workflow.temporal.caseprocess.ProcessedCommandIdentity;
import com.example.dispute.workflow.temporal.caseprocess.TargetIntakeCommandTerminalNoCommit;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmissionSnapshot;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore.CommandLookup;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore.MaterialSnapshot;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterial;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.targete2e.temporal.room.TargetRoomAgentRunTerminalNoCommit;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.example.dispute.workflow.temporal.room.intake.TargetIntakeSourceEventRef;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AgentRunStreamEventRepository agentRunStreamEventRepository;
    private final TargetIntakeCommandMaterialStore targetIntakeCommandMaterialStore;
    private final TargetEvidenceCommandMaterialStore targetEvidenceCommandMaterialStore;
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
        this(
                commandRepository,
                eventRepository,
                roomRepository,
                roomEpochRepository,
                projectionRepository,
                issueRepository,
                agentRunRepository,
                agentRunAttemptRepository,
                targetIntakeCommandMaterialStore,
                null,
                targetE2EActivationLedger,
                objectMapper,
                clock);
    }

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
            @Nullable TargetEvidenceCommandMaterialStore targetEvidenceCommandMaterialStore,
            @Nullable TargetE2EActivationLedger targetE2EActivationLedger,
            ObjectMapper objectMapper,
            Clock clock) {
        this(
                commandRepository,
                eventRepository,
                roomRepository,
                roomEpochRepository,
                projectionRepository,
                issueRepository,
                agentRunRepository,
                agentRunAttemptRepository,
                null,
                targetIntakeCommandMaterialStore,
                targetEvidenceCommandMaterialStore,
                targetE2EActivationLedger,
                objectMapper,
                clock);
    }

    @Autowired
    public CaseProcessLedgerActivitiesImpl(
            CaseCommandRepository commandRepository,
            CaseTimelineEventRepository eventRepository,
            CaseRoomRepository roomRepository,
            CaseRoomEpochRepository roomEpochRepository,
            CaseProcessProjectionRepository projectionRepository,
            ProcessReconciliationIssueRepository issueRepository,
            AgentRunRepository agentRunRepository,
            AgentRunAttemptRepository agentRunAttemptRepository,
            AgentRunStreamEventRepository agentRunStreamEventRepository,
            @Nullable TargetIntakeCommandMaterialStore targetIntakeCommandMaterialStore,
            @Nullable TargetEvidenceCommandMaterialStore targetEvidenceCommandMaterialStore,
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
        this.agentRunStreamEventRepository = agentRunStreamEventRepository;
        this.targetIntakeCommandMaterialStore = targetIntakeCommandMaterialStore;
        this.targetEvidenceCommandMaterialStore = targetEvidenceCommandMaterialStore;
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
        OffsetDateTime routedAt = routingTime(request, command);
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
        OffsetDateTime routedAt = routingTime(request, command);
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
    public ResolveTargetEvidenceTerminalNoCommitResult resolveTargetEvidenceTerminalNoCommit(
            ResolveTargetEvidenceTerminalNoCommit request) {
        requireTargetEvidenceParent(
                request.command(),
                request.caseWorkflowId(),
                request.caseWorkflowBuildId());
        CaseCommandEntity command =
                lockedCommand(request.command().tenantSurrogate(), request.command().commandId());
        requireTargetEvidenceCommand(command, request.command());
        if (command.getCommandStatus() != CommandStatus.ORCHESTRATION_ACCEPTED) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_COMMAND_CONFLICT",
                    "target Evidence terminal source is not an accepted orchestration");
        }
        TargetEvidenceCommandMaterialStore.MaterialSnapshot material =
                requireTargetEvidenceMaterial(
                        request.command(),
                        request.roomFencingToken(),
                        request.expectedRoomRevision(),
                        request.commandHash(),
                        request.commandEnvelopeHash(),
                        request.rootRequest());
        CaseRoomEpochEntity epoch =
                targetEvidenceTerminalEpoch(
                        request.command(),
                        request.roomFencingToken(),
                        request.expectedRoomRevision(),
                        request.roomWorkflowId(),
                        request.roomWorkflowRunId(),
                        request.roomWorkflowBuildId());
        CaseProcessProjectionEntity projection =
                targetEvidenceTerminalProjection(
                        request.command(),
                        request.roomFencingToken(),
                        request.caseWorkflowId(),
                        request.caseWorkflowRunId(),
                        request.caseWorkflowBuildId());
        long projectionLastCaseEventSequence = requireTargetEvidenceObservedSourceCoordinates(
                epoch,
                projection,
                request.command(),
                request.expectedRoomRevision(),
                request.expectedLastCaseEventSequence());
        TargetRoomAgentRunTerminalNoCommit authority =
                resolveTargetEvidenceTerminalAuthority(
                        request, material, projectionLastCaseEventSequence);
        requireTargetEvidenceTerminalRun(authority, material);
        return new ResolveTargetEvidenceTerminalNoCommitResult(
                "resolve-target-evidence-terminal-no-commit-result.v1",
                authority,
                authority.receiptUri(),
                authority.receiptSha256());
    }

    @Override
    @Transactional
    public ConvergeTargetEvidenceTerminalNoCommitResult convergeTargetEvidenceTerminalNoCommit(
            ConvergeTargetEvidenceTerminalNoCommit request) {
        TargetRoomAgentRunTerminalNoCommit authority = request.authority();
        CaseCommandRef source = authority.command();
        requireTargetEvidenceParent(
                source, request.caseWorkflowId(), request.caseWorkflowBuildId());
        CaseCommandEntity command = lockedCommand(source.tenantSurrogate(), source.commandId());
        requireTargetEvidenceCommand(command, source);
        TargetEvidenceCommandMaterialStore.MaterialSnapshot material =
                requireTargetEvidenceMaterial(
                        source,
                        authority.roomFencingToken(),
                        authority.expectedRoomRevision(),
                        authority.commandHash(),
                        authority.commandEnvelopeHash(),
                        authority.rootRequest());
        requireTargetEvidenceTerminalRun(authority, material);
        CaseRoomEpochEntity epoch =
                targetEvidenceTerminalEpoch(
                        source,
                        authority.roomFencingToken(),
                        authority.expectedRoomRevision(),
                        authority.roomWorkflowId(),
                        authority.roomWorkflowRunId(),
                        authority.roomWorkflowBuildId());
        CaseProcessProjectionEntity projection =
                targetEvidenceTerminalProjection(
                        source,
                        authority.roomFencingToken(),
                        request.caseWorkflowId(),
                        request.caseWorkflowRunId(),
                        request.caseWorkflowBuildId());
        ReceiptIdentity receipt =
                new ReceiptIdentity(authority.receiptUri(), authority.receiptSha256());

        if (command.getCommandStatus() == CommandStatus.FAILED) {
            requireExactTargetEvidenceTerminalFailure(command, authority, receipt);
            requireTargetEvidenceConvergedCoordinates(
                    epoch, projection, authority);
            return targetEvidenceTerminalNoCommitResult(
                    IDEMPOTENT_REPLAY, authority, receipt, epoch, projection);
        }
        if (command.getCommandStatus() != CommandStatus.ORCHESTRATION_ACCEPTED) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_COMMAND_CONFLICT",
                    "target Evidence command reached another terminal state");
        }
        requireTargetEvidenceSourceCoordinates(
                epoch,
                projection,
                source,
                authority.expectedRoomRevision(),
                authority.expectedLastCaseEventSequence());
        OffsetDateTime terminalAt =
                OffsetDateTime.ofInstant(authority.terminalAt(), ZoneOffset.UTC);
        int cursorUpdated =
                projectionRepository.advanceTerminalNoCommitCommandCursor(
                        source.tenantSurrogate(),
                        source.caseId(),
                        source.roomEpoch(),
                        authority.roomFencingToken(),
                        source.expectedProcessRevision(),
                        authority.expectedRoomRevision(),
                        Math.decrementExact(source.caseCommandSequence()),
                        source.caseCommandSequence(),
                        authority.expectedLastCaseEventSequence(),
                        request.caseWorkflowId(),
                        request.caseWorkflowRunId(),
                        request.caseWorkflowBuildId(),
                        terminalAt);
        if (cursorUpdated != 1) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_CURSOR_CAS_REJECTED",
                    "target Evidence projection changed before terminal convergence");
        }
        command.markAcceptedOrchestrationTerminalNoCommit(
                authority.terminalErrorCode(), receipt.uri(), receipt.sha256(), terminalAt);
        return new ConvergeTargetEvidenceTerminalNoCommitResult(
                "converge-target-evidence-terminal-no-commit-result.v1",
                TERMINALIZED,
                authority,
                receipt.uri(),
                receipt.sha256(),
                source.expectedProcessRevision(),
                authority.expectedRoomRevision(),
                source.caseCommandSequence(),
                authority.expectedLastCaseEventSequence());
    }

    @Override
    @Transactional
    public RecoverExpiredTargetEvidenceTerminalNoCommitResult
            recoverExpiredTargetEvidenceTerminalNoCommit(
                    RecoverExpiredTargetEvidenceTerminalNoCommit request) {
        var recovery = request.recovery();
        ProcessedCommandIdentity previous = recovery.previousCommand();
        CaseCommandEntity command =
                lockedCommand(recovery.tenantSurrogate(), previous.commandId());
        CaseCommandRef source = CaseCommandReferenceMapper.fromEntity(command, objectMapper);
        requireExpiredTargetEvidenceCommandSource(
                command, source, recovery, request.roomEpoch());
        boolean expired = command.getCommandStatus() == CommandStatus.EXPIRED;
        if (expired) {
            requireNoLaterTargetEvidenceCommand(command);
        }

        TargetEvidenceCommandMaterialStore.MaterialSnapshot material =
                resolveTargetEvidenceMaterial(
                        source,
                        request.roomFencingToken(),
                        recovery.expectedRoomRevision());
        CaseRoomEpochEntity epoch =
                targetEvidenceTerminalEpoch(
                        source,
                        request.roomFencingToken(),
                        recovery.expectedRoomRevision(),
                        request.roomWorkflowId(),
                        request.roomWorkflowRunId(),
                        request.roomWorkflowBuildId());
        CaseProcessProjectionEntity projection =
                targetEvidenceTerminalProjection(
                        source,
                        request.roomFencingToken(),
                        recovery.workflowId(),
                        recovery.firstExecutionRunId(),
                        request.caseWorkflowBuildId());

        long expectedLastEventSequence =
                Math.decrementExact(recovery.expectedNextCaseEventSequence());
        if (expired) {
            requireTargetEvidenceObservedSourceCoordinates(
                    epoch,
                    projection,
                    source,
                    recovery.expectedRoomRevision(),
                    expectedLastEventSequence);
        }

        TargetEvidenceCommandMaterial exactMaterial = material.material();
        ResolveTargetEvidenceTerminalNoCommit resolve =
                new ResolveTargetEvidenceTerminalNoCommit(
                        "resolve-target-evidence-terminal-no-commit.v1",
                        source,
                        request.roomFencingToken(),
                        recovery.expectedRoomRevision(),
                        expectedLastEventSequence,
                        request.roomWorkflowId(),
                        request.roomWorkflowRunId(),
                        request.roomWorkflowBuildId(),
                        exactMaterial.commandHash(),
                        exactMaterial.commandEnvelopeHash(),
                        exactMaterial.request(),
                        recovery.workflowId(),
                        recovery.firstExecutionRunId(),
                        request.caseWorkflowBuildId());
        TargetRoomAgentRunTerminalNoCommit authority =
                resolveTargetEvidenceTerminalAuthority(
                        resolve, material, projection.getLastCaseEventSequence());
        requireExpiredTargetEvidenceTerminalChronology(command, recovery, authority);
        ReceiptIdentity receipt =
                new ReceiptIdentity(authority.receiptUri(), authority.receiptSha256());
        OffsetDateTime expiredAt =
                OffsetDateTime.ofInstant(recovery.actualExpiredAt(), ZoneOffset.UTC);
        OffsetDateTime terminalAt =
                OffsetDateTime.ofInstant(authority.terminalAt(), ZoneOffset.UTC);

        CaseCommandLifecycleActivities.ExpiredTargetEvidenceTerminalRecoveryOutcome outcome;
        if (command.getCommandStatus() == CommandStatus.FAILED) {
            command.markExpiredOrchestrationTerminalNoCommit(
                    "COMMAND_DEADLINE_EXPIRED",
                    expiredAt,
                    authority.terminalErrorCode(),
                    receipt.uri(),
                    receipt.sha256(),
                    terminalAt);
            requireTargetEvidenceConvergedCoordinates(epoch, projection, authority);
            outcome =
                    CaseCommandLifecycleActivities
                            .ExpiredTargetEvidenceTerminalRecoveryOutcome.IDEMPOTENT_REPLAY;
        } else if (expired) {
            int cursorUpdated =
                    projectionRepository.advanceTerminalNoCommitCommandCursor(
                            source.tenantSurrogate(),
                            source.caseId(),
                            source.roomEpoch(),
                            request.roomFencingToken(),
                            source.expectedProcessRevision(),
                            recovery.expectedRoomRevision(),
                            Math.decrementExact(source.caseCommandSequence()),
                            source.caseCommandSequence(),
                            expectedLastEventSequence,
                            recovery.workflowId(),
                            recovery.firstExecutionRunId(),
                            request.caseWorkflowBuildId(),
                            expiredAt);
            if (cursorUpdated != 1) {
                throw permanentFailure(
                        "TARGET_EVIDENCE_EXPIRED_TERMINAL_RECOVERY_CURSOR_CAS_REJECTED",
                        "target Evidence projection changed before expired terminal recovery");
            }
            command.markExpiredOrchestrationTerminalNoCommit(
                    "COMMAND_DEADLINE_EXPIRED",
                    expiredAt,
                    authority.terminalErrorCode(),
                    receipt.uri(),
                    receipt.sha256(),
                    terminalAt);
            outcome =
                    CaseCommandLifecycleActivities
                            .ExpiredTargetEvidenceTerminalRecoveryOutcome.RECOVERED;
        } else {
            throw permanentFailure(
                    "TARGET_EVIDENCE_EXPIRED_TERMINAL_RECOVERY_COMMAND_CONFLICT",
                    "target Evidence command is neither exact expired authority nor replay");
        }

        return new RecoverExpiredTargetEvidenceTerminalNoCommitResult(
                RecoverExpiredTargetEvidenceTerminalNoCommitResult.SCHEMA_VERSION,
                outcome,
                recovery.recoveryId(),
                recovery.requestSha256(),
                authority,
                receipt.uri(),
                receipt.sha256(),
                recovery.actualExpiredAt(),
                source.expectedProcessRevision(),
                recovery.expectedRoomRevision(),
                source.caseCommandSequence(),
                expectedLastEventSequence);
    }

    @Override
    @Transactional
    public ResolveTargetIntakeTerminalNoCommitResult resolveTargetIntakeTerminalNoCommit(
            ResolveTargetIntakeTerminalNoCommit request) {
        TargetIntakeCommandTerminalNoCommit observedAuthority = request.authority();
        requireTerminalNoCommitEvidence(observedAuthority);
        CaseCommandEntity command =
                lockedCommand(observedAuthority.tenantSurrogate(), observedAuthority.commandId());
        requireTerminalNoCommitCommand(command, observedAuthority);
        CaseRoomEpochEntity epoch = terminalNoCommitEpoch(observedAuthority);
        CaseProcessProjectionEntity projection = terminalNoCommitProjection(observedAuthority);
        if (ResolveTargetIntakeTerminalNoCommit.V2_SCHEMA_VERSION.equals(
                request.schemaVersion())) {
            if (command.getCommandStatus() != CommandStatus.ORCHESTRATION_ACCEPTED) {
                throw permanentFailure(
                        "TARGET_INTAKE_TERMINAL_NO_COMMIT_COMMAND_CONFLICT",
                        "v3 authority can only be derived from the locked accepted source");
            }
            TargetIntakeCommandTerminalNoCommit authority =
                    resolveStrictV3Authority(
                            observedAuthority, epoch, projection, request.observedCaseEvents());
            ReceiptIdentity receipt = terminalNoCommitReceipt(authority);
            ParentWorkflowBinding parent = resolvedParentBinding(epoch, projection, authority);
            return new ResolveTargetIntakeTerminalNoCommitResult(
                    ResolveTargetIntakeTerminalNoCommitResult.V2_SCHEMA_VERSION,
                    authority,
                    receipt.uri(),
                    receipt.sha256(),
                    parent.workflowId(),
                    parent.runId(),
                    parent.buildId());
        }
        TargetIntakeCommandTerminalNoCommit authority = observedAuthority;
        ReceiptIdentity receipt = terminalNoCommitReceipt(authority);
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
                ResolveTargetIntakeTerminalNoCommitResult.SCHEMA_VERSION,
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
            requireInterveningEventLineage(authority);
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
        requireInterveningEventLineage(authority);
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
                        projectionTargetEventSequence(authority),
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
                projectionTargetEventSequence(authority));
    }

    private static void requireTargetEvidenceParent(
            CaseCommandRef command, String caseWorkflowId, String caseWorkflowBuildId) {
        String expectedWorkflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId(
                        command.tenantSurrogate(), command.caseId());
        if (!expectedWorkflowId.equals(caseWorkflowId)
                || caseWorkflowBuildId == null
                || caseWorkflowBuildId.isBlank()) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_PARENT_MISMATCH",
                    "CaseProcess workflow authority does not match the Evidence command");
        }
    }

    private void requireTargetEvidenceCommand(
            CaseCommandEntity stored, CaseCommandRef authority) {
        CaseCommandRef exact = CaseCommandReferenceMapper.fromEntity(stored, objectMapper);
        if (!authority.equals(exact)) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_COMMAND_MISMATCH",
                    "case command conflicts with target Evidence terminal authority");
        }
    }

    private static void requireExpiredTargetEvidenceCommandSource(
            CaseCommandEntity stored,
            CaseCommandRef source,
            CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest recovery,
            long roomEpoch) {
        ProcessedCommandIdentity previous = recovery.previousCommand();
        CommandStatus status = stored.getCommandStatus();
        boolean expired = status == CommandStatus.EXPIRED;
        boolean replay = status == CommandStatus.FAILED;
        boolean exact =
                source.commandId().equals(previous.commandId())
                        && source.caseCommandSequence() == previous.caseCommandSequence()
                        && source.requestHash().equals(previous.requestHash())
                        && source.tenantSurrogate().equals(recovery.tenantSurrogate())
                        && source.caseId().equals(recovery.caseId())
                        && source.roomType() == RoomType.EVIDENCE
                        && (source.commandType() == CommandType.EVIDENCE_OPENING
                                || source.commandType() == CommandType.EVIDENCE_SUBMIT)
                        && source.roomEpoch() == roomEpoch
                        && source.expectedProcessRevision()
                                == recovery.expectedProcessRevision()
                        && stored.getOrchestratedAt() != null
                        && stored.getAppliedAt() == null
                        && stored.getUpdatedAt() != null
                        && stored.getUpdatedAt()
                                .toInstant()
                                .equals(recovery.actualExpiredAt())
                        && !stored.getDeadlineAt()
                                .toInstant()
                                .isAfter(recovery.actualExpiredAt())
                        && (expired || replay);
        if (expired) {
            exact =
                    exact
                            && "COMMAND_DEADLINE_EXPIRED".equals(stored.getStatusReasonCode())
                            && stored.getResultUri() == null
                            && stored.getResultSha256() == null;
        } else if (replay) {
            exact =
                    exact
                            && stored.getStatusReasonCode() != null
                            && !stored.getStatusReasonCode().isBlank()
                            && stored.getResultUri() != null
                            && !stored.getResultUri().isBlank()
                            && stored.getResultSha256() != null
                            && stored.getResultSha256().matches("[0-9a-f]{64}");
        }
        if (!exact) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_EXPIRED_TERMINAL_RECOVERY_COMMAND_CONFLICT",
                    "expired target Evidence command does not match recovery authority");
        }
    }

    private TargetEvidenceCommandMaterialStore.MaterialSnapshot resolveTargetEvidenceMaterial(
            CaseCommandRef command, long fencingToken, long expectedRoomRevision) {
        if (targetEvidenceCommandMaterialStore == null) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_MATERIAL_STORE_UNAVAILABLE",
                    "target Evidence command material store is unavailable");
        }
        TargetEvidenceCommandMaterialStore.MaterialSnapshot observed =
                targetEvidenceCommandMaterialStore
                        .readByRoute(
                                new TargetEvidenceCommandMaterialStore.CommandLookup(
                                        command.tenantSurrogate(),
                                        command.caseId(),
                                        command.commandId(),
                                        command.roomEpoch(),
                                        fencingToken))
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_MATERIAL_MISSING",
                                                "target Evidence command material is unavailable"));
        TargetEvidenceCommandMaterial material = observed.material();
        if (material == null) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_MATERIAL_MISMATCH",
                    "target Evidence command material is incomplete");
        }
        TargetEvidenceCommandMaterialStore.MaterialSnapshot verified =
                requireTargetEvidenceMaterial(
                        command,
                        fencingToken,
                        expectedRoomRevision,
                        material.commandHash(),
                        material.commandEnvelopeHash(),
                        material.request());
        if (!observed.equals(verified)) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_MATERIAL_MISMATCH",
                    "target Evidence command material changed during recovery");
        }
        return verified;
    }

    private void requireNoLaterTargetEvidenceCommand(CaseCommandEntity command) {
        CaseCommandEntity highest =
                commandRepository
                        .findFirstByCaseIdOrderByCaseCommandSequenceDesc(command.getCaseId())
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "TARGET_EVIDENCE_EXPIRED_TERMINAL_RECOVERY_COMMAND_CONFLICT",
                                                "case command high-water authority is unavailable"));
        if (highest.getCaseCommandSequence() != command.getCaseCommandSequence()
                || !highest.getCommandId().equals(command.getCommandId())
                || !highest.getTenantSurrogate().equals(command.getTenantSurrogate())) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_EXPIRED_TERMINAL_RECOVERY_LATER_COMMAND_PRESENT",
                    "a later command prevents expired target Evidence recovery");
        }
    }

    private static void requireExpiredTargetEvidenceTerminalChronology(
            CaseCommandEntity command,
            CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest recovery,
            TargetRoomAgentRunTerminalNoCommit authority) {
        Instant deadline = command.getDeadlineAt().toInstant();
        if (!authority.terminalAt().isBefore(deadline)
                || deadline.isAfter(recovery.actualExpiredAt())
                || !authority.command().deadlineAt().equals(deadline)) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_EXPIRED_TERMINAL_RECOVERY_CHRONOLOGY_INVALID",
                    "durable Evidence terminal did not precede command expiration");
        }
    }

    private TargetEvidenceCommandMaterialStore.MaterialSnapshot requireTargetEvidenceMaterial(
            CaseCommandRef command,
            long fencingToken,
            long expectedRoomRevision,
            String commandHash,
            String commandEnvelopeHash,
            com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest rootRequest) {
        if (targetEvidenceCommandMaterialStore == null) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_MATERIAL_STORE_UNAVAILABLE",
                    "target Evidence command material store is unavailable");
        }
        if (targetE2EActivationLedger == null) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_ACTIVATION_LEDGER_UNAVAILABLE",
                    "target activation ledger is unavailable");
        }
        TargetEvidenceCommandMaterialStore.MaterialSnapshot snapshot =
                targetEvidenceCommandMaterialStore
                        .readByRoute(
                                new TargetEvidenceCommandMaterialStore.CommandLookup(
                                        command.tenantSurrogate(),
                                        command.caseId(),
                                        command.commandId(),
                                        command.roomEpoch(),
                                        fencingToken))
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_MATERIAL_MISSING",
                                                "target Evidence command material is unavailable"));
        TargetEvidenceCommandMaterial material = snapshot.material();
        var admission = snapshot.admission();
        if (material == null
                || admission == null
                || !TargetEvidenceCommandMaterial.TARGET_LANE.equals(material.executionLane())
                || !material.activationId().equals(admission.activationId())
                || !material.activationManifestHash().equals(admission.manifestHash())
                || !command.tenantSurrogate().equals(admission.tenantSurrogate())
                || !command.caseId().equals(admission.caseId())
                || !command.commandId().equals(admission.commandId())
                || command.roomEpoch() != admission.roomEpoch()
                || fencingToken != admission.roomFencingToken()
                || fencingToken != material.roomFencingToken()
                || command.expectedProcessRevision() != material.expectedProcessRevision()
                || expectedRoomRevision != material.expectedRoomRevision()
                || !commandHash.equals(admission.commandHash())
                || !commandHash.equals(material.commandHash())
                || !commandEnvelopeHash.equals(admission.commandEnvelopeHash())
                || !commandEnvelopeHash.equals(material.commandEnvelopeHash())
                || !command.requestHash().equals(material.caseCommandRequestHash())
                || !rootRequest.equals(material.request())) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_MATERIAL_MISMATCH",
                    "target Evidence command material conflicts with terminal authority");
        }
        CommandAdmissionSnapshot durableAdmission =
                targetE2EActivationLedger
                        .queryCommandAdmission(material.activationId(), command.commandId())
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_ADMISSION_MISSING",
                                                "target Evidence admission is unavailable"));
        if (!snapshot.admissionId().equals(durableAdmission.admissionId())
                || !material.activationManifestHash()
                        .equals(durableAdmission.activationManifestHash())
                || !command.tenantSurrogate().equals(durableAdmission.tenantSurrogate())
                || !command.caseId().equals(durableAdmission.caseId())
                || !command.commandId().equals(durableAdmission.commandId())
                || !commandHash.equals(durableAdmission.commandHash())
                || !commandEnvelopeHash.equals(durableAdmission.commandEnvelopeHash())
                || command.roomEpoch() != durableAdmission.roomEpoch()
                || fencingToken != durableAdmission.roomFencingToken()
                || durableAdmission.completed()) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_ADMISSION_CONFLICT",
                    "target Evidence admission has conflicting completion authority");
        }
        return snapshot;
    }

    private CaseRoomEpochEntity targetEvidenceTerminalEpoch(
            CaseCommandRef command,
            long fencingToken,
            long expectedRoomRevision,
            String roomWorkflowId,
            String roomWorkflowRunId,
            String roomWorkflowBuildId) {
        CaseRoomEpochEntity epoch =
                roomEpochRepository
                        .findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                                command.caseId(), RoomType.EVIDENCE, command.roomEpoch())
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_EPOCH_MISSING",
                                                "target Evidence epoch is unavailable"));
        if (!command.tenantSurrogate().equals(epoch.getTenantSurrogate())
                || epoch.getWriterMode() != WriterMode.TEMPORAL
                || epoch.getLifecycleStatus() != EpochLifecycleStatus.ACTIVE
                || epoch.getFencingToken() != fencingToken
                || epoch.getProcessRevision() != command.expectedProcessRevision()
                || epoch.getRoomRevision() != expectedRoomRevision
                || !roomWorkflowId.equals(epoch.getRoomTemporalWorkflowId())
                || !roomWorkflowRunId.equals(epoch.getRoomTemporalRunId())
                || !roomWorkflowBuildId.equals(epoch.getRoomWorkflowBuildId())) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_EPOCH_MISMATCH",
                    "target Evidence epoch conflicts with terminal authority");
        }
        return epoch;
    }

    private CaseProcessProjectionEntity targetEvidenceTerminalProjection(
            CaseCommandRef command,
            long fencingToken,
            String caseWorkflowId,
            String caseWorkflowRunId,
            String caseWorkflowBuildId) {
        CaseProcessProjectionEntity projection =
                projectionRepository
                        .findByIdForUpdate(command.caseId())
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_PROJECTION_MISSING",
                                                "case process projection is unavailable"));
        if (!command.tenantSurrogate().equals(projection.getTenantSurrogate())
                || projection.getWriterMode() != WriterMode.TEMPORAL
                || projection.getRoomEpoch() != command.roomEpoch()
                || projection.getFencingToken() != fencingToken
                || !RoomType.EVIDENCE.name().equals(projection.getCurrentRoom())
                || !caseWorkflowId.equals(projection.getTemporalWorkflowId())
                || !caseWorkflowRunId.equals(projection.getTemporalRunId())
                || !caseWorkflowBuildId.equals(projection.getTemporalBuildId())) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_PROJECTION_MISMATCH",
                    "case process projection conflicts with target Evidence authority");
        }
        return projection;
    }

    private static void requireTargetEvidenceSourceCoordinates(
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            CaseCommandRef command,
            long expectedRoomRevision,
            long expectedLastCaseEventSequence) {
        if (!RoomEpochReadiness.isTemporalReady(epoch, projection)
                || epoch.getProcessRevision() != command.expectedProcessRevision()
                || epoch.getRoomRevision() != expectedRoomRevision
                || projection.getProcessRevision() != command.expectedProcessRevision()
                || projection.getLastCommandSequence()
                        != Math.decrementExact(command.caseCommandSequence())
                || projection.getLastCaseEventSequence() != expectedLastCaseEventSequence) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_SOURCE_STALE",
                    "target Evidence terminal source coordinates are stale");
        }
    }

    /**
     * The Room workflow can already have consumed append-only submission/message events that do
     * not advance the formal process projection. Treat that workflow cursor as an observed upper
     * bound and bind the terminal receipt to the locked projection cursor instead of requiring the
     * two independent cursors to be equal.
     */
    static long requireTargetEvidenceObservedSourceCoordinates(
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            CaseCommandRef command,
            long expectedRoomRevision,
            long observedLastCaseEventSequence) {
        long projectionLastCaseEventSequence =
                projection == null ? Long.MAX_VALUE : projection.getLastCaseEventSequence();
        if (!RoomEpochReadiness.isTemporalReady(epoch, projection)
                || epoch.getProcessRevision() != command.expectedProcessRevision()
                || epoch.getRoomRevision() != expectedRoomRevision
                || projection.getProcessRevision() != command.expectedProcessRevision()
                || projection.getLastCommandSequence()
                        != Math.decrementExact(command.caseCommandSequence())
                || projectionLastCaseEventSequence > observedLastCaseEventSequence) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_SOURCE_STALE",
                    "target Evidence terminal source coordinates are stale");
        }
        return projectionLastCaseEventSequence;
    }

    private static void requireTargetEvidenceConvergedCoordinates(
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            TargetRoomAgentRunTerminalNoCommit authority) {
        CaseCommandRef command = authority.command();
        if (!RoomEpochReadiness.isTemporalReady(epoch, projection)
                || epoch.getProcessRevision() != command.expectedProcessRevision()
                || epoch.getRoomRevision() != authority.expectedRoomRevision()
                || projection.getProcessRevision() != command.expectedProcessRevision()
                || projection.getLastCommandSequence() != command.caseCommandSequence()
                || projection.getLastCaseEventSequence()
                        != authority.expectedLastCaseEventSequence()) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_REPLAY_CONFLICT",
                    "target Evidence terminal replay cannot prove exact unchanged coordinates");
        }
    }

    private TargetRoomAgentRunTerminalNoCommit resolveTargetEvidenceTerminalAuthority(
            ResolveTargetEvidenceTerminalNoCommit request,
            TargetEvidenceCommandMaterialStore.MaterialSnapshot material,
            long expectedProjectionLastCaseEventSequence) {
        EvidenceTerminalLedger terminal =
                targetEvidenceTerminalLedger(request.rootRequest().logicalRunId());
        ExecuteAgentRunResult result = terminal.result();
        AgentRunAttemptEntity attempt = terminal.terminalAttempt();
        String errorCode;
        boolean retryable;
        AgentRunRecoveryAction recoveryAction;
        long lastSequenceNo;
        Instant terminalAt;
        boolean finalFrameObserved;
        if (result.outcome() == ExecuteAgentRunResult.Outcome.FAILED) {
            errorCode = result.errorCode();
            retryable = result.retryable();
            recoveryAction = result.recoveryAction();
            lastSequenceNo = result.lastSequenceNo();
            terminalAt = result.completedAt();
            finalFrameObserved = false;
        } else if (result.outcome() == ExecuteAgentRunResult.Outcome.COMPLETED
                && "FINALIZATION_REJECTED".equals(terminal.run().getStopReason())) {
            errorCode = attempt.getErrorCode();
            retryable = Boolean.TRUE.equals(attempt.getErrorRetryable());
            recoveryAction = AgentRunRecoveryAction.valueOf(attempt.getTerminationCode());
            lastSequenceNo = attempt.getLastSequenceNo();
            terminalAt = attempt.getCompletedAt().toInstant();
            finalFrameObserved = attempt.isFinalFrameObserved();
        } else {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RESULT_INVALID",
                    "AgentRun did not persist a supported terminal-no-commit shape");
        }
        TargetRoomAgentRunTerminalNoCommit authority;
        try {
            authority =
                    new TargetRoomAgentRunTerminalNoCommit(
                            TargetRoomAgentRunTerminalNoCommit.SCHEMA_VERSION,
                            request.command(),
                            request.roomFencingToken(),
                            request.expectedRoomRevision(),
                            expectedProjectionLastCaseEventSequence,
                            request.roomWorkflowId(),
                            request.roomWorkflowRunId(),
                            request.roomWorkflowBuildId(),
                            request.commandHash(),
                            request.commandEnvelopeHash(),
                            request.rootRequest(),
                            result,
                            attempt.getAttemptStatus(),
                            errorCode,
                            retryable,
                            recoveryAction,
                            lastSequenceNo,
                            terminalAt,
                            finalFrameObserved);
        } catch (IllegalArgumentException mismatch) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_LINEAGE_INVALID",
                    "terminal Evidence root request conflicts with its case command");
        }
        requireTargetEvidenceTerminalRun(authority, material);
        return authority;
    }

    private void requireTargetEvidenceTerminalRun(
            TargetRoomAgentRunTerminalNoCommit authority,
            TargetEvidenceCommandMaterialStore.MaterialSnapshot material) {
        EvidenceTerminalLedger terminal =
                targetEvidenceTerminalLedger(authority.rootRequest().logicalRunId());
        AgentRunEntity run = terminal.run();
        List<AgentRunAttemptEntity> attempts = terminal.attempts();
        AgentRunAttemptEntity root = attempts.getFirst();
        AgentRunAttemptEntity attempt = terminal.terminalAttempt();
        ExecuteAgentRunResult result = terminal.result();
        RoomGraphCommand rootCommand = decodeTargetEvidenceGraphCommand(root);
        RoomGraphCommand terminalCommand = decodeTargetEvidenceGraphCommand(attempt);
        boolean completedAudit = result.outcome() == ExecuteAgentRunResult.Outcome.COMPLETED;
        try {
            if (!completedAudit) {
                attempt.requireDurableFailureResult(result);
            }
        } catch (IllegalArgumentException | IllegalStateException mismatch) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RESULT_INVALID",
                    "terminal Evidence result conflicts with its durable attempt");
        }
        if (!authority.terminalResult().equals(result)
                || attempts.size() != authority.terminalResult().attemptNo()
                || !authority.rootRequest().attemptId().equals(root.getId())
                || !authority.terminalResult().attemptId().equals(attempt.getId())
                || attempt.getAttemptStatus() != authority.terminalAttemptStatus()
                || attempt.isFinalFrameObserved() != authority.finalFrameObserved()
                || attempt.getLastSequenceNo() != authority.terminalLastSequenceNo()
                || !authority.terminalErrorCode().equals(attempt.getErrorCode())
                || !Boolean.FALSE.equals(attempt.getErrorRetryable())
                || !authority.terminalRecoveryAction().name().equals(attempt.getTerminationCode())
                || !authority.terminalAt().equals(attempt.getCompletedAt().toInstant())
                || !authority.rootRequest().command().equals(rootCommand)
                || !authority.rootRequest().logicalRunId().equals(terminalCommand.logicalRunId())
                || !authority.terminalResult().attemptId().equals(terminalCommand.attemptId())
                || !sameTargetEvidenceLogicalRun(rootCommand, terminalCommand)
                || terminalCommand.roomType() != RoomType.EVIDENCE
                || terminalCommand.roomEpoch() != authority.command().roomEpoch()
                || terminalCommand.processRevision()
                        != authority.command().expectedProcessRevision()
                || !authority.command().tenantSurrogate().equals(terminalCommand.tenantSurrogate())
                || !authority.command().caseId().equals(terminalCommand.caseId())
                || !authority.command().commandId().equals(root.getCommandId())
                || !rootCommand.requestHash().equals(root.getRequestHash())
                || !rootCommand.requestHash().equals(root.getCommandRequestHash())
                || !authority.rootRequest().logicalInputHash().equals(root.getLogicalInputHash())
                || !terminalCommand.requestHash().equals(attempt.getCommandRequestHash())
                || !material.material().request().equals(authority.rootRequest())) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_LINEAGE_INVALID",
                    "terminal Evidence attempt lineage conflicts with its source command");
        }
        Set<String> commandIds = new HashSet<>();
        Set<String> commandRequestHashes = new HashSet<>();
        RoomGraphCommand previousCommand = null;
        for (int index = 0; index < attempts.size(); index++) {
            AgentRunAttemptEntity candidate = attempts.get(index);
            String expectedPrevious = index == 0 ? null : attempts.get(index - 1).getId();
            RoomGraphCommand candidateCommand = decodeTargetEvidenceGraphCommand(candidate);
            boolean exactAttempt = candidate.getAttemptNo() == index + 1L
                    && authority.rootRequest().logicalRunId().equals(candidate.getAgentRunId())
                    && Objects.equals(expectedPrevious, candidate.getPreviousAttemptId())
                    && Objects.equals(candidate.getId(), candidateCommand.attemptId())
                    && Objects.equals(candidate.getAgentRunId(), candidateCommand.logicalRunId())
                    && Objects.equals(candidate.getCommandId(), candidateCommand.commandId())
                    && Objects.equals(candidate.getRequestHash(), candidateCommand.requestHash())
                    && Objects.equals(
                            candidate.getCommandRequestHash(), candidateCommand.requestHash())
                    && Objects.equals(root.getLogicalInputHash(), candidate.getLogicalInputHash())
                    && candidate.getExecutorKind() == AgentRunExecutorKind.TEMPORAL_ACTIVITY
                    && Objects.equals(candidate.getGraphKey(), candidateCommand.graphKey())
                    && Objects.equals(candidate.getGraphVersion(), candidateCommand.graphVersion())
                    && Objects.equals(
                            candidate.getCheckpointSchemaVersion(),
                            candidateCommand.checkpointSchemaVersion())
                    && Objects.equals(
                            candidate.getModelProfileId(),
                            candidateCommand.invocationContext().modelProfileId())
                    && Objects.equals(
                            candidate.getPromptVersion(),
                            candidateCommand.invocationContext().promptProfileId())
                    && Objects.equals(
                            candidate.getOutputSchemaVersion(),
                            candidateCommand.invocationContext().outputSchemaVersion())
                    && Objects.equals(
                            candidate.getPolicyVersion(),
                            candidateCommand.invocationContext().policyVersion())
                    && Objects.equals(
                            candidate.getGuardrailVersion(),
                            candidateCommand.invocationContext().guardrailVersion())
                    && candidateCommand.requestHash().matches("[0-9a-f]{64}")
                    && validRetryBudget(candidateCommand)
                    && sameTargetEvidenceLogicalRun(rootCommand, candidateCommand)
                    && commandIds.add(candidateCommand.commandId())
                    && commandRequestHashes.add(candidateCommand.requestHash())
                    && (previousCommand == null
                            || retryBudgetDoesNotIncrease(previousCommand, candidateCommand));
            if (!exactAttempt) {
                throw permanentFailure(
                        "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_LINEAGE_INVALID",
                        "terminal Evidence attempt lineage is not exact");
            }
            if (index < attempts.size() - 1) {
                requireRetryableTargetEvidencePredecessor(candidate);
            }
            previousCommand = candidateCommand;
        }
        boolean exactRootLineage = root.getLineageSchemaVersion() != null
                && !root.getLineageSchemaVersion().isBlank();
        for (AgentRunAttemptEntity candidate : attempts) {
            if (!exactRootLineage
                    || !root.getLineageSchemaVersion().equals(candidate.getLineageSchemaVersion())) {
                throw permanentFailure(
                        "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_LINEAGE_INVALID",
                        "terminal Evidence attempt lineage schema drifted");
            }
        }
        boolean exactRun =
                authority.command().tenantSurrogate().equals(run.getTenantSurrogate())
                        && authority.command().caseId().equals(run.getCaseId())
                        && AgentRunProtocol.V3.wireValue().equals(run.getProtocol())
                        && run.getExecutorKind() == AgentRunExecutorKind.TEMPORAL_ACTIVITY
                        && run.getRoomType() == RoomType.EVIDENCE
                        && run.getRoomEpoch() == authority.command().roomEpoch()
                        && run.getFencingToken() == authority.roomFencingToken()
                        && run.getProcessRevision()
                                == authority.command().expectedProcessRevision()
                        && rootCommand.requestHash().equals(run.getRequestHash())
                        && authority.rootRequest().logicalInputHash().equals(run.getLogicalInputHash())
                        && authority.terminalAttemptStatus().name().equals(run.getRunStatus())
                        && "UNCOMMITTED".equals(run.getFinalizationStatus())
                        && run.getCommittedAttemptId() == null
                        && run.getCommittedManifestId() == null
                        && run.getCommittedManifestHash() == null
                        && run.getFinalStreamSequenceNo() == null
                        && run.getFinalizedAt() == null
                        && run.getCompletedAt() != null
                        && authority.terminalAt().equals(run.getCompletedAt().toInstant());
        if (completedAudit) {
            exactRun =
                    exactRun
                            && authority.terminalErrorCode().equals(run.getErrorCode())
                            && Boolean.FALSE.equals(run.getErrorRetryable())
                            && "FINALIZATION_REJECTED".equals(run.getStopReason())
                            && authority.terminalResult().resultHash()
                                    .equals(attempt.getResultHash())
                            && authority.terminalResult().resultHash()
                                    .equals(run.getFinalResultHash())
                            && attempt.getId().equals(run.getResultReadyAttemptId());
        } else {
            exactRun =
                    exactRun
                            && run.getResultReadyAttemptId() == null
                            && run.getFinalResultHash() == null;
        }
        if (!exactRun) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID",
                    "logical Evidence AgentRun is not terminal without a commit");
        }
        if (completedAudit) {
            return;
        }

        requireTargetEvidenceFailureTerminalEvent(run, attempt, terminalCommand, authority);
        if (!authority.terminalErrorCode().equals(run.getErrorCode())
                || !Boolean.FALSE.equals(run.getErrorRetryable())
                || !AgentRunEntity.V3_LOGICAL_FAILURE_MESSAGE.equals(run.getErrorMessage())
                || !AgentRunEntity.V3_LOGICAL_FAILURE_STOP_REASON.equals(run.getStopReason())) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID",
                    "logical Evidence AgentRun failure projection is not canonical");
        }
    }

    private void requireTargetEvidenceFailureTerminalEvent(
            AgentRunEntity run,
            AgentRunAttemptEntity attempt,
            RoomGraphCommand terminalCommand,
            TargetRoomAgentRunTerminalNoCommit authority) {
        if (agentRunStreamEventRepository == null) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID",
                    "terminal Evidence stream authority is unavailable");
        }
        try {
            long highWatermark = agentRunStreamEventRepository.findMaxV2Sequence(
                    run.getId(), attempt.getId());
            if (highWatermark != authority.terminalLastSequenceNo()
                    || highWatermark != attempt.getLastSequenceNo()) {
                throw new IllegalStateException(
                        "terminal Evidence stream high-watermark drifted");
            }
            AgentRunStreamEventEntity persisted = agentRunStreamEventRepository
                    .findV2Event(run.getId(), attempt.getId(), highWatermark)
                    .orElseThrow(() -> new IllegalStateException(
                            "terminal Evidence ERROR event is missing"));
            persisted.requireCompatibilityBinding();
            persisted.canonicalPayloadHash(objectMapper);
            AgentStreamEvent terminal =
                    objectMapper.readValue(persisted.getPayloadJson(), AgentStreamEvent.class);
            Instant eventOccurredAt = persisted.getCreatedAt() == null
                    ? null
                    : persisted.getCreatedAt().toInstant();
            AgentStreamEvent expected = new AgentStreamEvent(
                    AgentRunProtocol.V3.wireValue(),
                    run.getId(),
                    attempt.getId(),
                    highWatermark,
                    StreamEventType.ERROR,
                    terminalCommand.actorScope().audience(),
                    eventOccurredAt,
                    new AgentStreamEvent.Payload(
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            authority.terminalErrorCode(),
                            false));
            boolean exact = run.getId().equals(persisted.getAgentRunId())
                    && attempt.getId().equals(persisted.getAgentRunAttemptId())
                    && highWatermark == persisted.getSequenceNo()
                    && AgentRunProtocol.V3.wireValue().equals(persisted.getStreamProtocol())
                    && StreamEventType.ERROR.wireValue().equals(persisted.getEventType())
                    && terminalCommand.actorScope().audience() == persisted.getAudience()
                    && eventOccurredAt != null
                    && attempt.getStartedAt() != null
                    && !eventOccurredAt.isBefore(attempt.getStartedAt().toInstant())
                    && !eventOccurredAt.isAfter(authority.terminalAt())
                    && expected.equals(terminal);
            if (!exact) {
                throw new IllegalStateException(
                        "terminal Evidence ERROR event conflicts with durable authority");
            }
        } catch (RuntimeException | java.io.IOException invalid) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID",
                    "terminal Evidence stream authority is invalid");
        }
    }

    private static boolean sameTargetEvidenceLogicalRun(
            RoomGraphCommand root, RoomGraphCommand candidate) {
        return root.schemaVersion().equals(candidate.schemaVersion())
                && root.tenantSurrogate().equals(candidate.tenantSurrogate())
                && root.caseId().equals(candidate.caseId())
                && root.roomType() == candidate.roomType()
                && root.roomEpoch() == candidate.roomEpoch()
                && root.graphKey().equals(candidate.graphKey())
                && root.graphVersion().equals(candidate.graphVersion())
                && root.checkpointSchemaVersion().equals(candidate.checkpointSchemaVersion())
                && root.threadId().equals(candidate.threadId())
                && root.actorScope().equals(candidate.actorScope())
                && root.processRevision() == candidate.processRevision()
                && root.stageCode().equals(candidate.stageCode())
                && root.stageSequence() == candidate.stageSequence()
                && root.domainSnapshotRef().equals(candidate.domainSnapshotRef())
                && Objects.equals(root.eventRef(), candidate.eventRef())
                && root.invocationContext()
                        .agentProfileId()
                        .equals(candidate.invocationContext().agentProfileId())
                && root.invocationContext()
                        .promptProfileId()
                        .equals(candidate.invocationContext().promptProfileId())
                && root.invocationContext()
                        .modelProfileId()
                        .equals(candidate.invocationContext().modelProfileId())
                && root.invocationContext()
                        .outputSchemaVersion()
                        .equals(candidate.invocationContext().outputSchemaVersion())
                && root.invocationContext()
                        .policyVersion()
                        .equals(candidate.invocationContext().policyVersion())
                && root.invocationContext()
                        .guardrailVersion()
                        .equals(candidate.invocationContext().guardrailVersion())
                && root.invocationContext()
                        .toolCapabilities()
                        .equals(candidate.invocationContext().toolCapabilities())
                && root.invocationContext()
                        .envelopeKeyId()
                        .equals(candidate.invocationContext().envelopeKeyId())
                && root.deadlineAt().equals(candidate.deadlineAt())
                && root.traceparent().equals(candidate.traceparent())
                && candidate.retryBudget().providerAttemptsRemaining()
                        <= root.retryBudget().providerAttemptsRemaining()
                && candidate.retryBudget().activityAttemptsRemaining()
                        <= root.retryBudget().activityAttemptsRemaining()
                && candidate.retryBudget().repairsRemaining()
                        <= root.retryBudget().repairsRemaining();
    }

    private static boolean retryBudgetDoesNotIncrease(
            RoomGraphCommand previous, RoomGraphCommand candidate) {
        return candidate.retryBudget().providerAttemptsRemaining()
                        <= previous.retryBudget().providerAttemptsRemaining()
                && candidate.retryBudget().activityAttemptsRemaining()
                        <= previous.retryBudget().activityAttemptsRemaining()
                && candidate.retryBudget().repairsRemaining()
                        <= previous.retryBudget().repairsRemaining();
    }

    private static boolean validRetryBudget(RoomGraphCommand candidate) {
        return candidate.retryBudget().providerAttemptsRemaining() >= 0
                && candidate.retryBudget().activityAttemptsRemaining() >= 0
                && candidate.retryBudget().repairsRemaining() >= 0;
    }

    private void requireRetryableTargetEvidencePredecessor(
            AgentRunAttemptEntity predecessor) {
        ExecuteAgentRunResult result = decodeTargetEvidenceTerminalResult(predecessor);
        try {
            predecessor.requireDurableFailureResult(result);
        } catch (IllegalArgumentException | IllegalStateException mismatch) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_LINEAGE_INVALID",
                    "intermediate Evidence attempt result conflicts with its durable entity");
        }
        boolean exact = result.outcome() == ExecuteAgentRunResult.Outcome.FAILED
                && result.retryable()
                && result.recoveryAction() == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT
                && Boolean.TRUE.equals(predecessor.getErrorRetryable())
                && AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT.name()
                        .equals(predecessor.getTerminationCode())
                && predecessor.getResultHash() == null;
        if (!exact) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_LINEAGE_INVALID",
                    "intermediate Evidence attempt did not authorize the next attempt");
        }
    }

    private EvidenceTerminalLedger targetEvidenceTerminalLedger(String logicalRunId) {
        AgentRunEntity run =
                agentRunRepository
                        .findByIdForUpdate(logicalRunId)
                        .orElseThrow(
                                () ->
                                        permanentFailure(
                                                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_MISSING",
                                                "terminal Evidence AgentRun is unavailable"));
        List<AgentRunAttemptEntity> attempts =
                agentRunAttemptRepository.findAllByAgentRunIdOrderByAttemptNoAsc(logicalRunId);
        if (attempts.isEmpty()) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_LINEAGE_INVALID",
                    "terminal Evidence AgentRun has no durable attempt");
        }
        AgentRunAttemptEntity terminal = attempts.getLast();
        ExecuteAgentRunResult result = decodeTargetEvidenceTerminalResult(terminal);
        return new EvidenceTerminalLedger(run, attempts, terminal, result);
    }

    private RoomGraphCommand decodeTargetEvidenceGraphCommand(AgentRunAttemptEntity attempt) {
        try {
            return objectMapper.readValue(attempt.getCommandJson(), RoomGraphCommand.class);
        } catch (RuntimeException | java.io.IOException invalid) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_LINEAGE_INVALID",
                    "Evidence AgentRun command lineage cannot be decoded");
        }
    }

    private ExecuteAgentRunResult decodeTargetEvidenceTerminalResult(
            AgentRunAttemptEntity attempt) {
        try {
            return objectMapper.readValue(attempt.getResultJson(), ExecuteAgentRunResult.class);
        } catch (RuntimeException | java.io.IOException invalid) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RESULT_INVALID",
                    "terminal Evidence AgentRun result cannot be decoded");
        }
    }

    private static void requireExactTargetEvidenceTerminalFailure(
            CaseCommandEntity command,
            TargetRoomAgentRunTerminalNoCommit authority,
            ReceiptIdentity receipt) {
        if (!authority.terminalErrorCode().equals(command.getStatusReasonCode())
                || !receipt.uri().equals(command.getResultUri())
                || !receipt.sha256().equals(command.getResultSha256())
                || command.getAppliedAt() != null) {
            throw permanentFailure(
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_REPLAY_CONFLICT",
                    "failed Evidence command is bound to another terminal receipt");
        }
    }

    private static ConvergeTargetEvidenceTerminalNoCommitResult
            targetEvidenceTerminalNoCommitResult(
                    CaseCommandLifecycleActivities.TerminalNoCommitOutcome outcome,
                    TargetRoomAgentRunTerminalNoCommit authority,
                    ReceiptIdentity receipt,
                    CaseRoomEpochEntity epoch,
                    CaseProcessProjectionEntity projection) {
        return new ConvergeTargetEvidenceTerminalNoCommitResult(
                "converge-target-evidence-terminal-no-commit-result.v1",
                outcome,
                authority,
                receipt.uri(),
                receipt.sha256(),
                projection.getProcessRevision(),
                epoch.getRoomRevision(),
                projection.getLastCommandSequence(),
                projection.getLastCaseEventSequence());
    }

    private record EvidenceTerminalLedger(
            AgentRunEntity run,
            List<AgentRunAttemptEntity> attempts,
            AgentRunAttemptEntity terminalAttempt,
            ExecuteAgentRunResult result) {}

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
                || !AgentRunProtocol.V3.wireValue().equals(run.getProtocol())
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
        boolean valid = (TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION.equals(
                                authority.schemaVersion())
                        || TargetIntakeCommandTerminalNoCommit.V3_SCHEMA_VERSION.equals(
                                authority.schemaVersion()))
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

    private TargetIntakeCommandTerminalNoCommit resolveStrictV3Authority(
            TargetIntakeCommandTerminalNoCommit observedAuthority,
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            List<TargetIntakeSourceEventRef> observedCaseEvents) {
        if (!TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION.equals(
                        observedAuthority.schemaVersion())
                || !RoomEpochReadiness.isTemporalReady(epoch, projection)
                || epoch.getProcessRevision() != observedAuthority.expectedProcessRevision()
                || epoch.getRoomRevision() != observedAuthority.expectedRoomRevision()
                || projection.getProcessRevision()
                        != observedAuthority.expectedProcessRevision()
                || projection.getLastCommandSequence()
                        != observedAuthority.caseCommandSequence() - 1
                || projection.getLastCaseEventSequence()
                        > observedAuthority.expectedLastCaseEventSequence()) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_SOURCE_STALE",
                    "strict v3 terminal-no-commit source coordinates are stale");
        }
        long expectedProjectionEventSequence = projection.getLastCaseEventSequence();
        long newProjectionEventSequence = observedAuthority.lastCaseEventSequence();
        List<TargetIntakeSourceEventRef> interveningEvents =
                observedCaseEvents.stream()
                        .filter(
                                event ->
                                        event.eventSequence() > expectedProjectionEventSequence
                                                && event.eventSequence()
                                                        <= newProjectionEventSequence)
                        .toList();
        TargetIntakeCommandTerminalNoCommit resolved;
        try {
            resolved =
                    observedAuthority.withProjectionLineage(
                            expectedProjectionEventSequence,
                            newProjectionEventSequence,
                            interveningEvents);
        } catch (IllegalArgumentException invalidLineage) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_EVENT_LINEAGE_INVALID",
                    "Room observations do not cover the locked projection cursor gap");
        }
        requireInterveningEventLineage(resolved);
        return resolved;
    }

    private ParentWorkflowBinding resolvedParentBinding(
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            TargetIntakeCommandTerminalNoCommit authority) {
        String workflowId = epoch.getTemporalWorkflowId();
        String runId = epoch.getTemporalRunId();
        String buildId = epoch.getTemporalBuildId();
        if (workflowId == null
                || workflowId.isBlank()
                || runId == null
                || runId.isBlank()
                || buildId == null
                || buildId.isBlank()
                || !workflowId.equals(
                        CaseProcessWorkflowProtocol.caseWorkflowId(
                                authority.tenantSurrogate(), authority.caseId()))
                || !workflowId.equals(projection.getTemporalWorkflowId())
                || !runId.equals(projection.getTemporalRunId())
                || !buildId.equals(projection.getTemporalBuildId())
                || !buildId.equals(authority.caseBuildId())) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_PARENT_MISMATCH",
                    "locked CaseProcess binding conflicts with strict v3 authority");
        }
        return new ParentWorkflowBinding(workflowId, runId, buildId);
    }

    private void requireInterveningEventLineage(
            TargetIntakeCommandTerminalNoCommit authority) {
        if (!TargetIntakeCommandTerminalNoCommit.V3_SCHEMA_VERSION.equals(
                authority.schemaVersion())) {
            return;
        }
        long fromSequence = Math.incrementExact(
                authority.expectedProjectionLastCaseEventSequence());
        long toSequence = authority.newProjectionLastCaseEventSequence();
        List<CaseTimelineEventEntity> stored =
                fromSequence > toSequence
                        ? List.of()
                        : eventRepository
                                .findByCaseIdAndSequenceNoBetweenOrderBySequenceNoAsc(
                                        authority.caseId(), fromSequence, toSequence);
        List<TargetIntakeSourceEventRef> expected = authority.interveningCaseEvents();
        if (stored.size() != expected.size()) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_EVENT_LINEAGE_INVALID",
                    "intervening case-event lineage is incomplete");
        }
        for (int index = 0; index < expected.size(); index++) {
            TargetIntakeSourceEventRef reference = expected.get(index);
            CaseTimelineEventEntity event = stored.get(index);
            boolean globalProjectionCursor =
                    TargetIntakeSourceEventRef.INTAKE_PROJECTION_READY.equals(
                                    reference.eventType())
                            && event.getRoomId() == null;
            boolean roomScopeMatches = globalProjectionCursor;
            if (!globalProjectionCursor) {
                try {
                    EventRoomEpoch eventRoom =
                            roomEpoch(authority.tenantSurrogate(), authority.caseId(), event);
                    roomScopeMatches =
                            eventRoom.roomType() == authority.roomType()
                                    && eventRoom.roomEpoch() == authority.roomEpoch();
                } catch (RuntimeException invalidRoom) {
                    roomScopeMatches = false;
                }
            }
            if (!reference.eventId().equals(event.getId())
                    || reference.eventSequence() != event.getSequenceNo()
                    || !reference.eventType().equals(event.getEventType())
                    || !reference.payloadHash().equals(
                            sha256(event.getEventJson().getBytes(StandardCharsets.UTF_8)))
                    || !TargetIntakeSourceEventRef.isCursorOnlyEventType(event.getEventType())
                    || !roomScopeMatches) {
                throw permanentFailure(
                        "TARGET_INTAKE_TERMINAL_NO_COMMIT_EVENT_LINEAGE_INVALID",
                        "intervening case-event lineage conflicts with Room observation");
            }
        }
    }

    private static void requireSourceTerminalCoordinates(
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            TargetIntakeCommandTerminalNoCommit authority) {
        long expectedLastCaseEventSequence =
                TargetIntakeCommandTerminalNoCommit.V3_SCHEMA_VERSION.equals(
                                authority.schemaVersion())
                        ? authority.expectedProjectionLastCaseEventSequence()
                        : TargetIntakeCommandTerminalNoCommit.LEGACY_SCHEMA_VERSION.equals(
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
                || projection.getLastCaseEventSequence()
                        < projectionTargetEventSequence(authority)) {
            throw permanentFailure(
                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_REPLAY_STALE",
                    "terminal-no-commit replay cannot prove converged coordinates");
        }
    }

    private static long projectionTargetEventSequence(
            TargetIntakeCommandTerminalNoCommit authority) {
        return TargetIntakeCommandTerminalNoCommit.V3_SCHEMA_VERSION.equals(
                        authority.schemaVersion())
                ? authority.newProjectionLastCaseEventSequence()
                : authority.lastCaseEventSequence();
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

    private record ParentWorkflowBinding(String workflowId, String runId, String buildId) {}

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

    private static OffsetDateTime routingTime(
            RecordCaseCommandRouted request, CaseCommandEntity command) {
        OffsetDateTime workflowRoutedAt =
                OffsetDateTime.ofInstant(request.routedAt(), ZoneOffset.UTC);
        return workflowRoutedAt.isBefore(command.getAcceptedAt())
                ? command.getAcceptedAt()
                : workflowRoutedAt;
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
                roomEpochRepository.findByRoomAuthority(
                        tenantSurrogate,
                        caseId,
                        event.getRoomId(),
                        roomType,
                        PageRequest.of(0, 2));
        if (epochs.size() != 1
                || !tenantSurrogate.equals(epochs.getFirst().getTenantSurrogate())
                || !caseId.equals(epochs.getFirst().getCaseId())
                || !event.getRoomId().equals(epochs.getFirst().getRoomId())
                || roomType != epochs.getFirst().getRoomType()
                || epochs.getFirst().getRoomEpoch() < 0) {
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
