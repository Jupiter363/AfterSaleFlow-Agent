package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.AppendReceipt;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.BatchAppendReceipt;
import com.example.dispute.agentstream.application.AgentRunTransientStreamPublisher;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.activity.agent.DurableAgentRunExecutionGateway;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.runtime.rooms.evidence.TargetEvidenceTurnResultV2;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/** Focused proof for the v3 transient-delta and frame-commit boundary. */
class DurableAgentRunExecutionGatewayV3Test {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES =
            Path.of("..", "..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-08-18T08:00:00Z");

    @Test
    void retriesTheExactDurableBatchInlineBeforeATransientAppendCanCancelTheGraphStream()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        AtomicInteger appendCalls = new AtomicInteger();
        List<List<Long>> attemptedBatches = new ArrayList<>();
        List<Long> progress = new ArrayList<>();
        AgentRunV2StreamStore store = new AgentRunV2StreamStore() {
            @Override
            public AppendReceipt append(AgentStreamEvent event) {
                BatchAppendReceipt receipt = appendBatch(List.of(event));
                return new AppendReceipt(
                        receipt.inserted().getFirst(), receipt.durableHighWatermark());
            }

            @Override
            public BatchAppendReceipt appendBatch(List<AgentStreamEvent> events) {
                attemptedBatches.add(
                        events.stream().map(AgentStreamEvent::sequenceNo).toList());
                if (appendCalls.incrementAndGet() == 1) {
                    throw new QueryTimeoutException("transient append timeout");
                }
                boolean inserted = appendCalls.get() != 2;
                return new BatchAppendReceipt(
                        events.stream().map(ignored -> inserted).toList(),
                        events.getLast().sequenceNo());
            }
        };
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(attemptStarted(request));
            eventSink.accept(visibleDelta(request, 1, "durable text"));
            assertThat(progress).containsExactly(1L);
            eventSink.accept(finalEvent(request, 2, result.outputHash()));
            return result;
        };

        var completion = new DurableAgentRunExecutionGateway(client, store)
                .execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        frame -> progress.add(frame.lastSequenceNo()),
                        new AgentRunCancellationToken());

        assertThat(attemptedBatches)
                .containsExactly(List.of(1L), List.of(1L), List.of(2L));
        assertThat(appendCalls).hasValue(3);
        assertThat(progress).containsExactly(1L);
        assertThat(completion.lastSequenceNo()).isEqualTo(2);
        assertThat(completion.graphResult()).isEqualTo(result);
    }

    @Test
    void durablyOrdersGenerationResetBeforeTheReplacementVisibleDelta() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        RecordingStore store = new RecordingStore();
        List<Long> progress = new ArrayList<>();
        AgentGraphCommandClient client = (actual, mode, sink, token) -> {
            sink.accept(attemptStarted(request));
            sink.accept(visibleDelta(request, 1, "第一代临时文本"));
            sink.accept(generationReset(request, 2));
            sink.accept(visibleDelta(request, 3, "第二代有效文本"));
            sink.accept(finalEvent(request, 4, result.outputHash()));
            return result;
        };

        var gateway = new DurableAgentRunExecutionGateway(client, store);
        var completion = gateway.execute(
                request,
                ExecutionMode.EXECUTE_OR_RECONCILE,
                state -> progress.add(state.lastSequenceNo()),
                new AgentRunCancellationToken());

        assertThat(store.batches)
                .flatExtracting(batch -> batch)
                .extracting(AgentStreamEvent::eventType)
                .containsExactly(
                        StreamEventType.VISIBLE_DELTA,
                        StreamEventType.GENERATION_RESET,
                        StreamEventType.VISIBLE_DELTA,
                        StreamEventType.FINAL);
        assertThat(store.batches.get(1).getFirst().payload().generation()).isEqualTo(2);
        assertThat(store.batches.get(1).getFirst().payload().reasonCode())
                .isEqualTo("OUTPUT_SCHEMA_INVALID");
        assertThat(progress).containsExactly(1L, 2L, 3L);
        assertThat(completion.lastSequenceNo()).isEqualTo(4L);
        assertThat(completion.publicOutputEmitted()).isTrue();
    }

    @Test
    void relaysEveryDeltaImmediatelyButPersistsOnlyTheCompletedFrameAndFinal() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        ObjectNode header = header(1, "ROOM_WELCOME");
        String frameId = TargetEvidenceTurnResultV2.frameId(
                request.command().commandId(), request.attemptId(), 1, "ROOM_WELCOME");
        String first = "欢迎";
        String second = "进入";
        AgentStreamEvent commit = committedFrame(
                request, 4, frameId, 1, "ROOM_WELCOME", header, first + second);
        ObjectNode secondHeader = header(2, "OPENING_ORIENTATION");
        String secondFrameId = TargetEvidenceTurnResultV2.frameId(
                request.command().commandId(), request.attemptId(), 2, "OPENING_ORIENTATION");
        String orientation = "正在梳理案情";
        AgentStreamEvent secondCommit = committedFrame(
                request,
                7,
                secondFrameId,
                2,
                "OPENING_ORIENTATION",
                secondHeader,
                orientation);
        RecordingStore store = new RecordingStore();
        List<AgentStreamEvent> transientEvents = new ArrayList<>();
        List<Long> progress = new ArrayList<>();
        AgentGraphCommandClient client = (actual, mode, sink, token) -> {
            sink.accept(attemptStarted(request));
            sink.accept(frameStart(request, 1, frameId, "ROOM_WELCOME", header));
            assertThat(store.batches).isEmpty();
            sink.accept(frameDelta(request, 2, frameId, 1, 0, first));
            assertThat(store.batches).isEmpty();
            sink.accept(frameDelta(request, 3, frameId, 1, 1, second));
            assertThat(store.batches).isEmpty();
            sink.accept(commit);
            assertThat(store.batches)
                    .extracting(batch -> batch.stream().map(AgentStreamEvent::eventType).toList())
                    .containsExactly(List.of(
                            StreamEventType.PUBLIC_FRAME_START,
                            StreamEventType.ACTIVE_FRAME_SNAPSHOT,
                            StreamEventType.PUBLIC_FRAME_COMMITTED));
            sink.accept(frameStart(request, 5, secondFrameId, "OPENING_ORIENTATION", secondHeader));
            sink.accept(frameDelta(request, 6, secondFrameId, 2, 0, orientation));
            sink.accept(secondCommit);
            sink.accept(finalEvent(request, 8, result.outputHash()));
            return result;
        };

        var gateway = new DurableAgentRunExecutionGateway(
                client,
                null,
                store,
                null,
                transientEvents::add);
        var completion = gateway.execute(
                request,
                ExecutionMode.EXECUTE_OR_RECONCILE,
                state -> progress.add(state.lastSequenceNo()),
                new AgentRunCancellationToken());

        assertThat(store.markCalls).isEqualTo(1);
        assertThat(transientEvents).extracting(AgentStreamEvent::eventType)
                .containsExactly(
                        StreamEventType.PUBLIC_FRAME_START,
                        StreamEventType.PUBLIC_FRAME_START,
                        StreamEventType.PUBLIC_TEXT_DELTA,
                        StreamEventType.PUBLIC_FRAME_START,
                        StreamEventType.PUBLIC_TEXT_DELTA,
                        StreamEventType.PUBLIC_FRAME_START,
                        StreamEventType.PUBLIC_FRAME_START,
                        StreamEventType.PUBLIC_TEXT_DELTA);
        assertThat(store.batches).hasSize(3);
        assertThat(store.batches.get(0)).extracting(AgentStreamEvent::sequenceNo)
                .containsExactly(1L, 2L, 3L);
        assertThat(store.batches.get(1)).extracting(AgentStreamEvent::sequenceNo)
                .containsExactly(4L, 5L, 6L);
        assertThat(store.batches.get(2)).extracting(AgentStreamEvent::sequenceNo)
                .containsExactly(7L);
        assertThat(store.batches.get(0).get(1).payload().publicText()).isEqualTo(first + second);
        assertThat(progress).containsExactly(3L, 6L);
        assertThat(completion.lastSequenceNo()).isEqualTo(7L);
        assertThat(completion.publicOutputEmitted()).isTrue();
    }

    @Test
    void rejectsAFrameDeltaGapBeforeAnyFrameProjectionIsDurablyWritten() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        ObjectNode header = header(1, "ROOM_WELCOME");
        String frameId = TargetEvidenceTurnResultV2.frameId(
                request.command().commandId(), request.attemptId(), 1, "ROOM_WELCOME");
        RecordingStore store = new RecordingStore();
        List<AgentStreamEvent> transientEvents = new ArrayList<>();
        AgentGraphCommandClient client = (actual, mode, sink, token) -> {
            sink.accept(attemptStarted(request));
            sink.accept(frameStart(request, 1, frameId, "ROOM_WELCOME", header));
            sink.accept(frameDelta(request, 2, frameId, 1, 1, "越过零"));
            return result;
        };

        var gateway = new DurableAgentRunExecutionGateway(
                client, null, store, null, transientEvents::add);
        assertThatThrownBy(() -> gateway.execute(
                request,
                ExecutionMode.EXECUTE_OR_RECONCILE,
                ignored -> { },
                new AgentRunCancellationToken()))
                .isInstanceOf(AgentRunExecutionException.class)
                .satisfies(error -> assertThat(((AgentRunExecutionException) error).errorCode())
                        .isEqualTo("AGENT_RUN_STREAM_V3_FRAME_INVALID"));
        assertThat(store.batches)
                .allSatisfy(batch -> assertThat(batch)
                        .extracting(AgentStreamEvent::eventType)
                        .doesNotContain(
                                StreamEventType.PUBLIC_FRAME_START,
                                StreamEventType.ACTIVE_FRAME_SNAPSHOT,
                                StreamEventType.PUBLIC_FRAME_COMMITTED));
        assertThat(transientEvents).extracting(AgentStreamEvent::eventType)
                .containsExactly(StreamEventType.PUBLIC_FRAME_START);
    }

    @Test
    void rejectsACommitHashThatDoesNotMatchTheTransientFrameBytes() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        ObjectNode header = header(1, "ROOM_WELCOME");
        String frameId = TargetEvidenceTurnResultV2.frameId(
                request.command().commandId(), request.attemptId(), 1, "ROOM_WELCOME");
        AgentStreamEvent valid = committedFrame(
                request, 3, frameId, 1, "ROOM_WELCOME", header, "欢迎");
        AgentStreamEvent invalid = new AgentStreamEvent(
                valid.schemaVersion(), valid.runId(), valid.attemptId(), valid.sequenceNo(),
                valid.eventType(), valid.audience(), valid.occurredAt(),
                new AgentStreamEvent.Payload(
                        null, null, null, null, null, null, null, null, null, null,
                        frameId, 1, "ROOM_WELCOME", null, null, null,
                        "v3:" + request.attemptId() + ":FRAME:1",
                        "0".repeat(64), valid.payload().publicTextSha256(),
                        valid.payload().frameSha256(), valid.payload().publicTextChars()));
        RecordingStore store = new RecordingStore();
        AgentGraphCommandClient client = (actual, mode, sink, token) -> {
            sink.accept(attemptStarted(request));
            sink.accept(frameStart(request, 1, frameId, "ROOM_WELCOME", header));
            sink.accept(frameDelta(request, 2, frameId, 1, 0, "欢迎"));
            sink.accept(invalid);
            return result;
        };
        var gateway = new DurableAgentRunExecutionGateway(
                client, null, store, null, AgentRunTransientStreamPublisher.noOp());

        assertThatThrownBy(() -> gateway.execute(
                request,
                ExecutionMode.EXECUTE_OR_RECONCILE,
                ignored -> { },
                new AgentRunCancellationToken()))
                .isInstanceOf(AgentRunExecutionException.class)
                .satisfies(error -> assertThat(((AgentRunExecutionException) error).errorCode())
                        .isEqualTo("AGENT_RUN_STREAM_V3_FRAME_INVALID"));
        assertThat(store.batches)
                .allSatisfy(batch -> assertThat(batch)
                        .extracting(AgentStreamEvent::eventType)
                        .doesNotContain(
                                StreamEventType.PUBLIC_FRAME_START,
                                StreamEventType.ACTIVE_FRAME_SNAPSHOT,
                                StreamEventType.PUBLIC_FRAME_COMMITTED));
    }

    @Test
    void normalizesAProviderTerminalToTheCompressedDurableSequence() throws Exception {
        ExecuteAgentRunRequest request = request();
        ObjectNode header = header(1, "ROOM_WELCOME");
        String frameId = TargetEvidenceTurnResultV2.frameId(
                request.command().commandId(), request.attemptId(), 1, "ROOM_WELCOME");
        RecordingStore store = new RecordingStore();
        List<Long> progress = new ArrayList<>();
        AgentGraphCommandClient client = (actual, mode, sink, token) -> {
            sink.accept(attemptStarted(request));
            sink.accept(frameStart(request, 1, frameId, "ROOM_WELCOME", header));
            sink.accept(frameDelta(request, 2, frameId, 1, 0, "欢"));
            sink.accept(frameDelta(request, 3, frameId, 1, 1, "迎"));
            sink.accept(committedFrame(
                    request, 4, frameId, 1, "ROOM_WELCOME", header, "欢迎"));
            sink.accept(errorEvent(request, 5, "AGENT_OUTPUT_SCHEMA_INVALID"));
            throw AgentRunExecutionException.failLogicalRun(
                    "AGENT_OUTPUT_SCHEMA_INVALID",
                    "graph command reached a logical error terminal",
                    5,
                    true,
                    null);
        };
        var gateway = new DurableAgentRunExecutionGateway(client, store);

        assertThatThrownBy(() -> gateway.execute(
                request,
                ExecutionMode.EXECUTE_OR_RECONCILE,
                state -> progress.add(state.lastSequenceNo()),
                new AgentRunCancellationToken()))
                .isInstanceOf(AgentRunExecutionException.class)
                .satisfies(error -> {
                    AgentRunExecutionException failure = (AgentRunExecutionException) error;
                    assertThat(failure.errorCode()).isEqualTo("AGENT_OUTPUT_SCHEMA_INVALID");
                    assertThat(failure.lastSequenceNo()).isEqualTo(4L);
                    assertThat(failure.publicOutputEmitted()).isTrue();
                });
        assertThat(progress).containsExactly(3L, 4L);
        assertThat(store.batches).hasSize(2);
        assertThat(store.batches.get(1))
                .extracting(AgentStreamEvent::sequenceNo, AgentStreamEvent::eventType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(4L, StreamEventType.ERROR));
    }

    private static AgentStreamEvent attemptStarted(ExecuteAgentRunRequest request) {
        return new AgentStreamEvent(
                "agent-stream.v3", request.agentRunId(), request.attemptId(), 0,
                StreamEventType.ATTEMPT_STARTED,
                request.command().actorScope().audience(), NOW,
                new AgentStreamEvent.Payload(
                        "production-runtime", null, null, null, null, null, null, null, null, null));
    }

    private static AgentStreamEvent visibleDelta(
            ExecuteAgentRunRequest request, long sequence, String delta) {
        return new AgentStreamEvent(
                "agent-stream.v3", request.agentRunId(), request.attemptId(), sequence,
                StreamEventType.VISIBLE_DELTA,
                request.command().actorScope().audience(), NOW.plusSeconds(sequence),
                new AgentStreamEvent.Payload(
                        "intake_turn_case_detail", "room_utterance", delta,
                        null, null, null, null, null, null, null));
    }

    private static AgentStreamEvent generationReset(
            ExecuteAgentRunRequest request, long sequence) {
        return new AgentStreamEvent(
                "agent-stream.v3", request.agentRunId(), request.attemptId(), sequence,
                StreamEventType.GENERATION_RESET,
                request.command().actorScope().audience(), NOW.plusSeconds(sequence),
                new AgentStreamEvent.Payload(
                        "intake_turn_case_detail",
                        null,
                        null,
                        null,
                        "OUTPUT_SCHEMA_INVALID",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        2));
    }

    private static AgentStreamEvent frameStart(
            ExecuteAgentRunRequest request,
            long sequence,
            String frameId,
            String frameType,
            JsonNode header) {
        return new AgentStreamEvent(
                "agent-stream.v3", request.agentRunId(), request.attemptId(), sequence,
                StreamEventType.PUBLIC_FRAME_START,
                request.command().actorScope().audience(), NOW.plusSeconds(sequence),
                new AgentStreamEvent.Payload(
                        null, null, null, null, null, null, null, null, null, null,
                        frameId, header.path("frame_sequence").intValue(), frameType, header,
                        null, null, null, null, null, null, null));
    }

    private static AgentStreamEvent frameDelta(
            ExecuteAgentRunRequest request,
            long sequence,
            String frameId,
            int frameSequence,
            int deltaIndex,
            String delta) {
        return new AgentStreamEvent(
                "agent-stream.v3", request.agentRunId(), request.attemptId(), sequence,
                StreamEventType.PUBLIC_TEXT_DELTA,
                request.command().actorScope().audience(), NOW.plusSeconds(sequence),
                new AgentStreamEvent.Payload(
                        null, null, delta, null, null, null, null, null, null, null,
                        frameId, frameSequence, null, null, deltaIndex, null,
                        null, null, null, null, null));
    }

    private static AgentStreamEvent committedFrame(
            ExecuteAgentRunRequest request,
            long sequence,
            String frameId,
            int frameSequence,
            String frameType,
            ObjectNode header,
            String text) {
        String headerHash = ContractJson.sha256Hex(header);
        String textHash = sha256(text);
        int chars = text.codePointCount(0, text.length());
        ObjectNode preimage = MAPPER.createObjectNode();
        preimage.put("frame_id", frameId);
        preimage.put("frame_sequence", frameSequence);
        preimage.put("frame_type", frameType);
        preimage.set("header", header);
        preimage.put("header_sha256", headerHash);
        preimage.put("public_text", text);
        preimage.put("public_text_sha256", textHash);
        preimage.put("public_text_length", chars);
        String frameHash = ContractJson.sha256Hex(preimage);
        return new AgentStreamEvent(
                "agent-stream.v3", request.agentRunId(), request.attemptId(), sequence,
                StreamEventType.PUBLIC_FRAME_COMMITTED,
                request.command().actorScope().audience(), NOW.plusSeconds(sequence),
                new AgentStreamEvent.Payload(
                        null, null, null, null, null, null, null, null, null, null,
                        frameId, frameSequence, null, null, null, null,
                        "v3:" + request.attemptId() + ":FRAME:" + frameSequence,
                        headerHash, textHash, frameHash, chars));
    }

    private static AgentStreamEvent finalEvent(
            ExecuteAgentRunRequest request, long sequence, String resultHash) {
        return new AgentStreamEvent(
                "agent-stream.v3", request.agentRunId(), request.attemptId(), sequence,
                StreamEventType.FINAL,
                request.command().actorScope().audience(), NOW.plusSeconds(sequence),
                new AgentStreamEvent.Payload(
                        null, null, null, null, null, null,
                        "urn:production-runtime:result:1", resultHash, null, null));
    }

    private static AgentStreamEvent errorEvent(
            ExecuteAgentRunRequest request, long sequence, String errorCode) {
        return new AgentStreamEvent(
                "agent-stream.v3", request.agentRunId(), request.attemptId(), sequence,
                StreamEventType.ERROR,
                request.command().actorScope().audience(), NOW.plusSeconds(sequence),
                new AgentStreamEvent.Payload(
                        null, null, null, null, null, null, null, null, errorCode, false));
    }

    private static ObjectNode header(int sequence, String frameType) {
        ObjectNode header = MAPPER.createObjectNode();
        header.put("frame_sequence", sequence);
        header.put("frame_type", frameType);
        return header;
    }

    private static ExecuteAgentRunRequest request() throws Exception {
        RoomGraphCommand command = fixture("room-graph-command-valid.json", RoomGraphCommand.class);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                command.logicalRunId(),
                1,
                "agent-stream.v3",
                "b".repeat(64),
                null,
                false,
                0,
                command);
    }

    private static RoomGraphResult graphResult() throws Exception {
        return fixture("room-graph-result-valid.json", RoomGraphResult.class);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }

    private static final class RecordingStore implements AgentRunV2StreamStore {
        private final List<List<AgentStreamEvent>> batches = new ArrayList<>();
        private int markCalls;

        @Override
        public AppendReceipt append(AgentStreamEvent event) {
            BatchAppendReceipt receipt = appendBatch(List.of(event));
            return new AppendReceipt(receipt.inserted().getFirst(), receipt.durableHighWatermark());
        }

        @Override
        public boolean markPublicOutputStarted(String runId, String attemptId) {
            markCalls++;
            return markCalls == 1;
        }

        @Override
        public BatchAppendReceipt appendBatch(List<AgentStreamEvent> events) {
            batches.add(List.copyOf(events));
            long highWatermark = events.getLast().sequenceNo();
            return new BatchAppendReceipt(
                    events.stream().map(ignored -> true).toList(), highWatermark);
        }
    }
}
