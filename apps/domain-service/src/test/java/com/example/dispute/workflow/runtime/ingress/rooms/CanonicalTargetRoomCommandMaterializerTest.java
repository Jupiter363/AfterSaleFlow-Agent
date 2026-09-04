package com.example.dispute.workflow.runtime.ingress.rooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory;
import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.room.application.EvidenceAgentTurnCommand;
import com.example.dispute.room.application.EvidenceContextEnvelopeV1;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.FrozenIntakeSubmissionAuthority;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomObjectIndex;
import com.example.dispute.workflow.runtime.graph.ProductionGraphEnvelopeCodec;
import com.example.dispute.workflow.runtime.ingress.materialization.TargetIntakeRuntimePins;
import com.example.dispute.workflow.runtime.persistence.JdbcProductionApiAuthority;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCommandMaterial;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceCompletionCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.hearing.TargetHearingCommandMaterialStore;
import com.example.dispute.workflow.runtime.rooms.review.TargetReviewCommandMaterialStore;
import com.example.dispute.workflow.runtime.temporal.TargetRoomEpochSelectionAuthority;
import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomProtocol;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CanonicalTargetRoomCommandMaterializerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Instant NOW = Instant.parse("2026-08-07T02:00:00Z");

    @Test
    void reviewThreadIdsMatchGraphContractAndRemainReplayStable() {
        CaseRoomEpochEntity epoch = matchingEpoch();
        AuthenticatedActor reviewer =
                new AuthenticatedActor("reviewer-local", ActorRole.PLATFORM_REVIEWER);

        String first = CanonicalTargetRoomCommandMaterializer.graphThreadId(
                epoch, reviewer, RoomType.REVIEW, "review-command-1");
        String replay = CanonicalTargetRoomCommandMaterializer.graphThreadId(
                epoch, reviewer, RoomType.REVIEW, "review-command-1");
        String sameRoomNextCommand = CanonicalTargetRoomCommandMaterializer.graphThreadId(
                epoch, reviewer, RoomType.REVIEW, "review-command-2");
        String otherReviewer = CanonicalTargetRoomCommandMaterializer.graphThreadId(
                epoch,
                new AuthenticatedActor("reviewer-other", ActorRole.PLATFORM_REVIEWER),
                RoomType.REVIEW,
                "review-command-1");

        assertThat(first).matches("^grt\\.v1\\.[0-9a-f]{32}$");
        assertThat(replay).isEqualTo(first);
        assertThat(sameRoomNextCommand).isEqualTo(first);
        assertThat(otherReviewer).isNotEqualTo(first);
    }

    @Test
    void targetEvidenceMaterializesPersistedClerkTurnWithFrozenMatrixAuthority() throws Exception {
        Map<String, byte[]> objects = new LinkedHashMap<>();
        MinioClient minio = mock(MinioClient.class);
        doAnswer(
                        invocation -> {
                            PutObjectArgs args = invocation.getArgument(0);
                            objects.put(args.object(), args.stream().readAllBytes());
                            return null;
                        })
                .when(minio)
                .putObject(any(PutObjectArgs.class));
        ProductionRoomObjectIndex objectIndex = mock(ProductionRoomObjectIndex.class);
        MinioProductionRoomCommandPayloadPublisher payloads =
                new MinioProductionRoomCommandPayloadPublisher(
                        minio, MAPPER, "production-runtime", "room-command-inputs", objectIndex);
        ProductionEvidenceTurnInvocationPublisher invocationPublisher =
                new ProductionEvidenceTurnInvocationPublisher(payloads, objectIndex, MAPPER);
        ProductionEvidenceManifestPublisher syntheticManifest =
                mock(ProductionEvidenceManifestPublisher.class);
        TargetEvidenceCommandMaterialStore evidence = mock(TargetEvidenceCommandMaterialStore.class);
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        when(ledger.createOrLoad(any()))
                .thenAnswer(
                        invocation -> {
                            AgentRunLedger.CreateLogicalRun allocation = invocation.getArgument(0);
                            AgentRunLedger.LogicalRun logical = mock(AgentRunLedger.LogicalRun.class);
                            when(logical.agentRunId()).thenReturn(allocation.agentRunId());
                            return logical;
                        });
        when(ledger.startNextAttempt(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            AgentRunLedger.AttemptAllocation allocation = invocation.getArgument(1);
                            AgentRunLedger.Attempt attempt = mock(AgentRunLedger.Attempt.class);
                            when(attempt.attemptId()).thenReturn(allocation.command().attemptId());
                            when(attempt.attemptNo()).thenReturn(allocation.attemptNo());
                            return attempt;
                        });

        CaseRoomEpochEntity epoch = matchingEpoch();
        CaseRoomEpochRepository epochs = mock(CaseRoomEpochRepository.class);
        when(epochs.findByCaseIdAndRoomTypeAndRoomEpochForUpdate("case-1", RoomType.EVIDENCE, 7L))
                .thenReturn(Optional.of(epoch));
        JdbcProductionApiAuthority authority = mock(JdbcProductionApiAuthority.class);
        when(authority.authorize(any(TargetRoomEpochSelectionAuthority.Request.class)))
                .thenReturn(Optional.of(grant()));
        CanonicalTargetRoomCommandMaterializer subject =
                new CanonicalTargetRoomCommandMaterializer(
                        epochs,
                        authority,
                        pins(),
                        ledger,
                        new AgentRunCommandBindingFactory(MAPPER),
                        new ProductionGraphEnvelopeCodec(MAPPER),
                        payloads,
                        objectIndex,
                        syntheticManifest,
                        invocationPublisher,
                        mock(ProductionReviewInvocationPublisher.class),
                        mock(JdbcTargetReviewInvocationFactsLoader.class),
                        evidence,
                        mock(TargetEvidenceCompletionCommandMaterialStore.class),
                        mock(TargetHearingCommandMaterialStore.class),
                        mock(TargetReviewCommandMaterialStore.class),
                        MAPPER,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        AcceptCaseCommand command = evidenceSubmitCommand();
        EvidenceAgentTurnCommand clerkTurn = clerkTurn();
        AuthenticatedActor actor = new AuthenticatedActor("party-1", ActorRole.USER);

        var firstReceipt = subject.materializeEvidenceSubmission(
                "case-1", "command-1", command, actor, "trace-1", clerkTurn);
        var replayReceipt = subject.materializeEvidenceSubmission(
                "case-1", "command-1", command, actor, "trace-1", clerkTurn);

        ArgumentCaptor<TargetEvidenceCommandMaterial> materials =
                ArgumentCaptor.forClass(TargetEvidenceCommandMaterial.class);
        verify(evidence, org.mockito.Mockito.times(2)).append(any(), materials.capture());
        assertThat(materials.getAllValues()).allSatisfy(material -> {
            assertThat(material.schemaVersion()).isEqualTo("production-runtime-evidence-command-material.v2");
            assertThat(material.request().streamProtocol())
                    .isEqualTo(AgentRunProtocol.V3.wireValue());
            assertThat(material.evidenceAgentTurnCommand()).isEqualTo(clerkTurn);
            assertThat(material.request().command().domainSnapshotRef().schemaVersion())
                    .isEqualTo("production-runtime-evidence-turn-invocation.v2");
            assertThat(material.request().command().eventRef().uri())
                    .isEqualTo("urn:production-runtime:timeline-event:event-1");
        });
        assertThat(materials.getAllValues().get(0)).isEqualTo(materials.getAllValues().get(1));
        assertThat(firstReceipt.logicalRunId())
                .isEqualTo(materials.getAllValues().get(0).request().logicalRunId());
        assertThat(replayReceipt).isEqualTo(firstReceipt);
        ArgumentCaptor<AgentRunLedger.CreateLogicalRun> logicalRuns =
                ArgumentCaptor.forClass(AgentRunLedger.CreateLogicalRun.class);
        verify(ledger, org.mockito.Mockito.times(2)).createOrLoad(logicalRuns.capture());
        assertThat(logicalRuns.getAllValues())
                .allSatisfy(
                        allocation -> {
                            assertThat(allocation.agentRunId())
                                    .isEqualTo(firstReceipt.logicalRunId());
                            assertThat(allocation.protocol()).isEqualTo(AgentRunProtocol.V3);
                        });
        assertThat(objects).hasSize(1);
        byte[] body = objects.values().iterator().next();
        ObjectNode invocation = (ObjectNode) MAPPER.readTree(body);
        assertThat(ContractJson.canonicalize(invocation)).isEqualTo(body);
        assertThat(invocation.path("schema_version").asText())
                .isEqualTo("production-runtime-evidence-turn-invocation.v2");
        assertThat(invocation.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrderElementsOf(
                        Set.of(
                                "schema_version",
                                "logical_run_id",
                                "tenant_surrogate",
                                "case_id",
                                "room_epoch",
                                "fencing_token",
                                "thread_id",
                                "actor_id",
                                "actor_role",
                                "actor_scope_hash",
                                "evidence_turn_request",
                                "invocation_hash"));
        assertThat(invocation.has("command_id")).isFalse();
        assertThat(invocation.has("attempt_id")).isFalse();
        assertThat(invocation.path("evidence_turn_request").path("context_envelope")
                        .path("current_event").path("attachment_refs").get(0).asText())
                .isEqualTo("EVIDENCE_REAL_1");
        assertThat(invocation.path("evidence_turn_request").path("context_envelope")
                        .path("intake_dossier_snapshot").path("payload")
                        .path("case_fact_matrix").path("matrix_version").asInt())
                .isEqualTo(8);
        assertThat(invocation.path("evidence_turn_request").path("agent_context")
                        .path("agent_session_id").asText())
                .isEqualTo("agent-session-private-1");
        assertThat(clerkTurn.agentContext().promptProfileId()).isEqualTo("evidence-clerk");
        assertThat(invocation.path("evidence_turn_request").path("agent_context")
                        .path("prompt_profile_id").asText())
                .isEqualTo(materials.getAllValues().get(0).request().command()
                        .invocationContext().promptProfileId());
        assertThat(invocation.path("evidence_turn_request").path("context_envelope")
                        .path("actor_snapshot").path("prompt_profile_id").asText())
                .isEqualTo(materials.getAllValues().get(0).request().command()
                        .invocationContext().promptProfileId());
        ObjectNode preimage = invocation.deepCopy();
        String invocationHash = preimage.remove("invocation_hash").asText();
        assertThat(invocationHash).isEqualTo(ContractJson.sha256Hex(preimage));
        assertThat(invocation.path("actor_scope_hash").asText())
                .isEqualTo(ContractJson.sha256Hex(
                        MAPPER.valueToTree(materials.getAllValues().get(0).request().command().actorScope())));
        verify(syntheticManifest, never()).publish(any());
        verify(objectIndex, org.mockito.Mockito.times(2)).bindInput(any(), any(), any(), any());

        assertThatThrownBy(
                        () -> subject.materialize(
                                "case-1", "command-2", command, actor, "trace-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("formal Evidence turn authority");
    }

    @Test
    void evidenceCommandsUseDistinctReplayStableThreadsWithoutChangingActorScope()
            throws Exception {
        Map<String, byte[]> objects = new LinkedHashMap<>();
        MinioClient minio = mock(MinioClient.class);
        doAnswer(
                        invocation -> {
                            PutObjectArgs args = invocation.getArgument(0);
                            objects.put(args.object(), args.stream().readAllBytes());
                            return null;
                        })
                .when(minio)
                .putObject(any(PutObjectArgs.class));
        ProductionRoomObjectIndex objectIndex = mock(ProductionRoomObjectIndex.class);
        MinioProductionRoomCommandPayloadPublisher payloads =
                new MinioProductionRoomCommandPayloadPublisher(
                        minio, MAPPER, "production-runtime", "room-command-inputs", objectIndex);
        ProductionEvidenceTurnInvocationPublisher invocationPublisher =
                new ProductionEvidenceTurnInvocationPublisher(payloads, objectIndex, MAPPER);
        TargetEvidenceCommandMaterialStore evidence = mock(TargetEvidenceCommandMaterialStore.class);
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        when(ledger.createOrLoad(any()))
                .thenAnswer(
                        invocation -> {
                            AgentRunLedger.CreateLogicalRun allocation = invocation.getArgument(0);
                            AgentRunLedger.LogicalRun logical = mock(AgentRunLedger.LogicalRun.class);
                            when(logical.agentRunId()).thenReturn(allocation.agentRunId());
                            return logical;
                        });
        when(ledger.startNextAttempt(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            AgentRunLedger.AttemptAllocation allocation = invocation.getArgument(1);
                            AgentRunLedger.Attempt attempt = mock(AgentRunLedger.Attempt.class);
                            when(attempt.attemptId()).thenReturn(allocation.command().attemptId());
                            when(attempt.attemptNo()).thenReturn(allocation.attemptNo());
                            return attempt;
                        });
        CaseRoomEpochEntity epoch = matchingEpoch();
        CaseRoomEpochRepository epochs = mock(CaseRoomEpochRepository.class);
        when(epochs.findByCaseIdAndRoomTypeAndRoomEpochForUpdate(
                        "case-1", RoomType.EVIDENCE, 7L))
                .thenReturn(Optional.of(epoch));
        JdbcProductionApiAuthority authority = mock(JdbcProductionApiAuthority.class);
        when(authority.authorize(any(TargetRoomEpochSelectionAuthority.Request.class)))
                .thenReturn(Optional.of(grant()));
        CanonicalTargetRoomCommandMaterializer subject =
                new CanonicalTargetRoomCommandMaterializer(
                        epochs,
                        authority,
                        pins(),
                        ledger,
                        new AgentRunCommandBindingFactory(MAPPER),
                        new ProductionGraphEnvelopeCodec(MAPPER),
                        payloads,
                        objectIndex,
                        mock(ProductionEvidenceManifestPublisher.class),
                        invocationPublisher,
                        mock(ProductionReviewInvocationPublisher.class),
                        mock(JdbcTargetReviewInvocationFactsLoader.class),
                        evidence,
                        mock(TargetEvidenceCompletionCommandMaterialStore.class),
                        mock(TargetHearingCommandMaterialStore.class),
                        mock(TargetReviewCommandMaterialStore.class),
                        MAPPER,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        AuthenticatedActor actor = new AuthenticatedActor("party-1", ActorRole.USER);

        subject.materializeEvidenceOpening(
                "case-1",
                "evidence-opening-binding",
                evidenceOpeningCommand(7L, 3L),
                actor,
                "trace-opening-binding",
                openingTurn(7L, 23L, "party-1"));
        subject.materializeEvidenceSubmission(
                "case-1",
                "evidence-submit-binding",
                evidenceSubmitCommand(),
                actor,
                "trace-submit-binding",
                clerkTurn());
        subject.materializeEvidenceSubmission(
                "case-1",
                "evidence-submit-binding",
                evidenceSubmitCommand(),
                actor,
                "trace-submit-binding",
                clerkTurn());
        subject.materializeEvidenceSubmission(
                "case-1",
                "evidence-submit-next-batch",
                evidenceSubmitCommand(),
                actor,
                "trace-submit-next-batch",
                clerkTurn());
        subject.materializeEvidenceSubmission(
                "case-1",
                "evidence-submit-other-actor",
                evidenceSubmitCommand(),
                new AuthenticatedActor("party-2", ActorRole.USER),
                "trace-submit-other-actor",
                clerkTurn("party-2"));

        ArgumentCaptor<TargetEvidenceCommandMaterial> materials =
                ArgumentCaptor.forClass(TargetEvidenceCommandMaterial.class);
        verify(evidence, org.mockito.Mockito.times(5)).append(any(), materials.capture());
        var opening = materials.getAllValues().get(0).request().command();
        var submission = materials.getAllValues().get(1).request().command();
        var replayedSubmission = materials.getAllValues().get(2).request().command();
        var nextSubmission = materials.getAllValues().get(3).request().command();
        var otherActor = materials.getAllValues().get(4).request().command();
        List<String> expectedCapabilities = List.of(
                "case:case-1:command:EVIDENCE_OPENING",
                "case:case-1:command:EVIDENCE_SUBMIT");

        assertThat(opening.threadId()).isNotEqualTo(submission.threadId());
        assertThat(replayedSubmission.threadId()).isEqualTo(submission.threadId());
        assertThat(nextSubmission.threadId())
                .isNotEqualTo(opening.threadId())
                .isNotEqualTo(submission.threadId());
        assertThat(opening.actorScope()).isEqualTo(submission.actorScope());
        assertThat(replayedSubmission.actorScope()).isEqualTo(submission.actorScope());
        assertThat(nextSubmission.actorScope()).isEqualTo(submission.actorScope());
        assertThat(opening.actorScope().capabilities()).containsExactlyElementsOf(expectedCapabilities);
        assertThat(ContractJson.sha256Hex(MAPPER.valueToTree(opening.actorScope())))
                .isEqualTo(ContractJson.sha256Hex(MAPPER.valueToTree(submission.actorScope())));
        assertThat(ContractJson.sha256Hex(MAPPER.valueToTree(opening.actorScope().capabilities())))
                .isEqualTo(ContractJson.sha256Hex(MAPPER.valueToTree(submission.actorScope().capabilities())));
        assertThat(otherActor.threadId()).isNotEqualTo(opening.threadId());
        assertThat(otherActor.actorScope()).isNotEqualTo(opening.actorScope());
        assertThat(materials.getAllValues().get(0).evidenceAgentTurnCommand()
                        .contextEnvelope().currentEvent().eventType())
                .isEqualTo("ROOM_OPENING");
        assertThat(materials.getAllValues().get(0).evidenceAgentTurnCommand()
                        .contextEnvelope().currentEvent().attachmentRefs())
                .isEmpty();
        assertThat(materials.getAllValues().get(1).evidenceAgentTurnCommand()
                        .contextEnvelope().currentEvent().eventType())
                .isEqualTo("PARTY_MESSAGE");
        assertThat(materials.getAllValues().get(1).evidenceAgentTurnCommand()
                        .contextEnvelope().currentEvent().attachmentRefs())
                .containsExactly("EVIDENCE_REAL_1");
    }

    @Test
    void materializesExactFrozenEvidenceOpeningOnceAndRejectsMissingOneSidedOrDriftedAuthority()
            throws Exception {
        ObjectMapper applicationMapper =
                MAPPER.copy().setSerializationInclusion(JsonInclude.Include.ALWAYS);
        Map<String, byte[]> objects = new LinkedHashMap<>();
        MinioClient minio = mock(MinioClient.class);
        doAnswer(
                        invocation -> {
                            PutObjectArgs args = invocation.getArgument(0);
                            objects.put(args.object(), args.stream().readAllBytes());
                            return null;
                        })
                .when(minio)
                .putObject(any(PutObjectArgs.class));
        ProductionRoomObjectIndex objectIndex = mock(ProductionRoomObjectIndex.class);
        MinioProductionRoomCommandPayloadPublisher payloads =
                new MinioProductionRoomCommandPayloadPublisher(
                        minio,
                        applicationMapper,
                        "production-runtime",
                        "room-command-inputs",
                        objectIndex);
        ProductionEvidenceTurnInvocationPublisher invocationPublisher =
                new ProductionEvidenceTurnInvocationPublisher(
                        payloads, objectIndex, applicationMapper);
        ProductionEvidenceManifestPublisher syntheticManifest =
                mock(ProductionEvidenceManifestPublisher.class);
        TargetEvidenceCommandMaterialStore evidence = mock(TargetEvidenceCommandMaterialStore.class);
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        when(ledger.createOrLoad(any()))
                .thenAnswer(
                        invocation -> {
                            AgentRunLedger.CreateLogicalRun allocation = invocation.getArgument(0);
                            AgentRunLedger.LogicalRun logical = mock(AgentRunLedger.LogicalRun.class);
                            when(logical.agentRunId()).thenReturn(allocation.agentRunId());
                            return logical;
                        });
        when(ledger.startNextAttempt(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            AgentRunLedger.AttemptAllocation allocation = invocation.getArgument(1);
                            AgentRunLedger.Attempt attempt = mock(AgentRunLedger.Attempt.class);
                            when(attempt.attemptId()).thenReturn(allocation.command().attemptId());
                            when(attempt.attemptNo()).thenReturn(allocation.attemptNo());
                            return attempt;
                        });
        CaseRoomEpochEntity epoch = matchingEpoch();
        when(epoch.getRoomEpoch()).thenReturn(0L);
        CaseRoomEpochRepository epochs = mock(CaseRoomEpochRepository.class);
        when(epochs.findByCaseIdAndRoomTypeAndRoomEpochForUpdate("case-1", RoomType.EVIDENCE, 0L))
                .thenReturn(Optional.of(epoch));
        JdbcProductionApiAuthority authority = mock(JdbcProductionApiAuthority.class);
        when(authority.authorize(any(TargetRoomEpochSelectionAuthority.Request.class)))
                .thenReturn(Optional.of(grant()));
        CanonicalTargetRoomCommandMaterializer subject =
                new CanonicalTargetRoomCommandMaterializer(
                        epochs,
                        authority,
                        pins(),
                        ledger,
                        new AgentRunCommandBindingFactory(applicationMapper),
                        new ProductionGraphEnvelopeCodec(applicationMapper),
                        payloads,
                        objectIndex,
                        syntheticManifest,
                        invocationPublisher,
                        mock(ProductionReviewInvocationPublisher.class),
                        mock(JdbcTargetReviewInvocationFactsLoader.class),
                        evidence,
                        mock(TargetEvidenceCompletionCommandMaterialStore.class),
                        mock(TargetHearingCommandMaterialStore.class),
                        mock(TargetReviewCommandMaterialStore.class),
                        applicationMapper,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        AcceptCaseCommand opening = evidenceOpeningCommand(3L);
        EvidenceAgentTurnCommand openingTurn = openingTurn();
        AuthenticatedActor actor = new AuthenticatedActor("party-1", ActorRole.USER);

        var first = subject.materializeEvidenceOpening(
                "case-1", "evidence-opening-1", opening, actor, "trace-opening", openingTurn);
        var replay = subject.materializeEvidenceOpening(
                "case-1", "evidence-opening-1", opening, actor, "trace-opening", openingTurn);

        assertThat(replay).isEqualTo(first);
        assertThat(first.rootAttemptId()).isEqualTo(first.logicalRunId() + ":1");
        ArgumentCaptor<TargetEvidenceCommandMaterial> materials =
                ArgumentCaptor.forClass(TargetEvidenceCommandMaterial.class);
        verify(evidence, org.mockito.Mockito.times(2)).append(any(), materials.capture());
        assertThat(materials.getAllValues()).hasSize(2);
        assertThat(materials.getAllValues().get(1)).isEqualTo(materials.getAllValues().get(0));
        TargetEvidenceCommandMaterial material = materials.getAllValues().getFirst();
        assertThat(material.request().command().actorScope().capabilities())
                .containsExactly(
                        "case:case-1:command:EVIDENCE_OPENING",
                        "case:case-1:command:EVIDENCE_SUBMIT");
        assertThat(material.request().command().eventRef().schemaVersion())
                .isEqualTo(opening.payloadRef().schemaVersion());
        assertThat(material.request().command().eventRef().uri())
                .isEqualTo(opening.payloadRef().uri());
        assertThat(material.request().command().eventRef().sha256())
                .isEqualTo(opening.payloadRef().sha256());
        assertThat(material.evidenceAgentTurnCommand().contextEnvelope().currentEvent().eventType())
                .isEqualTo("ROOM_OPENING");
        assertThat(material.evidenceAgentTurnCommand().contextEnvelope().currentEvent().messageType())
                .isEqualTo(com.example.dispute.room.domain.MessageType.AGENT_MESSAGE);
        assertThat(material.evidenceAgentTurnCommand().contextEnvelope().currentEvent().attachmentRefs())
                .isEmpty();
        assertThat(objects).hasSize(1);
        ObjectNode invocation = (ObjectNode) MAPPER.readTree(objects.values().iterator().next());
        String promptProfileId = material.request().command().invocationContext().promptProfileId();
        assertThat(invocation.path("evidence_turn_request").path("agent_context")
                        .path("prompt_profile_id").asText())
                .isEqualTo(promptProfileId);
        assertThat(invocation.path("evidence_turn_request").path("context_envelope")
                        .path("actor_snapshot").path("prompt_profile_id").asText())
                .isEqualTo(promptProfileId);
        verify(syntheticManifest, never()).publish(any());

        org.mockito.Mockito.clearInvocations(ledger, evidence);
        assertOpeningAuthorityRejected(
                subject, opening, actor, openingTurnWithFrozenAuthority(null, "f".repeat(64), 0, 23));
        assertOpeningAuthorityRejected(
                subject, opening, actor, openingTurnWithFrozenAuthority("urn:frozen:matrix", null, 0, 23));
        assertOpeningAuthorityRejected(
                subject,
                opening,
                actor,
                openingTurnWithFrozenAuthority("urn:frozen:matrix", "0".repeat(64), 0, 23));
        assertOpeningAuthorityRejected(
                subject,
                opening,
                actor,
                openingTurnWithFrozenAuthority(
                        openingTurn.contextEnvelope().frozenSubmission().projectionRef(),
                        openingTurn.contextEnvelope().frozenSubmission().projectionSha256(),
                        1,
                        23));
        assertOpeningAuthorityRejected(
                subject,
                opening,
                actor,
                openingTurnWithFrozenAuthority(
                        openingTurn.contextEnvelope().frozenSubmission().projectionRef(),
                        openingTurn.contextEnvelope().frozenSubmission().projectionSha256(),
                        0,
                        24));
        assertThatThrownBy(
                        () ->
                                subject.materializeEvidenceOpening(
                                        "case-1",
                                        "evidence-opening-stale-revision",
                                        evidenceOpeningCommand(4L),
                                        actor,
                                        "trace-opening",
                                        openingTurn))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("target room command process revision is stale");
        verify(ledger, never()).createOrLoad(any());
        verify(ledger, never()).startNextAttempt(any(), any(), any());
        verify(evidence, never()).append(any(), any());

        assertThatThrownBy(
                        () ->
                                new AcceptCaseCommand(
                                        CommandType.EVIDENCE_OPENING,
                                        RoomType.EVIDENCE,
                                        -1,
                                        opening.payloadRef(),
                                        opening.expectedProcessRevision(),
                                        opening.deadlineAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("roomEpoch must be non-negative");
    }

    private static CaseRoomEpochEntity matchingEpoch() {
        CaseRoomEpochEntity epoch = mock(CaseRoomEpochEntity.class);
        when(epoch.getTenantSurrogate()).thenReturn("tenant-surrogate-1");
        when(epoch.getCaseId()).thenReturn("case-1");
        when(epoch.getRoomId()).thenReturn("room-evidence-1");
        when(epoch.getRoomType()).thenReturn(RoomType.EVIDENCE);
        when(epoch.getRoomEpoch()).thenReturn(7L);
        when(epoch.getFencingToken()).thenReturn(23L);
        when(epoch.getProcessRevision()).thenReturn(3L);
        when(epoch.getRoomRevision()).thenReturn(4L);
        when(epoch.getWriterMode())
                .thenReturn(com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode.TEMPORAL);
        when(epoch.getLifecycleStatus()).thenReturn(EpochLifecycleStatus.ACTIVE);
        when(epoch.getProvisioningStatus()).thenReturn(EpochProvisioningStatus.READY);
        when(epoch.getTemporalBuildId()).thenReturn("p9-control-build");
        when(epoch.getGraphKey()).thenReturn(TargetTypedRoomProtocol.GRAPH_KEY);
        when(epoch.getGraphVersion()).thenReturn(TargetTypedRoomProtocol.GRAPH_VERSION);
        when(epoch.getCheckpointSchemaVersion())
                .thenReturn(TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION);
        return epoch;
    }

    private static TargetRoomEpochSelectionAuthority.Grant grant() {
        TargetRoomEpochSelectionAuthority.Request request =
                new TargetRoomEpochSelectionAuthority.Request(
                        TargetRoomEpochSelectionAuthority.PROFILE,
                        TargetRoomEpochSelectionAuthority.EXECUTION_LANE,
                        "tenant-surrogate-1",
                        "case-1",
                        RoomType.EVIDENCE,
                        TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC);
        return new TargetRoomEpochSelectionAuthority.Grant(
                "p9act.v1.0123456789abcdef0123456789abcdef",
                "a".repeat(64),
                "b".repeat(64),
                request,
                TargetTypedRoomProtocol.SELECTION_SCHEMA_VERSION,
                TargetTypedRoomProtocol.PROCESS_CONTRACT_VERSION,
                TargetTypedRoomProtocol.CASE_WORKFLOW_TYPE,
                "p9-case-build",
                TargetTypedRoomProtocol.workflowType(RoomType.EVIDENCE),
                "p9-control-build",
                TargetTypedRoomProtocol.GRAPH_KEY,
                TargetTypedRoomProtocol.GRAPH_VERSION,
                TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION,
                TargetTypedRoomProtocol.STREAM_PROTOCOL);
    }

    private static TargetIntakeRuntimePins pins() {
        return new TargetIntakeRuntimePins(
                "case-build", "agent-build", "c".repeat(64), "graph-build", "d".repeat(64),
                "evidence-clerk", "evidence-prompt-v1", "evidence-model-v1", "litellm", "policy-v1",
                "guardrail-v1", "tools-v1", "memory-v1", "key-v1");
    }

    private static AcceptCaseCommand evidenceSubmitCommand() {
        return new AcceptCaseCommand(
                CommandType.EVIDENCE_SUBMIT,
                RoomType.EVIDENCE,
                7L,
                new PayloadRef(
                        "production-runtime-evidence-submission.v1",
                        "urn:production-runtime:timeline-event:event-1",
                        "e".repeat(64),
                        128L),
                3L,
                NOW.plusSeconds(300));
    }

    private static AcceptCaseCommand evidenceOpeningCommand(long expectedProcessRevision) {
        return evidenceOpeningCommand(0L, expectedProcessRevision);
    }

    private static AcceptCaseCommand evidenceOpeningCommand(
            long roomEpoch, long expectedProcessRevision) {
        return new AcceptCaseCommand(
                CommandType.EVIDENCE_OPENING,
                RoomType.EVIDENCE,
                roomEpoch,
                new PayloadRef(
                        "production-runtime-evidence-opening.v1",
                        "urn:production-runtime:evidence-opening:opening-1",
                        "e".repeat(64),
                        256L),
                expectedProcessRevision,
                NOW.plusSeconds(300));
    }

    private static void assertOpeningAuthorityRejected(
            CanonicalTargetRoomCommandMaterializer subject,
            AcceptCaseCommand opening,
            AuthenticatedActor actor,
            EvidenceAgentTurnCommand turn) {
        assertThatThrownBy(
                        () ->
                                subject.materializeEvidenceOpening(
                                        "case-1",
                                        "evidence-opening-invalid",
                                        opening,
                                        actor,
                                        "trace-opening-invalid",
                                        turn))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("formal Evidence turn authority does not bind its outer command");
    }

    private static EvidenceAgentTurnCommand openingTurnWithFrozenAuthority(
            String projectionRef,
            String projectionSha256,
            long roomEpoch,
            long fencingToken) {
        EvidenceAgentTurnCommand exact = openingTurn();
        EvidenceContextEnvelopeV1 exactEnvelope = exact.contextEnvelope();
        EvidenceContextEnvelopeV1.FrozenSubmission malformed =
                mock(EvidenceContextEnvelopeV1.FrozenSubmission.class);
        when(malformed.evidenceRoomEpoch()).thenReturn(roomEpoch);
        when(malformed.evidenceFencingToken()).thenReturn(fencingToken);
        when(malformed.projectionRef()).thenReturn(projectionRef);
        when(malformed.projectionSha256()).thenReturn(projectionSha256);
        when(malformed.authority())
                .thenReturn(exactEnvelope.frozenSubmission().authority());
        when(malformed.matrix()).thenReturn(exactEnvelope.frozenSubmission().matrix());
        EvidenceContextEnvelopeV1 envelope = mock(EvidenceContextEnvelopeV1.class);
        when(envelope.schemaVersion()).thenReturn(exactEnvelope.schemaVersion());
        when(envelope.capturedAt()).thenReturn(exactEnvelope.capturedAt());
        when(envelope.caseSnapshot()).thenReturn(exactEnvelope.caseSnapshot());
        when(envelope.actorSnapshot()).thenReturn(exactEnvelope.actorSnapshot());
        when(envelope.currentEvent()).thenReturn(exactEnvelope.currentEvent());
        when(envelope.visibleEvidence()).thenReturn(exactEnvelope.visibleEvidence());
        when(envelope.privateConversation()).thenReturn(exactEnvelope.privateConversation());
        when(envelope.roomPolicy()).thenReturn(exactEnvelope.roomPolicy());
        when(envelope.frozenSubmission()).thenReturn(malformed);
        EvidenceAgentTurnCommand turn = mock(EvidenceAgentTurnCommand.class);
        when(turn.contextEnvelope()).thenReturn(envelope);
        when(turn.agentContext()).thenReturn(exact.agentContext());
        return turn;
    }

    private static EvidenceAgentTurnCommand openingTurn() {
        return openingTurn(0L, 23L, "party-1");
    }

    private static EvidenceAgentTurnCommand openingTurn(
            long roomEpoch, long fencingToken, String actorId) {
        EvidenceAgentTurnCommand submission = clerkTurn(actorId);
        ObjectNode matrix = MAPPER.createObjectNode();
        matrix.put("schema_version", FrozenIntakeSubmissionAuthority.MATRIX_SCHEMA_VERSION);
        matrix.put("matrix_kind", FrozenIntakeSubmissionAuthority.MATRIX_KIND);
        matrix.put("case_id", "case-1");
        matrix.put("matrix_version", 3);
        ObjectNode overview = matrix.putObject("case_overview");
        overview.put("neutral_summary", "Frozen bilateral Evidence opening.");
        overview.put("core_conflict", "Whether the submitted records prove the disputed delivery.");
        String matrixId = "CASE_MATRIX_"
                + ContractJson.sha256Hex(matrix)
                        .substring(0, 20)
                        .toUpperCase(java.util.Locale.ROOT);
        matrix.put("matrix_id", matrixId);
        matrix.put("content_hash", ContractJson.sha256Hex(matrix));
        FrozenIntakeSubmissionAuthority authority =
                FrozenIntakeSubmissionAuthority.capture(
                        "tenant-run001",
                        "case-1",
                        "party-merchant",
                        com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole.MERCHANT,
                        "INTAKE_COMPLETION_RESPONDENT",
                        FrozenIntakeSubmissionAuthority.COMPLETION_STATUS,
                        NOW.minusSeconds(60),
                        "intake-respondent-confirm:operation",
                        "intake-respondent-confirm:command",
                        7,
                        "1".repeat(64),
                        "EVIB_EVIDENCE_FROZEN",
                        "urn:after-sale-flow:intake-event:EVIB_EVIDENCE_FROZEN",
                        9,
                        1,
                        11,
                        6,
                        5,
                        "INTAKE_DOSSIER_FROZEN",
                        3,
                        matrix);
        EvidenceContextEnvelopeV1 base = submission.contextEnvelope();
        EvidenceContextEnvelopeV1.CurrentEvent event =
                new EvidenceContextEnvelopeV1.CurrentEvent(
                        "agent-evidence-opening:freeze-v1:case-1:" + actorId + ":7",
                        "ROOM_OPENING",
                        com.example.dispute.room.domain.MessageType.AGENT_MESSAGE,
                        actorId,
                        "USER",
                        null,
                        List.of(),
                        1,
                        NOW.toString());
        EvidenceContextEnvelopeV1 envelope =
                new EvidenceContextEnvelopeV1(
                        EvidenceContextEnvelopeV1.FROZEN_SUBMISSION_SCHEMA_VERSION,
                        NOW.toString(),
                        base.caseSnapshot(),
                        null,
                        base.actorSnapshot(),
                        event,
                        List.of(),
                        base.privateConversation(),
                        base.roomPolicy(),
                        new EvidenceContextEnvelopeV1.FrozenSubmission(
                                roomEpoch,
                                fencingToken,
                                authority.projectionRef(),
                                authority.matrixContentHash(),
                                authority,
                                matrix));
        return new EvidenceAgentTurnCommand(envelope, submission.agentContext());
    }

    private static EvidenceAgentTurnCommand clerkTurn() {
        return clerkTurn("party-1");
    }

    private static EvidenceAgentTurnCommand clerkTurn(String actorId) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode envelope = root.putObject("context_envelope");
        envelope.put("schema_version", "evidence_context_envelope.v1");
        envelope.put("captured_at", NOW.toString());
        ObjectNode caseSnapshot = envelope.putObject("case_snapshot");
        caseSnapshot.put("case_id", "case-1");
        caseSnapshot.put("current_room", "EVIDENCE");
        ObjectNode dossier = envelope.putObject("intake_dossier_snapshot");
        dossier.put("dossier_id", "dossier-v10");
        dossier.put("schema_version", "intake_dossier.v1");
        dossier.put("dossier_version", 10);
        dossier.put("source_turn_no", 10);
        dossier.put("quality_score", 100);
        dossier.put("ready_for_next_step", true);
        ObjectNode matrix = dossier.putObject("payload").putObject("case_fact_matrix");
        matrix.put("schema_version", "case_fact_matrix.v2");
        matrix.put("matrix_version", 8);
        matrix.putArray("facts").addObject().put("fact_id", "FACT_PARENT_1");
        ObjectNode actor = envelope.putObject("actor_snapshot");
        actor.put("actor_id", actorId);
        actor.put("actor_role", "USER");
        actor.put("access_session_id", "access-session-1");
        actor.put("agent_session_id", "agent-session-private-1");
        actor.put("conversation_scope", "PARTY_PRIVATE");
        ObjectNode event = envelope.putObject("current_event");
        event.put("event_id", "MESSAGE_REAL_1");
        event.put("event_type", "PARTY_MESSAGE");
        event.put("message_type", "PARTY_EVIDENCE_REFERENCE");
        event.put("actor_id", actorId);
        event.put("actor_role", "USER");
        event.put("text", "submitted real evidence");
        event.putArray("attachment_refs").add("EVIDENCE_REAL_1");
        event.put("turn_no", 2);
        event.put("occurred_at", NOW.toString());
        envelope.putArray("visible_evidence").addObject().put("evidence_id", "EVIDENCE_REAL_1");
        ObjectNode privateConversation = envelope.putObject("private_conversation");
        privateConversation.put("agent_session_id", "agent-session-private-1");
        privateConversation.put("conversation_scope", "PARTY_PRIVATE");
        privateConversation.put("source_count", 0);
        privateConversation.put("truncated", false);
        privateConversation.putArray("recent_turns");
        ObjectNode roomPolicy = envelope.putObject("room_policy");
        roomPolicy.put("room_id", "room-evidence-1");
        roomPolicy.put("room_type", "EVIDENCE");
        roomPolicy.put("room_status", "OPEN");
        ObjectNode agentContext = root.putObject("agent_context");
        agentContext.put("tenant_id", "tenant-domain-1");
        agentContext.put("case_id", "case-1");
        agentContext.put("room_type", "EVIDENCE");
        agentContext.put("actor_id", actorId);
        agentContext.put("actor_role", "USER");
        agentContext.put("access_session_id", "access-session-1");
        agentContext.put("permission_level", "PARTY");
        agentContext.putArray("permission_scopes").add("ROOM_MESSAGE_WRITE");
        agentContext.put("agent_key", "EVIDENCE_CLERK");
        agentContext.put("agent_invocation_id", "agent-invocation-1");
        agentContext.put("agent_session_id", "agent-session-private-1");
        agentContext.put("conversation_scope", "PARTY_PRIVATE");
        agentContext.put("scope_type", "EVIDENCE_PARTY_PRIVATE");
        agentContext.putArray("allowed_actor_ids").add(actorId);
        agentContext.putArray("allowed_actor_roles").add("USER");
        agentContext.put("prompt_profile_id", "evidence-clerk");
        agentContext.put("memory_policy_id", "memory-v1");
        return MAPPER.convertValue(root, EvidenceAgentTurnCommand.class);
    }
}
