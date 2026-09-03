package com.example.dispute.workflow.targete2e.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.AssemblyConflictException;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelAssemblyStore.FrameSetAuthority;
import com.example.dispute.workflow.application.intake.parallel.IntakeParallelFrameStagingPort.EventAuthority;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.ActorScope;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.InvocationContext;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.SnapshotRef;
import com.example.dispute.workflow.targete2e.persistence.TargetE2EActivationLedger.CommandAdmission;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore.CommandLookup;
import com.example.dispute.workflow.targete2e.persistence.material.TargetIntakeCommandMaterialStore.MaterialSnapshot;
import com.example.dispute.workflow.temporal.room.intake.IntakeActivityProtocol.RetryBudget;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeParallelTurnContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeTargetAgentRunContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MaterializedIntakeParallelAssemblyContextResolverTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Instant DEADLINE = Instant.parse("2030-08-25T00:00:00Z");
    private static final String SESSION_ID = "SESSION_1";
    private static final long FENCING_TOKEN = 31;

    @Test
    void resolvesOnlyTheImmutableCommandBoundTurnContext() {
        ExecuteAgentRunRequest request = request();
        FrameSetAuthority authority = authority(request, 2);
        MaterialSnapshot material = material(request, 2);
        TargetIntakeCommandMaterialStore store = mock(TargetIntakeCommandMaterialStore.class);
        when(store.readByRoute(any())).thenReturn(Optional.of(material));

        var resolver = new MaterializedIntakeParallelAssemblyContextResolver(store, MAPPER);
        var resolved = resolver.resolve(request, authority);

        assertThat(resolved.sourceMessageId()).isEqualTo("MESSAGE_1");
        assertThat(resolved.currentMessageText()).isEqualTo("本轮补充了核心事实。");
        assertThat(resolved.cognitiveRevision()).isEqualTo(2);
        assertThat(resolved.previousDossier()).isEqualTo(previousDossier());
        assertThat(resolved.executionProvider()).isEqualTo("aliyun-bailian");
        assertThat(resolved.executionModel()).isEqualTo("qwen3.7-max-2026-06-08");
        verify(store).readByRoute(new CommandLookup(
                request.command().tenantSurrogate(),
                request.command().caseId(),
                request.command().commandId(),
                request.command().roomEpoch(),
                FENCING_TOKEN));
    }

    @Test
    void failsClosedWhenTheAdmittedCommandMaterialIsMissing() {
        ExecuteAgentRunRequest request = request();
        TargetIntakeCommandMaterialStore store = mock(TargetIntakeCommandMaterialStore.class);
        when(store.readByRoute(any())).thenReturn(Optional.empty());

        var resolver = new MaterializedIntakeParallelAssemblyContextResolver(store, MAPPER);

        assertThatThrownBy(() -> resolver.resolve(request, authority(request, 2)))
                .isInstanceOf(AssemblyConflictException.class)
                .hasMessageContaining("command material was not found");
    }

    @Test
    void rejectsAFrameSetWhoseEventRevisionDriftsFromTheFrozenTurn() {
        ExecuteAgentRunRequest request = request();
        TargetIntakeCommandMaterialStore store = mock(TargetIntakeCommandMaterialStore.class);
        when(store.readByRoute(any())).thenReturn(Optional.of(material(request, 2)));

        var resolver = new MaterializedIntakeParallelAssemblyContextResolver(store, MAPPER);

        assertThatThrownBy(() -> resolver.resolve(request, authority(request, 3)))
                .isInstanceOf(AssemblyConflictException.class)
                .hasMessageContaining("frozen parallel turn context differs");
    }

    private static MaterialSnapshot material(ExecuteAgentRunRequest request, long revision) {
        RoomGraphCommand command = request.command();
        ObjectNode previous = previousDossier();
        IntakeParallelTurnContext turn = new IntakeParallelTurnContext(
                IntakeParallelTurnContext.SCHEMA_VERSION,
                IntakeParallelTurnContext.SOURCE_TYPE,
                "MESSAGE_1",
                "本轮补充了核心事实。",
                IntakeParallelTurnContext.messageHash("本轮补充了核心事实。"),
                revision,
                previous,
                ContractJson.sha256Hex(previous),
                command.domainSnapshotRef().sha256(),
                command.eventRef().sha256(),
                "aliyun-bailian",
                command.invocationContext().modelProfileId());
        IntakeTargetAgentRunContext target = new IntakeTargetAgentRunContext(
                IntakeTargetAgentRunContext.INITIAL_SCHEMA_VERSION,
                IntakeTargetAgentRunContext.TARGET_LANE,
                "p9act.v1." + "a".repeat(32),
                hash('3'),
                FENCING_TOKEN,
                2,
                1,
                "case-build-p9",
                "control-build-p9",
                "agent-build-p9",
                hash('4'),
                "graph-build-p9",
                hash('5'),
                hash('6'),
                request,
                turn);
        IntakeCommandExecutionContext context = new IntakeCommandExecutionContext(
                "intake-command-execution-context.v2",
                command.threadId(),
                SESSION_ID,
                DEADLINE.toEpochMilli(),
                new RetryBudget("intake-retry-budget.v1", 2, 3, 1),
                null,
                target);
        CommandAdmission admission = new CommandAdmission(
                target.activationId(),
                target.activationManifestHash(),
                hash('7'),
                command.tenantSurrogate(),
                command.caseId(),
                command.commandId(),
                target.commandHash(),
                target.commandEnvelopeHash(),
                command.roomEpoch(),
                FENCING_TOKEN);
        return new MaterialSnapshot(
                "ADMISSION_1", admission, context, hash('8'), Instant.parse("2026-08-25T00:00:00Z"));
    }

    private static FrameSetAuthority authority(
            ExecuteAgentRunRequest request, long logicalSequence) {
        RoomGraphCommand command = request.command();
        return new FrameSetAuthority(
                "IPFS_TEST_1",
                request.agentRunId(),
                request.attemptId(),
                command.commandId(),
                command.requestHash(),
                command.tenantSurrogate(),
                command.caseId(),
                "ROOM_1",
                command.roomEpoch(),
                FENCING_TOKEN,
                command.threadId(),
                ContractJson.sha256Hex(MAPPER.valueToTree(command.actorScope())),
                SESSION_ID,
                new EventAuthority(
                        "BINDING_1",
                        "THREAD_REGISTRATION_1",
                        logicalSequence,
                        1,
                        0,
                        command.requestHash()),
                hash('9'),
                hash('a'),
                TargetE2EIntakeParallelAssemblyCoordinator.EXECUTION_PROFILE_ID,
                "projection.v1",
                command.invocationContext().modelProfileId(),
                DEADLINE,
                0);
    }

    private static ExecuteAgentRunRequest request() {
        SnapshotRef snapshot = new SnapshotRef(
                "SNAPSHOT_1",
                "intake-domain-snapshot.v2",
                "urn:intake:snapshot:1",
                hash('b'),
                1024);
        SnapshotRef event = new SnapshotRef(
                "EVENT_1",
                "intake-turn-event.v2",
                "urn:intake:event:1",
                hash('c'),
                512);
        InvocationContext invocation = new InvocationContext(
                TargetE2EIntakeParallelAssemblyCoordinator.AGENT_PROFILE_ID,
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
                "ROOM_1",
                RoomType.INTAKE,
                1,
                "all-rooms.target-e2e.v2",
                "graph.v1",
                "checkpoint.v1",
                "grt.v1." + "d".repeat(32),
                new ActorScope("user-local", ActorRole.USER, Audience.USER, List.of()),
                2,
                "INTAKE_ACTIVE",
                1,
                snapshot,
                event,
                invocation,
                new RoomGraphCommand.RetryBudget(6, 3, 1),
                DEADLINE,
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                hash('0'));
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
                "agent-stream.v4",
                hash('e'),
                null,
                false,
                0,
                command);
    }

    private static ObjectNode previousDossier() {
        return MAPPER.createObjectNode()
                .put("schema_version", "intake-dossier.v3")
                .put("source_turn", 1);
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
