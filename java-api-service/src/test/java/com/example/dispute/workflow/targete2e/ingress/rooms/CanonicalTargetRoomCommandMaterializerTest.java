package com.example.dispute.workflow.targete2e.ingress.rooms;

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
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.persistence.entity.CaseRoomEpochEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochLifecycleStatus;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.EpochProvisioningStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.CaseRoomEpochRepository;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomObjectIndex;
import com.example.dispute.workflow.targete2e.graph.TargetE2EGraphEnvelopeCodec;
import com.example.dispute.workflow.targete2e.ingress.materialization.TargetIntakeRuntimePins;
import com.example.dispute.workflow.targete2e.persistence.JdbcTargetE2eApiAuthority;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterial;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.evidence.TargetEvidenceCompletionCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.hearing.TargetHearingCommandMaterialStore;
import com.example.dispute.workflow.targete2e.rooms.review.TargetReviewCommandMaterialStore;
import com.example.dispute.workflow.targete2e.temporal.TargetRoomEpochSelectionAuthority;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
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
        TargetE2eRoomObjectIndex objectIndex = mock(TargetE2eRoomObjectIndex.class);
        MinioTargetE2eRoomCommandPayloadPublisher payloads =
                new MinioTargetE2eRoomCommandPayloadPublisher(
                        minio, MAPPER, "target-e2e", "room-command-inputs", objectIndex);
        TargetE2eEvidenceTurnInvocationPublisher invocationPublisher =
                new TargetE2eEvidenceTurnInvocationPublisher(payloads, objectIndex, MAPPER);
        TargetE2eEvidenceManifestPublisher syntheticManifest =
                mock(TargetE2eEvidenceManifestPublisher.class);
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
        JdbcTargetE2eApiAuthority authority = mock(JdbcTargetE2eApiAuthority.class);
        when(authority.authorize(any(TargetRoomEpochSelectionAuthority.Request.class)))
                .thenReturn(Optional.of(grant()));
        CanonicalTargetRoomCommandMaterializer subject =
                new CanonicalTargetRoomCommandMaterializer(
                        epochs,
                        authority,
                        pins(),
                        ledger,
                        new AgentRunCommandBindingFactory(MAPPER),
                        new TargetE2EGraphEnvelopeCodec(MAPPER),
                        payloads,
                        objectIndex,
                        syntheticManifest,
                        invocationPublisher,
                        mock(TargetE2eReviewInvocationPublisher.class),
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
            assertThat(material.schemaVersion()).isEqualTo("target-e2e-evidence-command-material.v2");
            assertThat(material.evidenceAgentTurnCommand()).isEqualTo(clerkTurn);
            assertThat(material.request().command().domainSnapshotRef().schemaVersion())
                    .isEqualTo("target-e2e-evidence-turn-invocation.v2");
            assertThat(material.request().command().eventRef().uri())
                    .isEqualTo("urn:target-e2e:timeline-event:event-1");
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
                        allocation ->
                                assertThat(allocation.agentRunId())
                                        .isEqualTo(firstReceipt.logicalRunId()));
        assertThat(objects).hasSize(1);
        byte[] body = objects.values().iterator().next();
        ObjectNode invocation = (ObjectNode) MAPPER.readTree(body);
        assertThat(ContractJson.canonicalize(invocation)).isEqualTo(body);
        assertThat(invocation.path("schema_version").asText())
                .isEqualTo("target-e2e-evidence-turn-invocation.v2");
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
                "evidence-clerk", "evidence-prompt-v1", "evidence-model-v1", "policy-v1",
                "guardrail-v1", "tools-v1", "memory-v1", "key-v1");
    }

    private static AcceptCaseCommand evidenceSubmitCommand() {
        return new AcceptCaseCommand(
                CommandType.EVIDENCE_SUBMIT,
                RoomType.EVIDENCE,
                7L,
                new PayloadRef(
                        "target-e2e-evidence-submission.v1",
                        "urn:target-e2e:timeline-event:event-1",
                        "e".repeat(64),
                        128L),
                3L,
                NOW.plusSeconds(300));
    }

    private static EvidenceAgentTurnCommand clerkTurn() {
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
        actor.put("actor_id", "party-1");
        actor.put("actor_role", "USER");
        actor.put("access_session_id", "access-session-1");
        actor.put("agent_session_id", "agent-session-private-1");
        actor.put("conversation_scope", "PARTY_PRIVATE");
        ObjectNode event = envelope.putObject("current_event");
        event.put("event_id", "MESSAGE_REAL_1");
        event.put("event_type", "PARTY_MESSAGE");
        event.put("message_type", "PARTY_EVIDENCE_REFERENCE");
        event.put("actor_id", "party-1");
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
        agentContext.put("actor_id", "party-1");
        agentContext.put("actor_role", "USER");
        agentContext.put("access_session_id", "access-session-1");
        agentContext.put("permission_level", "PARTY");
        agentContext.putArray("permission_scopes").add("ROOM_MESSAGE_WRITE");
        agentContext.put("agent_key", "EVIDENCE_CLERK");
        agentContext.put("agent_invocation_id", "agent-invocation-1");
        agentContext.put("agent_session_id", "agent-session-private-1");
        agentContext.put("conversation_scope", "PARTY_PRIVATE");
        agentContext.put("scope_type", "EVIDENCE_PARTY_PRIVATE");
        agentContext.putArray("allowed_actor_ids").add("party-1");
        agentContext.putArray("allowed_actor_roles").add("USER");
        agentContext.put("prompt_profile_id", "evidence-clerk");
        agentContext.put("memory_policy_id", "memory-v1");
        return MAPPER.convertValue(root, EvidenceAgentTurnCommand.class);
    }
}
