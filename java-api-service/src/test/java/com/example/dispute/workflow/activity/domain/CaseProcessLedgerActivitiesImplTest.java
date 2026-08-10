package com.example.dispute.workflow.activity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.room.infrastructure.persistence.repository.CaseRoomRepository;
import com.example.dispute.room.infrastructure.persistence.repository.CaseTimelineEventRepository;
import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
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
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.RecordCaseCommandRouted;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ConvergeTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.ResolveTargetIntakeTerminalNoCommit;
import com.example.dispute.workflow.temporal.caseprocess.CaseCommandLifecycleActivities.TerminalNoCommitOutcome;
import com.example.dispute.workflow.temporal.caseprocess.TargetIntakeCommandTerminalNoCommit;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.protobuf.ByteString;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ApplicationFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class CaseProcessLedgerActivitiesImplTest {

    private static final String TENANT = "tenant-routing";
    private static final String CASE_ID = "CASE_ROUTING";
    private static final String COMMAND_ID = "CMD_ROUTING";

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
                        mock(com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore.class),
                        mock(com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.class),
                        new ObjectMapper(),
                        Clock.systemUTC());

        var result = activities.recordCaseCommandRouted(routingRequest());

        assertThat(result.outcome()).isEqualTo(CommandLifecycleOutcome.ALREADY_APPLIED);
        verify(commandRepository).findByTenantSurrogateAndCommandIdForUpdate(TENANT, COMMAND_ID);
        verify(commandRepository, never()).findByTenantSurrogateAndCommandId(anyString(), anyString());
        verify(commandRepository, never()).findByIdForUpdate(anyString());
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
                    .hasMessageContaining("target E2E activation ledger is unavailable");
        }
    }

    @Test
    void abortedGraphStreamConvergesAcceptedIntakeCommandToRetryableFailureAndClearsPendingProjection()
            throws Exception {
        String caseWorkflowId = "case-process:" + TENANT + ":" + CASE_ID;
        String caseWorkflowRunId = "case-run-1";
        TargetIntakeCommandTerminalNoCommit authority = terminalAuthority("GRAPH_STREAM_PROTOCOL_REJECTED");
        OffsetDateTime terminalAt = OffsetDateTime.ofInstant(authority.terminalAt(), ZoneOffset.UTC);

        CaseCommandRepository commandRepository = mock(CaseCommandRepository.class);
        CaseRoomEpochRepository epochRepository = mock(CaseRoomEpochRepository.class);
        CaseProcessProjectionRepository projectionRepository = mock(CaseProcessProjectionRepository.class);
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentRunAttemptRepository attemptRepository = mock(AgentRunAttemptRepository.class);
        TargetIntakeCommandMaterialStore materialStore = mock(TargetIntakeCommandMaterialStore.class);
        TargetE2EActivationLedger activationLedger = mock(TargetE2EActivationLedger.class);
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
        TargetE2EActivationLedger.CommandAdmission admission =
                mock(TargetE2EActivationLedger.CommandAdmission.class);
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

        TargetE2EActivationLedger.CommandAdmissionSnapshot admissionSnapshot =
                mock(TargetE2EActivationLedger.CommandAdmissionSnapshot.class);
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
        when(run.getProtocol()).thenReturn(AgentRunProtocol.V2.wireValue());
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
        assertThat(run.getProtocol()).as("run protocol").isEqualTo(AgentRunProtocol.V2.wireValue());
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
        TargetE2EActivationLedger activationLedger = mock(TargetE2EActivationLedger.class);
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
        TargetE2EActivationLedger.CommandAdmission admission =
                mock(TargetE2EActivationLedger.CommandAdmission.class);
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

        TargetE2EActivationLedger.CommandAdmissionSnapshot admissionSnapshot =
                mock(TargetE2EActivationLedger.CommandAdmissionSnapshot.class);
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
        when(run.getProtocol()).thenReturn(AgentRunProtocol.V2.wireValue());
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
