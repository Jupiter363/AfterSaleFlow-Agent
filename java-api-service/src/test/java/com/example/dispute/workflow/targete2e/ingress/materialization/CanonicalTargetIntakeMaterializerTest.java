package com.example.dispute.workflow.targete2e.ingress.materialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunLedger.CreateLogicalRun;
import com.example.dispute.agentstream.application.AgentRunLedger.LogicalRun;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.ActorRole;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.AgentSessionResolver;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.workflow.application.intake.IntakeDomainSnapshotPublisher;
import com.example.dispute.workflow.application.intake.IntakeGraphBindingStore;
import com.example.dispute.workflow.application.intake.IntakeGraphCommandFactory;
import com.example.dispute.workflow.application.intake.IntakeGraphThreadBinding;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrar;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistration;
import com.example.dispute.workflow.application.intake.IntakePrivateThreadRegistrationFactory;
import com.example.dispute.workflow.application.intake.IntakeSnapshotReference;
import com.example.dispute.workflow.application.intake.IntakeTurnEventPublisher;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphCommandEnvelope;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeActivationGrant;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeMessageRequest;
import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eApiAuthority;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

class CanonicalTargetIntakeMaterializerTest {

    private static final String TARGET_TENANT_SURROGATE = "tenant-target-activation";
    private static final String CASE_ID = "CASE_TARGET_001";
    private static final String ACTOR_ID = "user-local";

    @Test
    void convertsTheApplicationTraceIdToW3cTraceparent() {
        assertThat(CanonicalTargetIntakeMaterializer.traceparent(
                        "TRACE_ae3fa9df57c76361ca14af2948ddba85"))
                .isEqualTo("00-ae3fa9df57c76361ca14af2948ddba85-0000000000000001-01");
    }

    @Test
    void requiresAccessSessionToUseTheActivationTenantSurrogate() {
        CaseAccessSessionEntity access = access(TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.USER);

        CanonicalTargetIntakeMaterializer.requireActor(
                access, TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.USER);
    }

    @Test
    void rejectsARequestForAnotherCase() {
        CaseAccessSessionEntity access = access(TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.USER);

        assertRejected(access, TARGET_TENANT_SURROGATE, "CASE_TARGET_002", ACTOR_ID, ActorRole.USER);
    }

    @Test
    void rejectsAnotherActor() {
        CaseAccessSessionEntity access = access(TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.USER);

        assertRejected(access, TARGET_TENANT_SURROGATE, CASE_ID, "merchant-local", ActorRole.USER);
    }

    @Test
    void rejectsAnotherRole() {
        CaseAccessSessionEntity access = access(TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.USER);

        assertRejected(access, TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.MERCHANT);
    }

    @Test
    void rejectsAnAccessSessionFromAnotherTenant() {
        CaseAccessSessionEntity access = access("default", CASE_ID, ACTOR_ID, ActorRole.USER);

        assertRejected(access, TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.USER);
    }

    @Test
    void bindsTheAgentRunToTheExactPersistedIntakeEpoch() {
        TargetIntakeActivationGrant activation = activation();
        TargetIntakeMessageRequest request = request(activation);
        CaseRoomEpochEntity epoch = matchingEpoch();

        assertThat(CanonicalTargetIntakeMaterializer.requireEpochAuthority(
                        epoch, request, activation, pins()))
                .isEqualTo("CRE_1");
    }

    @Test
    void persistsTheLogicalRunWithTheExactPersistedEpochId() {
        TargetIntakeActivationGrant activation = activation();
        TargetIntakeMessageRequest request = request(activation);
        CaseRoomEpochEntity epoch = matchingEpoch();
        AccessSessionResolver accessSessions = Mockito.mock(AccessSessionResolver.class);
        AgentSessionResolver agentSessions = Mockito.mock(AgentSessionResolver.class);
        ParticipantService participants = Mockito.mock(ParticipantService.class);
        IntakePrivateThreadRegistrar threadRegistrar = Mockito.mock(IntakePrivateThreadRegistrar.class);
        IntakeDomainSnapshotPublisher snapshots = Mockito.mock(IntakeDomainSnapshotPublisher.class);
        IntakeTurnEventPublisher events = Mockito.mock(IntakeTurnEventPublisher.class);
        IntakeGraphCommandFactory commands = Mockito.mock(IntakeGraphCommandFactory.class);
        AgentRunCommandBindingFactory bindings = Mockito.mock(AgentRunCommandBindingFactory.class);
        AgentRunLedger ledger = Mockito.mock(AgentRunLedger.class);
        TargetE2EGraphEnvelopeCodec envelopes = Mockito.mock(TargetE2EGraphEnvelopeCodec.class);
        TargetIntakeCommandMaterialStore materialStore = Mockito.mock(TargetIntakeCommandMaterialStore.class);
        JdbcTargetE2eApiAuthority activationAuthority = Mockito.mock(JdbcTargetE2eApiAuthority.class);
        CaseRoomEpochRepository epochs = Mockito.mock(CaseRoomEpochRepository.class);
        TargetIntakeRuntimePins pins = pins();
        String requestHash = "c".repeat(64);
        String logicalInputHash = "d".repeat(64);

        when(activationAuthority.resolveIntakeRuntimePins(activation, pins)).thenReturn(pins);
        when(epochs.findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                        CASE_ID, RoomType.INTAKE, activation.roomEpoch()))
                .thenReturn(Optional.of(epoch));
        when(accessSessions.resolve(activation.tenantSurrogate(), CASE_ID, request.actor()))
                .thenReturn(access(TARGET_TENANT_SURROGATE, CASE_ID, ACTOR_ID, ActorRole.USER));
        AgentConversationSessionEntity session = Mockito.mock(AgentConversationSessionEntity.class);
        when(session.getId()).thenReturn("SESSION_1");
        when(agentSessions.resolve(any(), any(), any(), any(), any())).thenReturn(session);
        IntakeGraphThreadBinding thread = Mockito.mock(IntakeGraphThreadBinding.class);
        IntakePrivateThreadRegistration registration = Mockito.mock(IntakePrivateThreadRegistration.class);
        when(thread.registration()).thenReturn(registration);
        when(registration.threadId()).thenReturn("grt.v1.0123456789abcdef0123456789abcdef");
        when(threadRegistrar.register(any(IntakePrivateThreadRegistrationFactory.IssueRequest.class)))
                .thenReturn(IntakeGraphBindingStore.WriteReceipt.created(thread));
        IntakeSnapshotReference snapshot = Mockito.mock(IntakeSnapshotReference.class);
        when(snapshots.publishOrLoad(any()))
                .thenReturn(IntakeGraphBindingStore.WriteReceipt.created(snapshot));
        RoomGraphCommand.SnapshotRef eventPayload = new RoomGraphCommand.SnapshotRef(
                "EVENT_1", "event.v1", "memory://event", "e".repeat(64), 1);
        var event = Mockito.mock(com.example.dispute.workflow.application.intake.IntakeEventReference.class);
        when(event.payloadRef()).thenReturn(eventPayload);
        when(event.sequenceNo()).thenReturn(1L);
        var eventAllocation = new IntakeGraphBindingStore.EventAllocation(1, Optional.of(event));
        when(events.allocate(any(), any(), any())).thenReturn(eventAllocation);
        RoomGraphCommand graph = Mockito.mock(RoomGraphCommand.class);
        when(graph.roomType()).thenReturn(RoomType.INTAKE);
        when(graph.roomEpoch()).thenReturn(activation.roomEpoch());
        when(graph.processRevision()).thenReturn(activation.processRevision());
        when(graph.requestHash()).thenReturn(requestHash);
        String messageIdentity = java.util.UUID.nameUUIDFromBytes(
                        (activation.activationId() + "\n" + request.messageId())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
        when(graph.logicalRunId()).thenReturn("target-intake-run:" + messageIdentity);
        when(graph.attemptId()).thenReturn("target-intake-attempt:logical-epoch-test:1");
        when(graph.commandId()).thenReturn("intake-message:logical-epoch-test");
        when(commands.create(any())).thenReturn(graph);
        TargetE2EGraphCommandEnvelope envelope = Mockito.mock(TargetE2EGraphCommandEnvelope.class);
        when(envelope.commandHash()).thenReturn("a".repeat(64));
        when(envelope.commandEnvelopeHash()).thenReturn("b".repeat(64));
        when(envelopes.wrapCommand(activation.activationId(), activation.roomFencingToken(), graph))
                .thenReturn(envelope);
        AgentRunCommandBindingFactory.Binding binding = new AgentRunCommandBindingFactory.Binding(
                logicalInputHash, requestHash, "{\"command\":\"canonical\"}");
        when(bindings.bind(any(), eq(graph))).thenReturn(binding);
        when(ledger.createOrLoad(any())).thenAnswer(invocation -> {
            CreateLogicalRun create = invocation.getArgument(0);
            return new LogicalRun(
                    create.agentRunId(), create.caseId(), create.logicalIdempotencyKey(), create.protocol(),
                    create.executorKind(), create.roomEpochId(), create.roomEpoch(), create.processRevision(),
                    create.fencingToken(), "RUNNING", null, null, "agent-run-attempt-lineage.v1",
                    create.logicalInputHash(), create.attemptLimit(), create.deadlineAt(), 0);
        });
        when(ledger.startNextAttempt(any(), any(), any())).thenAnswer(invocation -> {
            String agentRunId = invocation.getArgument(0);
            AgentRunLedger.AttemptAllocation allocation = invocation.getArgument(1);
            return new AgentRunLedger.Attempt(
                    allocation.command().attemptId(), agentRunId, allocation.attemptNo(),
                    AgentRunAttemptStatus.RUNNING, false, false, 0, null, request.createdAt(), null, 0,
                    "agent-run-attempt-lineage.v1", allocation.command().commandId(), requestHash,
                    logicalInputHash, "{\"command\":\"canonical\"}", null, false, 0, null);
        });
        when(materialStore.append(any(), any())).thenReturn(new TargetIntakeCommandMaterialStore.AppendResult(
                TargetIntakeCommandMaterialStore.AppendDisposition.STORED,
                "admission-1", request.createdAt(), "f".repeat(64)));

        CanonicalTargetIntakeMaterializer materializer = new CanonicalTargetIntakeMaterializer(
                accessSessions, agentSessions, participants, threadRegistrar, snapshots, events, commands,
                bindings, ledger, envelopes, materialStore, activationAuthority, epochs, pins,
                Clock.fixed(request.createdAt(), ZoneOffset.UTC));

        materializer.materialize(request);

        ArgumentCaptor<CreateLogicalRun> logicalRun = ArgumentCaptor.forClass(CreateLogicalRun.class);
        verify(ledger).createOrLoad(logicalRun.capture());
        assertThat(logicalRun.getValue().roomEpochId()).isEqualTo("CRE_1");
    }

    @Test
    void rejectsAnEpochWithAnotherFencingToken() {
        TargetIntakeActivationGrant activation = activation();
        TargetIntakeMessageRequest request = request(activation);
        CaseRoomEpochEntity epoch = matchingEpoch();
        when(epoch.getFencingToken()).thenReturn(2L);

        assertThatThrownBy(() -> CanonicalTargetIntakeMaterializer.requireEpochAuthority(
                        epoch, request, activation, pins()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("target Intake activation conflicts with persisted room epoch authority");
    }

    private static CaseAccessSessionEntity access(
            String domainTenant, String caseId, String actorId, ActorRole actorRole) {
        return CaseAccessSessionEntity.create(
                "access-1", domainTenant, caseId, actorId, actorRole,
                PermissionLevel.PARTY_USER, "test");
    }

    private static void assertRejected(
            CaseAccessSessionEntity access,
            String tenantId,
            String caseId,
            String actorId,
            ActorRole actorRole) {
        assertThatThrownBy(() -> CanonicalTargetIntakeMaterializer.requireActor(
                        access, tenantId, caseId, actorId, actorRole))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("target Intake access session does not match the active authority");
    }

    private static TargetIntakeActivationGrant activation() {
        return new TargetIntakeActivationGrant(
                TargetIntakeActivationGrant.TARGET_LANE,
                "p9act.v1.0123456789abcdef0123456789abcdef",
                "a".repeat(64),
                TARGET_TENANT_SURROGATE,
                CASE_ID,
                0,
                1,
                0,
                0,
                "case-process:tenant-target-activation:CASE_TARGET_001",
                "p9-control-build",
                Instant.parse("2026-07-30T00:00:00Z"));
    }

    private static TargetIntakeMessageRequest request(TargetIntakeActivationGrant activation) {
        return new TargetIntakeMessageRequest(
                CASE_ID,
                "ROOM_1",
                "MSG_1",
                com.example.dispute.room.domain.MessageType.PARTY_TEXT,
                "message",
                List.of(),
                new AuthenticatedActor(ACTOR_ID, ActorRole.USER),
                "idempotency-1",
                "TRACE_ae3fa9df57c76361ca14af2948ddba85",
                Instant.parse("2026-07-29T00:00:00Z"),
                activation);
    }

    private static TargetIntakeRuntimePins pins() {
        return new TargetIntakeRuntimePins(
                "case-build",
                "agent-build",
                "b".repeat(64),
                "graph-code-build",
                "c".repeat(64),
                "agent-profile",
                "prompt-v1",
                "model-profile",
                "policy-v1",
                "guardrail-v1",
                "tool-policy-v1",
                "memory-policy-v1",
                "key-1");
    }

    private static CaseRoomEpochEntity matchingEpoch() {
        CaseRoomEpochEntity epoch = Mockito.mock(CaseRoomEpochEntity.class);
        when(epoch.getId()).thenReturn("CRE_1");
        when(epoch.getTenantSurrogate()).thenReturn(TARGET_TENANT_SURROGATE);
        when(epoch.getCaseId()).thenReturn(CASE_ID);
        when(epoch.getRoomId()).thenReturn("ROOM_1");
        when(epoch.getRoomType()).thenReturn(RoomType.INTAKE);
        when(epoch.getWriterMode()).thenReturn(WriterMode.TEMPORAL);
        when(epoch.getLifecycleStatus()).thenReturn(EpochLifecycleStatus.ACTIVE);
        when(epoch.getProvisioningStatus()).thenReturn(EpochProvisioningStatus.READY);
        when(epoch.getRoomEpoch()).thenReturn(0L);
        when(epoch.getFencingToken()).thenReturn(1L);
        when(epoch.getProcessRevision()).thenReturn(0L);
        when(epoch.getRoomRevision()).thenReturn(0L);
        when(epoch.getTemporalWorkflowId())
                .thenReturn("case-process:tenant-target-activation:CASE_TARGET_001");
        when(epoch.getTemporalBuildId()).thenReturn("p9-control-build");
        when(epoch.getGraphKey()).thenReturn("all-rooms.target-e2e.v1");
        when(epoch.getGraphVersion()).thenReturn("target-e2e-graph.2026-07-27.1");
        when(epoch.getCheckpointSchemaVersion()).thenReturn("target-e2e-checkpoint.v1");
        return epoch;
    }
}
