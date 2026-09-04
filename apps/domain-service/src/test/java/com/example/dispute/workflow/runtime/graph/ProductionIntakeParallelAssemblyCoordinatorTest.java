package com.example.dispute.workflow.runtime.graph;

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

class ProductionIntakeParallelAssemblyCoordinatorTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final String ACTIVATION = "p9act.v1." + "a".repeat(32);
    private static final String NEXT_ACTIVATION = "p9act.v1." + "b".repeat(32);
    private static final String FRAME_SET_ID = "IPFS_TEST_1";
    private static final String ACTOR_SCOPE_HASH = "c".repeat(64);
    private static final String REGISTRY_HASH = "9".repeat(64);
    private static final String TOOL_POLICY = "tools.none.v1";

    @Test
    void publishesReadyOnceAndThenReplaysTheSameImmutableArtifacts() {
        ExecuteAgentRunRequest request = request(
                ProductionIntakeParallelAssemblyCoordinator.AGENT_PROFILE_ID);
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
                            "qwen3.7-max-2026-06-08",
                            ACTIVATION);
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
                .isEqualTo("urn:production-runtime:proposal:intake:"
                        + first.artifact().proposalSha256());
        assertThat(first.artifact().resultRef())
                .isEqualTo("urn:production-runtime:result:intake:"
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
    void replaysReadyWithoutResolvingTheCurrentRegistryBinding() {
        ExecuteAgentRunRequest request = request(
                ProductionIntakeParallelAssemblyCoordinator.AGENT_PROFILE_ID);
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
                        "qwen3.7-max-2026-06-08",
                        ACTIVATION),
                REGISTRY_HASH);
        firstCoordinator.assembleReady(
                request, FRAME_SET_ID, new AgentRunCancellationToken());

        var replayCoordinator = coordinator(
                NEXT_ACTIVATION,
                store,
                (execution, authority) -> {
                    throw new AssertionError("READY replay must not resolve mutable context");
                },
                binding -> {
                    throw new AssertionError("READY replay must not resolve current registry");
                },
                31);

        var replay = replayCoordinator.assembleReady(
                request, FRAME_SET_ID, new AgentRunCancellationToken());
        var reconciliation = replayCoordinator.reconcileReady(
                request, new AgentRunCancellationToken());

        assertThat(replay.newlyPublished()).isFalse();
        assertThat(replay.artifact().registryBindingSha256()).isEqualTo(REGISTRY_HASH);
        assertThat(replay.artifact().toolPolicyVersion()).isEqualTo(TOOL_POLICY);
        assertThat(reconciliation.registryBindingHash()).isEqualTo(REGISTRY_HASH);
        assertThat(reconciliation.toolPolicyVersion()).isEqualTo(TOOL_POLICY);
        assertThat(store.publishCalls).isEqualTo(1);
    }

    @Test
    void firstAssemblyAfterRuntimeRotationPreservesFrozenAdmissionActivation() {
        ExecuteAgentRunRequest request = request(
                ProductionIntakeParallelAssemblyCoordinator.AGENT_PROFILE_ID);
        ObjectNode previous = previousDossier();
        InMemoryAssemblyStore store = new InMemoryAssemblyStore(inputs(request, previous));
        var rotatedCoordinator = coordinator(
                NEXT_ACTIVATION,
                store,
                (execution, authority) -> new TrustedTurnContext(
                        "MESSAGE_1",
                        "本轮补充了核心事实。",
                        2,
                        previous,
                        "aliyun-bailian",
                        "qwen3.7-max-2026-06-08",
                        ACTIVATION),
                REGISTRY_HASH,
                TOOL_POLICY,
                31);

        var assembled = rotatedCoordinator.assembleReady(
                request, FRAME_SET_ID, new AgentRunCancellationToken());
        ProductionGraphCommandEnvelope storedEnvelope =
                new ProductionGraphEnvelopeCodec(MAPPER)
                        .decodeCommand(assembled.artifact().canonicalCommandEnvelopeBytes());

        assertThat(storedEnvelope.activationId()).isEqualTo(ACTIVATION);
        assertThat(storedEnvelope.activationId()).isNotEqualTo(NEXT_ACTIVATION);
    }

    @Test
    void replaysDurableReadyAcrossDeploymentWithExactCommandAndRoomAuthorities() {
        ExecuteAgentRunRequest request = request(
                ProductionIntakeParallelAssemblyCoordinator.AGENT_PROFILE_ID);
        ObjectNode previous = previousDossier();
        InMemoryAssemblyStore store = new InMemoryAssemblyStore(inputs(request, previous));
        AtomicInteger contextCalls = new AtomicInteger();
        var originalCoordinator = coordinator(
                ACTIVATION,
                store,
                (execution, authority) -> {
                    contextCalls.incrementAndGet();
                    return new TrustedTurnContext(
                            "MESSAGE_1",
                            "本轮补充了核心事实。",
                            2,
                        previous,
                        "aliyun-bailian",
                        "qwen3.7-max-2026-06-08",
                        ACTIVATION);
                },
                REGISTRY_HASH,
                TOOL_POLICY,
                31);
        var first = originalCoordinator.assembleReady(
                request, FRAME_SET_ID, new AgentRunCancellationToken());
        ProductionGraphCommandEnvelope historicalEnvelope =
                new ProductionGraphEnvelopeCodec(MAPPER)
                        .decodeCommand(first.artifact().canonicalCommandEnvelopeBytes());

        var reactivatedCoordinator = coordinator(
                NEXT_ACTIVATION,
                store,
                (execution, authority) -> {
                    throw new AssertionError("READY replay must not resolve mutable context");
                },
                "8".repeat(64),
                "tools.none.v2",
                31);
        var replay = reactivatedCoordinator.assembleReady(
                request, FRAME_SET_ID, new AgentRunCancellationToken());
        var reconciliation = reactivatedCoordinator.reconcileReady(
                request, new AgentRunCancellationToken());

        assertThat(historicalEnvelope.activationId()).isEqualTo(ACTIVATION);
        assertThat(historicalEnvelope.activationId()).isNotEqualTo(NEXT_ACTIVATION);
        assertThat(historicalEnvelope.command()).isEqualTo(request.command());
        assertThat(historicalEnvelope.commandEnvelopeHash())
                .isEqualTo(first.artifact().commandEnvelopeSha256());
        assertThat(replay.newlyPublished()).isFalse();
        assertThat(replay.artifact().canonicalCommandEnvelopeBytes())
                .isEqualTo(first.artifact().canonicalCommandEnvelopeBytes());
        assertThat(replay.artifact().canonicalResultEnvelopeBytes())
                .isEqualTo(first.artifact().canonicalResultEnvelopeBytes());
        assertThat(replay.artifact().registryBindingSha256()).isEqualTo(REGISTRY_HASH);
        assertThat(replay.artifact().toolPolicyVersion()).isEqualTo(TOOL_POLICY);
        assertThat(replay.graphResult()).isEqualTo(first.graphResult());
        assertThat(reconciliation.result()).isEqualTo(first.graphResult());
        assertThat(reconciliation.registryBindingHash()).isEqualTo(REGISTRY_HASH);
        assertThat(reconciliation.toolPolicyVersion()).isEqualTo(TOOL_POLICY);
        assertThat(contextCalls).hasValue(1);
        assertThat(store.publishCalls).isEqualTo(1);

        ReadyRaceAssemblyStore raceStore = new ReadyRaceAssemblyStore(first.artifact());
        var racedReplay = coordinator(
                        NEXT_ACTIVATION,
                        raceStore,
                        (execution, authority) -> {
                            throw new AssertionError("READY race replay must not resolve context");
                        },
                        binding -> {
                            throw new AssertionError(
                                    "READY race replay must not resolve current registry");
                        },
                        31)
                .assembleReady(request, FRAME_SET_ID, new AgentRunCancellationToken());
        assertThat(racedReplay.newlyPublished()).isFalse();
        assertThat(racedReplay.artifact().canonicalResultEnvelopeBytes())
                .isEqualTo(first.artifact().canonicalResultEnvelopeBytes());
        assertThat(racedReplay.graphResult()).isEqualTo(first.graphResult());
        assertThat(raceStore.readyReads).isEqualTo(2);
        assertThat(raceStore.publishCalls).isZero();

        assertReadyRequestConflict(
                coordinator(
                        NEXT_ACTIVATION,
                        store,
                        (execution, authority) -> {
                            throw new AssertionError("READY replay must not resolve mutable context");
                        },
                        REGISTRY_HASH,
                        TOOL_POLICY,
                        31),
                withChangedCommand(request));
        assertReadyRequestConflict(
                coordinator(
                        NEXT_ACTIVATION,
                        store,
                        (execution, authority) -> {
                            throw new AssertionError("READY replay must not resolve mutable context");
                        },
                        REGISTRY_HASH,
                        TOOL_POLICY,
                        32),
                request);
        assertThat(store.publishCalls).isEqualTo(1);
    }

    private static ProductionIntakeParallelAssemblyCoordinator coordinator(
            IntakeParallelAssemblyStore store,
            com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyContextResolver
                    contextResolver,
            String registryHash) {
        return coordinator(
                ACTIVATION,
                store,
                contextResolver,
                registryHash,
                TOOL_POLICY,
                31);
    }

    private static ProductionIntakeParallelAssemblyCoordinator coordinator(
            String activationId,
            IntakeParallelAssemblyStore store,
            com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyContextResolver
                    contextResolver,
            String registryHash,
            String toolPolicy,
            long roomFencingToken) {
        GraphRegistryBindingPolicy registry = binding ->
                new GraphRegistryBindingPolicy.ExpectedBinding(registryHash, toolPolicy);
        return coordinator(
                activationId,
                store,
                contextResolver,
                registry,
                roomFencingToken);
    }

    private static ProductionIntakeParallelAssemblyCoordinator coordinator(
            String activationId,
            IntakeParallelAssemblyStore store,
            com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyContextResolver
                    contextResolver,
            GraphRegistryBindingPolicy registry,
            long roomFencingToken) {
        ProductionAgentRunIdentityResolver identities = execution ->
                ProductionAgentRunIdentityResolver.DurableIdentity.from(
                        execution, roomFencingToken);
        return new ProductionIntakeParallelAssemblyCoordinator(
                activationId,
                identities,
                registry,
                contextResolver,
                store,
                new IntakeParallelFrameAssembler(),
                new ProductionGraphEnvelopeCodec(MAPPER),
                MAPPER);
    }

    private static void assertReadyRequestConflict(
            ProductionIntakeParallelAssemblyCoordinator coordinator,
            ExecuteAgentRunRequest request) {
        assertThatThrownBy(() -> coordinator.assembleReady(
                        request, FRAME_SET_ID, new AgentRunCancellationToken()))
                .isInstanceOfSatisfying(AssemblyConflictException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("INTAKE_PARALLEL_READY_REQUEST_CONFLICT"));
    }

    private static ExecuteAgentRunRequest withChangedCommand(ExecuteAgentRunRequest request) {
        ObjectNode changedJson = (ObjectNode) ProductionGraphTestFixtures.V1_CODEC
                .encode("room-graph-command.schema.json", request.command())
                .deepCopy();
        changedJson.put(
                "traceparent",
                "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        ObjectNode hashSource = changedJson.deepCopy();
        hashSource.remove("request_hash");
        changedJson.put("request_hash", ContractJson.sha256Hex(hashSource));
        RoomGraphCommand changed = ProductionGraphTestFixtures.V1_CODEC.decode(
                "room-graph-command.schema.json", changedJson, RoomGraphCommand.class);
        return new ExecuteAgentRunRequest(
                request.schemaVersion(),
                request.logicalRunId(),
                request.attemptNo(),
                request.attemptLimit(),
                request.streamProtocol(),
                request.logicalInputHash(),
                request.previousAttemptId(),
                request.resetRequired(),
                request.publicSequenceOffset(),
                changed);
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
                ProductionIntakeParallelAssemblyCoordinator.EXECUTION_PROFILE_ID,
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
        boolean parallel = ProductionIntakeParallelAssemblyCoordinator.AGENT_PROFILE_ID.equals(
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
                ProductionIntakeParallelAssemblyCoordinator.EXECUTION_OUTPUT_SCHEMA,
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
                "all-rooms.production-runtime.v2",
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
        ObjectNode hashSource = (ObjectNode) ProductionGraphTestFixtures.V1_CODEC
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
        item.put("segment_kind", "ACKNOWLEDGEMENT");
        item.put("candidate_text", "已记录您本轮补充的信息。");
        ObjectNode dialogue = root.putObject("dialogue");
        dialogue.putNull("remark_disposition");
        return root;
    }

    private static ObjectNode dossier() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode item = root.putArray("public_projection_items").addObject();
        ObjectNode delta = root.putObject("dossier_delta");
        ObjectNode row = MAPPER.createObjectNode();
        row.put("fact_key", "FACT_01");
        row.put("category", "OTHER");
        row.put("fact_target", "本轮核心事实");
        row.put("materiality", "CORE");
        row.put("stance", "CONFIRM");
        row.put("position_summary", "本轮补充了核心事实");
        row.put("asserted_value", "本轮补充了核心事实");
        item.set("source_row", row.deepCopy());
        delta.putNull("respondent_claim");
        return root;
    }

    private static ObjectNode quality() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode items = root.putArray("public_projection_items");
        ObjectNode quality = root.putObject("quality");
        Map<String, Integer> values = Map.ofEntries(
                Map.entry("references", 15),
                Map.entry("event_story", 20),
                Map.entry("party_positions", 20),
                Map.entry("requested_resolution", 15),
                Map.entry("risk_and_conflicts", 15),
                Map.entry("next_action_clarity", 15));
        List<Map.Entry<String, String>> dimensions = List.of(
                Map.entry("references", "REFERENCES"),
                Map.entry("event_story", "EVENT_STORY"),
                Map.entry("party_positions", "PARTY_POSITIONS"),
                Map.entry("requested_resolution", "REQUESTED_RESOLUTION"),
                Map.entry("risk_and_conflicts", "RISK_AND_CONFLICTS"),
                Map.entry("next_action_clarity", "NEXT_ACTION_CLARITY"));
        dimensions.forEach(dimension -> {
            String field = dimension.getKey();
            ObjectNode item = items.addObject();
            item.put("projection_kind", "DIMENSION_SCORE");
            item.put("dimension", dimension.getValue());
            item.put("candidate_score", values.get(field));
        });
        quality.put("assessment_reasoning", "依据当前消息形成六项评分。");
        return reorderRoot(root, "public_projection_items", "quality");
    }

    private static ObjectNode previousDossier() {
        ObjectNode dossier = MAPPER.createObjectNode();
        dossier.put("schema_version", "intake-dossier.v2");
        ObjectNode state = dossier.putObject("party_intake_state");
        state.put("schema_version", "party-intake-state.v1");
        state.set("USER", actorEntry());
        state.set("MERCHANT", actorEntry());
        ObjectNode matrix = dossier.putObject("case_fact_matrix");
        matrix.put("matrix_kind", "INITIATOR_FROZEN");
        matrix.putObject("party_map")
                .put("initiator_role", "USER")
                .put("respondent_role", "MERCHANT");
        ObjectNode row = matrix.putArray("fact_rows").addObject();
        row.put("fact_id", "FACT_01");
        row.put("category", "OTHER");
        row.put("fact_target", "本轮核心事实");
        row.put("materiality", "CORE");
        ObjectNode positions = row.putObject("positions");
        positions.putObject("USER")
                .put("stance", "CONFIRM")
                .put("position_summary", "上一轮已记录核心事实")
                .put("asserted_value", "上一轮核心事实")
                .put("source_type", "DIRECT_PARTY_STATEMENT")
                .putArray("source_refs")
                .add("MESSAGE_PREVIOUS");
        positions.putObject("MERCHANT")
                .put("stance", "NOT_ADDRESSED")
                .put("position_summary", "该方尚未直接陈述。")
                .putNull("asserted_value")
                .put("source_type", "NO_DIRECT_POSITION")
                .putArray("source_refs");
        row.putObject("party_alignment").put("status", "NOT_COMPUTED");
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

    private static final class ReadyRaceAssemblyStore implements IntakeParallelAssemblyStore {
        private final ReadyArtifact ready;
        private int readyReads;
        private int publishCalls;

        private ReadyRaceAssemblyStore(ReadyArtifact ready) {
            this.ready = ready;
        }

        @Override
        public ExactThreeInputs loadExactThree(AssemblyLookup lookup) {
            throw new AssemblyConflictException(
                    "INTAKE_PARALLEL_ASSEMBLY_NOT_COLLECTING",
                    "parallel Intake assembly became READY concurrently");
        }

        @Override
        public ReadyReceipt publishReady(PublishReady command) {
            publishCalls++;
            throw new AssertionError("READY race replay must not publish");
        }

        @Override
        public Optional<ReadyArtifact> loadReady(ReadyLookup lookup) {
            readyReads++;
            return readyReads == 1 ? Optional.empty() : Optional.of(ready);
        }

        @Override
        public ReadyAuthority lockReadyForTerminal(ReadyLookup lookup) {
            throw new AssertionError("READY race replay must not lock for terminal");
        }
    }
}
