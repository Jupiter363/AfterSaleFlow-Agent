package com.example.dispute.workflow.targete2e.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyContextResolver.TrustedTurnContext;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.AssemblyConflictException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.AssemblyLookup;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ExactThreeInputs;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.FrameSetAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.PublishReady;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyArtifact;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyLookup;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.ReadyReceipt;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.SealedFrameRecord;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameAssembler;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.AssemblyState;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.EventAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.FrameType;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.ActorScope;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.InvocationContext;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.RetryBudget;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.SnapshotRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TargetE2EIntakeParallelAssemblyCoordinatorTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final String ACTIVATION = "p9act.v1." + "a".repeat(32);
    private static final String FRAME_SET_ID = "IPFS_TEST_1";
    private static final String ACTOR_SCOPE_HASH = "c".repeat(64);
    private static final String REGISTRY_HASH = "9".repeat(64);
    private static final String TOOL_POLICY = "tools.none.v1";

    @Test
    void publishesReadyOnceAndThenReplaysTheSameImmutableArtifacts() {
        ExecuteAgentRunRequest request = request(
                TargetE2EIntakeParallelAssemblyCoordinator.AGENT_PROFILE_ID);
        ObjectNode previous = previousDossier();
        InMemoryAssemblyStore store = new InMemoryAssemblyStore(inputs(request, previous));
        AtomicInteger contextCalls = new AtomicInteger();
        var coordinator = coordinator(
                store,
                (execution, authority) -> {
                    contextCalls.incrementAndGet();
                    return new TrustedTurnContext(
                            "MESSAGE_1",
                            "本轮补充了核心事实。",
                            2,
                            previous,
                            "aliyun-bailian",
                            "qwen3.7-max-2026-06-08");
                },
                REGISTRY_HASH);

        var first = coordinator.assembleReady(
                request, FRAME_SET_ID, new AgentRunCancellationToken());
        var replay = coordinator.assembleReady(
                request, FRAME_SET_ID, new AgentRunCancellationToken());
        var reconciliation = coordinator.reconcileReady(
                request, new AgentRunCancellationToken());

        assertThat(first.newlyPublished()).isTrue();
        assertThat(replay.newlyPublished()).isFalse();
        assertThat(contextCalls).hasValue(1);
        assertThat(store.publishCalls).isEqualTo(1);
        assertThat(replay.artifact().graphResultSha256())
                .isEqualTo(first.artifact().graphResultSha256());
        assertThat(replay.artifact().canonicalResultEnvelopeBytes())
                .isEqualTo(first.artifact().canonicalResultEnvelopeBytes());
        assertThat(replay.graphResult()).isEqualTo(first.graphResult());
        assertThat(reconciliation.result()).isEqualTo(first.graphResult());
        assertThat(reconciliation.resultRef()).isEqualTo(first.artifact().resultRef());
        assertThat(reconciliation.resultHash())
                .isEqualTo(first.artifact().graphResultSha256());
        assertThat(reconciliation.disposition())
                .isEqualTo(
                        com.example.dispute.workflow.contract.v1.GraphReconcileResponse
                                .Disposition.RETURN_CACHED);
        assertThat(first.artifact().proposalArtifactId())
                .startsWith("intake.proposal.");
        assertThat(first.artifact().resultArtifactId())
                .startsWith("intake.graph-result.");
        assertThat(first.artifact().proposalUri())
                .isEqualTo("urn:target-e2e:proposal:intake:"
                        + first.artifact().proposalSha256());
        assertThat(first.artifact().resultRef())
                .isEqualTo("urn:target-e2e:result:intake:"
                        + first.artifact().graphResultSha256());
    }

    @Test
    void rejectsARequestWithoutTheExplicitParallelAgentProfileBeforeReadingStaging() {
        ExecuteAgentRunRequest request = request("dispute-intake-officer");
        InMemoryAssemblyStore store = new InMemoryAssemblyStore(null);
        var coordinator = coordinator(
                store,
                (execution, authority) -> {
                    throw new AssertionError("context must not be resolved");
                },
                REGISTRY_HASH);

        assertThatThrownBy(() -> coordinator.assembleReady(
                        request, FRAME_SET_ID, new AgentRunCancellationToken()))
                .isInstanceOf(AssemblyConflictException.class)
                .hasMessageContaining("explicit ROOM_MESSAGE profile");
        assertThat(store.readCalls).isZero();
    }

    @Test
    void rejectsReadyReplayWhenTheCurrentRegistryBindingDrifts() {
        ExecuteAgentRunRequest request = request(
                TargetE2EIntakeParallelAssemblyCoordinator.AGENT_PROFILE_ID);
        ObjectNode previous = previousDossier();
        InMemoryAssemblyStore store = new InMemoryAssemblyStore(inputs(request, previous));
        var firstCoordinator = coordinator(
                store,
                (execution, authority) -> new TrustedTurnContext(
                        "MESSAGE_1",
                        "本轮补充了核心事实。",
                        2,
                        previous,
                        "aliyun-bailian",
                        "qwen3.7-max-2026-06-08"),
                REGISTRY_HASH);
        firstCoordinator.assembleReady(
                request, FRAME_SET_ID, new AgentRunCancellationToken());

        var changedRegistry = coordinator(
                store,
                (execution, authority) -> {
                    throw new AssertionError("READY replay must not resolve mutable context");
                },
                "8".repeat(64));

        assertThatThrownBy(() -> changedRegistry.assembleReady(
                        request, FRAME_SET_ID, new AgentRunCancellationToken()))
                .isInstanceOf(AssemblyConflictException.class)
                .hasMessageContaining("registry authority");
    }

    private static TargetE2EIntakeParallelAssemblyCoordinator coordinator(
            IntakeParallelAssemblyStore store,
            com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyContextResolver
                    contextResolver,
            String registryHash) {
        GraphRegistryBindingPolicy registry = binding ->
                new GraphRegistryBindingPolicy.ExpectedBinding(registryHash, TOOL_POLICY);
        TargetE2EAgentRunIdentityResolver identities = execution ->
                TargetE2EAgentRunIdentityResolver.DurableIdentity.from(execution, 31);
        return new TargetE2EIntakeParallelAssemblyCoordinator(
                ACTIVATION,
                identities,
                registry,
                contextResolver,
                store,
                new IntakeParallelFrameAssembler(),
                new TargetE2EGraphEnvelopeCodec(MAPPER),
                MAPPER);
    }

    private static ExactThreeInputs inputs(
            ExecuteAgentRunRequest request, ObjectNode previous) {
        RoomGraphCommand command = request.command();
        FrameSetAuthority authority = new FrameSetAuthority(
                FRAME_SET_ID,
                request.agentRunId(),
                request.attemptId(),
                command.commandId(),
                command.requestHash(),
                command.tenantSurrogate(),
                command.caseId(),
                "ROOM_1",
                command.roomEpoch(),
                31,
                command.threadId(),
                ACTOR_SCOPE_HASH,
                "SESSION_1",
                new EventAuthority(
                        "BINDING_1",
                        "THREAD_REGISTRATION_1",
                        1,
                        1,
                        0,
                        command.requestHash()),
                "d".repeat(64),
                "e".repeat(64),
                TargetE2EIntakeParallelAssemblyCoordinator.EXECUTION_PROFILE_ID,
                "projection.v1",
                command.invocationContext().modelProfileId(),
                command.deadlineAt(),
                0);
        Map<FrameType, ObjectNode> documents = Map.of(
                FrameType.DIALOGUE_FRAME, dialogue(previous),
                FrameType.DOSSIER_FRAME, dossier(),
                FrameType.QUALITY_FRAME, quality());
        EnumMap<FrameType, SealedFrameRecord> frames = new EnumMap<>(FrameType.class);
        for (FrameType type : FrameType.values()) {
            ObjectNode document = documents.get(type);
            frames.put(type, new SealedFrameRecord(
                    type,
                    1,
                    "FRAME_" + type.name(),
                    "RESULT_" + type.name(),
                    ContractJson.canonicalString(document),
                    ContractJson.sha256Hex(document),
                    switch (type) {
                        case DIALOGUE_FRAME -> "f".repeat(64);
                        case DOSSIER_FRAME -> "1".repeat(64);
                        case QUALITY_FRAME -> "2".repeat(64);
                    },
                    document.path("public_projection_items").size(),
                    100,
                    50,
                    150,
                    500,
                    1));
        }
        return new ExactThreeInputs(authority, frames);
    }

    private static ExecuteAgentRunRequest request(String agentProfileId) {
        boolean parallel = TargetE2EIntakeParallelAssemblyCoordinator.AGENT_PROFILE_ID.equals(
                agentProfileId);
        Instant deadline = Instant.parse("2030-08-25T00:00:00Z");
        SnapshotRef snapshot = new SnapshotRef(
                "SNAPSHOT_1",
                "intake-domain-snapshot.v2",
                "urn:intake:snapshot:1",
                "a".repeat(64),
                1024);
        SnapshotRef event = new SnapshotRef(
                "EVENT_1",
                "intake-turn-event.v2",
                "urn:intake:event:1",
                "b".repeat(64),
                512);
        InvocationContext invocation = new InvocationContext(
                agentProfileId,
                "prompt.parallel.v1",
                "qwen3.7-max-2026-06-08",
                TargetE2EIntakeParallelAssemblyCoordinator.EXECUTION_OUTPUT_SCHEMA,
                "policy.v1",
                "guardrail.v1",
                List.of(),
                "key.v1",
                "nonce.v1");
        RoomGraphCommand unsigned = new RoomGraphCommand(
                "room-graph-command.v1",
                "COMMAND_1",
                "RUN_1",
                "ATTEMPT_1",
                "TENANT_1",
                "CASE_1",
                parallel ? "ROOM_1" : null,
                RoomType.INTAKE,
                1,
                "all-rooms.target-e2e.v2",
                "graph.v1",
                "checkpoint.v1",
                "grt.v1." + "3".repeat(32),
                new ActorScope("user-local", ActorRole.USER, Audience.USER, List.of()),
                2,
                "INTAKE_ACTIVE",
                1,
                snapshot,
                event,
                invocation,
                new RetryBudget(parallel ? 6 : 2, 3, 1),
                deadline,
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                "0".repeat(64));
        ObjectNode hashSource = (ObjectNode) TargetE2EGraphTestFixtures.V1_CODEC
                .encode("room-graph-command.schema.json", unsigned)
                .deepCopy();
        hashSource.remove("request_hash");
        RoomGraphCommand command = new RoomGraphCommand(
                unsigned.schemaVersion(),
                unsigned.commandId(),
                unsigned.logicalRunId(),
                unsigned.attemptId(),
                unsigned.tenantSurrogate(),
                unsigned.caseId(),
                unsigned.roomId(),
                unsigned.roomType(),
                unsigned.roomEpoch(),
                unsigned.graphKey(),
                unsigned.graphVersion(),
                unsigned.checkpointSchemaVersion(),
                unsigned.threadId(),
                unsigned.actorScope(),
                unsigned.processRevision(),
                unsigned.stageCode(),
                unsigned.stageSequence(),
                unsigned.domainSnapshotRef(),
                unsigned.eventRef(),
                unsigned.invocationContext(),
                unsigned.retryBudget(),
                unsigned.deadlineAt(),
                unsigned.traceparent(),
                ContractJson.sha256Hex(hashSource));
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                command.logicalRunId(),
                1,
                3,
                ExecuteAgentRunRequest.isParallelIntakeCommand(command)
                        ? "agent-stream.v4"
                        : "agent-stream.v3",
                "7".repeat(64),
                null,
                false,
                0,
                command);
    }

    private static ObjectNode dialogue(ObjectNode previous) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode items = root.putArray("public_projection_items");
        ObjectNode item = items.addObject();
        item.put("schema_version", "intake.dialogue-public-segment-proposal.v1");
        item.put("provider_slot_id", "DSEG_01");
        item.put("segment_kind", "ACKNOWLEDGEMENT");
        item.put("candidate_text", "已记录您本轮补充的信息。");
        root.put("frame_type", "DIALOGUE_FRAME");
        root.put("schema_version", "intake.dialogue-frame.v1");
        ObjectNode dialogue = root.putObject("dialogue");
        ObjectNode binding = dialogue.putObject("action_binding");
        binding.put("action", "ASK_SUBSTANTIVE");
        binding.put(
                "phase_source_sha256",
                ContractJson.sha256Hex(previous.at("/party_intake_state/USER")));
        dialogue.putArray("public_projection_slots").add("DSEG_01");
        dialogue.put("language", "zh-CN");
        return root;
    }

    private static ObjectNode dossier() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode item = root.putArray("public_projection_items").addObject();
        item.put("schema_version", "intake.dossier-public-patch-proposal.v1");
        item.put("provider_slot_id", "DPATCH_01");
        item.put("projection_kind", "CURRENT_FACT");
        item.put("projection_path_id", "case_story.one_sentence_summary");
        item.put("candidate_value", "本轮补充了核心事实");
        root.put("frame_type", "DOSSIER_FRAME");
        root.put("schema_version", "intake.dossier-frame.v1");
        ObjectNode delta = root.putObject("dossier_delta");
        ObjectNode matrix = delta.putObject("matrix_patch");
        matrix.put("schema_version", "case_fact_matrix.delta.v2");
        ObjectNode row = matrix.putArray("fact_rows").addObject();
        row.put("fact_key", "FACT_01");
        row.put("category", "OTHER");
        row.put("fact_target", "本轮核心事实");
        row.put("materiality", "CORE");
        row.put("stance", "CONFIRM");
        row.put("position_summary", "本轮补充了核心事实");
        row.put("asserted_value", "本轮补充了核心事实");
        row.put("source_scope", "CURRENT_SOURCE");
        row.putNull("agreed_statement");
        row.putNull("conflict_summary");
        item.set("source_row", row.deepCopy());
        matrix.putArray("summary_source_fact_keys").add("FACT_01");
        matrix.putNull("respondent_claim");
        delta.putArray("public_projection_slots").add("DPATCH_01");
        return root;
    }

    private static ObjectNode quality() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode items = root.putArray("public_projection_items");
        ObjectNode quality = root.putObject("quality");
        ObjectNode scores = quality.putObject("scores");
        Map<String, Integer> values = Map.ofEntries(
                Map.entry("references", 15),
                Map.entry("event_story", 20),
                Map.entry("party_positions", 20),
                Map.entry("requested_resolution", 15),
                Map.entry("risk_and_conflicts", 15),
                Map.entry("next_action_clarity", 15));
        Map<String, String> dimensions = Map.ofEntries(
                Map.entry("references", "REFERENCES"),
                Map.entry("event_story", "EVENT_STORY"),
                Map.entry("party_positions", "PARTY_POSITIONS"),
                Map.entry("requested_resolution", "REQUESTED_RESOLUTION"),
                Map.entry("risk_and_conflicts", "RISK_AND_CONFLICTS"),
                Map.entry("next_action_clarity", "NEXT_ACTION_CLARITY"));
        dimensions.keySet().stream().sorted().forEach(field -> {
            scores.put(field, values.get(field));
            ObjectNode item = items.addObject();
            item.put("schema_version", "intake.quality-public-metric-proposal.v1");
            item.put("provider_slot_id", "QMETRIC_" + dimensions.get(field));
            item.put("projection_kind", "DIMENSION_SCORE");
            item.put("dimension", dimensions.get(field));
            item.put("candidate_score", values.get(field));
            item.putArray("linked_fact_keys");
        });
        quality.putArray("gap_proposals");
        quality.put("assessment_reasoning", "依据当前消息形成六项评分。");
        ArrayNode slots = quality.putArray("public_projection_slots");
        items.forEach(item -> slots.add(item.path("provider_slot_id").asText()));
        root.put("frame_type", "QUALITY_FRAME");
        root.put("schema_version", "intake.quality-frame.v1");
        return reorderRoot(root, "public_projection_items", "frame_type", "schema_version", "quality");
    }

    private static ObjectNode previousDossier() {
        ObjectNode dossier = MAPPER.createObjectNode();
        dossier.put("schema_version", "intake-dossier.v2");
        ObjectNode state = dossier.putObject("party_intake_state");
        state.put("schema_version", "party-intake-state.v1");
        state.set("USER", actorEntry());
        state.set("MERCHANT", actorEntry());
        return dossier;
    }

    private static ObjectNode actorEntry() {
        ObjectNode entry = MAPPER.createObjectNode();
        ObjectNode quality = entry.putObject("intake_quality");
        quality.put("score", 0);
        quality.put("threshold", 85);
        quality.put("ready_for_next_step", false);
        ObjectNode breakdown = quality.putObject("score_breakdown");
        List.of(
                        "references",
                        "event_story",
                        "party_positions",
                        "requested_resolution",
                        "risk_and_conflicts",
                        "next_action_clarity")
                .forEach(field -> breakdown.put(field, 0));
        quality.put("improvement_reason", "等待补充案情。");
        ObjectNode missing = entry.putObject("missing_information");
        missing.putArray("blocking_gaps");
        missing.putArray("nice_to_have_gaps");
        missing.putArray("next_questions");
        ObjectNode handoff = entry.putObject("handoff_notes");
        handoff.put("remark_status", "NOT_READY");
        handoff.put("phase_source_message_id", "");
        handoff.put("latest_remark", "");
        handoff.putArray("remarks");
        handoff.put("instruction", "等待后续阶段。");
        ObjectNode admission = entry.putObject("admission");
        admission.put("recommendation", "NEED_MORE_INFO");
        admission.put("reasoning", "信息不足。");
        admission.put("confidence", BigDecimal.ZERO);
        return entry;
    }

    private static ObjectNode reorderRoot(ObjectNode source, String... fields) {
        ObjectNode ordered = MAPPER.createObjectNode();
        Stream.of(fields).forEach(field -> ordered.set(field, source.get(field)));
        return ordered;
    }

    private static final class InMemoryAssemblyStore implements IntakeParallelAssemblyStore {
        private final ExactThreeInputs inputs;
        private ReadyArtifact ready;
        private int readCalls;
        private int publishCalls;

        private InMemoryAssemblyStore(ExactThreeInputs inputs) {
            this.inputs = inputs;
        }

        @Override
        public ExactThreeInputs loadExactThree(AssemblyLookup lookup) {
            readCalls++;
            return inputs;
        }

        @Override
        public ReadyReceipt publishReady(PublishReady command) {
            publishCalls++;
            ready = command.artifact();
            return new ReadyReceipt(true, AssemblyState.READY, 1, ready);
        }

        @Override
        public Optional<ReadyArtifact> loadReady(ReadyLookup lookup) {
            readCalls++;
            return Optional.ofNullable(ready);
        }

        @Override
        public ReadyAuthority lockReadyForTerminal(ReadyLookup lookup) {
            if (ready == null) {
                throw new IllegalStateException("READY artifact is missing");
            }
            return new ReadyAuthority(
                    inputs.authority().frameSetId(),
                    AssemblyState.READY,
                    1,
                    Instant.parse("2026-08-19T00:00:00Z"),
                    ready);
        }
    }
}
