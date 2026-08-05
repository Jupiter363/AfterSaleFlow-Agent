package com.example.dispute.workflow.targete2e.ingress.materialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.agentstream.application.AgentRunLedger.CreateLogicalRun;
import com.example.dispute.agentstream.application.AgentRunLedger.LogicalRun;
import com.example.dispute.casecore.domain.CasePartyAssignment;
import com.example.dispute.casecore.domain.CaseSourceType;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.config.ActorRole;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.room.application.AccessSessionResolver;
import com.example.dispute.room.application.AgentSessionResolver;
import com.example.dispute.room.application.IntakeCaseSeedMetadata;
import com.example.dispute.room.application.IntakeLobbySeed;
import com.example.dispute.room.application.ParticipantService;
import com.example.dispute.room.domain.PermissionLevel;
import com.example.dispute.room.infrastructure.persistence.entity.AgentConversationSessionEntity;
import com.example.dispute.room.infrastructure.persistence.entity.CaseAccessSessionEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
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
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.WriterActivationStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseProcessProjectionRepository;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphCommandEnvelope;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeActivationGrant;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeCommandIdentity;
import com.example.dispute.workflow.targete2e.ingress.TargetIntakeMessageRequest;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eApiAuthority;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    void replaysTheInitialFormAcrossActivationRotationWithTheOriginalRunAndDeadline() {
        assertOpeningMaterialization(
                false, "OPEN", 0L, 1L, true);
    }

    @Test
    void materializesRespondentOpeningOnlyFromWaitingPartyAndReplaysWithoutNewAllocation() {
        assertThat(TargetIntakeMessageRequest.SourceType.values())
                .extracting(Enum::name)
                .contains("RESPONDENT_OPENING");
        assertOpeningMaterialization(
                true, "WAITING_PARTY", 1L, 1L, true);
    }

    @Test
    void rejectsRespondentOpeningPhaseAndSequenceBeforeLedgerOrMaterialAdmission() {
        assertThat(TargetIntakeMessageRequest.SourceType.values())
                .extracting(Enum::name)
                .contains("RESPONDENT_OPENING");
        assertOpeningMaterialization(
                true, "READY_TO_CONFIRM", 1L, 1L, false);
        assertOpeningMaterialization(
                true, "WAITING_PARTY", 1L, 2L, false);
    }

    @Test
    void rejectsRespondentOpeningForWrongCaseActorOrRoleBeforeLedgerOrMaterialAdmission() {
        assertOpeningMaterialization(
                true,
                "WAITING_PARTY",
                1L,
                1L,
                false,
                new AuthenticatedActor("merchant-other", ActorRole.MERCHANT));
        assertOpeningMaterialization(
                true,
                "WAITING_PARTY",
                1L,
                1L,
                false,
                new AuthenticatedActor(ACTOR_ID, ActorRole.USER));
    }

    private void assertOpeningMaterialization(
            boolean respondentOpening,
            String projectionPhase,
            long projectionSequence,
            long eventSequence,
            boolean expectedSuccess) {
        assertOpeningMaterialization(
                respondentOpening,
                projectionPhase,
                projectionSequence,
                eventSequence,
                expectedSuccess,
                respondentOpening
                        ? new AuthenticatedActor("merchant-local", ActorRole.MERCHANT)
                        : new AuthenticatedActor(ACTOR_ID, ActorRole.USER));
    }

    private void assertOpeningMaterialization(
            boolean respondentOpening,
            String projectionPhase,
            long projectionSequence,
            long eventSequence,
            boolean expectedSuccess,
            AuthenticatedActor openingActor) {
        TargetIntakeActivationGrant activation = activation();
        TargetIntakeMessageRequest request =
                respondentOpening
                        ? respondentOpeningRequest(activation, openingActor)
                        : initialFormRequest(activation);
        TargetIntakeActivationGrant rotatedActivation = rotatedActivation();
        TargetIntakeMessageRequest rotatedRequest =
                respondentOpening
                        ? respondentOpeningRequest(rotatedActivation, openingActor)
                        : initialFormRequest(rotatedActivation);
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
        FulfillmentCaseRepository cases = Mockito.mock(FulfillmentCaseRepository.class);
        CaseIntakeDossierRepository dossiers = Mockito.mock(CaseIntakeDossierRepository.class);
        CaseRoomEpochRepository epochs = Mockito.mock(CaseRoomEpochRepository.class);
        CaseProcessProjectionRepository projections = Mockito.mock(CaseProcessProjectionRepository.class);
        CaseProcessProjectionEntity projection =
                matchingProjection(projectionPhase, projectionSequence);
        TargetIntakeRuntimePins pins = pins();
        String requestHash = "c".repeat(64);
        String logicalInputHash = "d".repeat(64);

        when(activationAuthority.resolveIntakeRuntimePins(any(), eq(pins))).thenReturn(pins);
        when(epochs.findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                        CASE_ID, RoomType.INTAKE, activation.roomEpoch()))
                .thenReturn(Optional.of(epoch));
        when(projections.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(projection));
        FulfillmentCaseEntity dispute = Mockito.mock(FulfillmentCaseEntity.class);
        when(dispute.getId()).thenReturn(CASE_ID);
        when(dispute.getDescription()).thenReturn("The signed parcel was not received.");
        when(dispute.getOrderId()).thenReturn("ORDER_1");
        when(dispute.getAfterSaleId()).thenReturn("AFTER_SALE_1");
        when(dispute.getLogisticsId()).thenReturn("LOGISTICS_1");
        when(dispute.getInitiatorRole()).thenReturn(ActorRole.USER);
        when(dispute.getRespondentRole()).thenReturn(ActorRole.MERCHANT);
        when(dispute.partyAssignment())
                .thenReturn(
                        new CasePartyAssignment(
                                ACTOR_ID,
                                ActorRole.USER,
                                "merchant-local",
                                ActorRole.MERCHANT));
        when(dispute.getDisputeType()).thenReturn("PRODUCT_QUALITY");
        when(dispute.getSourceType()).thenReturn(CaseSourceType.EXTERNAL_IMPORT);
        when(dispute.getMetadataJson())
                .thenReturn(IntakeCaseSeedMetadata.encode(
                        new IntakeLobbySeed(
                                "ORDER_1",
                                "AFTER_SALE_1",
                                "LOGISTICS_1",
                                "USER",
                                "The product shuts down repeatedly.",
                                "REPLACE_OR_REPAIR"),
                        "EXTERNAL_IMPORT"));
        when(dispute.getCaseType()).thenReturn("FULFILLMENT_DISPUTE");
        when(dispute.getTitle()).thenReturn("Target E2E case");
        when(cases.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(dispute));
        when(dossiers.findByCaseIdAndRoomType(CASE_ID, com.example.dispute.room.domain.RoomType.INTAKE))
                .thenReturn(Optional.empty());
        when(accessSessions.resolve(activation.tenantSurrogate(), CASE_ID, request.actor()))
                .thenReturn(
                        access(
                                TARGET_TENANT_SURROGATE,
                                CASE_ID,
                                request.actor().actorId(),
                                request.actor().role()));
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
        when(event.sequenceNo()).thenReturn(eventSequence);
        var newEventAllocation =
                new IntakeGraphBindingStore.EventAllocation(eventSequence, Optional.empty());
        var replayEventAllocation =
                new IntakeGraphBindingStore.EventAllocation(eventSequence, Optional.of(event));
        when(events.allocate(any(), any(), any()))
                .thenReturn(newEventAllocation, replayEventAllocation);
        when(events.publish(any())).thenReturn(IntakeGraphBindingStore.WriteReceipt.created(event));
        RoomGraphCommand graph = Mockito.mock(RoomGraphCommand.class);
        when(graph.roomType()).thenReturn(RoomType.INTAKE);
        when(graph.roomEpoch()).thenReturn(activation.roomEpoch());
        when(graph.processRevision()).thenReturn(activation.processRevision());
        when(graph.requestHash()).thenReturn(requestHash);
        String messageIdentity =
                CanonicalTargetIntakeMaterializer.durableMessageIdentity(activation, request);
        when(graph.logicalRunId()).thenReturn("target-intake-run:" + messageIdentity);
        when(graph.attemptId()).thenReturn("target-intake-attempt:" + messageIdentity + ":1");
        when(graph.commandId()).thenReturn("intake-message:" + messageIdentity);
        when(graph.eventRef()).thenReturn(eventPayload);
        when(graph.deadlineAt()).thenReturn(request.commandDeadlineAt());
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
        when(materialStore.readByRoute(any())).thenReturn(Optional.empty());

        CanonicalTargetIntakeMaterializer materializer = new CanonicalTargetIntakeMaterializer(
                accessSessions, agentSessions, participants, threadRegistrar, snapshots, events, commands,
                bindings, ledger, envelopes, materialStore, activationAuthority, cases, dossiers, epochs,
                projections, pins, new ObjectMapper(), Clock.fixed(request.createdAt(), ZoneOffset.UTC));

        if (!expectedSuccess) {
            assertThatThrownBy(() -> materializer.materialize(request))
                    .isInstanceOf(IllegalStateException.class);
            verify(ledger, never()).createOrLoad(any());
            verify(ledger, never()).startNextAttempt(any(), any(), any());
            verify(materialStore, never()).append(any(), any());
            return;
        }

        TargetIntakeMaterializer.MaterializedIntake first = materializer.materialize(request);
        ArgumentCaptor<CommandAdmission> admission = ArgumentCaptor.forClass(CommandAdmission.class);
        ArgumentCaptor<IntakeCommandExecutionContext> context =
                ArgumentCaptor.forClass(IntakeCommandExecutionContext.class);
        verify(materialStore).append(admission.capture(), context.capture());
        TargetIntakeCommandMaterialStore.MaterialSnapshot stored =
                new TargetIntakeCommandMaterialStore.MaterialSnapshot(
                        "admission-1",
                        admission.getValue(),
                        context.getValue(),
                        "f".repeat(64),
                        request.createdAt());
        when(materialStore.readByRoute(any())).thenReturn(Optional.of(stored));
        TargetIntakeMaterializer.MaterializedIntake replay = materializer.materialize(rotatedRequest);

        assertThat(first.runId()).isEqualTo("target-intake-run:" + messageIdentity);
        assertThat(replay.runId()).isEqualTo(first.runId());
        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(replay.deadlineAt()).isEqualTo(first.deadlineAt());
        assertThat(first.deadlineAt()).isEqualTo(request.commandDeadlineAt());
        assertThat(CanonicalTargetIntakeMaterializer.durableMessageIdentity(
                        rotatedActivation, rotatedRequest))
                .isEqualTo(messageIdentity);
        assertThat(TargetIntakeCommandIdentity.messageCommandId(activation, request))
                .isEqualTo("intake-message:" + messageIdentity);

        ArgumentCaptor<CreateLogicalRun> logicalRun = ArgumentCaptor.forClass(CreateLogicalRun.class);
        verify(ledger).createOrLoad(logicalRun.capture());
        assertThat(logicalRun.getValue().roomEpochId()).isEqualTo("CRE_1");
        ArgumentCaptor<IntakeGraphCommandFactory.CommandRequest> graphRequest =
                ArgumentCaptor.forClass(IntakeGraphCommandFactory.CommandRequest.class);
        verify(commands).create(graphRequest.capture());
        assertThat(graphRequest.getValue().stageCode()).isEqualTo(projectionPhase);
        assertThat(graphRequest.getValue().stageSequence()).isEqualTo(projectionSequence);
        ArgumentCaptor<IntakeDomainSnapshotPublisher.SnapshotRequest> snapshotRequest =
                ArgumentCaptor.forClass(IntakeDomainSnapshotPublisher.SnapshotRequest.class);
        verify(snapshots).publishOrLoad(snapshotRequest.capture());
        assertThat(snapshotRequest.getValue().sourceRefs())
                .containsExactly(request.messageId());
        assertThat(snapshotRequest.getValue().ownMessages()).isEmpty();
        assertThat(snapshotRequest.getValue().initialCaseFacts().path("initiator_role").asText())
                .isEqualTo("USER");
        assertThat(snapshotRequest.getValue().initialCaseFacts().path("order_reference").asText())
                .isEqualTo("ORDER_1");
        assertThat(snapshotRequest
                        .getValue()
                        .initialCaseFacts()
                        .path("requested_outcome_hint")
                        .asText())
                .isEqualTo("REPLACE_OR_REPAIR");
        assertThat(snapshotRequest
                        .getValue()
                        .initialCaseFacts()
                        .path("requested_outcome_hint")
                        .asText())
                .isNotEqualTo(dispute.getDisputeType());
        ArgumentCaptor<IntakeTurnEventPublisher.EventRequest> eventRequest =
                ArgumentCaptor.forClass(IntakeTurnEventPublisher.EventRequest.class);
        verify(events).publish(eventRequest.capture());
        assertThat(eventRequest.getValue().sourceType().name())
                .isEqualTo(respondentOpening ? "RESPONDENT_OPENING" : "INITIAL_FORM");
        assertThat(eventRequest.getValue().sequenceNo()).isEqualTo(1L);
        assertThat(eventRequest.getValue().messageId()).isEqualTo(request.messageId());
        assertThat(eventRequest.getValue().sourceRefs()).containsExactly(request.messageId());
        assertThat(eventRequest.getValue().text()).isEqualTo(request.text());
        assertThat(eventRequest.getValue().occurredAt()).isEqualTo(request.createdAt());
        verify(activationAuthority, times(2)).resolveIntakeRuntimePins(any(), eq(pins));
        verify(epochs, times(2))
                .findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                        CASE_ID, RoomType.INTAKE, activation.roomEpoch());
        verify(projections, times(2)).findByIdForUpdate(CASE_ID);
        verify(cases, times(2)).findByIdForUpdate(CASE_ID);
        verify(accessSessions, times(2))
                .resolve(TARGET_TENANT_SURROGATE, CASE_ID, request.actor());
        verify(participants).activateExistingParty(any(), any(), any());
        ArgumentCaptor<String> agentPromptProfile = ArgumentCaptor.forClass(String.class);
        verify(agentSessions).resolve(
                any(), eq(com.example.dispute.room.domain.RoomType.INTAKE), any(),
                agentPromptProfile.capture(), eq(pins.memoryPolicyVersion()));
        String expectedPromptProfile =
                "DISPUTE_INTAKE_OFFICER:" + request.actor().role().name() + ":v1";
        assertThat(agentPromptProfile.getValue()).isEqualTo(expectedPromptProfile);
        ArgumentCaptor<IntakePrivateThreadRegistrationFactory.IssueRequest> registrationRequest =
                ArgumentCaptor.forClass(IntakePrivateThreadRegistrationFactory.IssueRequest.class);
        verify(threadRegistrar).register(registrationRequest.capture());
        assertThat(registrationRequest.getValue().versionPins().promptVersion())
                .isEqualTo(expectedPromptProfile);
        assertThat(registrationRequest.getValue().versionPins().modelProfileId())
                .isEqualTo(pins.modelProfileId());
        verify(events, times(1)).allocate(any(), any(), any());
        verify(materialStore, times(2)).readByRoute(any());
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

    @Test
    void rejectsAProjectionThatDoesNotMatchTheIntakeActivationAuthority() {
        TargetIntakeActivationGrant activation = activation();
        CaseProcessProjectionEntity projection = matchingProjection("OPEN", 0L);
        when(projection.getTemporalBuildId()).thenReturn("another-build");

        assertThatThrownBy(() -> CanonicalTargetIntakeMaterializer.requireProjectionAuthority(
                        projection, request(activation), activation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("target Intake activation conflicts with persisted process projection authority");
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

    private static TargetIntakeActivationGrant rotatedActivation() {
        return new TargetIntakeActivationGrant(
                TargetIntakeActivationGrant.TARGET_LANE,
                "p9act.v1.abcdefabcdefabcdefabcdefabcdefab",
                "d".repeat(64),
                TARGET_TENANT_SURROGATE,
                CASE_ID,
                0,
                1,
                0,
                0,
                "case-process:tenant-target-activation:CASE_TARGET_001",
                "p9-control-build",
                Instant.parse("2026-07-30T12:00:00Z"));
    }

    private static TargetIntakeMessageRequest request(TargetIntakeActivationGrant activation) {
        return TargetIntakeMessageRequest.roomMessage(
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

    private static TargetIntakeMessageRequest initialFormRequest(
            TargetIntakeActivationGrant activation) {
        return TargetIntakeMessageRequest.initialForm(
                CASE_ID,
                "ROOM_1",
                "INTAKE_FORM_" + CASE_ID,
                "The signed parcel was not received.",
                new AuthenticatedActor(ACTOR_ID, ActorRole.USER),
                "target-intake-opening:" + CASE_ID,
                "TRACE_ae3fa9df57c76361ca14af2948ddba85",
                Instant.parse("2026-07-29T00:00:00Z"),
                activation);
    }

    private static TargetIntakeMessageRequest respondentOpeningRequest(
            TargetIntakeActivationGrant activation, AuthenticatedActor actor) {
        return TargetIntakeMessageRequest.respondentOpening(
                CASE_ID,
                "ROOM_1",
                actor,
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

    private static CaseProcessProjectionEntity matchingProjection(String roomPhase, long lastCommandSequence) {
        CaseProcessProjectionEntity projection = Mockito.mock(CaseProcessProjectionEntity.class);
        when(projection.getTenantSurrogate()).thenReturn(TARGET_TENANT_SURROGATE);
        when(projection.getCaseId()).thenReturn(CASE_ID);
        when(projection.getCurrentRoom()).thenReturn(RoomType.INTAKE.name());
        when(projection.getWriterMode()).thenReturn(WriterMode.TEMPORAL);
        when(projection.getWriterActivationStatus()).thenReturn(WriterActivationStatus.READY);
        when(projection.getProcessRevision()).thenReturn(0L);
        when(projection.getRoomEpoch()).thenReturn(0L);
        when(projection.getFencingToken()).thenReturn(1L);
        when(projection.getTemporalWorkflowId())
                .thenReturn("case-process:tenant-target-activation:CASE_TARGET_001");
        when(projection.getTemporalBuildId()).thenReturn("p9-control-build");
        when(projection.getRoomPhase()).thenReturn(roomPhase);
        when(projection.getLastCommandSequence()).thenReturn(lastCommandSequence);
        return projection;
    }
}
