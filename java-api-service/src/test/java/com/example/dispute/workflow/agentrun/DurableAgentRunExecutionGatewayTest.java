package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.activity.agent.DurableAgentRunExecutionGateway;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DurableAgentRunExecutionGatewayTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES =
            Path.of("..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @Test
    void persistsEveryFrameBeforeProgressAndPassesExecutionMode() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        List<String> order = new ArrayList<>();
        AtomicReference<ExecutionMode> observedMode = new AtomicReference<>();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            assertThat(actualRequest).isEqualTo(request);
            observedMode.set(mode);
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(event(request, 1, StreamEventType.VISIBLE_DELTA, null));
            eventSink.accept(event(request, 2, StreamEventType.FINAL, result.outputHash()));
            return result;
        };
        AgentRunV2StreamStore store = event -> {
            order.add("persist-" + event.sequenceNo());
            return new AgentRunV2StreamStore.AppendReceipt(true, event.sequenceNo());
        };
        var gateway = new DurableAgentRunExecutionGateway(client, store);

        var completion = gateway.execute(
                request,
                ExecutionMode.RECONCILE_ONLY,
                progress -> order.add("progress-" + progress.lastSequenceNo()),
                new AgentRunCancellationToken());

        assertThat(observedMode.get()).isEqualTo(ExecutionMode.RECONCILE_ONLY);
        assertThat(order)
                .containsExactly(
                        "persist-0",
                        "progress-0",
                        "persist-1",
                        "progress-1",
                        "persist-2",
                        "progress-2");
        assertThat(completion.graphResult()).isEqualTo(result);
        assertThat(completion.lastSequenceNo()).isEqualTo(2);
        assertThat(completion.publicOutputEmitted()).isTrue();
    }

    @Test
    void rejectsResultMetadataThatDoesNotMatchAuthorizedInvocation() throws Exception {
        ExecuteAgentRunRequest request = request();
        JsonNode wrapper = MAPPER.readTree(
                FIXTURES.resolve("room-graph-result-valid.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        wrapper.required("instance").required("execution_metadata"))
                .put("model_profile_id", "unauthorized-model.v1");
        RoomGraphResult mismatchedResult =
                MAPPER.treeToValue(wrapper.required("instance"), RoomGraphResult.class);
        List<Long> persisted = new ArrayList<>();
        var gateway = gatewayReturning(
                request,
                mismatchedResult,
                mismatchedResult.outputHash(),
                recordingStore(persisted));

        assertThatThrownBy(() -> gateway.execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {},
                        new AgentRunCancellationToken()))
                .isInstanceOf(AgentRunExecutionException.class)
                .extracting(failure -> ((AgentRunExecutionException) failure).errorCode())
                .isEqualTo("AGENT_RUN_STREAM_V2_INVALID");
        assertThat(persisted).containsExactly(0L);
    }

    @Test
    void rejectsFinalHashThatDoesNotMatchGraphResult() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        List<Long> persisted = new ArrayList<>();
        var gateway = gatewayReturning(
                request, result, "0".repeat(64), recordingStore(persisted));

        assertThatThrownBy(() -> gateway.execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {},
                        new AgentRunCancellationToken()))
                .isInstanceOf(AgentRunExecutionException.class)
                .hasMessageContaining("does not match the final stream");
        assertThat(persisted).containsExactly(0L);
    }

    @Test
    void rejectsGraphIdentityBeforePersistingFinal() throws Exception {
        ExecuteAgentRunRequest request = request();
        JsonNode wrapper = MAPPER.readTree(
                FIXTURES.resolve("room-graph-result-valid.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrapper.required("instance"))
                .put("graph_version", "unrequested-graph.v2");
        RoomGraphResult mismatchedResult =
                MAPPER.treeToValue(wrapper.required("instance"), RoomGraphResult.class);
        List<Long> persisted = new ArrayList<>();
        var gateway = gatewayReturning(
                request,
                mismatchedResult,
                mismatchedResult.outputHash(),
                recordingStore(persisted));

        assertThatThrownBy(() -> gateway.execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {},
                        new AgentRunCancellationToken()))
                .isInstanceOf(AgentRunExecutionException.class)
                .hasMessageContaining("does not match the final stream");
        assertThat(persisted).containsExactly(0L);
    }

    @Test
    void suppressesRegressingProgressForExactDuplicateReplay() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        var gateway = gatewayReturning(
                request,
                result,
                result.outputHash(),
                event -> new AgentRunV2StreamStore.AppendReceipt(false, 1));
        List<Long> progress = new ArrayList<>();

        var completion = gateway.execute(
                request,
                ExecutionMode.RECONCILE_ONLY,
                frame -> progress.add(frame.lastSequenceNo()),
                new AgentRunCancellationToken());

        assertThat(progress).isEmpty();
        assertThat(completion.lastSequenceNo()).isEqualTo(1);
        assertThat(completion.graphResult()).isEqualTo(result);
    }

    @Test
    void treatsTransportLossAfterFinalAsReplaySafeWithoutPersistingFinal() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        List<Long> persisted = new ArrayList<>();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(event(request, 1, StreamEventType.FINAL, result.outputHash()));
            throw new IllegalStateException("transport closed before result return");
        };
        var gateway = new DurableAgentRunExecutionGateway(client, recordingStore(persisted));

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> gateway.execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {},
                        new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_RESULT_AFTER_FINAL_UNAVAILABLE");
        assertThat(failure.commandReplaySafe()).isTrue();
        assertThat(failure.lastSequenceNo()).isZero();
        assertThat(persisted).containsExactly(0L);
    }

    @Test
    void doesNotNotifyProgressWhenDurableHighWatermarkLags() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(event(request, 1, StreamEventType.VISIBLE_DELTA, null));
            return result;
        };
        var gateway = new DurableAgentRunExecutionGateway(
                client, event -> new AgentRunV2StreamStore.AppendReceipt(true, 0));
        List<Long> progress = new ArrayList<>();

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> gateway.execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        frame -> progress.add(frame.lastSequenceNo()),
                        new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_DURABLE_APPEND_LAGGED");
        assertThat(failure.lastSequenceNo()).isZero();
        assertThat(failure.publicOutputEmitted()).isFalse();
        assertThat(progress).containsExactly(0L);
    }

    private static DurableAgentRunExecutionGateway gatewayReturning(
            ExecuteAgentRunRequest request,
            RoomGraphResult result,
            String finalHash,
            AgentRunV2StreamStore store) {
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(event(request, 1, StreamEventType.FINAL, finalHash));
            return result;
        };
        return new DurableAgentRunExecutionGateway(client, store);
    }

    private static AgentRunV2StreamStore recordingStore(List<Long> persisted) {
        return event -> {
            persisted.add(event.sequenceNo());
            return new AgentRunV2StreamStore.AppendReceipt(true, event.sequenceNo());
        };
    }

    private static AgentStreamEvent event(
            ExecuteAgentRunRequest request,
            long sequenceNo,
            StreamEventType eventType,
            String finalHash) {
        return new AgentStreamEvent(
                "agent-stream.v2",
                request.agentRunId(),
                request.attemptId(),
                sequenceNo,
                eventType,
                request.command().actorScope().audience(),
                NOW.plusSeconds(sequenceNo),
                new AgentStreamEvent.Payload(
                        "node",
                        eventType == StreamEventType.VISIBLE_DELTA ? "room_utterance" : null,
                        eventType == StreamEventType.VISIBLE_DELTA ? "public" : null,
                        null,
                        null,
                        null,
                        eventType == StreamEventType.FINAL ? "urn:result:1" : null,
                        finalHash,
                        null,
                        null));
    }

    private static ExecuteAgentRunRequest request() throws Exception {
        RoomGraphCommand command = fixture("room-graph-command-valid.json", RoomGraphCommand.class);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                command.logicalRunId(),
                1,
                "agent-stream.v2",
                command);
    }

    private static RoomGraphResult graphResult() throws Exception {
        return fixture("room-graph-result-valid.json", RoomGraphResult.class);
    }

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }
}
