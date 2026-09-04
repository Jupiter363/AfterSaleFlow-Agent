package com.example.dispute.workflow.activity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunLedger.CreateLogicalRun;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamEventEntity;
import com.example.dispute.agentstream.infrastructure.persistence.AgentRunStreamEventRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.room.infrastructure.persistence.entity.CaseRoomEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseTimelineEventEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.CommandStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.WriterActivationStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseCommandRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.ProcessReconciliationIssueRepository;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.CommandLifecycleOutcome;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetEvidenceTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ExpiredTargetEvidenceTerminalRecoveryOutcome;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecoverExpiredTargetEvidenceTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.TerminalNoCommitOutcome;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessCarryState.RecoveryErrorOrigin;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest;
import com.example.dispute.workflow.temporal.caseprocess.ProcessedCommandIdentity;
import com.example.dispute.workflow.temporal.caseprocess.TargetIntakeCommandTerminalNoCommit;
import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomProtocol;
import com.example.dispute.workflow.runtime.temporal.room.TargetRoomAgentRunTerminalNoCommit;
import com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger;
import com.example.dispute.workflow.runtime.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCommandMaterial;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessLedgerActivities.LoadSequenceRange;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.example.dispute.workflow.temporal.room.intake.TargetIntakeSourceEventRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.protobuf.ByteString;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ApplicationFailure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jpa.repository.Query;
import org.springframework.test.util.ReflectionTestUtils;

class CaseProcessLedgerActivitiesImplTest {

    private static final String TENANT = "tenant-routing";
    private static final String CASE_ID = "CASE_ROUTING";
    private static final String COMMAND_ID = "CMD_ROUTING";
    private static final String EVIDENCE_ATTEMPT_TWO_COMMAND_ID =
            COMMAND_ID + ":attempt:2";
    private static final String EVIDENCE_ATTEMPT_THREE_COMMAND_ID =
            COMMAND_ID + ":attempt:3";

    @Test
    void routingLocksTheCommandByTenantAndCommandIdInOneRepositoryCall() {
        CaseCommandRepository commandRepository = mock(CaseCommandRepository.class);
        CaseCommandEntity command = mock(CaseCommandEntity.class);
        when(commandRepository.findByTenantSurrogateAndCommandIdForUpdate(TENANT, COMMAND_ID))
                .thenReturn(Optional.of(command));
        when(command.getCaseId()).thenReturn(CASE_ID);
        when(command.getCaseCommandSequence()).thenReturn(1L);
        when(command.getRequestHash()).thenReturn("request-hash");
        when(command.getRoomType()).thenReturn(RoomType.EVIDENCE);
        when(command.getRoomEpoch()).thenReturn(7L);
        when(command.getCommandStatus()).thenReturn(CommandStatus.APPLIED);

        CaseProcessLedgerActivitiesImpl activities =
                new CaseProcessLedgerActivitiesImpl(
                        commandRepository,
                        mock(CaseTimelineEventRepository.class),
                        mock(CaseRoomRepository.class),
                        mock(CaseRoomEpochRepository.class),
                        mock(CaseProcessProjectionRepository.class),
                        mock(ProcessReconciliationIssueRepository.class),
                        mock(com.example.dispute.infrastructure.persistence.repository.AgentRunRepository.class),
                        mock(com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository.class),
                        mock(com.example.dispute.workflow.runtime.persistence.material.TargetIntakeCommandMaterialStore.class),
                        mock(com.example.dispute.workflow.runtime.persistence.ProductionActivationLedger.class),
                        new ObjectMapper(),
                        Clock.systemUTC());

        var result = activities.recordCaseCommandRouted(routingRequest());

        assertThat(result.outcome()).isEqualTo(CommandLifecycleOutcome.ALREADY_APPLIED);
        verify(commandRepository).findByTenantSurrogateAndCommandIdForUpdate(TENANT, COMMAND_ID);
        verify(commandRepository, never()).findByTenantSurrogateAndCommandId(anyString(), anyString());
        verify(commandRepository, never()).findByIdForUpdate(anyString());
    }

    @Test
    void evidenceTerminalSourceTreatsWorkflowTimelineAsObservedUpperBound() {
        CaseCommandRef command = evidenceOpeningCommand();
        long expectedRoomRevision = 6L;
        long projectionLastCaseEventSequence = 18L;
        String caseWorkflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId(TENANT, CASE_ID);
        String roomWorkflowId =
                CaseProcessWorkflowProtocol.roomWorkflowId(
                        CASE_ID, RoomType.EVIDENCE, command.roomEpoch());

        CaseRoomEpochEntity epoch = mock(CaseRoomEpochEntity.class);
        when(epoch.getTenantSurrogate()).thenReturn(TENANT);
        when(epoch.getCaseId()).thenReturn(CASE_ID);
        when(epoch.getRoomType()).thenReturn(RoomType.EVIDENCE);
        when(epoch.getRoomEpoch()).thenReturn(command.roomEpoch());
        when(epoch.getWriterMode()).thenReturn(WriterMode.TEMPORAL);
        when(epoch.getLifecycleStatus()).thenReturn(EpochLifecycleStatus.ACTIVE);
        when(epoch.getProvisioningStatus()).thenReturn(EpochProvisioningStatus.READY);
        when(epoch.getFencingToken()).thenReturn(11L);
        when(epoch.getProcessRevision()).thenReturn(command.expectedProcessRevision());
        when(epoch.getRoomRevision()).thenReturn(expectedRoomRevision);
        when(epoch.getTemporalWorkflowId()).thenReturn(caseWorkflowId);
        when(epoch.getTemporalRunId()).thenReturn("case-run-evidence-upper-bound");
        when(epoch.getTemporalBuildId()).thenReturn("case-build-evidence-upper-bound");
        when(epoch.getRoomTemporalWorkflowId()).thenReturn(roomWorkflowId);
        when(epoch.getRoomTemporalRunId()).thenReturn("room-run-evidence-upper-bound");

        CaseProcessProjectionEntity projection = mock(CaseProcessProjectionEntity.class);
        when(projection.getCaseId()).thenReturn(CASE_ID);
        when(projection.getTenantSurrogate()).thenReturn(TENANT);
        when(projection.getWriterMode()).thenReturn(WriterMode.TEMPORAL);
        when(projection.getWriterActivationStatus()).thenReturn(WriterActivationStatus.READY);
        when(projection.getRoomEpoch()).thenReturn(command.roomEpoch());
        when(projection.getFencingToken()).thenReturn(11L);
        when(projection.getProcessRevision()).thenReturn(command.expectedProcessRevision());
        when(projection.getTemporalWorkflowId()).thenReturn(caseWorkflowId);
        when(projection.getTemporalRunId()).thenReturn("case-run-evidence-upper-bound");
        when(projection.getTemporalBuildId()).thenReturn("case-build-evidence-upper-bound");
        when(projection.getLastCommandSequence())
                .thenReturn(command.caseCommandSequence() - 1L);
        when(projection.getLastCaseEventSequence())
                .thenReturn(projectionLastCaseEventSequence);

        assertThat(
                        CaseProcessLedgerActivitiesImpl
                                .requireTargetEvidenceObservedSourceCoordinates(
                                        epoch,
                                        projection,
                                        command,
                                        expectedRoomRevision,
                                        projectionLastCaseEventSequence + 3L))
                .isEqualTo(projectionLastCaseEventSequence);
        assertThat(
                        CaseProcessLedgerActivitiesImpl
                                .requireTargetEvidenceObservedSourceCoordinates(
                                        epoch,
                                        projection,
                                        command,
                                        expectedRoomRevision,
                                        projectionLastCaseEventSequence))
                .isEqualTo(projectionLastCaseEventSequence);
        assertApplicationFailureType(
                () ->
                        CaseProcessLedgerActivitiesImpl
                                .requireTargetEvidenceObservedSourceCoordinates(
                                        epoch,
                                        projection,
                                        command,
                                        expectedRoomRevision,
                                        projectionLastCaseEventSequence - 1L),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_SOURCE_STALE");
    }

    @Test
    void absentTargetAuthorityBeansAllowWiringAndRoutingButRecoveryFailsClosed() {
        CaseCommandRepository commandRepository = mock(CaseCommandRepository.class);
        CaseCommandEntity command = mock(CaseCommandEntity.class);
        when(commandRepository.findByTenantSurrogateAndCommandIdForUpdate(TENANT, COMMAND_ID))
                .thenReturn(Optional.of(command));
        when(command.getCaseId()).thenReturn(CASE_ID);
        when(command.getCaseCommandSequence()).thenReturn(1L);
        when(command.getRequestHash()).thenReturn("request-hash");
        when(command.getRoomType()).thenReturn(RoomType.EVIDENCE);
        when(command.getRoomEpoch()).thenReturn(7L);
        when(command.getCommandStatus()).thenReturn(CommandStatus.APPLIED);

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.registerBean(CaseCommandRepository.class, () -> commandRepository);
            context.registerBean(
                    CaseTimelineEventRepository.class,
                    () -> mock(CaseTimelineEventRepository.class));
            context.registerBean(CaseRoomRepository.class, () -> mock(CaseRoomRepository.class));
            context.registerBean(
                    CaseRoomEpochRepository.class,
                    () -> mock(CaseRoomEpochRepository.class));
            context.registerBean(
                    CaseProcessProjectionRepository.class,
                    () -> mock(CaseProcessProjectionRepository.class));
            context.registerBean(
                    ProcessReconciliationIssueRepository.class,
                    () -> mock(ProcessReconciliationIssueRepository.class));
            context.registerBean(AgentRunRepository.class, () -> mock(AgentRunRepository.class));
            context.registerBean(
                    AgentRunAttemptRepository.class,
                    () -> mock(AgentRunAttemptRepository.class));
            context.registerBean(
                    AgentRunStreamEventRepository.class,
                    () -> mock(AgentRunStreamEventRepository.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(Clock.class, Clock::systemUTC);
            context.registerBean(CaseProcessLedgerActivitiesImpl.class);
            context.refresh();

            CaseProcessLedgerActivitiesImpl activities =
                    context.getBean(CaseProcessLedgerActivitiesImpl.class);
            assertThat(activities.recordCaseCommandRouted(routingRequest()).outcome())
                    .isEqualTo(CommandLifecycleOutcome.ALREADY_APPLIED);

            TargetIntakeCommandTerminalNoCommit authority =
                    terminalAuthority("GRAPH_STREAM_PROTOCOL_REJECTED");
            assertThatThrownBy(
                            () ->
                                    activities.resolveTargetIntakeTerminalNoCommit(
                                            new ResolveTargetIntakeTerminalNoCommit(
                                                    "resolve-target-intake-terminal-no-commit.v1",
                                                    authority)))
                    .isInstanceOf(ApplicationFailure.class)
                    .satisfies(
                            failure ->
                                    assertThat(((ApplicationFailure) failure).getType())
                                            .isEqualTo(
                                                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_MATERIAL_STORE_UNAVAILABLE"))
                    .hasMessageContaining("target Intake command material store is unavailable");

            CaseProcessLedgerActivitiesImpl missingActivationLedger =
                    new CaseProcessLedgerActivitiesImpl(
                            commandRepository,
                            mock(CaseTimelineEventRepository.class),
                            mock(CaseRoomRepository.class),
                            mock(CaseRoomEpochRepository.class),
                            mock(CaseProcessProjectionRepository.class),
                            mock(ProcessReconciliationIssueRepository.class),
                            mock(AgentRunRepository.class),
                            mock(AgentRunAttemptRepository.class),
                            mock(TargetIntakeCommandMaterialStore.class),
                            null,
                            new ObjectMapper(),
                            Clock.systemUTC());
            assertThatThrownBy(
                            () ->
                                    missingActivationLedger.resolveTargetIntakeTerminalNoCommit(
                                            new ResolveTargetIntakeTerminalNoCommit(
                                                    "resolve-target-intake-terminal-no-commit.v1",
                                                    authority)))
                    .isInstanceOf(ApplicationFailure.class)
                    .satisfies(
                            failure ->
                                    assertThat(((ApplicationFailure) failure).getType())
                                            .isEqualTo(
                                                    "TARGET_INTAKE_TERMINAL_NO_COMMIT_ACTIVATION_LEDGER_UNAVAILABLE"))
                    .hasMessageContaining("production runtime activation ledger is unavailable");
        }
    }

    @Test
    void hearingOpenedBeforeActivationResolvesItsExactRoomEpochAndFailsClosedOnDrift() {
        Instant eventTime = Instant.parse("2026-08-15T19:39:42.818941Z");
        OffsetDateTime activationTime =
                OffsetDateTime.ofInstant(eventTime.plusNanos(259_798_000), ZoneOffset.UTC);
        String roomId = "ROOM_HEARING_AUTHORITY";

        CaseTimelineEventRepository eventRepository = mock(CaseTimelineEventRepository.class);
        CaseRoomRepository roomRepository = mock(CaseRoomRepository.class);
        CaseRoomEpochRepository epochRepository = mock(CaseRoomEpochRepository.class);
        CaseProcessProjectionRepository projectionRepository =
                mock(CaseProcessProjectionRepository.class);
        CaseProcessProjectionEntity projection = mock(CaseProcessProjectionEntity.class);
        CaseTimelineEventEntity event = mock(CaseTimelineEventEntity.class);
        CaseRoomEntity room = mock(CaseRoomEntity.class);
        CaseRoomEpochEntity epoch = mock(CaseRoomEpochEntity.class);

        when(projectionRepository.findById(CASE_ID)).thenReturn(Optional.of(projection));
        when(projection.getTenantSurrogate()).thenReturn(TENANT);
        when(eventRepository.findByCaseIdAndSequenceNoBetweenOrderBySequenceNoAsc(
                        CASE_ID, 20L, 20L))
                .thenReturn(List.of(event));
        when(event.getId()).thenReturn("EVT_HEARING_OPENED");
        when(event.getSequenceNo()).thenReturn(20L);
        when(event.getEventType()).thenReturn("HEARING_OPENED");
        when(event.getEventJson()).thenReturn("{}");
        when(event.getRoomId()).thenReturn(roomId);
        when(event.getEventTime()).thenReturn(eventTime);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(room.getCaseId()).thenReturn(CASE_ID);
        when(room.getRoomType()).thenReturn(com.example.dispute.room.domain.RoomType.HEARING);
        when(epoch.getTenantSurrogate()).thenReturn(TENANT);
        when(epoch.getCaseId()).thenReturn(CASE_ID);
        when(epoch.getRoomId()).thenReturn(roomId);
        when(epoch.getRoomType()).thenReturn(RoomType.HEARING);
        when(epoch.getRoomEpoch()).thenReturn(0L);
        when(epoch.getActivatedAt()).thenReturn(activationTime);
        when(epochRepository.findByRoomAuthority(
                        eq(TENANT),
                        eq(CASE_ID),
                        eq(roomId),
                        eq(RoomType.HEARING),
                        any()))
                .thenReturn(List.of(epoch));

        CaseProcessLedgerActivitiesImpl activities =
                new CaseProcessLedgerActivitiesImpl(
                        mock(CaseCommandRepository.class),
                        eventRepository,
                        roomRepository,
                        epochRepository,
                        projectionRepository,
                        mock(ProcessReconciliationIssueRepository.class),
                        mock(AgentRunRepository.class),
                        mock(AgentRunAttemptRepository.class),
                        mock(TargetIntakeCommandMaterialStore.class),
                        mock(ProductionActivationLedger.class),
                        new ObjectMapper(),
                        Clock.systemUTC());
        LoadSequenceRange range =
                new LoadSequenceRange(
                        "load-sequence-range.v1", TENANT, CASE_ID, 20L, 20L, 1);

        var first = activities.loadDomainEvents(range);
        var replay = activities.loadDomainEvents(range);

        assertThat(first).isEqualTo(replay).hasSize(1);
        assertThat(first.getFirst().roomType()).isEqualTo(RoomType.HEARING);
        assertThat(first.getFirst().roomEpoch()).isZero();
        assertThat(Duration.between(eventTime, epoch.getActivatedAt().toInstant()).toNanos())
                .isEqualTo(259_798_000L);
        verify(epochRepository, times(2))
                .findByRoomAuthority(
                        eq(TENANT),
                        eq(CASE_ID),
                        eq(roomId),
                        eq(RoomType.HEARING),
                        any());
        verify(epochRepository, never())
                .findEpochAt(anyString(), anyString(), any(), any(), any());

        CaseTimelineEventEntity unscopedEvent = mock(CaseTimelineEventEntity.class);
        when(unscopedEvent.getId()).thenReturn("EVT_CASE_ONLY");
        when(unscopedEvent.getSequenceNo()).thenReturn(20L);
        when(unscopedEvent.getEventType()).thenReturn("CASE_STATE_CHANGED");
        when(unscopedEvent.getEventJson()).thenReturn("{}");
        when(unscopedEvent.getRoomId()).thenReturn(null);
        when(unscopedEvent.getEventTime()).thenReturn(eventTime);
        when(eventRepository.findByCaseIdAndSequenceNoBetweenOrderBySequenceNoAsc(
                        CASE_ID, 20L, 20L))
                .thenReturn(List.of(unscopedEvent));
        clearInvocations(roomRepository, epochRepository);

        var unscoped = activities.loadDomainEvents(range);

        assertThat(unscoped).hasSize(1);
        assertThat(unscoped.getFirst().roomType()).isNull();
        assertThat(unscoped.getFirst().roomEpoch()).isZero();
        verifyNoInteractions(roomRepository, epochRepository);

        when(eventRepository.findByCaseIdAndSequenceNoBetweenOrderBySequenceNoAsc(
                        CASE_ID, 20L, 20L))
                .thenReturn(List.of(event));
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> activities.loadDomainEvents(range))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("timeline event room binding is invalid");

        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(room.getCaseId()).thenReturn("CASE_MOVED");
        assertThatThrownBy(() -> activities.loadDomainEvents(range))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("timeline event room binding is invalid");
        when(room.getCaseId()).thenReturn(CASE_ID);

        when(epochRepository.findByRoomAuthority(
                        eq(TENANT),
                        eq(CASE_ID),
                        eq(roomId),
                        eq(RoomType.HEARING),
                        any()))
                .thenReturn(List.of());
        assertThatThrownBy(() -> activities.loadDomainEvents(range))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("timeline event does not resolve to exactly one room epoch");

        CaseRoomEpochEntity duplicate = mock(CaseRoomEpochEntity.class);
        when(epochRepository.findByRoomAuthority(
                        eq(TENANT),
                        eq(CASE_ID),
                        eq(roomId),
                        eq(RoomType.HEARING),
                        any()))
                .thenReturn(List.of(epoch, duplicate));
        assertThatThrownBy(() -> activities.loadDomainEvents(range))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("timeline event does not resolve to exactly one room epoch");

        when(epochRepository.findByRoomAuthority(
                        eq(TENANT),
                        eq(CASE_ID),
                        eq(roomId),
                        eq(RoomType.HEARING),
                        any()))
                .thenReturn(List.of(epoch));
        when(epoch.getTenantSurrogate()).thenReturn("tenant-drift");
        assertThatThrownBy(() -> activities.loadDomainEvents(range))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("timeline event does not resolve to exactly one room epoch");
        when(epoch.getTenantSurrogate()).thenReturn(TENANT);
        when(epoch.getCaseId()).thenReturn("case-drift");
        assertThatThrownBy(() -> activities.loadDomainEvents(range))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("timeline event does not resolve to exactly one room epoch");
        when(epoch.getCaseId()).thenReturn(CASE_ID);
        when(epoch.getRoomId()).thenReturn("ROOM_MOVED");
        assertThatThrownBy(() -> activities.loadDomainEvents(range))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("timeline event does not resolve to exactly one room epoch");
        when(epoch.getRoomId()).thenReturn(roomId);
        when(epoch.getRoomType()).thenReturn(RoomType.EVIDENCE);
        assertThatThrownBy(() -> activities.loadDomainEvents(range))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("timeline event does not resolve to exactly one room epoch");

        verify(epochRepository, never()).save(any(CaseRoomEpochEntity.class));
    }

    @Test
    void abortedGraphStreamConvergesAcceptedIntakeCommandToRetryableFailureAndClearsPendingProjection()
            throws Exception {
        String caseWorkflowId = "case-process:" + TENANT + ":" + CASE_ID;
        String caseWorkflowRunId = "case-run-1";
        TargetIntakeCommandTerminalNoCommit authority = terminalAuthority("GRAPH_STREAM_PROTOCOL_REJECTED");
        OffsetDateTime terminalAt = OffsetDateTime.ofInstant(authority.terminalAt(), ZoneOffset.UTC);

        CaseCommandRepository commandRepository = mock(CaseCommandRepository.class);
        CaseTimelineEventRepository eventRepository = mock(CaseTimelineEventRepository.class);
        CaseRoomRepository roomRepository = mock(CaseRoomRepository.class);
        CaseRoomEpochRepository epochRepository = mock(CaseRoomEpochRepository.class);
        CaseProcessProjectionRepository projectionRepository = mock(CaseProcessProjectionRepository.class);
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentRunAttemptRepository attemptRepository = mock(AgentRunAttemptRepository.class);
        TargetIntakeCommandMaterialStore materialStore = mock(TargetIntakeCommandMaterialStore.class);
        ProductionActivationLedger activationLedger = mock(ProductionActivationLedger.class);
        ObjectMapper mapper = mock(ObjectMapper.class);

        CaseCommandEntity command = mock(CaseCommandEntity.class);
        when(commandRepository.findByTenantSurrogateAndCommandIdForUpdate(TENANT, COMMAND_ID))
                .thenReturn(Optional.of(command));
        when(command.getCaseId()).thenReturn(CASE_ID);
        when(command.getCaseCommandSequence()).thenReturn(7L);
        when(command.getRequestHash()).thenReturn(authority.commandRequestHash());
        when(command.getRoomType()).thenReturn(RoomType.INTAKE);
        when(command.getRoomEpoch()).thenReturn(0L);
        when(command.getExpectedProcessRevision()).thenReturn(6L);
        when(command.getPayloadUri()).thenReturn(authority.messageRef());
        when(command.getPayloadSha256()).thenReturn(authority.messageHash());
        when(command.getCommandStatus())
                .thenReturn(
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.FAILED);
        when(command.getStatusReasonCode()).thenReturn(authority.errorCode());
        when(command.getResultUri()).thenReturn(authority.receiptUri());
        when(command.getResultSha256()).thenReturn(authority.receiptSha256());

        TargetIntakeCommandMaterialStore.MaterialSnapshot material =
                mock(TargetIntakeCommandMaterialStore.MaterialSnapshot.class);
        ProductionActivationLedger.CommandAdmission admission =
                mock(ProductionActivationLedger.CommandAdmission.class);
        IntakeCommandExecutionContext context = mock(IntakeCommandExecutionContext.class);
        IntakeTargetAgentRunContext target = mock(IntakeTargetAgentRunContext.class);
        ExecuteAgentRunRequest agentRequest = mock(ExecuteAgentRunRequest.class);
        RoomGraphCommand graph = mock(RoomGraphCommand.class);
        when(materialStore.readByRoute(any())).thenReturn(Optional.of(material));
        when(material.admission()).thenReturn(admission);
        when(material.admissionId()).thenReturn("admission-1");
        when(material.context()).thenReturn(context);
        when(context.targetAgentRun()).thenReturn(target);
        when(admission.activationId()).thenReturn(authority.activationId());
        when(admission.manifestHash()).thenReturn(authority.activationManifestHash());
        when(admission.tenantSurrogate()).thenReturn(TENANT);
        when(admission.caseId()).thenReturn(CASE_ID);
        when(admission.commandId()).thenReturn(COMMAND_ID);
        when(admission.commandHash()).thenReturn(authority.commandHash());
        when(admission.commandEnvelopeHash()).thenReturn(authority.commandEnvelopeHash());
        when(admission.roomEpoch()).thenReturn(0L);
        when(admission.roomFencingToken()).thenReturn(11L);
        when(target.activationId()).thenReturn(authority.activationId());
        when(target.activationManifestHash()).thenReturn(authority.activationManifestHash());
        when(target.caseBuildId()).thenReturn(authority.caseBuildId());
        when(target.controlBuildId()).thenReturn(authority.controlBuildId());
        when(target.agentBuildId()).thenReturn(authority.agentBuildId());
        when(target.graphBindingHash()).thenReturn(authority.graphBindingHash());
        when(target.graphCodeBuildId()).thenReturn(authority.graphCodeBuildId());
        when(target.commandHash()).thenReturn(authority.commandHash());
        when(target.commandEnvelopeHash()).thenReturn(authority.commandEnvelopeHash());
        when(target.expectedProcessRevision()).thenReturn(6L);
        when(target.expectedRoomRevision()).thenReturn(6L);
        when(target.request()).thenReturn(agentRequest);
        when(agentRequest.logicalInputHash()).thenReturn(authority.logicalInputHash());
        when(agentRequest.logicalRunId()).thenReturn(authority.logicalRunId());
        when(agentRequest.attemptId()).thenReturn(authority.rootAttemptId());
        when(agentRequest.command()).thenReturn(graph);

        ProductionActivationLedger.CommandAdmissionSnapshot admissionSnapshot =
                mock(ProductionActivationLedger.CommandAdmissionSnapshot.class);
        when(activationLedger.queryCommandAdmission(authority.activationId(), COMMAND_ID))
                .thenReturn(Optional.of(admissionSnapshot));
        when(admissionSnapshot.admissionId()).thenReturn("admission-1");
        when(admissionSnapshot.activationManifestHash())
                .thenReturn(authority.activationManifestHash());
        when(admissionSnapshot.commandHash()).thenReturn(authority.commandHash());
        when(admissionSnapshot.commandEnvelopeHash()).thenReturn(authority.commandEnvelopeHash());
        when(admissionSnapshot.completed()).thenReturn(false);

        AgentRunEntity run = mock(AgentRunEntity.class);
        AgentRunAttemptEntity attempt = mock(AgentRunAttemptEntity.class);
        RoomGraphCommand.SnapshotRef eventRef = mock(RoomGraphCommand.SnapshotRef.class);
        ExecuteAgentRunResult storedResult = terminalResult(authority);
        when(runRepository.findByIdForUpdate(authority.logicalRunId()))
                .thenReturn(Optional.of(run));
        when(attemptRepository.findAllByAgentRunIdOrderByAttemptNoAsc(authority.logicalRunId()))
                .thenReturn(List.of(attempt));
        when(attempt.getId()).thenReturn(authority.rootAttemptId());
        when(attempt.getAgentRunId()).thenReturn(authority.logicalRunId());
        when(attempt.getAttemptNo()).thenReturn(1L);
        when(attempt.getPreviousAttemptId()).thenReturn(null);
        when(attempt.getAttemptStatus()).thenReturn(AgentRunAttemptStatus.ABORTED);
        when(attempt.getCommandId()).thenReturn(COMMAND_ID);
        when(attempt.getRequestHash()).thenReturn(authority.agentRunExecutionRequestHash());
        when(attempt.getCommandRequestHash()).thenReturn("b".repeat(64));
        when(attempt.getCommandJson()).thenReturn("root-command-json");
        when(attempt.getResultJson()).thenReturn("terminal-result-json");
        when(mapper.readValue("root-command-json", RoomGraphCommand.class)).thenReturn(graph);
        when(mapper.readValue("terminal-result-json", ExecuteAgentRunResult.class))
                .thenReturn(storedResult);
        when(graph.requestHash()).thenReturn(authority.agentRunExecutionRequestHash());
        when(graph.logicalRunId()).thenReturn(authority.logicalRunId());
        when(graph.attemptId()).thenReturn(authority.rootAttemptId());
        when(graph.tenantSurrogate()).thenReturn(TENANT);
        when(graph.caseId()).thenReturn(CASE_ID);
        when(graph.roomType()).thenReturn(RoomType.INTAKE);
        when(graph.roomEpoch()).thenReturn(0L);
        when(graph.processRevision()).thenReturn(6L);
        when(graph.eventRef()).thenReturn(eventRef);
        when(eventRef.artifactId()).thenReturn(authority.messageId());
        when(eventRef.uri()).thenReturn(authority.messageRef());
        when(eventRef.sha256()).thenReturn(authority.messageHash());
        when(run.getTenantSurrogate()).thenReturn(TENANT);
        when(run.getCaseId()).thenReturn(CASE_ID);
        when(run.getProtocol()).thenReturn(AgentRunProtocol.V4.wireValue());
        when(run.getExecutorKind()).thenReturn(AgentRunExecutorKind.TEMPORAL_ACTIVITY);
        when(run.getRoomType()).thenReturn(RoomType.INTAKE);
        when(run.getRoomEpoch()).thenReturn(0L);
        when(run.getFencingToken()).thenReturn(11L);
        when(run.getProcessRevision()).thenReturn(6L);
        when(run.getRequestHash()).thenReturn(authority.agentRunExecutionRequestHash());
        when(run.getLogicalInputHash()).thenReturn(authority.logicalInputHash());
        when(run.getRunStatus()).thenReturn("ABORTED");
        when(run.getFinalizationStatus()).thenReturn("UNCOMMITTED");
        when(run.getFinalStreamSequenceNo()).thenReturn(null);

        TargetIntakeCommandTerminalNoCommit legacyAuthority =
                legacyTerminalAuthority("GRAPH_STREAM_PROTOCOL_REJECTED");
        var temporalConverter = DefaultDataConverter.newDefaultInstance();
        var legacyPayload = temporalConverter.toPayload(legacyAuthority).orElseThrow();
        String legacyJson = legacyTerminalAuthorityJson();
        assertThat(legacyPayload.getData().toStringUtf8()).isEqualTo(legacyJson);
        TargetIntakeCommandTerminalNoCommit legacyRoundTripped =
                temporalConverter.fromPayload(
                        legacyPayload,
                        TargetIntakeCommandTerminalNoCommit.class,
                        TargetIntakeCommandTerminalNoCommit.class);
        assertThat(legacyRoundTripped).isEqualTo(legacyAuthority);
        assertThat(legacyRoundTripped.agentRunExecutionRequestHash()).isNull();
        assertThat(legacyRoundTripped.expectedLastCaseEventSequence()).isNull();
        assertThat(
                        temporalConverter
                                .toPayload(legacyRoundTripped)
                                .orElseThrow()
                                .getData()
                                .toStringUtf8())
                .isEqualTo(legacyJson);
        var missingV2Payload =
                legacyPayload.toBuilder()
                        .setData(
                                ByteString.copyFromUtf8(
                                        legacyJson.replace(
                                                TargetIntakeCommandTerminalNoCommit
                                                        .LEGACY_SCHEMA_VERSION,
                                                TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION)))
                        .build();
        assertThatThrownBy(
                        () ->
                                temporalConverter.fromPayload(
                                        missingV2Payload,
                                        TargetIntakeCommandTerminalNoCommit.class,
                                        TargetIntakeCommandTerminalNoCommit.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                terminalAuthority(
                                        TargetIntakeCommandTerminalNoCommit.LEGACY_SCHEMA_VERSION,
                                        authority.errorCode(),
                                        authority.commandEnvelopeHash(),
                                        13L,
                                        13))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v1 terminal authority must omit v2 fields");

        ObjectMapper canonicalMapper = JsonMapper.builder().findAndAddModules().build();
        canonicalMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        TargetIntakeCommandTerminalNoCommit roundTripped =
                canonicalMapper.treeToValue(
                        canonicalMapper.valueToTree(authority),
                        TargetIntakeCommandTerminalNoCommit.class);
        assertThat(roundTripped).isEqualTo(authority);
        assertThat(roundTripped.receiptSha256()).isEqualTo(authority.receiptSha256());
        assertThat(roundTripped.receiptUri()).isEqualTo(authority.receiptUri());
        assertThat(authority.receiptUri()).endsWith(authority.receiptSha256());

        assertThat(run.getTenantSurrogate()).as("run tenant").isEqualTo(TENANT);
        assertThat(run.getCaseId()).as("run case").isEqualTo(CASE_ID);
        assertThat(run.getProtocol()).as("run protocol").isEqualTo(AgentRunProtocol.V4.wireValue());
        assertThat(run.getExecutorKind())
                .as("run executor")
                .isEqualTo(AgentRunExecutorKind.TEMPORAL_ACTIVITY);
        assertThat(run.getRoomType()).as("run room type").isEqualTo(RoomType.INTAKE);
        assertThat(run.getRoomEpoch()).as("run epoch").isZero();
        assertThat(run.getFencingToken()).as("run fence").isEqualTo(11L);
        assertThat(run.getProcessRevision()).as("run revision").isEqualTo(6L);
        assertThat(run.getRequestHash())
                .as("run request hash")
                .isEqualTo(authority.agentRunExecutionRequestHash());
        assertThat(authority.agentRunExecutionRequestHash())
                .as("AgentRun request hash is independent from the material envelope")
                .isNotEqualTo(authority.commandEnvelopeHash());
        assertThat(run.getLogicalInputHash())
                .as("run logical input hash")
                .isEqualTo(authority.logicalInputHash());
        assertThat(run.getRunStatus()).as("run status").isEqualTo("ABORTED");
        assertThat(run.getFinalizationStatus())
                .as("run finalization status")
                .isEqualTo("UNCOMMITTED");
        assertThat(run.getResultReadyAttemptId()).isNull();
        assertThat(run.getCommittedAttemptId()).isNull();
        assertThat(run.getFinalResultHash()).isNull();
        assertThat(run.getCommittedManifestId()).isNull();
        assertThat(run.getCommittedManifestHash()).isNull();
        assertThat(run.getFinalStreamSequenceNo()).isNull();
        assertThat(run.getFinalizedAt()).isNull();

        CaseRoomEpochEntity epoch = mock(CaseRoomEpochEntity.class);
        CaseProcessProjectionEntity projection = mock(CaseProcessProjectionEntity.class);
        when(epochRepository.findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                        CASE_ID, RoomType.INTAKE, 0L))
                .thenReturn(Optional.of(epoch));
        when(projectionRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(projection));
        stubSourceCoordinates(
                epoch, projection, authority, caseWorkflowId, caseWorkflowRunId);
        when(epochRepository.advanceFencedEpoch(
                        TENANT,
                        CASE_ID,
                        "INTAKE",
                        0L,
                        11L,
                        6L,
                        7L,
                        6L,
                        7L,
                        caseWorkflowId,
                        caseWorkflowRunId,
                        authority.caseBuildId(),
                        terminalAt))
                .thenReturn(1);
        when(projectionRepository.advanceFencedProjection(
                        TENANT,
                        CASE_ID,
                        0L,
                        11L,
                        6L,
                        7L,
                        "INTAKE",
                        "INTAKE",
                        "WAITING_PARTY",
                        7L,
                        15L,
                        null,
                        caseWorkflowId,
                        caseWorkflowRunId,
                        authority.caseBuildId(),
                        "urn:projection:6",
                        "c".repeat(64),
                        terminalAt))
                .thenReturn(1);
        when(projectionRepository.advanceFencedProjection(
                        TENANT,
                        CASE_ID,
                        0L,
                        11L,
                        6L,
                        7L,
                        "INTAKE",
                        "INTAKE",
                        "WAITING_PARTY",
                        7L,
                        13L,
                        null,
                        caseWorkflowId,
                        caseWorkflowRunId,
                        authority.caseBuildId(),
                        "urn:projection:6",
                        "c".repeat(64),
                        terminalAt))
                .thenReturn(1);

        CaseProcessLedgerActivitiesImpl activities =
                new CaseProcessLedgerActivitiesImpl(
                        commandRepository,
                        eventRepository,
                        roomRepository,
                        epochRepository,
                        projectionRepository,
                        mock(ProcessReconciliationIssueRepository.class),
                        runRepository,
                        attemptRepository,
                        materialStore,
                        activationLedger,
                        mapper,
                        Clock.systemUTC());
        ConvergeTargetIntakeTerminalNoCommit request =
                new ConvergeTargetIntakeTerminalNoCommit(
                        "converge-target-intake-terminal-no-commit.v1",
                        authority,
                        caseWorkflowId,
                        caseWorkflowRunId,
                        authority.caseBuildId());

        when(command.getCommandStatus())
                .thenReturn(
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.ORCHESTRATION_ACCEPTED);
        when(attempt.getRequestHash()).thenReturn(legacyAuthority.commandEnvelopeHash());
        when(attempt.getCommandRequestHash()).thenReturn(legacyAuthority.commandEnvelopeHash());
        when(graph.requestHash()).thenReturn(legacyAuthority.commandEnvelopeHash());
        when(run.getRequestHash()).thenReturn(legacyAuthority.commandEnvelopeHash());
        var legacyResolved =
                activities.resolveTargetIntakeTerminalNoCommit(
                        new ResolveTargetIntakeTerminalNoCommit(
                                "resolve-target-intake-terminal-no-commit.v1",
                                legacyAuthority));
        var legacyApplied =
                activities.convergeTargetIntakeTerminalNoCommit(
                        new ConvergeTargetIntakeTerminalNoCommit(
                                request.schemaVersion(),
                                legacyAuthority,
                                request.caseWorkflowId(),
                                request.caseWorkflowRunId(),
                                request.caseWorkflowBuildId()));
        assertThat(legacyResolved.authority()).isEqualTo(legacyAuthority);
        assertThat(legacyApplied.outcome()).isEqualTo(TerminalNoCommitOutcome.TERMINALIZED);
        assertThat(legacyApplied.lastCaseEventSequence()).isEqualTo(13);
        verify(projectionRepository)
                .advanceFencedProjection(
                        TENANT,
                        CASE_ID,
                        0L,
                        11L,
                        6L,
                        7L,
                        "INTAKE",
                        "INTAKE",
                        "WAITING_PARTY",
                        7L,
                        13L,
                        null,
                        caseWorkflowId,
                        caseWorkflowRunId,
                        authority.caseBuildId(),
                        "urn:projection:6",
                        "c".repeat(64),
                        terminalAt);
        clearInvocations(command, epochRepository, projectionRepository);
        when(command.getCommandStatus())
                .thenReturn(
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.ORCHESTRATION_ACCEPTED,
                        CommandStatus.FAILED);
        when(attempt.getRequestHash()).thenReturn(authority.agentRunExecutionRequestHash());
        when(attempt.getCommandRequestHash()).thenReturn(authority.agentRunExecutionRequestHash());
        when(graph.requestHash()).thenReturn(authority.agentRunExecutionRequestHash());
        when(run.getRequestHash()).thenReturn(authority.agentRunExecutionRequestHash());

        TargetIntakeCommandTerminalNoCommit executionHashDrift =
                terminalAuthority(
                        authority.errorCode(), "9".repeat(64), 13, 15);
        assertThatThrownBy(
                        () ->
                                activities.resolveTargetIntakeTerminalNoCommit(
                                        new ResolveTargetIntakeTerminalNoCommit(
                                                "resolve-target-intake-terminal-no-commit.v1",
                                                executionHashDrift)))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(
                        failure ->
                                assertThat(((ApplicationFailure) failure).getType())
                                        .isEqualTo(
                                                "TARGET_INTAKE_TERMINAL_NO_COMMIT_MATERIAL_MISMATCH"));
        TargetIntakeCommandTerminalNoCommit preEventDrift =
                terminalAuthority(authority.errorCode(), authority.agentRunExecutionRequestHash(), 12, 15);
        assertThatThrownBy(
                        () ->
                                activities.resolveTargetIntakeTerminalNoCommit(
                                        new ResolveTargetIntakeTerminalNoCommit(
                                                "resolve-target-intake-terminal-no-commit.v1",
                                                preEventDrift)))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(
                        failure ->
                                assertThat(((ApplicationFailure) failure).getType())
                                        .isEqualTo("TARGET_INTAKE_TERMINAL_NO_COMMIT_SOURCE_STALE"));
        var resolved =
                activities.resolveTargetIntakeTerminalNoCommit(
                        new ResolveTargetIntakeTerminalNoCommit(
                                "resolve-target-intake-terminal-no-commit.v1", authority));
        assertThat(resolved.authority()).isEqualTo(authority);

        var applied = activities.convergeTargetIntakeTerminalNoCommit(request);

        assertThat(applied.outcome()).isEqualTo(TerminalNoCommitOutcome.TERMINALIZED);
        assertThat(applied.processRevision()).isEqualTo(7);
        assertThat(applied.roomRevision()).isEqualTo(7);
        assertThat(applied.lastCommandSequence()).isEqualTo(7);
        assertThat(applied.lastCaseEventSequence()).isEqualTo(15);
        assertThat(applied.receiptUri()).isEqualTo(authority.receiptUri());
        assertThat(applied.receiptSha256()).isEqualTo(authority.receiptSha256());
        verify(command)
                .markAcceptedOrchestrationTerminalNoCommit(
                        authority.errorCode(),
                        authority.receiptUri(),
                        authority.receiptSha256(),
                        terminalAt);

        when(epoch.getProcessRevision()).thenReturn(7L);
        when(epoch.getRoomRevision()).thenReturn(7L);
        when(projection.getProcessRevision()).thenReturn(7L);
        when(projection.getLastCommandSequence()).thenReturn(7L);
        when(projection.getLastCaseEventSequence()).thenReturn(15L);
        var replay = activities.convergeTargetIntakeTerminalNoCommit(request);

        assertThat(replay.outcome()).isEqualTo(TerminalNoCommitOutcome.IDEMPOTENT_REPLAY);
        verify(epochRepository, times(1)).advanceFencedEpoch(
                TENANT, CASE_ID, "INTAKE", 0L, 11L, 6L, 7L, 6L, 7L,
                caseWorkflowId, caseWorkflowRunId, authority.caseBuildId(), terminalAt);
        verify(projectionRepository, times(1)).advanceFencedProjection(
                TENANT, CASE_ID, 0L, 11L, 6L, 7L, "INTAKE", "INTAKE", "WAITING_PARTY",
                7L, 15L, null, caseWorkflowId, caseWorkflowRunId, authority.caseBuildId(),
                "urn:projection:6", "c".repeat(64), terminalAt);

        clearInvocations(command, epochRepository, projectionRepository);
        TargetIntakeCommandTerminalNoCommit liveObservedAuthority =
                terminalAuthority(
                        authority.errorCode(),
                        authority.agentRunExecutionRequestHash(),
                        11,
                        12);
        String emptyObjectHash =
                "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";
        TargetIntakeSourceEventRef projectionReady =
                new TargetIntakeSourceEventRef(
                        TargetIntakeSourceEventRef.SCHEMA_VERSION,
                        "EVENT_PROJECTION_READY_11",
                        11,
                        TargetIntakeSourceEventRef.INTAKE_PROJECTION_READY,
                        TENANT,
                        CASE_ID,
                        RoomType.INTAKE,
                        0,
                        11,
                        emptyObjectHash);
        TargetIntakeSourceEventRef roomMessage =
                new TargetIntakeSourceEventRef(
                        TargetIntakeSourceEventRef.SCHEMA_VERSION,
                        "EVENT_ROOM_MESSAGE_12",
                        12,
                        TargetIntakeSourceEventRef.ROOM_MESSAGE_CREATED,
                        TENANT,
                        CASE_ID,
                        RoomType.INTAKE,
                        0,
                        11,
                        emptyObjectHash);
        CaseTimelineEventEntity projectionReadyRow = mock(CaseTimelineEventEntity.class);
        CaseTimelineEventEntity roomMessageRow = mock(CaseTimelineEventEntity.class);
        CaseRoomEntity room = mock(CaseRoomEntity.class);
        when(projectionReadyRow.getId()).thenReturn(projectionReady.eventId());
        when(projectionReadyRow.getSequenceNo()).thenReturn(11L);
        when(projectionReadyRow.getEventType()).thenReturn(projectionReady.eventType());
        when(projectionReadyRow.getEventJson()).thenReturn("{}");
        when(projectionReadyRow.getRoomId()).thenReturn(null);
        when(roomMessageRow.getId()).thenReturn(roomMessage.eventId());
        when(roomMessageRow.getSequenceNo()).thenReturn(12L);
        when(roomMessageRow.getEventType()).thenReturn(roomMessage.eventType());
        when(roomMessageRow.getEventJson()).thenReturn("{}");
        when(roomMessageRow.getRoomId()).thenReturn("ROOM_INTAKE_1");
        when(roomMessageRow.getEventTime()).thenReturn(Instant.parse("2026-07-29T00:00:02Z"));
        when(roomRepository.findById("ROOM_INTAKE_1")).thenReturn(Optional.of(room));
        when(room.getCaseId()).thenReturn(CASE_ID);
        when(room.getRoomType())
                .thenReturn(com.example.dispute.room.domain.RoomType.INTAKE);
        when(epoch.getTenantSurrogate()).thenReturn(TENANT);
        when(epoch.getCaseId()).thenReturn(CASE_ID);
        when(epoch.getRoomId()).thenReturn("ROOM_INTAKE_1");
        when(epoch.getRoomType()).thenReturn(RoomType.INTAKE);
        when(epochRepository.findByRoomAuthority(
                        eq(TENANT),
                        eq(CASE_ID),
                        eq("ROOM_INTAKE_1"),
                        eq(RoomType.INTAKE),
                        any()))
                .thenReturn(List.of(epoch));
        when(eventRepository.findByCaseIdAndSequenceNoBetweenOrderBySequenceNoAsc(
                        CASE_ID, 11L, 12L))
                .thenReturn(List.of(projectionReadyRow, roomMessageRow));
        when(command.getCommandStatus()).thenReturn(CommandStatus.ORCHESTRATION_ACCEPTED);
        when(epoch.getProcessRevision()).thenReturn(6L);
        when(epoch.getRoomRevision()).thenReturn(6L);
        when(projection.getProcessRevision()).thenReturn(6L);
        when(projection.getLastCommandSequence()).thenReturn(6L);
        when(projection.getLastCaseEventSequence()).thenReturn(10L);
        when(projectionRepository.advanceFencedProjection(
                        TENANT, CASE_ID, 0L, 11L, 6L, 7L, "INTAKE", "INTAKE", "WAITING_PARTY",
                        7L, 12L, null, caseWorkflowId, caseWorkflowRunId,
                        authority.caseBuildId(), "urn:projection:6", "c".repeat(64), terminalAt))
                .thenReturn(1);

        var liveResolved =
                activities.resolveTargetIntakeTerminalNoCommit(
                        new ResolveTargetIntakeTerminalNoCommit(
                                ResolveTargetIntakeTerminalNoCommit.V2_SCHEMA_VERSION,
                                liveObservedAuthority,
                                List.of(projectionReady, roomMessage)));
        TargetIntakeCommandTerminalNoCommit liveV3 = liveResolved.authority();
        assertThat(liveV3.schemaVersion())
                .isEqualTo(TargetIntakeCommandTerminalNoCommit.V3_SCHEMA_VERSION);
        assertThat(liveV3.expectedProjectionLastCaseEventSequence()).isEqualTo(10);
        assertThat(liveV3.expectedLastCaseEventSequence()).isEqualTo(11);
        assertThat(liveV3.newProjectionLastCaseEventSequence()).isEqualTo(12);
        assertThat(liveV3.lastCaseEventSequence()).isEqualTo(12);
        assertThat(liveV3.interveningCaseEvents())
                .containsExactly(projectionReady, roomMessage);

        ConvergeTargetIntakeTerminalNoCommit liveConvergence =
                new ConvergeTargetIntakeTerminalNoCommit(
                        "converge-target-intake-terminal-no-commit.v1",
                        liveV3,
                        caseWorkflowId,
                        caseWorkflowRunId,
                        authority.caseBuildId());
        when(eventRepository.findByCaseIdAndSequenceNoBetweenOrderBySequenceNoAsc(
                        CASE_ID, 11L, 12L))
                .thenReturn(List.of(projectionReadyRow));
        assertThatThrownBy(
                        () -> activities.convergeTargetIntakeTerminalNoCommit(liveConvergence))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(
                        failure ->
                                assertThat(((ApplicationFailure) failure).getType())
                                        .isEqualTo(
                                                "TARGET_INTAKE_TERMINAL_NO_COMMIT_EVENT_LINEAGE_INVALID"));
        when(eventRepository.findByCaseIdAndSequenceNoBetweenOrderBySequenceNoAsc(
                        CASE_ID, 11L, 12L))
                .thenReturn(List.of(projectionReadyRow, roomMessageRow));
        when(roomMessageRow.getId()).thenReturn("EVENT_FOREIGN_12");
        assertThatThrownBy(
                        () -> activities.convergeTargetIntakeTerminalNoCommit(liveConvergence))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(
                        failure ->
                                assertThat(((ApplicationFailure) failure).getType())
                                        .isEqualTo(
                                                "TARGET_INTAKE_TERMINAL_NO_COMMIT_EVENT_LINEAGE_INVALID"));
        when(roomMessageRow.getId()).thenReturn(roomMessage.eventId());

        var liveApplied = activities.convergeTargetIntakeTerminalNoCommit(liveConvergence);
        assertThat(liveApplied.outcome()).isEqualTo(TerminalNoCommitOutcome.TERMINALIZED);
        assertThat(liveApplied.lastCaseEventSequence()).isEqualTo(12);
        when(command.getCommandStatus()).thenReturn(CommandStatus.FAILED);
        when(command.getStatusReasonCode()).thenReturn(liveV3.errorCode());
        when(command.getResultUri()).thenReturn(liveV3.receiptUri());
        when(command.getResultSha256()).thenReturn(liveV3.receiptSha256());
        when(epoch.getProcessRevision()).thenReturn(7L);
        when(epoch.getRoomRevision()).thenReturn(7L);
        when(projection.getProcessRevision()).thenReturn(7L);
        when(projection.getLastCommandSequence()).thenReturn(7L);
        when(projection.getLastCaseEventSequence()).thenReturn(12L);
        assertThat(activities.convergeTargetIntakeTerminalNoCommit(liveConvergence).outcome())
                .isEqualTo(TerminalNoCommitOutcome.IDEMPOTENT_REPLAY);
        verify(epochRepository, times(1)).advanceFencedEpoch(
                TENANT, CASE_ID, "INTAKE", 0L, 11L, 6L, 7L, 6L, 7L,
                caseWorkflowId, caseWorkflowRunId, authority.caseBuildId(), terminalAt);
        verify(projectionRepository, times(1)).advanceFencedProjection(
                TENANT, CASE_ID, 0L, 11L, 6L, 7L, "INTAKE", "INTAKE", "WAITING_PARTY",
                7L, 12L, null, caseWorkflowId, caseWorkflowRunId, authority.caseBuildId(),
                "urn:projection:6", "c".repeat(64), terminalAt);

        when(command.getCommandStatus()).thenReturn(CommandStatus.APPLIED);
        assertThatThrownBy(() -> activities.convergeTargetIntakeTerminalNoCommit(request))
                .isInstanceOf(ApplicationFailure.class);
        assertThatThrownBy(
                        () ->
                                activities.convergeTargetIntakeTerminalNoCommit(
                                        new ConvergeTargetIntakeTerminalNoCommit(
                                                request.schemaVersion(),
                                                terminalAuthority("OTHER_TERMINAL_ERROR"),
                                                caseWorkflowId,
                                                caseWorkflowRunId,
                                                authority.caseBuildId())))
                .isInstanceOf(ApplicationFailure.class);
        verify(epochRepository, times(1)).advanceFencedEpoch(
                TENANT, CASE_ID, "INTAKE", 0L, 11L, 6L, 7L, 6L, 7L,
                caseWorkflowId, caseWorkflowRunId, authority.caseBuildId(), terminalAt);

        CaseCommandEntity terminalized = acceptedCommand(authority, terminalAt.minusSeconds(1));
        terminalized.markAcceptedOrchestrationTerminalNoCommit(
                authority.errorCode(),
                authority.receiptUri(),
                authority.receiptSha256(),
                terminalAt);
        assertThatThrownBy(
                        () ->
                                terminalized.markApplied(
                                        "urn:late-formal-success",
                                        "f".repeat(64),
                                        terminalAt.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("command cannot transition to APPLIED");
        assertThat(terminalized.getCommandStatus()).isEqualTo(CommandStatus.FAILED);
        assertThat(terminalized.getResultUri()).isEqualTo(authority.receiptUri());
        assertThat(terminalized.getResultSha256()).isEqualTo(authority.receiptSha256());
        assertThat(terminalized.getAppliedAt()).isNull();
    }

    @Test
    void finalizationRejectedCompletedAuditConvergesExactTerminalNoCommitEvidence()
            throws Exception {
        String caseWorkflowId = "case-process:" + TENANT + ":" + CASE_ID;
        String caseWorkflowRunId = "case-run-finalization-rejected";
        TargetIntakeCommandTerminalNoCommit authority =
                terminalAuthority("INTAKE_RESPONDENT_MATRIX_NOT_READY");
        OffsetDateTime terminalAt =
                OffsetDateTime.ofInstant(authority.terminalAt(), ZoneOffset.UTC);

        CaseCommandRepository commandRepository = mock(CaseCommandRepository.class);
        CaseRoomEpochRepository epochRepository = mock(CaseRoomEpochRepository.class);
        CaseProcessProjectionRepository projectionRepository =
                mock(CaseProcessProjectionRepository.class);
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentRunAttemptRepository attemptRepository = mock(AgentRunAttemptRepository.class);
        TargetIntakeCommandMaterialStore materialStore =
                mock(TargetIntakeCommandMaterialStore.class);
        ProductionActivationLedger activationLedger = mock(ProductionActivationLedger.class);
        ObjectMapper mapper = mock(ObjectMapper.class);

        CaseCommandEntity command = mock(CaseCommandEntity.class);
        when(commandRepository.findByTenantSurrogateAndCommandIdForUpdate(TENANT, COMMAND_ID))
                .thenReturn(Optional.of(command));
        when(command.getCaseId()).thenReturn(CASE_ID);
        when(command.getCaseCommandSequence()).thenReturn(7L);
        when(command.getRequestHash()).thenReturn(authority.commandRequestHash());
        when(command.getRoomType()).thenReturn(RoomType.INTAKE);
        when(command.getRoomEpoch()).thenReturn(0L);
        when(command.getExpectedProcessRevision()).thenReturn(6L);
        when(command.getPayloadUri()).thenReturn(authority.messageRef());
        when(command.getPayloadSha256()).thenReturn(authority.messageHash());
        when(command.getCommandStatus()).thenReturn(CommandStatus.ORCHESTRATION_ACCEPTED);
        when(command.getStatusReasonCode()).thenReturn(authority.errorCode());
        when(command.getResultUri()).thenReturn(authority.receiptUri());
        when(command.getResultSha256()).thenReturn(authority.receiptSha256());

        TargetIntakeCommandMaterialStore.MaterialSnapshot material =
                mock(TargetIntakeCommandMaterialStore.MaterialSnapshot.class);
        ProductionActivationLedger.CommandAdmission admission =
                mock(ProductionActivationLedger.CommandAdmission.class);
        IntakeCommandExecutionContext context = mock(IntakeCommandExecutionContext.class);
        IntakeTargetAgentRunContext target = mock(IntakeTargetAgentRunContext.class);
        ExecuteAgentRunRequest agentRequest = mock(ExecuteAgentRunRequest.class);
        RoomGraphCommand graph = mock(RoomGraphCommand.class);
        when(materialStore.readByRoute(any())).thenReturn(Optional.of(material));
        when(material.admission()).thenReturn(admission);
        when(material.admissionId()).thenReturn("admission-finalization-rejected");
        when(material.context()).thenReturn(context);
        when(context.targetAgentRun()).thenReturn(target);
        when(admission.activationId()).thenReturn(authority.activationId());
        when(admission.manifestHash()).thenReturn(authority.activationManifestHash());
        when(admission.tenantSurrogate()).thenReturn(TENANT);
        when(admission.caseId()).thenReturn(CASE_ID);
        when(admission.commandId()).thenReturn(COMMAND_ID);
        when(admission.commandHash()).thenReturn(authority.commandHash());
        when(admission.commandEnvelopeHash()).thenReturn(authority.commandEnvelopeHash());
        when(admission.roomEpoch()).thenReturn(0L);
        when(admission.roomFencingToken()).thenReturn(11L);
        when(target.activationId()).thenReturn(authority.activationId());
        when(target.activationManifestHash()).thenReturn(authority.activationManifestHash());
        when(target.caseBuildId()).thenReturn(authority.caseBuildId());
        when(target.controlBuildId()).thenReturn(authority.controlBuildId());
        when(target.agentBuildId()).thenReturn(authority.agentBuildId());
        when(target.graphBindingHash()).thenReturn(authority.graphBindingHash());
        when(target.graphCodeBuildId()).thenReturn(authority.graphCodeBuildId());
        when(target.commandHash()).thenReturn(authority.commandHash());
        when(target.commandEnvelopeHash()).thenReturn(authority.commandEnvelopeHash());
        when(target.expectedProcessRevision()).thenReturn(6L);
        when(target.expectedRoomRevision()).thenReturn(6L);
        when(target.request()).thenReturn(agentRequest);
        when(agentRequest.logicalInputHash()).thenReturn(authority.logicalInputHash());
        when(agentRequest.logicalRunId()).thenReturn(authority.logicalRunId());
        when(agentRequest.attemptId()).thenReturn(authority.rootAttemptId());
        when(agentRequest.command()).thenReturn(graph);

        ProductionActivationLedger.CommandAdmissionSnapshot admissionSnapshot =
                mock(ProductionActivationLedger.CommandAdmissionSnapshot.class);
        when(activationLedger.queryCommandAdmission(authority.activationId(), COMMAND_ID))
                .thenReturn(Optional.of(admissionSnapshot));
        when(admissionSnapshot.admissionId()).thenReturn("admission-finalization-rejected");
        when(admissionSnapshot.activationManifestHash())
                .thenReturn(authority.activationManifestHash());
        when(admissionSnapshot.commandHash()).thenReturn(authority.commandHash());
        when(admissionSnapshot.commandEnvelopeHash())
                .thenReturn(authority.commandEnvelopeHash());
        when(admissionSnapshot.completed()).thenReturn(false);

        AgentRunEntity run = mock(AgentRunEntity.class);
        AgentRunAttemptEntity attempt = mock(AgentRunAttemptEntity.class);
        RoomGraphCommand.SnapshotRef eventRef = mock(RoomGraphCommand.SnapshotRef.class);
        RoomGraphResult completedGraph = mock(RoomGraphResult.class);
        String completedResultHash = "e".repeat(64);
        when(completedGraph.logicalRunId()).thenReturn(authority.logicalRunId());
        when(completedGraph.attemptId()).thenReturn(authority.terminalAttemptId());
        when(completedGraph.outputHash()).thenReturn(completedResultHash);
        when(completedGraph.commandId()).thenReturn(COMMAND_ID);
        ExecuteAgentRunResult completedAudit =
                new ExecuteAgentRunResult(
                        ExecuteAgentRunResult.SCHEMA_VERSION,
                        authority.logicalRunId(),
                        authority.logicalRunId(),
                        authority.terminalAttemptId(),
                        authority.terminalAttemptNo(),
                        ExecuteAgentRunResult.Outcome.COMPLETED,
                        completedGraph,
                        completedResultHash,
                        authority.lastSequenceNo() - 1,
                        true,
                        null,
                        false,
                        null,
                        authority.terminalAt());
        when(runRepository.findByIdForUpdate(authority.logicalRunId()))
                .thenReturn(Optional.of(run));
        when(attemptRepository.findAllByAgentRunIdOrderByAttemptNoAsc(authority.logicalRunId()))
                .thenReturn(List.of(attempt));
        when(attempt.getId()).thenReturn(authority.rootAttemptId());
        when(attempt.getAgentRunId()).thenReturn(authority.logicalRunId());
        when(attempt.getAttemptNo()).thenReturn(1L);
        when(attempt.getPreviousAttemptId()).thenReturn(null);
        when(attempt.getAttemptStatus()).thenReturn(AgentRunAttemptStatus.ABORTED);
        when(attempt.getCommandId()).thenReturn(COMMAND_ID);
        when(attempt.getRequestHash()).thenReturn(authority.agentRunExecutionRequestHash());
        when(attempt.getCommandRequestHash()).thenReturn(authority.agentRunExecutionRequestHash());
        when(attempt.getCommandJson()).thenReturn("finalization-rejected-command-json");
        when(attempt.getResultJson()).thenReturn("completed-audit-json");
        when(attempt.getResultHash()).thenReturn(completedResultHash);
        when(attempt.getLastSequenceNo()).thenReturn(authority.lastSequenceNo());
        when(attempt.isPublicOutputEmitted()).thenReturn(true);
        when(attempt.isFinalFrameObserved()).thenReturn(true);
        when(attempt.getErrorCode()).thenReturn(authority.errorCode());
        when(attempt.getErrorRetryable()).thenReturn(false);
        when(attempt.getTerminationCode()).thenReturn("FAIL_LOGICAL_RUN");
        when(attempt.getCompletedAt()).thenReturn(terminalAt);
        when(mapper.readValue("finalization-rejected-command-json", RoomGraphCommand.class))
                .thenReturn(graph);
        when(mapper.readValue("completed-audit-json", ExecuteAgentRunResult.class))
                .thenReturn(completedAudit);
        when(graph.requestHash()).thenReturn(authority.agentRunExecutionRequestHash());
        when(graph.logicalRunId()).thenReturn(authority.logicalRunId());
        when(graph.attemptId()).thenReturn(authority.rootAttemptId());
        when(graph.commandId()).thenReturn(COMMAND_ID);
        when(graph.tenantSurrogate()).thenReturn(TENANT);
        when(graph.caseId()).thenReturn(CASE_ID);
        when(graph.roomType()).thenReturn(RoomType.INTAKE);
        when(graph.roomEpoch()).thenReturn(0L);
        when(graph.processRevision()).thenReturn(6L);
        when(graph.eventRef()).thenReturn(eventRef);
        when(eventRef.artifactId()).thenReturn(authority.messageId());
        when(eventRef.uri()).thenReturn(authority.messageRef());
        when(eventRef.sha256()).thenReturn(authority.messageHash());
        when(run.getTenantSurrogate()).thenReturn(TENANT);
        when(run.getCaseId()).thenReturn(CASE_ID);
        when(run.getProtocol()).thenReturn(AgentRunProtocol.V3.wireValue());
        when(run.getExecutorKind()).thenReturn(AgentRunExecutorKind.TEMPORAL_ACTIVITY);
        when(run.getRoomType()).thenReturn(RoomType.INTAKE);
        when(run.getRoomEpoch()).thenReturn(0L);
        when(run.getFencingToken()).thenReturn(11L);
        when(run.getProcessRevision()).thenReturn(6L);
        when(run.getRequestHash()).thenReturn(authority.agentRunExecutionRequestHash());
        when(run.getLogicalInputHash()).thenReturn(authority.logicalInputHash());
        when(run.getRunStatus()).thenReturn("ABORTED");
        when(run.getStopReason()).thenReturn("FINALIZATION_REJECTED");
        when(run.getErrorCode()).thenReturn(authority.errorCode());
        when(run.getErrorRetryable()).thenReturn(false);
        when(run.getCompletedAt()).thenReturn(terminalAt);
        when(run.getFinalizationStatus()).thenReturn("UNCOMMITTED");
        when(run.getResultReadyAttemptId()).thenReturn(authority.terminalAttemptId());
        when(run.getFinalResultHash()).thenReturn(completedResultHash);
        when(run.getFinalStreamSequenceNo()).thenReturn(null);

        CaseRoomEpochEntity epoch = mock(CaseRoomEpochEntity.class);
        CaseProcessProjectionEntity projection = mock(CaseProcessProjectionEntity.class);
        when(epochRepository.findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                        CASE_ID, RoomType.INTAKE, 0L))
                .thenReturn(Optional.of(epoch));
        when(projectionRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(projection));
        stubSourceCoordinates(epoch, projection, authority, caseWorkflowId, caseWorkflowRunId);
        when(epochRepository.advanceFencedEpoch(
                        TENANT, CASE_ID, "INTAKE", 0L, 11L, 6L, 7L, 6L, 7L,
                        caseWorkflowId, caseWorkflowRunId, authority.caseBuildId(), terminalAt))
                .thenReturn(1);
        when(projectionRepository.advanceFencedProjection(
                        TENANT, CASE_ID, 0L, 11L, 6L, 7L, "INTAKE", "INTAKE", "WAITING_PARTY",
                        7L, 15L, null, caseWorkflowId, caseWorkflowRunId,
                        authority.caseBuildId(), "urn:projection:6", "c".repeat(64), terminalAt))
                .thenReturn(1);

        CaseProcessLedgerActivitiesImpl activities =
                new CaseProcessLedgerActivitiesImpl(
                        commandRepository,
                        mock(CaseTimelineEventRepository.class),
                        mock(CaseRoomRepository.class),
                        epochRepository,
                        projectionRepository,
                        mock(ProcessReconciliationIssueRepository.class),
                        runRepository,
                        attemptRepository,
                        materialStore,
                        activationLedger,
                        mapper,
                        Clock.systemUTC());
        ConvergeTargetIntakeTerminalNoCommit convergence =
                new ConvergeTargetIntakeTerminalNoCommit(
                        "converge-target-intake-terminal-no-commit.v1",
                        authority,
                        caseWorkflowId,
                        caseWorkflowRunId,
                        authority.caseBuildId());

        assertThat(
                        activities
                                .resolveTargetIntakeTerminalNoCommit(
                                        new ResolveTargetIntakeTerminalNoCommit(
                                                "resolve-target-intake-terminal-no-commit.v1",
                                                authority))
                                .authority())
                .isEqualTo(authority);
        var applied = activities.convergeTargetIntakeTerminalNoCommit(convergence);
        assertThat(applied.outcome()).isEqualTo(TerminalNoCommitOutcome.TERMINALIZED);
        verify(command)
                .markAcceptedOrchestrationTerminalNoCommit(
                        authority.errorCode(),
                        authority.receiptUri(),
                        authority.receiptSha256(),
                        terminalAt);

        when(epoch.getProcessRevision()).thenReturn(7L);
        when(epoch.getRoomRevision()).thenReturn(7L);
        when(projection.getProcessRevision()).thenReturn(7L);
        when(projection.getLastCommandSequence()).thenReturn(7L);
        when(projection.getLastCaseEventSequence()).thenReturn(15L);
        when(command.getCommandStatus()).thenReturn(CommandStatus.FAILED);
        assertThat(activities.convergeTargetIntakeTerminalNoCommit(convergence).outcome())
                .isEqualTo(TerminalNoCommitOutcome.IDEMPOTENT_REPLAY);
        verify(epochRepository, times(1)).advanceFencedEpoch(
                TENANT, CASE_ID, "INTAKE", 0L, 11L, 6L, 7L, 6L, 7L,
                caseWorkflowId, caseWorkflowRunId, authority.caseBuildId(), terminalAt);

        when(run.getStopReason()).thenReturn("OTHER_TERMINAL_REASON");
        when(command.getCommandStatus()).thenReturn(CommandStatus.ORCHESTRATION_ACCEPTED);
        assertThatThrownBy(
                        () ->
                                activities.resolveTargetIntakeTerminalNoCommit(
                                        new ResolveTargetIntakeTerminalNoCommit(
                                                "resolve-target-intake-terminal-no-commit.v1",
                                                authority)))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(
                        failure ->
                                assertThat(((ApplicationFailure) failure).getType())
                                        .isEqualTo(
                                                "TARGET_INTAKE_TERMINAL_NO_COMMIT_RESULT_INVALID"));
        when(run.getStopReason()).thenReturn("FINALIZATION_REJECTED");
        when(admissionSnapshot.completed()).thenReturn(true);
        assertThatThrownBy(
                        () ->
                                activities.resolveTargetIntakeTerminalNoCommit(
                                        new ResolveTargetIntakeTerminalNoCommit(
                                                "resolve-target-intake-terminal-no-commit.v1",
                                                authority)))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(
                        failure ->
                                assertThat(((ApplicationFailure) failure).getType())
                                        .isEqualTo(
                                                "TARGET_INTAKE_TERMINAL_NO_COMMIT_ADMISSION_CONFLICT"));
    }

    @Test
    void evidenceTerminalNoCommitBindsLaterAttemptAndAdvancesOnlyOneCommandCursor()
            throws Exception {
        TargetRoomAgentRunTerminalNoCommit authority = evidenceTerminalAuthority();
        CaseCommandRef source = authority.command();
        String caseWorkflowId =
                CaseProcessWorkflowProtocol.caseWorkflowId(
                        source.tenantSurrogate(), source.caseId());
        String caseWorkflowRunId = "case-run-evidence-terminal";
        String caseWorkflowBuildId = "case-build-evidence-terminal";
        OffsetDateTime terminalAt =
                OffsetDateTime.ofInstant(authority.terminalAt(), ZoneOffset.UTC);

        CaseCommandRepository commandRepository = mock(CaseCommandRepository.class);
        CaseTimelineEventRepository eventRepository = mock(CaseTimelineEventRepository.class);
        CaseRoomRepository roomRepository = mock(CaseRoomRepository.class);
        CaseRoomEpochRepository epochRepository = mock(CaseRoomEpochRepository.class);
        CaseProcessProjectionRepository projectionRepository =
                mock(CaseProcessProjectionRepository.class);
        ProcessReconciliationIssueRepository issueRepository =
                mock(ProcessReconciliationIssueRepository.class);
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentRunAttemptRepository attemptRepository = mock(AgentRunAttemptRepository.class);
        AgentRunStreamEventRepository streamEventRepository =
                mock(AgentRunStreamEventRepository.class);
        TargetEvidenceCommandMaterialStore materialStore =
                mock(TargetEvidenceCommandMaterialStore.class);
        ProductionActivationLedger activationLedger = mock(ProductionActivationLedger.class);
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

        CaseCommandEntity command =
                CaseCommandEntity.pending(
                        "case-command-evidence-terminal",
                        source,
                        mapper.writeValueAsString(source.actorRef().actorScopes()),
                        terminalAt.minusSeconds(2));
        command.markOrchestrationAccepted(terminalAt.minusSeconds(1));
        when(commandRepository.findByTenantSurrogateAndCommandIdForUpdate(TENANT, COMMAND_ID))
                .thenReturn(Optional.of(command));

        String activationId = "p9act.v1." + "1".repeat(32);
        String activationManifestHash = "2".repeat(64);
        var admission = mock(ProductionActivationLedger.CommandAdmission.class);
        when(admission.activationId()).thenReturn(activationId);
        when(admission.manifestHash()).thenReturn(activationManifestHash);
        when(admission.tenantSurrogate()).thenReturn(TENANT);
        when(admission.caseId()).thenReturn(CASE_ID);
        when(admission.commandId()).thenReturn(COMMAND_ID);
        when(admission.commandHash()).thenReturn(authority.commandHash());
        when(admission.commandEnvelopeHash()).thenReturn(authority.commandEnvelopeHash());
        when(admission.roomEpoch()).thenReturn(source.roomEpoch());
        when(admission.roomFencingToken()).thenReturn(authority.roomFencingToken());
        TargetEvidenceCommandMaterial material = mock(TargetEvidenceCommandMaterial.class);
        when(material.executionLane()).thenReturn(TargetEvidenceCommandMaterial.TARGET_LANE);
        when(material.activationId()).thenReturn(activationId);
        when(material.activationManifestHash()).thenReturn(activationManifestHash);
        when(material.roomFencingToken()).thenReturn(authority.roomFencingToken());
        when(material.expectedProcessRevision()).thenReturn(source.expectedProcessRevision());
        when(material.expectedRoomRevision()).thenReturn(authority.expectedRoomRevision());
        when(material.commandHash()).thenReturn(authority.commandHash());
        when(material.commandEnvelopeHash()).thenReturn(authority.commandEnvelopeHash());
        when(material.caseCommandRequestHash()).thenReturn(source.requestHash());
        when(material.request()).thenReturn(authority.rootRequest());
        TargetEvidenceCommandMaterialStore.MaterialSnapshot materialSnapshot =
                new TargetEvidenceCommandMaterialStore.MaterialSnapshot(
                        "admission-evidence-terminal",
                        admission,
                        material,
                        "3".repeat(64),
                        authority.terminalAt().minusSeconds(2));
        when(materialStore.readByRoute(any())).thenReturn(Optional.of(materialSnapshot));

        var durableAdmission =
                mock(ProductionActivationLedger.CommandAdmissionSnapshot.class);
        when(activationLedger.queryCommandAdmission(activationId, COMMAND_ID))
                .thenReturn(Optional.of(durableAdmission));
        when(durableAdmission.admissionId()).thenReturn(materialSnapshot.admissionId());
        when(durableAdmission.activationManifestHash()).thenReturn(activationManifestHash);
        when(durableAdmission.tenantSurrogate()).thenReturn(TENANT);
        when(durableAdmission.caseId()).thenReturn(CASE_ID);
        when(durableAdmission.commandId()).thenReturn(COMMAND_ID);
        when(durableAdmission.commandHash()).thenReturn(authority.commandHash());
        when(durableAdmission.commandEnvelopeHash())
                .thenReturn(authority.commandEnvelopeHash());
        when(durableAdmission.roomEpoch()).thenReturn(source.roomEpoch());
        when(durableAdmission.roomFencingToken()).thenReturn(authority.roomFencingToken());
        when(durableAdmission.completed()).thenReturn(false);

        RoomGraphCommand rootCommand = authority.rootRequest().command();
        AgentRunEntity run = AgentRunEntity.logicalV2(new CreateLogicalRun(
                authority.rootRequest().logicalRunId(),
                TENANT,
                CASE_ID,
                "ROOM_EVIDENCE_TERMINAL",
                "EVIDENCE_OPENING",
                "evidence-terminal-no-commit",
                AgentRunProtocol.V2,
                AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                "EPOCH_EVIDENCE_TERMINAL",
                RoomType.EVIDENCE,
                source.roomEpoch(),
                source.expectedProcessRevision(),
                authority.roomFencingToken(),
                rootCommand.requestHash(),
                authority.rootRequest().logicalInputHash(),
                3,
                rootCommand.deadlineAt(),
                authority.terminalAt().minusSeconds(3)));
        run.markV2AttemptStarted();
        run.markFailed(
                authority.terminalErrorCode(),
                AgentRunEntity.V2_LOGICAL_FAILURE_MESSAGE,
                false,
                null);
        run.markV2AttemptFailed(
                authority.terminalAttemptStatus(), false, authority.terminalAt());
        clearLegacyFailureProjection(run);
        AgentRunAttemptEntity rootAttempt = mock(AgentRunAttemptEntity.class);
        AgentRunAttemptEntity intermediateAttempt = mock(AgentRunAttemptEntity.class);
        AgentRunAttemptEntity terminalAttempt = mock(AgentRunAttemptEntity.class);
        String intermediateAttemptId = authority.rootRequest().logicalRunId() + ":2";
        RoomGraphCommand intermediateCommand =
                withAttemptAuthority(
                        rootCommand,
                        intermediateAttemptId,
                        EVIDENCE_ATTEMPT_TWO_COMMAND_ID,
                        "b".repeat(64),
                        "nonce-v2",
                        new RoomGraphCommand.RetryBudget(1, 1, 0));
        RoomGraphCommand terminalCommand =
                withAttemptAuthority(
                        rootCommand,
                        authority.terminalResult().attemptId(),
                        EVIDENCE_ATTEMPT_THREE_COMMAND_ID,
                        "c".repeat(64),
                        "nonce-v3",
                        new RoomGraphCommand.RetryBudget(0, 1, 0));
        ExecuteAgentRunResult rootFailure =
                retryableEvidenceFailure(
                        authority.rootRequest().logicalRunId(),
                        authority.rootRequest().attemptId(),
                        1,
                        1,
                        authority.terminalAt().minusSeconds(2));
        ExecuteAgentRunResult validIntermediateFailure =
                retryableEvidenceFailure(
                        authority.rootRequest().logicalRunId(),
                        intermediateAttemptId,
                        2,
                        2,
                        authority.terminalAt().minusSeconds(1));
        when(runRepository.findByIdForUpdate(authority.rootRequest().logicalRunId()))
                .thenReturn(Optional.of(run));
        when(attemptRepository.findAllByAgentRunIdOrderByAttemptNoAsc(
                        authority.rootRequest().logicalRunId()))
                .thenReturn(List.of(rootAttempt, intermediateAttempt, terminalAttempt));
        stubEvidenceAttemptCommand(
                rootAttempt,
                rootCommand,
                1,
                null,
                authority.rootRequest().logicalInputHash(),
                mapper);
        stubRetryableEvidenceFailure(rootAttempt, rootFailure, mapper);
        stubEvidenceAttemptCommand(
                intermediateAttempt,
                intermediateCommand,
                2,
                authority.rootRequest().attemptId(),
                authority.rootRequest().logicalInputHash(),
                mapper);
        AtomicReference<String> intermediateCommandJson =
                new AtomicReference<>(mapper.writeValueAsString(intermediateCommand));
        when(intermediateAttempt.getCommandJson())
                .thenAnswer(ignored -> intermediateCommandJson.get());
        AtomicReference<ExecuteAgentRunResult> intermediateFailure =
                new AtomicReference<>(validIntermediateFailure);
        when(intermediateAttempt.getAttemptStatus()).thenReturn(AgentRunAttemptStatus.FAILED);
        when(intermediateAttempt.getResultJson())
                .thenAnswer(ignored -> mapper.writeValueAsString(intermediateFailure.get()));
        when(intermediateAttempt.getResultHash()).thenReturn(null);
        when(intermediateAttempt.getLastSequenceNo())
                .thenAnswer(ignored -> intermediateFailure.get().lastSequenceNo());
        when(intermediateAttempt.isPublicOutputEmitted())
                .thenAnswer(ignored -> intermediateFailure.get().publicOutputEmitted());
        when(intermediateAttempt.getErrorCode())
                .thenAnswer(ignored -> intermediateFailure.get().errorCode());
        when(intermediateAttempt.getErrorRetryable())
                .thenAnswer(ignored -> intermediateFailure.get().retryable());
        when(intermediateAttempt.getTerminationCode())
                .thenAnswer(ignored -> intermediateFailure.get().recoveryAction().name());
        when(intermediateAttempt.getCompletedAt())
                .thenAnswer(
                        ignored ->
                                OffsetDateTime.ofInstant(
                                        intermediateFailure.get().completedAt(),
                                        ZoneOffset.UTC));
        stubEvidenceAttemptCommand(
                terminalAttempt,
                terminalCommand,
                3,
                intermediateAttemptId,
                authority.rootRequest().logicalInputHash(),
                mapper);
        AtomicReference<String> terminalPrevious =
                new AtomicReference<>(intermediateAttemptId);
        AtomicBoolean terminalFinalFrame = new AtomicBoolean(false);
        when(terminalAttempt.getPreviousAttemptId())
                .thenAnswer(ignored -> terminalPrevious.get());
        when(terminalAttempt.getAttemptStatus()).thenReturn(authority.terminalAttemptStatus());
        when(terminalAttempt.getResultJson())
                .thenReturn(mapper.writeValueAsString(authority.terminalResult()));
        when(terminalAttempt.getResultHash()).thenReturn(authority.terminalResult().resultHash());
        when(terminalAttempt.getLastSequenceNo()).thenReturn(authority.terminalLastSequenceNo());
        when(terminalAttempt.isPublicOutputEmitted())
                .thenReturn(authority.terminalResult().publicOutputEmitted());
        when(terminalAttempt.isFinalFrameObserved())
                .thenAnswer(ignored -> terminalFinalFrame.get());
        when(terminalAttempt.getErrorCode()).thenReturn(authority.terminalErrorCode());
        when(terminalAttempt.getErrorRetryable()).thenReturn(false);
        when(terminalAttempt.getTerminationCode())
                .thenReturn(authority.terminalRecoveryAction().name());
        Instant terminalAttemptStartedAt = authority.terminalAt().minusSeconds(1);
        Instant terminalErrorOccurredAt = authority.terminalAt().minusNanos(23_025_000);
        when(terminalAttempt.getStartedAt())
                .thenReturn(OffsetDateTime.ofInstant(terminalAttemptStartedAt, ZoneOffset.UTC));
        when(terminalAttempt.getCompletedAt()).thenReturn(terminalAt);
        assertThat(terminalErrorOccurredAt)
                .isAfterOrEqualTo(terminalAttemptStartedAt)
                .isBefore(authority.terminalAt());
        AtomicLong streamHighWatermark =
                new AtomicLong(authority.terminalLastSequenceNo());
        AtomicReference<AgentRunStreamEventEntity> terminalStreamEvent =
                new AtomicReference<>(evidenceFailureTerminalEvent(
                        authority, terminalCommand, terminalErrorOccurredAt, mapper));
        when(streamEventRepository.findMaxV2Sequence(
                        run.getId(), terminalAttempt.getId()))
                .thenAnswer(ignored -> streamHighWatermark.get());
        when(streamEventRepository.findV2Event(
                        run.getId(),
                        terminalAttempt.getId(),
                        authority.terminalLastSequenceNo()))
                .thenAnswer(ignored -> Optional.of(terminalStreamEvent.get()));

        CaseRoomEpochEntity epoch = mock(CaseRoomEpochEntity.class);
        CaseProcessProjectionEntity projection = mock(CaseProcessProjectionEntity.class);
        when(epochRepository.findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                        CASE_ID, RoomType.EVIDENCE, source.roomEpoch()))
                .thenReturn(Optional.of(epoch));
        when(projectionRepository.findByIdForUpdate(CASE_ID))
                .thenReturn(Optional.of(projection));
        when(epoch.getTenantSurrogate()).thenReturn(TENANT);
        when(epoch.getCaseId()).thenReturn(CASE_ID);
        when(epoch.getRoomType()).thenReturn(RoomType.EVIDENCE);
        when(epoch.getRoomEpoch()).thenReturn(source.roomEpoch());
        when(epoch.getWriterMode()).thenReturn(WriterMode.TEMPORAL);
        when(epoch.getLifecycleStatus()).thenReturn(EpochLifecycleStatus.ACTIVE);
        when(epoch.getProvisioningStatus()).thenReturn(EpochProvisioningStatus.READY);
        when(epoch.getFencingToken()).thenReturn(authority.roomFencingToken());
        when(epoch.getProcessRevision()).thenReturn(source.expectedProcessRevision());
        when(epoch.getRoomRevision()).thenReturn(authority.expectedRoomRevision());
        when(epoch.getTemporalWorkflowId()).thenReturn(caseWorkflowId);
        when(epoch.getTemporalRunId()).thenReturn(caseWorkflowRunId);
        when(epoch.getTemporalBuildId()).thenReturn(caseWorkflowBuildId);
        when(epoch.getRoomTemporalWorkflowId()).thenReturn(authority.roomWorkflowId());
        when(epoch.getRoomTemporalRunId()).thenReturn(authority.roomWorkflowRunId());
        when(epoch.getRoomWorkflowBuildId()).thenReturn(authority.roomWorkflowBuildId());
        when(projection.getCaseId()).thenReturn(CASE_ID);
        when(projection.getTenantSurrogate()).thenReturn(TENANT);
        when(projection.getWriterMode()).thenReturn(WriterMode.TEMPORAL);
        when(projection.getWriterActivationStatus()).thenReturn(WriterActivationStatus.READY);
        when(projection.getRoomEpoch()).thenReturn(source.roomEpoch());
        when(projection.getFencingToken()).thenReturn(authority.roomFencingToken());
        when(projection.getProcessRevision()).thenReturn(source.expectedProcessRevision());
        when(projection.getTemporalWorkflowId()).thenReturn(caseWorkflowId);
        when(projection.getTemporalRunId()).thenReturn(caseWorkflowRunId);
        when(projection.getTemporalBuildId()).thenReturn(caseWorkflowBuildId);
        when(projection.getCurrentRoom()).thenReturn(RoomType.EVIDENCE.name());
        when(projection.getMacroPhase()).thenReturn("EVIDENCE");
        when(projection.getRoomPhase()).thenReturn("WAITING_PARTIES");
        AtomicLong lastCommandSequence = new AtomicLong(source.caseCommandSequence() - 1);
        AtomicLong lastCaseEventSequence =
                new AtomicLong(authority.expectedLastCaseEventSequence());
        when(projection.getLastCommandSequence())
                .thenAnswer(ignored -> lastCommandSequence.get());
        when(projection.getLastCaseEventSequence())
                .thenAnswer(ignored -> lastCaseEventSequence.get());
        when(projectionRepository.advanceTerminalNoCommitCommandCursor(
                        TENANT,
                        CASE_ID,
                        source.roomEpoch(),
                        authority.roomFencingToken(),
                        source.expectedProcessRevision(),
                        authority.expectedRoomRevision(),
                        source.caseCommandSequence() - 1,
                        source.caseCommandSequence(),
                        authority.expectedLastCaseEventSequence(),
                        caseWorkflowId,
                        caseWorkflowRunId,
                        caseWorkflowBuildId,
                        terminalAt))
                .thenReturn(1);

        CaseProcessLedgerActivitiesImpl activities =
                new CaseProcessLedgerActivitiesImpl(
                        commandRepository,
                        eventRepository,
                        roomRepository,
                        epochRepository,
                        projectionRepository,
                        issueRepository,
                        runRepository,
                        attemptRepository,
                        streamEventRepository,
                        null,
                        materialStore,
                        activationLedger,
                        mapper,
                        Clock.systemUTC());
        ConvergeTargetEvidenceTerminalNoCommit request =
                new ConvergeTargetEvidenceTerminalNoCommit(
                        "converge-target-evidence-terminal-no-commit.v1",
                        authority,
                        caseWorkflowId,
                        caseWorkflowRunId,
                        caseWorkflowBuildId);

        String sourceTraceparent = source.traceparent();
        String sourceTraceId = sourceTraceparent.substring(3, 35);
        String canonicalGraphTraceparent =
                "00-" + sourceTraceId + "-0000000000000001-01";
        assertThat(rootCommand.traceparent())
                .isEqualTo(canonicalGraphTraceparent)
                .isNotEqualTo(sourceTraceparent);
        assertThat(
                        List.of(
                                source.requestHash(),
                                authority.commandHash(),
                                authority.commandEnvelopeHash(),
                                rootCommand.requestHash()))
                .doesNotHaveDuplicates();

        assertLegacyNullFailureProjection(run, authority);

        record InvalidTraceBinding(
                String label, String sourceTraceparent, String graphTraceparent) {}
        List<InvalidTraceBinding> invalidTraceBindings =
                List.of(
                        new InvalidTraceBinding(
                                "different trace id",
                                sourceTraceparent,
                                "00-" + "3".repeat(32) + "-0000000000000001-01"),
                        new InvalidTraceBinding(
                                "different flags",
                                sourceTraceparent,
                                "00-" + sourceTraceId + "-0000000000000001-00"),
                        new InvalidTraceBinding(
                                "malformed source span",
                                "00-" + sourceTraceId + "-not-a-span-01",
                                canonicalGraphTraceparent),
                        new InvalidTraceBinding(
                                "zero source trace id",
                                "00-" + "0".repeat(32) + "-2222222222222222-01",
                                "00-" + "0".repeat(32) + "-0000000000000001-01"),
                        new InvalidTraceBinding(
                                "zero source span",
                                "00-" + sourceTraceId + "-" + "0".repeat(16) + "-01",
                                canonicalGraphTraceparent),
                        new InvalidTraceBinding(
                                "noncanonical graph span",
                                sourceTraceparent,
                                "00-" + sourceTraceId + "-0000000000000002-01"));
        for (InvalidTraceBinding invalid : invalidTraceBindings) {
            assertThatThrownBy(
                            () ->
                                    evidenceAuthorityWithTraceBinding(
                                            authority,
                                            invalid.sourceTraceparent(),
                                            invalid.graphTraceparent()))
                    .as(invalid.label())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(command.getCommandStatus())
                    .isEqualTo(CommandStatus.ORCHESTRATION_ACCEPTED);
            assertLegacyNullFailureProjection(run, authority);
        }

        RoomGraphCommand.ActorScope actorDrift =
                new RoomGraphCommand.ActorScope(
                        "actor-evidence-foreign",
                        source.actorRef().actorRole(),
                        rootCommand.actorScope().audience(),
                        source.actorRef().actorScopes());
        RoomGraphCommand.ActorScope scopeDrift =
                new RoomGraphCommand.ActorScope(
                        source.actorRef().actorId(),
                        source.actorRef().actorRole(),
                        rootCommand.actorScope().audience(),
                        List.of("evidence:foreign"));
        RoomGraphCommand.SnapshotRef payloadDrift =
                new RoomGraphCommand.SnapshotRef(
                        rootCommand.eventRef().artifactId(),
                        rootCommand.eventRef().schemaVersion(),
                        rootCommand.eventRef().uri(),
                        "7".repeat(64),
                        rootCommand.eventRef().sizeBytes());
        record InvalidMaterialRoot(String label, ExecuteAgentRunRequest request) {}
        List<InvalidMaterialRoot> invalidMaterialRoots =
                List.of(
                        new InvalidMaterialRoot(
                                "root actor drift",
                                evidenceRootRequest(
                                        authority.rootRequest(),
                                        evidenceGraphCommand(
                                                rootCommand,
                                                actorDrift,
                                                rootCommand.eventRef(),
                                                rootCommand.deadlineAt(),
                                                rootCommand.traceparent(),
                                                rootCommand.requestHash()),
                                        authority.rootRequest().logicalInputHash())),
                        new InvalidMaterialRoot(
                                "root scope drift",
                                evidenceRootRequest(
                                        authority.rootRequest(),
                                        evidenceGraphCommand(
                                                rootCommand,
                                                scopeDrift,
                                                rootCommand.eventRef(),
                                                rootCommand.deadlineAt(),
                                                rootCommand.traceparent(),
                                                rootCommand.requestHash()),
                                        authority.rootRequest().logicalInputHash())),
                        new InvalidMaterialRoot(
                                "root deadline drift",
                                evidenceRootRequest(
                                        authority.rootRequest(),
                                        evidenceGraphCommand(
                                                rootCommand,
                                                rootCommand.actorScope(),
                                                rootCommand.eventRef(),
                                                rootCommand.deadlineAt().plusNanos(1),
                                                rootCommand.traceparent(),
                                                rootCommand.requestHash()),
                                        authority.rootRequest().logicalInputHash())),
                        new InvalidMaterialRoot(
                                "root payload drift",
                                evidenceRootRequest(
                                        authority.rootRequest(),
                                        evidenceGraphCommand(
                                                rootCommand,
                                                rootCommand.actorScope(),
                                                payloadDrift,
                                                rootCommand.deadlineAt(),
                                                rootCommand.traceparent(),
                                                rootCommand.requestHash()),
                                        authority.rootRequest().logicalInputHash())),
                        new InvalidMaterialRoot(
                                "root command request hash drift",
                                evidenceRootRequest(
                                        authority.rootRequest(),
                                        evidenceGraphCommand(
                                                rootCommand,
                                                rootCommand.actorScope(),
                                                rootCommand.eventRef(),
                                                rootCommand.deadlineAt(),
                                                rootCommand.traceparent(),
                                                "8".repeat(64)),
                                        authority.rootRequest().logicalInputHash())),
                        new InvalidMaterialRoot(
                                "root logical input hash drift",
                                evidenceRootRequest(
                                        authority.rootRequest(),
                                        rootCommand,
                                        "9".repeat(64))));
        for (InvalidMaterialRoot invalid : invalidMaterialRoots) {
            when(material.request()).thenReturn(invalid.request());
            assertApplicationFailureType(
                    () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                    "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_MATERIAL_MISMATCH");
            assertThat(command.getCommandStatus())
                    .isEqualTo(CommandStatus.ORCHESTRATION_ACCEPTED);
            assertLegacyNullFailureProjection(run, authority);
        }
        when(material.request()).thenReturn(authority.rootRequest());

        streamHighWatermark.decrementAndGet();
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID");
        streamHighWatermark.set(authority.terminalLastSequenceNo());
        assertLegacyNullFailureProjection(run, authority);

        AgentRunStreamEventEntity exactTerminalEvent = terminalStreamEvent.get();
        OffsetDateTime exactTerminalCreatedAt = exactTerminalEvent.getCreatedAt();
        ReflectionTestUtils.setField(
                exactTerminalEvent, "createdAt", exactTerminalCreatedAt.plusNanos(1));
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID");
        ReflectionTestUtils.setField(
                exactTerminalEvent, "createdAt", exactTerminalCreatedAt);
        assertLegacyNullFailureProjection(run, authority);

        terminalStreamEvent.set(evidenceFailureTerminalEvent(
                authority,
                terminalCommand,
                terminalAttemptStartedAt.minusNanos(1),
                mapper));
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID");
        terminalStreamEvent.set(exactTerminalEvent);
        assertLegacyNullFailureProjection(run, authority);

        terminalStreamEvent.set(evidenceFailureTerminalEvent(
                authority,
                terminalCommand,
                authority.terminalAt().plusNanos(1),
                mapper));
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID");
        terminalStreamEvent.set(exactTerminalEvent);
        assertLegacyNullFailureProjection(run, authority);

        String exactTerminalHash = exactTerminalEvent.getPayloadHash();
        ReflectionTestUtils.setField(exactTerminalEvent, "payloadHash", "9".repeat(64));
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID");
        ReflectionTestUtils.setField(exactTerminalEvent, "payloadHash", exactTerminalHash);
        assertLegacyNullFailureProjection(run, authority);

        ReflectionTestUtils.setField(
                exactTerminalEvent, "eventType", StreamEventType.FINAL.wireValue());
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID");
        ReflectionTestUtils.setField(
                exactTerminalEvent, "eventType", StreamEventType.ERROR.wireValue());
        assertLegacyNullFailureProjection(run, authority);

        TargetRoomAgentRunTerminalNoCommit hashDrift =
                copyEvidenceAuthority(authority, "9".repeat(64));
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(
                        new ConvergeTargetEvidenceTerminalNoCommit(
                                request.schemaVersion(),
                                hashDrift,
                                caseWorkflowId,
                                caseWorkflowRunId,
                                caseWorkflowBuildId)),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_MATERIAL_MISMATCH");
        assertLegacyNullFailureProjection(run, authority);

        ReflectionTestUtils.setField(command, "requestHash", "8".repeat(64));
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_COMMAND_MISMATCH");
        ReflectionTestUtils.setField(command, "requestHash", source.requestHash());
        assertLegacyNullFailureProjection(run, authority);

        RoomGraphCommand driftedIntermediateCommand =
                withProcessRevisionDrift(intermediateCommand);
        intermediateCommandJson.set(mapper.writeValueAsString(driftedIntermediateCommand));
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_LINEAGE_INVALID");
        assertThat(command.getCommandStatus()).isEqualTo(CommandStatus.ORCHESTRATION_ACCEPTED);
        assertLegacyNullFailureProjection(run, authority);
        intermediateCommandJson.set(mapper.writeValueAsString(intermediateCommand));

        ExecuteAgentRunResult invalidIntermediateFailure =
                new ExecuteAgentRunResult(
                        validIntermediateFailure.schemaVersion(),
                        validIntermediateFailure.agentRunId(),
                        validIntermediateFailure.logicalRunId(),
                        validIntermediateFailure.attemptId(),
                        validIntermediateFailure.attemptNo(),
                        ExecuteAgentRunResult.Outcome.FAILED,
                        null,
                        null,
                        validIntermediateFailure.lastSequenceNo(),
                        validIntermediateFailure.publicOutputEmitted(),
                        validIntermediateFailure.errorCode(),
                        false,
                        AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                        validIntermediateFailure.completedAt());
        intermediateFailure.set(invalidIntermediateFailure);
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_LINEAGE_INVALID");
        assertThat(command.getCommandStatus()).isEqualTo(CommandStatus.ORCHESTRATION_ACCEPTED);
        assertLegacyNullFailureProjection(run, authority);
        intermediateFailure.set(validIntermediateFailure);

        terminalPrevious.set("foreign-attempt");
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_LINEAGE_INVALID");
        terminalPrevious.set(intermediateAttemptId);
        assertLegacyNullFailureProjection(run, authority);
        terminalFinalFrame.set(true);
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_LINEAGE_INVALID");
        terminalFinalFrame.set(false);
        assertThat(command.getCommandStatus()).isEqualTo(CommandStatus.ORCHESTRATION_ACCEPTED);
        assertLegacyNullFailureProjection(run, authority);

        ReflectionTestUtils.setField(run, "runStatus", AgentRunAttemptStatus.ABORTED.name());
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID");
        ReflectionTestUtils.setField(run, "runStatus", authority.terminalAttemptStatus().name());
        assertLegacyNullFailureProjection(run, authority);

        OffsetDateTime exactCompletedAt = run.getCompletedAt();
        ReflectionTestUtils.setField(run, "completedAt", exactCompletedAt.plusSeconds(1));
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID");
        ReflectionTestUtils.setField(run, "completedAt", exactCompletedAt);
        assertLegacyNullFailureProjection(run, authority);

        ReflectionTestUtils.setField(run, "errorCode", "FOREIGN_TERMINAL_ERROR");
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID");
        assertThat(run.getErrorCode()).isEqualTo("FOREIGN_TERMINAL_ERROR");
        assertThat(run.getErrorRetryable()).isNull();
        assertThat(run.getErrorMessage()).isNull();
        assertThat(run.getStopReason()).isNull();
        ReflectionTestUtils.setField(run, "errorCode", null);

        ReflectionTestUtils.setField(run, "stopReason", "FOREIGN_TERMINAL_STOP");
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID");
        assertThat(run.getStopReason()).isEqualTo("FOREIGN_TERMINAL_STOP");
        assertThat(run.getErrorCode()).isNull();
        assertThat(run.getErrorRetryable()).isNull();
        assertThat(run.getErrorMessage()).isNull();
        ReflectionTestUtils.setField(run, "stopReason", null);
        assertLegacyNullFailureProjection(run, authority);

        verify(projectionRepository, never())
                .advanceTerminalNoCommitCommandCursor(
                        anyString(), anyString(), anyLong(), anyLong(), anyLong(),
                        anyLong(), anyLong(), anyLong(), anyLong(),
                        anyString(), anyString(), anyString(), any(OffsetDateTime.class));

        assertThat(run.repairLegacyV2TerminalFailureScalars(
                        authority.terminalAttemptStatus(),
                        authority.terminalErrorCode(),
                        authority.terminalAt()))
                .isTrue();
        OffsetDateTime canonicalUpdatedAt = run.getUpdatedAt();
        lastCaseEventSequence.incrementAndGet();
        assertApplicationFailureType(
                () -> activities.convergeTargetEvidenceTerminalNoCommit(request),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_SOURCE_STALE");
        assertCanonicalFailureProjection(run, authority);
        assertThat(run.getUpdatedAt()).isEqualTo(canonicalUpdatedAt);
        lastCaseEventSequence.set(authority.expectedLastCaseEventSequence());
        clearLegacyFailureProjection(run);
        assertLegacyNullFailureProjection(run, authority);

        OffsetDateTime durableRunCompletedAt = run.getCompletedAt();
        String durableFinalizationStatus = run.getFinalizationStatus();
        OffsetDateTime durableUpdatedAt = run.getUpdatedAt();
        var applied = activities.convergeTargetEvidenceTerminalNoCommit(request);
        assertThat(applied.outcome()).isEqualTo(TerminalNoCommitOutcome.TERMINALIZED);
        assertThat(applied.processRevision()).isEqualTo(source.expectedProcessRevision());
        assertThat(applied.roomRevision()).isEqualTo(authority.expectedRoomRevision());
        assertThat(applied.lastCommandSequence()).isEqualTo(source.caseCommandSequence());
        assertThat(applied.lastCaseEventSequence())
                .isEqualTo(authority.expectedLastCaseEventSequence());
        assertThat(command.getCommandStatus()).isEqualTo(CommandStatus.FAILED);
        assertCanonicalFailureProjection(run, authority);
        assertThat(run.getCompletedAt()).isEqualTo(durableRunCompletedAt);
        assertThat(run.getUpdatedAt()).isEqualTo(durableUpdatedAt);
        assertThat(run.getFinalizationStatus()).isEqualTo(durableFinalizationStatus);
        assertThat(run.getResultReadyAttemptId()).isNull();
        assertThat(run.getCommittedAttemptId()).isNull();
        assertThat(run.getFinalResultHash()).isNull();
        assertThat(run.getCommittedManifestId()).isNull();
        assertThat(run.getCommittedManifestHash()).isNull();
        assertThat(run.getFinalStreamSequenceNo()).isNull();
        assertThat(run.getFinalizedAt()).isNull();

        lastCommandSequence.set(source.caseCommandSequence());
        terminalStreamEvent.set(evidenceFailureTerminalEvent(
                authority, terminalCommand, authority.terminalAt(), mapper));
        var replay = activities.convergeTargetEvidenceTerminalNoCommit(request);
        assertThat(replay.outcome()).isEqualTo(TerminalNoCommitOutcome.IDEMPOTENT_REPLAY);
        assertCanonicalFailureProjection(run, authority);
        assertThat(run.getCompletedAt()).isEqualTo(durableRunCompletedAt);
        assertThat(run.getUpdatedAt()).isEqualTo(durableUpdatedAt);
        terminalStreamEvent.set(evidenceFailureTerminalEvent(
                authority, terminalCommand, terminalAttemptStartedAt, mapper));
        var lowerBoundaryReplay =
                activities.convergeTargetEvidenceTerminalNoCommit(request);
        assertThat(lowerBoundaryReplay.outcome())
                .isEqualTo(TerminalNoCommitOutcome.IDEMPOTENT_REPLAY);
        assertCanonicalFailureProjection(run, authority);
        assertThat(run.getCompletedAt()).isEqualTo(durableRunCompletedAt);
        assertThat(run.getUpdatedAt()).isEqualTo(durableUpdatedAt);
        verify(projectionRepository, times(1))
                .advanceTerminalNoCommitCommandCursor(
                        TENANT,
                        CASE_ID,
                        source.roomEpoch(),
                        authority.roomFencingToken(),
                        source.expectedProcessRevision(),
                        authority.expectedRoomRevision(),
                        source.caseCommandSequence() - 1,
                        source.caseCommandSequence(),
                        authority.expectedLastCaseEventSequence(),
                        caseWorkflowId,
                        caseWorkflowRunId,
                        caseWorkflowBuildId,
                        terminalAt);
        verifyNoInteractions(eventRepository, roomRepository, issueRepository);
        verify(runRepository, never()).save(any());
        verify(attemptRepository, never()).save(any());
        verify(streamEventRepository, never()).save(any());

        Query cursorCas =
                java.util.Arrays.stream(CaseProcessProjectionRepository.class.getMethods())
                        .filter(
                                method ->
                                        method.getName()
                                                .equals("advanceTerminalNoCommitCommandCursor"))
                        .findFirst()
                        .orElseThrow()
                        .getAnnotation(Query.class);
        assertThat(cursorCas).isNotNull();
        assertThat(cursorCas.value())
                .contains(":newLastCommandSequence = :expectedLastCommandSequence + 1")
                .contains("last_case_event_sequence = :lastCaseEventSequence");

        OffsetDateTime expiredAt =
                OffsetDateTime.ofInstant(source.deadlineAt().plusSeconds(1), ZoneOffset.UTC);
        CaseCommandEntity expiredCommand =
                CaseCommandEntity.pending(
                        "case-command-evidence-terminal-expired",
                        source,
                        mapper.writeValueAsString(source.actorRef().actorScopes()),
                        terminalAt.minusSeconds(2));
        expiredCommand.markOrchestrationAccepted(terminalAt.minusSeconds(1));
        expiredCommand.markExpired("COMMAND_DEADLINE_EXPIRED", expiredAt);
        when(commandRepository.findByTenantSurrogateAndCommandIdForUpdate(TENANT, COMMAND_ID))
                .thenReturn(Optional.of(expiredCommand));
        when(commandRepository.findFirstByCaseIdOrderByCaseCommandSequenceDesc(CASE_ID))
                .thenReturn(Optional.of(expiredCommand));

        lastCommandSequence.set(source.caseCommandSequence() - 1);
        lastCaseEventSequence.set(authority.expectedLastCaseEventSequence());
        terminalStreamEvent.set(evidenceFailureTerminalEvent(
                authority, terminalCommand, terminalErrorOccurredAt, mapper));
        clearLegacyFailureProjection(run);
        assertLegacyNullFailureProjection(run, authority);

        CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest expiredRecovery =
                expiredEvidenceRecovery(
                        authority,
                        caseWorkflowId,
                        caseWorkflowRunId,
                        expiredAt.toInstant());
        RecoverExpiredTargetEvidenceTerminalNoCommit expiredRequest =
                expiredEvidenceRecoveryActivity(
                        authority, expiredRecovery, caseWorkflowBuildId);

        ReflectionTestUtils.setField(expiredCommand, "updatedAt", expiredAt.plusNanos(1));
        assertApplicationFailureType(
                () -> activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest),
                "TARGET_EVIDENCE_EXPIRED_TERMINAL_RECOVERY_COMMAND_CONFLICT");
        ReflectionTestUtils.setField(expiredCommand, "updatedAt", expiredAt);
        assertLegacyNullFailureProjection(run, authority);

        OffsetDateTime prematureExpiredAt =
                OffsetDateTime.ofInstant(source.deadlineAt().minusNanos(1), ZoneOffset.UTC);
        ReflectionTestUtils.setField(expiredCommand, "updatedAt", prematureExpiredAt);
        CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest prematureRecovery =
                expiredEvidenceRecovery(
                        authority,
                        caseWorkflowId,
                        caseWorkflowRunId,
                        prematureExpiredAt.toInstant());
        assertApplicationFailureType(
                () ->
                        activities.recoverExpiredTargetEvidenceTerminalNoCommit(
                                expiredEvidenceRecoveryActivity(
                                        authority,
                                        prematureRecovery,
                                        caseWorkflowBuildId)),
                "TARGET_EVIDENCE_EXPIRED_TERMINAL_RECOVERY_COMMAND_CONFLICT");
        ReflectionTestUtils.setField(expiredCommand, "updatedAt", expiredAt);
        assertLegacyNullFailureProjection(run, authority);

        ReflectionTestUtils.setField(expiredCommand, "resultUri", "urn:foreign:receipt");
        ReflectionTestUtils.setField(expiredCommand, "resultSha256", "8".repeat(64));
        assertApplicationFailureType(
                () -> activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest),
                "TARGET_EVIDENCE_EXPIRED_TERMINAL_RECOVERY_COMMAND_CONFLICT");
        ReflectionTestUtils.setField(expiredCommand, "resultUri", null);
        ReflectionTestUtils.setField(expiredCommand, "resultSha256", null);
        ReflectionTestUtils.setField(expiredCommand, "appliedAt", expiredAt);
        assertApplicationFailureType(
                () -> activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest),
                "TARGET_EVIDENCE_EXPIRED_TERMINAL_RECOVERY_COMMAND_CONFLICT");
        ReflectionTestUtils.setField(expiredCommand, "appliedAt", null);
        ReflectionTestUtils.setField(expiredCommand, "commandStatus", CommandStatus.APPLIED);
        assertApplicationFailureType(
                () -> activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest),
                "TARGET_EVIDENCE_EXPIRED_TERMINAL_RECOVERY_COMMAND_CONFLICT");
        ReflectionTestUtils.setField(expiredCommand, "commandStatus", CommandStatus.EXPIRED);
        assertLegacyNullFailureProjection(run, authority);

        CaseCommandRef successor =
                new CaseCommandRef(
                        "case-command-ref.v1",
                        "evidence-opening-successor",
                        TENANT,
                        CASE_ID,
                        source.caseCommandSequence() + 1,
                        CommandType.EVIDENCE_OPENING,
                        RoomType.EVIDENCE,
                        source.roomEpoch(),
                        source.actorRef(),
                        new PayloadRef(
                                "production-runtime-evidence-opening.v1",
                                "urn:evidence-opening:evidence-opening-successor",
                                "e".repeat(64),
                                64),
                        source.expectedProcessRevision(),
                        source.deadlineAt().plusSeconds(1),
                        source.deadlineAt().plusSeconds(60),
                        source.traceparent(),
                        "f".repeat(64));
        CaseCommandEntity successorCommand =
                CaseCommandEntity.pending(
                        "case-command-evidence-terminal-successor",
                        successor,
                        mapper.writeValueAsString(successor.actorRef().actorScopes()),
                        expiredAt.plusSeconds(1));
        OffsetDateTime successorUpdatedAt = successorCommand.getUpdatedAt();
        when(commandRepository.findFirstByCaseIdOrderByCaseCommandSequenceDesc(CASE_ID))
                .thenReturn(Optional.of(successorCommand));
        assertApplicationFailureType(
                () -> activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest),
                "TARGET_EVIDENCE_EXPIRED_TERMINAL_RECOVERY_LATER_COMMAND_PRESENT");
        assertThat(expiredCommand.getCommandStatus()).isEqualTo(CommandStatus.EXPIRED);
        assertThat(expiredCommand.getResultUri()).isNull();
        assertThat(expiredCommand.getResultSha256()).isNull();
        assertThat(expiredCommand.getUpdatedAt()).isEqualTo(expiredAt);
        assertThat(successorCommand.getCommandStatus())
                .isEqualTo(CommandStatus.PENDING_ORCHESTRATION);
        assertThat(successorCommand.getUpdatedAt()).isEqualTo(successorUpdatedAt);
        assertThat(successorCommand.getResultUri()).isNull();
        assertThat(successorCommand.getResultSha256()).isNull();
        verify(projectionRepository, never())
                .advanceTerminalNoCommitCommandCursor(
                        TENANT,
                        CASE_ID,
                        source.roomEpoch(),
                        authority.roomFencingToken(),
                        source.expectedProcessRevision(),
                        authority.expectedRoomRevision(),
                        source.caseCommandSequence() - 1,
                        source.caseCommandSequence(),
                        authority.expectedLastCaseEventSequence(),
                        caseWorkflowId,
                        caseWorkflowRunId,
                        caseWorkflowBuildId,
                        expiredAt);
        when(commandRepository.findFirstByCaseIdOrderByCaseCommandSequenceDesc(CASE_ID))
                .thenReturn(Optional.of(expiredCommand));
        assertLegacyNullFailureProjection(run, authority);

        lastCommandSequence.decrementAndGet();
        assertApplicationFailureType(
                () -> activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_SOURCE_STALE");
        lastCommandSequence.set(source.caseCommandSequence() - 1);
        lastCaseEventSequence.incrementAndGet();
        assertApplicationFailureType(
                () -> activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_SOURCE_STALE");
        lastCaseEventSequence.set(authority.expectedLastCaseEventSequence());
        when(projection.getProcessRevision())
                .thenReturn(source.expectedProcessRevision() + 1);
        assertApplicationFailureType(
                () -> activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_SOURCE_STALE");
        when(projection.getProcessRevision()).thenReturn(source.expectedProcessRevision());
        assertLegacyNullFailureProjection(run, authority);

        when(material.commandHash()).thenReturn("9".repeat(64));
        assertApplicationFailureType(
                () -> activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_MATERIAL_MISMATCH");
        when(material.commandHash()).thenReturn(authority.commandHash());
        terminalPrevious.set("foreign-expired-predecessor");
        assertApplicationFailureType(
                () -> activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_LINEAGE_INVALID");
        terminalPrevious.set(intermediateAttemptId);
        streamHighWatermark.decrementAndGet();
        assertApplicationFailureType(
                () -> activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID");
        streamHighWatermark.set(authority.terminalLastSequenceNo());
        ReflectionTestUtils.setField(run, "errorCode", "PARTIAL_EXPIRED_PARENT");
        assertApplicationFailureType(
                () -> activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest),
                "TARGET_EVIDENCE_TERMINAL_NO_COMMIT_RUN_INVALID");
        ReflectionTestUtils.setField(run, "errorCode", null);
        assertLegacyNullFailureProjection(run, authority);

        when(projectionRepository.advanceTerminalNoCommitCommandCursor(
                        TENANT,
                        CASE_ID,
                        source.roomEpoch(),
                        authority.roomFencingToken(),
                        source.expectedProcessRevision(),
                        authority.expectedRoomRevision(),
                        source.caseCommandSequence() - 1,
                        source.caseCommandSequence(),
                        authority.expectedLastCaseEventSequence(),
                        caseWorkflowId,
                        caseWorkflowRunId,
                        caseWorkflowBuildId,
                        expiredAt))
                .thenReturn(0);
        assertApplicationFailureType(
                () -> activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest),
                "TARGET_EVIDENCE_EXPIRED_TERMINAL_RECOVERY_CURSOR_CAS_REJECTED");
        assertThat(expiredCommand.getCommandStatus()).isEqualTo(CommandStatus.EXPIRED);
        assertThat(lastCommandSequence.get()).isEqualTo(source.caseCommandSequence() - 1);
        assertThat(CaseProcessLedgerActivitiesImpl.class
                        .getMethod(
                                "recoverExpiredTargetEvidenceTerminalNoCommit",
                                RecoverExpiredTargetEvidenceTerminalNoCommit.class)
                        .getAnnotation(
                                org.springframework.transaction.annotation.Transactional.class))
                .isNotNull();
        clearLegacyFailureProjection(run);
        assertLegacyNullFailureProjection(run, authority);
        clearInvocations(projectionRepository);

        when(projectionRepository.advanceTerminalNoCommitCommandCursor(
                        TENANT,
                        CASE_ID,
                        source.roomEpoch(),
                        authority.roomFencingToken(),
                        source.expectedProcessRevision(),
                        authority.expectedRoomRevision(),
                        source.caseCommandSequence() - 1,
                        source.caseCommandSequence(),
                        authority.expectedLastCaseEventSequence(),
                        caseWorkflowId,
                        caseWorkflowRunId,
                        caseWorkflowBuildId,
                        expiredAt))
                .thenAnswer(
                        ignored ->
                                lastCommandSequence.compareAndSet(
                                                source.caseCommandSequence() - 1,
                                                source.caseCommandSequence())
                                        ? 1
                                        : 0);

        OffsetDateTime expiredRunCompletedAt = run.getCompletedAt();
        String expiredRunFinalizationStatus = run.getFinalizationStatus();
        var recovered =
                activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest);
        assertThat(recovered.outcome())
                .isEqualTo(ExpiredTargetEvidenceTerminalRecoveryOutcome.RECOVERED);
        assertThat(recovered.recoveryId()).isEqualTo(expiredRecovery.recoveryId());
        assertThat(recovered.requestSha256()).isEqualTo(expiredRecovery.requestSha256());
        assertThat(recovered.authority()).isEqualTo(authority);
        assertThat(recovered.actualExpiredAt()).isEqualTo(expiredAt.toInstant());
        assertThat(recovered.lastCommandSequence()).isEqualTo(source.caseCommandSequence());
        assertThat(recovered.lastCaseEventSequence())
                .isEqualTo(authority.expectedLastCaseEventSequence());
        assertThat(expiredCommand.getCommandStatus()).isEqualTo(CommandStatus.FAILED);
        assertThat(expiredCommand.getStatusReasonCode())
                .isEqualTo(authority.terminalErrorCode());
        assertThat(expiredCommand.getResultUri()).isEqualTo(authority.receiptUri());
        assertThat(expiredCommand.getResultSha256()).isEqualTo(authority.receiptSha256());
        assertThat(expiredCommand.getUpdatedAt()).isEqualTo(expiredAt);
        assertCanonicalFailureProjection(run, authority);
        assertThat(run.getCompletedAt()).isEqualTo(expiredRunCompletedAt);
        assertThat(run.getFinalizationStatus()).isEqualTo(expiredRunFinalizationStatus);
        assertThat(run.getResultReadyAttemptId()).isNull();
        assertThat(run.getCommittedAttemptId()).isNull();
        assertThat(run.getFinalResultHash()).isNull();
        assertThat(run.getCommittedManifestId()).isNull();
        assertThat(run.getCommittedManifestHash()).isNull();
        assertThat(run.getFinalStreamSequenceNo()).isNull();
        assertThat(run.getFinalizedAt()).isNull();
        assertThat(lastCommandSequence.get()).isEqualTo(source.caseCommandSequence());
        assertThat(lastCaseEventSequence.get())
                .isEqualTo(authority.expectedLastCaseEventSequence());

        OffsetDateTime repairedRunUpdatedAt = run.getUpdatedAt();
        String recoveredReceiptUri = expiredCommand.getResultUri();
        String recoveredReceiptSha256 = expiredCommand.getResultSha256();
        verify(projectionRepository, times(1))
                .advanceTerminalNoCommitCommandCursor(
                        TENANT,
                        CASE_ID,
                        source.roomEpoch(),
                        authority.roomFencingToken(),
                        source.expectedProcessRevision(),
                        authority.expectedRoomRevision(),
                        source.caseCommandSequence() - 1,
                        source.caseCommandSequence(),
                        authority.expectedLastCaseEventSequence(),
                        caseWorkflowId,
                        caseWorkflowRunId,
                        caseWorkflowBuildId,
                        expiredAt);
        clearInvocations(
                commandRepository,
                projectionRepository,
                runRepository,
                attemptRepository);
        when(commandRepository.findFirstByCaseIdOrderByCaseCommandSequenceDesc(CASE_ID))
                .thenReturn(Optional.of(successorCommand));
        var expiredReplay =
                activities.recoverExpiredTargetEvidenceTerminalNoCommit(expiredRequest);
        assertThat(expiredReplay.outcome())
                .isEqualTo(ExpiredTargetEvidenceTerminalRecoveryOutcome.IDEMPOTENT_REPLAY);
        assertThat(expiredReplay.authority()).isEqualTo(recovered.authority());
        assertThat(expiredCommand.getResultUri()).isEqualTo(recoveredReceiptUri);
        assertThat(expiredCommand.getResultSha256()).isEqualTo(recoveredReceiptSha256);
        assertThat(expiredCommand.getUpdatedAt()).isEqualTo(expiredAt);
        assertCanonicalFailureProjection(run, authority);
        assertThat(run.getUpdatedAt()).isEqualTo(repairedRunUpdatedAt);
        assertThat(successorCommand.getCommandStatus())
                .isEqualTo(CommandStatus.PENDING_ORCHESTRATION);
        assertThat(successorCommand.getUpdatedAt()).isEqualTo(successorUpdatedAt);
        assertThat(successorCommand.getResultUri()).isNull();
        assertThat(successorCommand.getResultSha256()).isNull();
        verify(commandRepository, never())
                .findFirstByCaseIdOrderByCaseCommandSequenceDesc(CASE_ID);
        verify(projectionRepository, never())
                .advanceTerminalNoCommitCommandCursor(
                        TENANT,
                        CASE_ID,
                        source.roomEpoch(),
                        authority.roomFencingToken(),
                        source.expectedProcessRevision(),
                        authority.expectedRoomRevision(),
                        source.caseCommandSequence() - 1,
                        source.caseCommandSequence(),
                        authority.expectedLastCaseEventSequence(),
                        caseWorkflowId,
                        caseWorkflowRunId,
                        caseWorkflowBuildId,
                        expiredAt);
        verify(runRepository, never()).save(any());
        verify(attemptRepository, never()).save(any());

        String exactExpiredReceiptSha = expiredCommand.getResultSha256();
        CaseCommandEntity foreignMalformedSuccessor = mock(CaseCommandEntity.class);
        when(foreignMalformedSuccessor.getCaseCommandSequence())
                .thenReturn(source.caseCommandSequence() + 1);
        when(foreignMalformedSuccessor.getCommandId()).thenReturn("");
        when(foreignMalformedSuccessor.getTenantSurrogate()).thenReturn("tenant-foreign");
        when(commandRepository.findFirstByCaseIdOrderByCaseCommandSequenceDesc(CASE_ID))
                .thenReturn(Optional.of(foreignMalformedSuccessor));
        ReflectionTestUtils.setField(expiredCommand, "resultSha256", "7".repeat(64));
        assertThatThrownBy(
                        () ->
                                activities.recoverExpiredTargetEvidenceTerminalNoCommit(
                                        expiredRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another terminal authority");
        ReflectionTestUtils.setField(
                expiredCommand, "resultSha256", exactExpiredReceiptSha);
        assertThat(expiredCommand.getUpdatedAt()).isEqualTo(expiredAt);
        assertThat(successorCommand.getCommandStatus())
                .isEqualTo(CommandStatus.PENDING_ORCHESTRATION);
        assertThat(successorCommand.getUpdatedAt()).isEqualTo(successorUpdatedAt);
        assertCanonicalFailureProjection(run, authority);
    }

    private static RecordCaseCommandRouted routingRequest() {
        return new RecordCaseCommandRouted(
                "record-case-command-routed.v1",
                TENANT,
                CASE_ID,
                COMMAND_ID,
                1,
                "request-hash",
                RoomType.EVIDENCE,
                7,
                Instant.parse("2026-07-29T00:00:00Z"),
                "case-process:" + TENANT + ":" + CASE_ID,
                "run-routing");
    }

    private static TargetRoomAgentRunTerminalNoCommit evidenceTerminalAuthority() {
        CaseCommandRef command = evidenceOpeningCommand();
        String logicalRunId = "target-evidence-run:" + command.commandId();
        String rootAttemptId = logicalRunId + ":1";
        String terminalAttemptId = logicalRunId + ":3";
        RoomGraphCommand graph =
                new RoomGraphCommand(
                        "room-graph-command.v1",
                        command.commandId(),
                        logicalRunId,
                        rootAttemptId,
                        command.tenantSurrogate(),
                        command.caseId(),
                        RoomType.EVIDENCE,
                        command.roomEpoch(),
                        TargetTypedRoomProtocol.GRAPH_KEY,
                        TargetTypedRoomProtocol.GRAPH_VERSION,
                        TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
                        "grt.v1.evidence-terminal-ledger-test",
                        new RoomGraphCommand.ActorScope(
                                command.actorRef().actorId(),
                                command.actorRef().actorRole(),
                                Audience.USER,
                                command.actorRef().actorScopes()),
                        command.expectedProcessRevision(),
                        "EVIDENCE_SEAL",
                        command.expectedProcessRevision(),
                        new RoomGraphCommand.SnapshotRef(
                                "evidence-snapshot-7",
                                command.payloadRef().schemaVersion(),
                                command.payloadRef().uri(),
                                command.payloadRef().sha256(),
                                command.payloadRef().sizeBytes()),
                        new RoomGraphCommand.SnapshotRef(
                                "evidence-event-7",
                                command.payloadRef().schemaVersion(),
                                command.payloadRef().uri(),
                                command.payloadRef().sha256(),
                                command.payloadRef().sizeBytes()),
                        new RoomGraphCommand.InvocationContext(
                                "evidence-clerk",
                                "prompt-v1",
                                "model-v1",
                                "output-v1",
                                "policy-v1",
                                "guardrail-v1",
                                List.of(),
                                "key-v1",
                                "nonce-v1"),
                        new RoomGraphCommand.RetryBudget(2, 1, 0),
                        command.deadlineAt(),
                        canonicalGraphTraceparent(command.traceparent()),
                        "6".repeat(64));
        ExecuteAgentRunRequest root =
                new ExecuteAgentRunRequest(
                        ExecuteAgentRunRequest.SCHEMA_VERSION,
                        logicalRunId,
                        1,
                        "agent-stream.v2",
                        "e".repeat(64),
                        null,
                        false,
                        0,
                        graph);
        Instant completedAt = Instant.parse("2026-07-29T00:00:03Z");
        ExecuteAgentRunResult failed =
                new ExecuteAgentRunResult(
                        ExecuteAgentRunResult.SCHEMA_VERSION,
                        logicalRunId,
                        logicalRunId,
                        terminalAttemptId,
                        3,
                        ExecuteAgentRunResult.Outcome.FAILED,
                        null,
                        null,
                        4,
                        false,
                        "GRAPH_CONTRACT_REJECTED",
                        false,
                        AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                        completedAt);
        return new TargetRoomAgentRunTerminalNoCommit(
                TargetRoomAgentRunTerminalNoCommit.SCHEMA_VERSION,
                command,
                11,
                6,
                13,
                "room-workflow:" + CASE_ID + ":EVIDENCE:0",
                "room-run-evidence-terminal",
                "control-build-evidence-terminal",
                "1".repeat(64),
                "2".repeat(64),
                root,
                failed,
                AgentRunAttemptStatus.FAILED,
                failed.errorCode(),
                false,
                AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                4,
                completedAt,
                false);
    }

    private static CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest
            expiredEvidenceRecovery(
                    TargetRoomAgentRunTerminalNoCommit authority,
                    String caseWorkflowId,
                    String caseWorkflowFirstExecutionRunId,
                    Instant actualExpiredAt) {
        CaseCommandRef source = authority.command();
        ProcessedCommandIdentity previous =
                new ProcessedCommandIdentity(
                        source.commandId(),
                        source.caseCommandSequence(),
                        source.requestHash());
        String recoveryId =
                CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest.recoveryId(
                        caseWorkflowId,
                        caseWorkflowFirstExecutionRunId,
                        previous,
                        actualExpiredAt);
        return new CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest(
                CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest.SCHEMA_VERSION,
                recoveryId,
                caseWorkflowId,
                caseWorkflowFirstExecutionRunId,
                source.tenantSurrogate(),
                source.caseId(),
                source.caseCommandSequence() + 1,
                source.caseCommandSequence(),
                authority.expectedLastCaseEventSequence() + 1,
                authority.expectedLastCaseEventSequence(),
                source.expectedProcessRevision(),
                authority.expectedRoomRevision(),
                "TARGET_TYPED_ROOM_COMMAND_DISPATCH_FAILED",
                RecoveryErrorOrigin.COMMAND,
                actualExpiredAt,
                previous);
    }

    private static RecoverExpiredTargetEvidenceTerminalNoCommit
            expiredEvidenceRecoveryActivity(
                    TargetRoomAgentRunTerminalNoCommit authority,
                    CaseProcessExpiredTargetEvidenceTerminalRecoveryRequest recovery,
                    String caseWorkflowBuildId) {
        return new RecoverExpiredTargetEvidenceTerminalNoCommit(
                RecoverExpiredTargetEvidenceTerminalNoCommit.SCHEMA_VERSION,
                recovery,
                authority.command().roomEpoch(),
                authority.roomFencingToken(),
                authority.roomWorkflowId(),
                authority.roomWorkflowRunId(),
                authority.roomWorkflowBuildId(),
                caseWorkflowBuildId);
    }

    private static AgentRunStreamEventEntity evidenceFailureTerminalEvent(
            TargetRoomAgentRunTerminalNoCommit authority,
            RoomGraphCommand terminalCommand,
            Instant occurredAt,
            ObjectMapper mapper) {
        AgentStreamEvent terminal = new AgentStreamEvent(
                AgentRunProtocol.V2.wireValue(),
                authority.rootRequest().logicalRunId(),
                authority.terminalResult().attemptId(),
                authority.terminalLastSequenceNo(),
                StreamEventType.ERROR,
                terminalCommand.actorScope().audience(),
                occurredAt,
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
        var terminalJson = mapper.valueToTree(terminal);
        return AgentRunStreamEventEntity.createV2Prelude(
                "ARSE2_EVIDENCE_TERMINAL",
                terminal.runId(),
                terminal.attemptId(),
                terminal.sequenceNo(),
                terminal.eventType().wireValue(),
                terminal.audience(),
                ContractJson.canonicalString(terminalJson),
                ContractJson.sha256Hex(terminalJson),
                terminal.occurredAt());
    }

    private static void clearLegacyFailureProjection(AgentRunEntity run) {
        ReflectionTestUtils.setField(run, "errorCode", null);
        ReflectionTestUtils.setField(run, "errorRetryable", null);
        ReflectionTestUtils.setField(run, "errorMessage", null);
        ReflectionTestUtils.setField(run, "stopReason", null);
    }

    private static void assertLegacyNullFailureProjection(
            AgentRunEntity run, TargetRoomAgentRunTerminalNoCommit authority) {
        assertThat(run.getRunStatus()).isEqualTo(authority.terminalAttemptStatus().name());
        assertThat(run.getCompletedAt().toInstant()).isEqualTo(authority.terminalAt());
        assertThat(run.getFinalizationStatus()).isEqualTo("UNCOMMITTED");
        assertThat(run.getResultReadyAttemptId()).isNull();
        assertThat(run.getCommittedAttemptId()).isNull();
        assertThat(run.getFinalResultHash()).isNull();
        assertThat(run.getCommittedManifestId()).isNull();
        assertThat(run.getCommittedManifestHash()).isNull();
        assertThat(run.getFinalStreamSequenceNo()).isNull();
        assertThat(run.getFinalizedAt()).isNull();
        assertThat(run.getErrorCode()).isNull();
        assertThat(run.getErrorRetryable()).isNull();
        assertThat(run.getErrorMessage()).isNull();
        assertThat(run.getStopReason()).isNull();
    }

    private static void assertCanonicalFailureProjection(
            AgentRunEntity run, TargetRoomAgentRunTerminalNoCommit authority) {
        assertThat(run.getRunStatus()).isEqualTo(authority.terminalAttemptStatus().name());
        assertThat(run.getCompletedAt().toInstant()).isEqualTo(authority.terminalAt());
        assertThat(run.getErrorCode()).isEqualTo(authority.terminalErrorCode());
        assertThat(run.getErrorRetryable()).isFalse();
        assertThat(run.getErrorMessage()).isEqualTo(AgentRunEntity.V2_LOGICAL_FAILURE_MESSAGE);
        assertThat(run.getStopReason()).isEqualTo(AgentRunEntity.V2_LOGICAL_FAILURE_STOP_REASON);
    }

    private static void assertApplicationFailureType(Runnable invocation, String expectedType) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(
                        failure ->
                                assertThat(((ApplicationFailure) failure).getType())
                                        .isEqualTo(expectedType));
    }

    private static TargetRoomAgentRunTerminalNoCommit copyEvidenceAuthority(
            TargetRoomAgentRunTerminalNoCommit authority, String commandHash) {
        return new TargetRoomAgentRunTerminalNoCommit(
                authority.schemaVersion(),
                authority.command(),
                authority.roomFencingToken(),
                authority.expectedRoomRevision(),
                authority.expectedLastCaseEventSequence(),
                authority.roomWorkflowId(),
                authority.roomWorkflowRunId(),
                authority.roomWorkflowBuildId(),
                commandHash,
                authority.commandEnvelopeHash(),
                authority.rootRequest(),
                authority.terminalResult(),
                authority.terminalAttemptStatus(),
                authority.terminalErrorCode(),
                authority.terminalRetryable(),
                authority.terminalRecoveryAction(),
                authority.terminalLastSequenceNo(),
                authority.terminalAt(),
                authority.finalFrameObserved());
    }

    private static TargetRoomAgentRunTerminalNoCommit evidenceAuthorityWithTraceBinding(
            TargetRoomAgentRunTerminalNoCommit authority,
            String sourceTraceparent,
            String graphTraceparent) {
        CaseCommandRef source = authority.command();
        CaseCommandRef reboundSource =
                new CaseCommandRef(
                        source.schemaVersion(),
                        source.commandId(),
                        source.tenantSurrogate(),
                        source.caseId(),
                        source.caseCommandSequence(),
                        source.commandType(),
                        source.roomType(),
                        source.roomEpoch(),
                        source.actorRef(),
                        source.payloadRef(),
                        source.expectedProcessRevision(),
                        source.occurredAt(),
                        source.deadlineAt(),
                        sourceTraceparent,
                        source.requestHash());
        RoomGraphCommand graph = authority.rootRequest().command();
        ExecuteAgentRunRequest reboundRoot =
                evidenceRootRequest(
                        authority.rootRequest(),
                        evidenceGraphCommand(
                                graph,
                                graph.actorScope(),
                                graph.eventRef(),
                                graph.deadlineAt(),
                                graphTraceparent,
                                graph.requestHash()),
                        authority.rootRequest().logicalInputHash());
        return new TargetRoomAgentRunTerminalNoCommit(
                authority.schemaVersion(),
                reboundSource,
                authority.roomFencingToken(),
                authority.expectedRoomRevision(),
                authority.expectedLastCaseEventSequence(),
                authority.roomWorkflowId(),
                authority.roomWorkflowRunId(),
                authority.roomWorkflowBuildId(),
                authority.commandHash(),
                authority.commandEnvelopeHash(),
                reboundRoot,
                authority.terminalResult(),
                authority.terminalAttemptStatus(),
                authority.terminalErrorCode(),
                authority.terminalRetryable(),
                authority.terminalRecoveryAction(),
                authority.terminalLastSequenceNo(),
                authority.terminalAt(),
                authority.finalFrameObserved());
    }

    private static ExecuteAgentRunRequest evidenceRootRequest(
            ExecuteAgentRunRequest source,
            RoomGraphCommand command,
            String logicalInputHash) {
        return new ExecuteAgentRunRequest(
                source.schemaVersion(),
                source.logicalRunId(),
                source.attemptNo(),
                "agent-stream.v2",
                logicalInputHash,
                null,
                false,
                0,
                command);
    }

    private static RoomGraphCommand evidenceGraphCommand(
            RoomGraphCommand source,
            RoomGraphCommand.ActorScope actorScope,
            RoomGraphCommand.SnapshotRef eventRef,
            Instant deadlineAt,
            String traceparent,
            String requestHash) {
        return new RoomGraphCommand(
                source.schemaVersion(),
                source.commandId(),
                source.logicalRunId(),
                source.attemptId(),
                source.tenantSurrogate(),
                source.caseId(),
                source.roomType(),
                source.roomEpoch(),
                source.graphKey(),
                source.graphVersion(),
                source.checkpointSchemaVersion(),
                source.threadId(),
                actorScope,
                source.processRevision(),
                source.stageCode(),
                source.stageSequence(),
                source.domainSnapshotRef(),
                eventRef,
                source.invocationContext(),
                source.retryBudget(),
                deadlineAt,
                traceparent,
                requestHash);
    }

    private static String canonicalGraphTraceparent(String sourceTraceparent) {
        return "00-"
                + sourceTraceparent.substring(3, 35)
                + "-0000000000000001-01";
    }

    private static RoomGraphCommand withAttemptAuthority(
            RoomGraphCommand command,
            String attemptId,
            String commandId,
            String requestHash,
            String envelopeNonce,
            RoomGraphCommand.RetryBudget retryBudget) {
        return new RoomGraphCommand(
                command.schemaVersion(),
                commandId,
                command.logicalRunId(),
                attemptId,
                command.tenantSurrogate(),
                command.caseId(),
                command.roomType(),
                command.roomEpoch(),
                command.graphKey(),
                command.graphVersion(),
                command.checkpointSchemaVersion(),
                command.threadId(),
                command.actorScope(),
                command.processRevision(),
                command.stageCode(),
                command.stageSequence(),
                command.domainSnapshotRef(),
                command.eventRef(),
                new RoomGraphCommand.InvocationContext(
                        command.invocationContext().agentProfileId(),
                        command.invocationContext().promptProfileId(),
                        command.invocationContext().modelProfileId(),
                        command.invocationContext().outputSchemaVersion(),
                        command.invocationContext().policyVersion(),
                        command.invocationContext().guardrailVersion(),
                        command.invocationContext().toolCapabilities(),
                        command.invocationContext().envelopeKeyId(),
                        envelopeNonce),
                retryBudget,
                command.deadlineAt(),
                command.traceparent(),
                requestHash);
    }

    private static RoomGraphCommand withProcessRevisionDrift(RoomGraphCommand command) {
        return new RoomGraphCommand(
                command.schemaVersion(),
                command.commandId(),
                command.logicalRunId(),
                command.attemptId(),
                command.tenantSurrogate(),
                command.caseId(),
                command.roomType(),
                command.roomEpoch(),
                command.graphKey(),
                command.graphVersion(),
                command.checkpointSchemaVersion(),
                command.threadId(),
                command.actorScope(),
                command.processRevision() + 1,
                command.stageCode(),
                command.stageSequence(),
                command.domainSnapshotRef(),
                command.eventRef(),
                command.invocationContext(),
                command.retryBudget(),
                command.deadlineAt(),
                command.traceparent(),
                "9".repeat(64));
    }

    private static ExecuteAgentRunResult retryableEvidenceFailure(
            String logicalRunId,
            String attemptId,
            long attemptNo,
            long lastSequenceNo,
            Instant completedAt) {
        return new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                logicalRunId,
                logicalRunId,
                attemptId,
                attemptNo,
                ExecuteAgentRunResult.Outcome.FAILED,
                null,
                null,
                lastSequenceNo,
                false,
                "PROVIDER_UNAVAILABLE",
                true,
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                completedAt);
    }

    private static void stubEvidenceAttemptCommand(
            AgentRunAttemptEntity attempt,
            RoomGraphCommand command,
            long attemptNo,
            String previousAttemptId,
            String logicalInputHash,
            ObjectMapper mapper)
            throws Exception {
        when(attempt.getId()).thenReturn(command.attemptId());
        when(attempt.getAgentRunId()).thenReturn(command.logicalRunId());
        when(attempt.getAttemptNo()).thenReturn(attemptNo);
        when(attempt.getPreviousAttemptId()).thenReturn(previousAttemptId);
        when(attempt.getCommandId()).thenReturn(command.commandId());
        when(attempt.getRequestHash()).thenReturn(command.requestHash());
        when(attempt.getCommandRequestHash()).thenReturn(command.requestHash());
        when(attempt.getLogicalInputHash()).thenReturn(logicalInputHash);
        when(attempt.getExecutorKind()).thenReturn(AgentRunExecutorKind.TEMPORAL_ACTIVITY);
        when(attempt.getGraphKey()).thenReturn(command.graphKey());
        when(attempt.getGraphVersion()).thenReturn(command.graphVersion());
        when(attempt.getCheckpointSchemaVersion()).thenReturn(command.checkpointSchemaVersion());
        when(attempt.getModelProfileId())
                .thenReturn(command.invocationContext().modelProfileId());
        when(attempt.getPromptVersion())
                .thenReturn(command.invocationContext().promptProfileId());
        when(attempt.getOutputSchemaVersion())
                .thenReturn(command.invocationContext().outputSchemaVersion());
        when(attempt.getPolicyVersion())
                .thenReturn(command.invocationContext().policyVersion());
        when(attempt.getGuardrailVersion())
                .thenReturn(command.invocationContext().guardrailVersion());
        when(attempt.getLineageSchemaVersion()).thenReturn("agent-run-attempt-lineage.v1");
        when(attempt.getCommandJson()).thenReturn(mapper.writeValueAsString(command));
    }

    private static void stubRetryableEvidenceFailure(
            AgentRunAttemptEntity attempt,
            ExecuteAgentRunResult result,
            ObjectMapper mapper)
            throws Exception {
        when(attempt.getAttemptStatus()).thenReturn(AgentRunAttemptStatus.FAILED);
        when(attempt.getResultJson()).thenReturn(mapper.writeValueAsString(result));
        when(attempt.getResultHash()).thenReturn(null);
        when(attempt.getLastSequenceNo()).thenReturn(result.lastSequenceNo());
        when(attempt.isPublicOutputEmitted()).thenReturn(result.publicOutputEmitted());
        when(attempt.getErrorCode()).thenReturn(result.errorCode());
        when(attempt.getErrorRetryable()).thenReturn(result.retryable());
        when(attempt.getTerminationCode()).thenReturn(result.recoveryAction().name());
        when(attempt.getCompletedAt())
                .thenReturn(OffsetDateTime.ofInstant(result.completedAt(), ZoneOffset.UTC));
    }

    private static CaseCommandRef evidenceOpeningCommand() {
        return new CaseCommandRef(
                "case-command-ref.v1",
                COMMAND_ID,
                TENANT,
                CASE_ID,
                7,
                CommandType.EVIDENCE_OPENING,
                RoomType.EVIDENCE,
                0,
                new ActorRef("actor-evidence-terminal", ActorRole.USER, List.of("evidence:opening")),
                new PayloadRef(
                        "production-runtime-evidence-opening.v1",
                        "urn:evidence-opening:" + COMMAND_ID,
                        "d".repeat(64),
                        64),
                6,
                Instant.parse("2026-07-29T00:00:01Z"),
                Instant.parse("2026-07-29T01:00:00Z"),
                "00-11111111111111111111111111111111-2222222222222222-01",
                "a".repeat(64));
    }

    private static TargetIntakeCommandTerminalNoCommit terminalAuthority(String errorCode) {
        return terminalAuthority(
                TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION,
                errorCode,
                "b".repeat(64),
                13L,
                15);
    }

    private static TargetIntakeCommandTerminalNoCommit terminalAuthority(
            String errorCode,
            String agentRunExecutionRequestHash,
            long expectedLastCaseEventSequence,
            long lastCaseEventSequence) {
        return terminalAuthority(
                TargetIntakeCommandTerminalNoCommit.SCHEMA_VERSION,
                errorCode,
                agentRunExecutionRequestHash,
                expectedLastCaseEventSequence,
                lastCaseEventSequence);
    }

    private static TargetIntakeCommandTerminalNoCommit legacyTerminalAuthority(String errorCode) {
        return terminalAuthority(
                TargetIntakeCommandTerminalNoCommit.LEGACY_SCHEMA_VERSION,
                errorCode,
                null,
                null,
                13);
    }

    private static TargetIntakeCommandTerminalNoCommit terminalAuthority(
            String schemaVersion,
            String errorCode,
            String agentRunExecutionRequestHash,
            Long expectedLastCaseEventSequence,
            long lastCaseEventSequence) {
        return new TargetIntakeCommandTerminalNoCommit(
                schemaVersion,
                TENANT,
                CASE_ID,
                RoomType.INTAKE,
                0,
                11,
                "room-workflow:" + CASE_ID + ":INTAKE:0",
                "room-run-1",
                "control-build-1",
                "p9act.v1." + "1".repeat(32),
                "2".repeat(64),
                "case-build-1",
                "control-build-1",
                "agent-build-1",
                "3".repeat(64),
                "graph-code-build-1",
                "4".repeat(64),
                "5".repeat(64),
                "6".repeat(64),
                agentRunExecutionRequestHash,
                COMMAND_ID,
                7,
                "a".repeat(64),
                "MSG_ROUTING_7",
                "urn:room-message:MSG_ROUTING_7",
                "d".repeat(64),
                6,
                7,
                6,
                7,
                expectedLastCaseEventSequence,
                lastCaseEventSequence,
                "logical-run-1",
                "attempt-root-1",
                "attempt-root-1",
                1,
                AgentRunAttemptStatus.ABORTED,
                ExecuteAgentRunResult.Outcome.FAILED,
                errorCode,
                false,
                com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction
                        .FAIL_LOGICAL_RUN,
                3,
                true,
                Instant.parse("2026-07-29T00:00:03Z"));
    }

    private static String legacyTerminalAuthorityJson() {
        return "{\"schemaVersion\":\"target-intake-command-terminal-no-commit.v1\""
                + ",\"tenantSurrogate\":\"tenant-routing\""
                + ",\"caseId\":\"CASE_ROUTING\""
                + ",\"roomType\":\"INTAKE\""
                + ",\"roomEpoch\":0"
                + ",\"fencingToken\":11"
                + ",\"roomWorkflowId\":\"room-workflow:CASE_ROUTING:INTAKE:0\""
                + ",\"roomWorkflowRunId\":\"room-run-1\""
                + ",\"roomWorkflowBuildId\":\"control-build-1\""
                + ",\"activationId\":\"p9act.v1."
                + "1".repeat(32)
                + "\""
                + ",\"activationManifestHash\":\""
                + "2".repeat(64)
                + "\""
                + ",\"caseBuildId\":\"case-build-1\""
                + ",\"controlBuildId\":\"control-build-1\""
                + ",\"agentBuildId\":\"agent-build-1\""
                + ",\"graphBindingHash\":\""
                + "3".repeat(64)
                + "\""
                + ",\"graphCodeBuildId\":\"graph-code-build-1\""
                + ",\"commandHash\":\""
                + "4".repeat(64)
                + "\""
                + ",\"commandEnvelopeHash\":\""
                + "5".repeat(64)
                + "\""
                + ",\"logicalInputHash\":\""
                + "6".repeat(64)
                + "\""
                + ",\"commandId\":\"CMD_ROUTING\""
                + ",\"caseCommandSequence\":7"
                + ",\"commandRequestHash\":\""
                + "a".repeat(64)
                + "\""
                + ",\"messageId\":\"MSG_ROUTING_7\""
                + ",\"messageRef\":\"urn:room-message:MSG_ROUTING_7\""
                + ",\"messageHash\":\""
                + "d".repeat(64)
                + "\""
                + ",\"expectedProcessRevision\":6"
                + ",\"newProcessRevision\":7"
                + ",\"expectedRoomRevision\":6"
                + ",\"newRoomRevision\":7"
                + ",\"lastCaseEventSequence\":13"
                + ",\"logicalRunId\":\"logical-run-1\""
                + ",\"rootAttemptId\":\"attempt-root-1\""
                + ",\"terminalAttemptId\":\"attempt-root-1\""
                + ",\"terminalAttemptNo\":1"
                + ",\"terminalAttemptStatus\":\"ABORTED\""
                + ",\"agentRunOutcome\":\"FAILED\""
                + ",\"errorCode\":\"GRAPH_STREAM_PROTOCOL_REJECTED\""
                + ",\"retryable\":false"
                + ",\"recoveryAction\":\"FAIL_LOGICAL_RUN\""
                + ",\"lastSequenceNo\":3"
                + ",\"publicOutputEmitted\":true"
                + ",\"terminalAt\":\"2026-07-29T00:00:03Z\"}";
    }

    private static CaseCommandEntity acceptedCommand(
            TargetIntakeCommandTerminalNoCommit authority, OffsetDateTime acceptedAt) {
        CaseCommandRef command =
                new CaseCommandRef(
                        "case-command-ref.v1",
                        authority.commandId(),
                        authority.tenantSurrogate(),
                        authority.caseId(),
                        authority.caseCommandSequence(),
                        CommandType.INTAKE_MESSAGE,
                        RoomType.INTAKE,
                        authority.roomEpoch(),
                        new ActorRef("actor-terminal-no-commit", ActorRole.USER, List.of("intake:message")),
                        new PayloadRef(
                                "intake-command.v1",
                                authority.messageRef(),
                                authority.messageHash(),
                                1),
                        authority.expectedProcessRevision(),
                        authority.terminalAt().minusSeconds(2),
                        authority.terminalAt().plusSeconds(60),
                        "00-11111111111111111111111111111111-2222222222222222-01",
                        authority.commandRequestHash());
        CaseCommandEntity entity =
                CaseCommandEntity.pending("case-command-row-terminal-no-commit", command, "[]", acceptedAt);
        entity.markOrchestrationAccepted(acceptedAt);
        return entity;
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

    private static void stubSourceCoordinates(
            CaseRoomEpochEntity epoch,
            CaseProcessProjectionEntity projection,
            TargetIntakeCommandTerminalNoCommit authority,
            String caseWorkflowId,
            String caseWorkflowRunId) {
        when(epoch.getTenantSurrogate()).thenReturn(TENANT);
        when(epoch.getCaseId()).thenReturn(CASE_ID);
        when(epoch.getRoomType()).thenReturn(RoomType.INTAKE);
        when(epoch.getRoomEpoch()).thenReturn(0L);
        when(epoch.getWriterMode()).thenReturn(WriterMode.TEMPORAL);
        when(epoch.getLifecycleStatus()).thenReturn(EpochLifecycleStatus.ACTIVE);
        when(epoch.getProvisioningStatus()).thenReturn(EpochProvisioningStatus.READY);
        when(epoch.getFencingToken()).thenReturn(11L);
        when(epoch.getProcessRevision()).thenReturn(6L);
        when(epoch.getRoomRevision()).thenReturn(6L);
        when(epoch.getTemporalWorkflowId()).thenReturn(caseWorkflowId);
        when(epoch.getTemporalRunId()).thenReturn(caseWorkflowRunId);
        when(epoch.getTemporalBuildId()).thenReturn(authority.caseBuildId());
        when(epoch.getRoomTemporalWorkflowId()).thenReturn(authority.roomWorkflowId());
        when(epoch.getRoomTemporalRunId()).thenReturn(authority.roomWorkflowRunId());
        when(epoch.getRoomWorkflowBuildId()).thenReturn(authority.roomWorkflowBuildId());
        when(projection.getCaseId()).thenReturn(CASE_ID);
        when(projection.getTenantSurrogate()).thenReturn(TENANT);
        when(projection.getWriterMode()).thenReturn(WriterMode.TEMPORAL);
        when(projection.getWriterActivationStatus()).thenReturn(WriterActivationStatus.READY);
        when(projection.getRoomEpoch()).thenReturn(0L);
        when(projection.getFencingToken()).thenReturn(11L);
        when(projection.getProcessRevision()).thenReturn(6L);
        when(projection.getTemporalWorkflowId()).thenReturn(caseWorkflowId);
        when(projection.getTemporalRunId()).thenReturn(caseWorkflowRunId);
        when(projection.getTemporalBuildId()).thenReturn(authority.caseBuildId());
        when(projection.getCurrentRoom()).thenReturn("INTAKE");
        when(projection.getMacroPhase()).thenReturn("INTAKE");
        when(projection.getRoomPhase()).thenReturn("WAITING_PARTY");
        when(projection.getLastCommandSequence()).thenReturn(6L);
        when(projection.getLastCaseEventSequence())
                .thenReturn(authority.expectedLastCaseEventSequence());
        when(projection.getProjectionRef()).thenReturn("urn:projection:6");
        when(projection.getProjectionSha256()).thenReturn("c".repeat(64));
    }
}
