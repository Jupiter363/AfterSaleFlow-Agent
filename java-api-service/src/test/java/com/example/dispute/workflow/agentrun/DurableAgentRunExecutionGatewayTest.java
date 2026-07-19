package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.AppendReceipt;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.BatchAppendReceipt;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.activity.agent.DurableAgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.GraphReconciliationException;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.temporal.client.ActivityCanceledException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DurableAgentRunExecutionGatewayTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES =
            Path.of("..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @Test
    void persistsRealBoundedBatchesBeforeProgressAndPassesExecutionMode() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        List<String> order = new ArrayList<>();
        List<List<Long>> batches = new ArrayList<>();
        AtomicReference<ExecutionMode> observedMode = new AtomicReference<>();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            assertThat(actualRequest).isEqualTo(request);
            observedMode.set(mode);
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(
                    event(
                            request,
                            1,
                            StreamEventType.VISIBLE_DELTA,
                            null,
                            "a".repeat(600)));
            eventSink.accept(
                    event(
                            request,
                            2,
                            StreamEventType.VISIBLE_DELTA,
                            null,
                            "b".repeat(600)));
            eventSink.accept(event(request, 3, StreamEventType.FINAL, result.outputHash()));
            return result;
        };
        AgentRunV2StreamStore store = batchStore(events -> {
            List<Long> sequences = sequences(events);
            batches.add(sequences);
            order.add("persist-" + sequences);
            return receipt(events, true, sequences.getLast());
        });
        var gateway = new DurableAgentRunExecutionGateway(client, store);

        var completion = gateway.execute(
                request,
                ExecutionMode.EXECUTE_OR_RECONCILE,
                progress -> order.add("progress-" + progress.lastSequenceNo()),
                new AgentRunCancellationToken());

        assertThat(observedMode.get()).isEqualTo(ExecutionMode.EXECUTE_OR_RECONCILE);
        assertThat(batches).containsExactly(List.of(0L), List.of(1L, 2L), List.of(3L));
        assertThat(order)
                .containsExactly(
                        "persist-[0]",
                        "progress-0",
                        "persist-[1, 2]",
                        "progress-2",
                        "persist-[3]",
                        "progress-3");
        assertThat(completion.graphResult()).isEqualTo(result);
        assertThat(completion.lastSequenceNo()).isEqualTo(3);
        assertThat(completion.publicOutputEmitted()).isTrue();
    }

    @Test
    void finalFlushCommitsATrailingSmallDeltaInOneBatch() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        List<List<Long>> batches = new ArrayList<>();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(event(request, 1, StreamEventType.VISIBLE_DELTA, null));
            eventSink.accept(event(request, 2, StreamEventType.FINAL, result.outputHash()));
            return result;
        };
        AgentRunV2StreamStore store = batchStore(events -> {
            List<Long> sequences = sequences(events);
            batches.add(sequences);
            return receipt(events, true, sequences.getLast());
        });

        var completion = new DurableAgentRunExecutionGateway(client, store)
                .execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {},
                        new AgentRunCancellationToken());

        assertThat(batches).containsExactly(List.of(0L), List.of(1L, 2L));
        assertThat(completion.lastSequenceNo()).isEqualTo(2);
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
    void rejectsADuplicateSequenceWhileEarlierEventsAreStillBuffered() throws Exception {
        ExecuteAgentRunRequest request = request();
        List<Long> persisted = new ArrayList<>();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(event(request, 1, StreamEventType.VISIBLE_DELTA, null));
            eventSink.accept(event(request, 1, StreamEventType.VISIBLE_DELTA, null));
            throw new AssertionError("duplicate sequence must fail in the event sink");
        };

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(client, recordingStore(persisted))
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_STREAM_V2_INVALID");
        assertThat(persisted).containsExactly(0L, 1L);
    }

    @Test
    void suppressesProgressForExactDuplicateBatchReplay() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        var gateway = gatewayReturning(
                request,
                result,
                result.outputHash(),
                batchStore(events -> receipt(events, false, 1)));
        List<Long> progress = new ArrayList<>();

        var completion = gateway.execute(
                request,
                ExecutionMode.EXECUTE_OR_RECONCILE,
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
        assertThat(failure.recoveryAction().name()).isEqualTo("RECONCILE_TERMINAL");
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
                client, batchStore(events -> receipt(events, true, 0)));
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

    @Test
    void appendFailureDoesNotPublishTheUncommittedBatch() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        AtomicInteger invocation = new AtomicInteger();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(event(request, 1, StreamEventType.VISIBLE_DELTA, null));
            eventSink.accept(event(request, 2, StreamEventType.FINAL, result.outputHash()));
            return result;
        };
        AgentRunV2StreamStore store = batchStore(events -> {
            if (invocation.incrementAndGet() == 2) {
                throw new IllegalStateException("postgres unavailable");
            }
            return receipt(events, true, events.getLast().sequenceNo());
        });
        List<Long> progress = new ArrayList<>();

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(client, store)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                frame -> progress.add(frame.lastSequenceNo()),
                                new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_DURABLE_APPEND_FAILED");
        assertThat(failure.commandReplaySafe()).isTrue();
        assertThat(failure.recoveryAction().name()).isEqualTo("RECONCILE_TERMINAL");
        assertThat(failure.lastSequenceNo()).isZero();
        assertThat(failure.publicOutputEmitted()).isFalse();
        assertThat(progress).containsExactly(0L);
    }

    @Test
    void flushesAcceptedFramesBeforePropagatingTransportCancellation() throws Exception {
        ExecuteAgentRunRequest request = request();
        List<List<Long>> batches = new ArrayList<>();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(event(request, 1, StreamEventType.VISIBLE_DELTA, null));
            throw new ActivityCanceledException();
        };
        AgentRunV2StreamStore store = batchStore(events -> {
            batches.add(sequences(events));
            return receipt(events, true, events.getLast().sequenceNo());
        });
        List<Long> progress = new ArrayList<>();

        assertThatThrownBy(() -> new DurableAgentRunExecutionGateway(client, store)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                frame -> progress.add(frame.lastSequenceNo()),
                                new AgentRunCancellationToken()))
                .isInstanceOf(ActivityCanceledException.class);

        assertThat(batches).containsExactly(List.of(0L), List.of(1L));
        assertThat(progress).containsExactly(0L, 1L);
    }

    @Test
    void reconcileOnlyUsesTheResultClientAndAppendsOnlyTheReturnedDurableFinal()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphReconcileResponse reconciliation = reconciliation(request);
        AtomicInteger executionCalls = new AtomicInteger();
        AtomicInteger reconciliationCalls = new AtomicInteger();
        AgentGraphCommandClient commandClient =
                (actualRequest, mode, eventSink, cancellationToken) -> {
                    executionCalls.incrementAndGet();
                    throw new AssertionError("RECONCILE_ONLY must not open an Agent Stream");
                };
        AgentGraphReconciliationClient reconciliationClient =
                (actualRequest, cancellationToken) -> {
                    reconciliationCalls.incrementAndGet();
                    assertThat(actualRequest).isEqualTo(request);
                    return reconciliation;
                };
        AgentStreamEvent storedFinal = event(
                request,
                4,
                StreamEventType.FINAL,
                reconciliation.resultHash());
        storedFinal = new AgentStreamEvent(
                storedFinal.schemaVersion(),
                storedFinal.runId(),
                storedFinal.attemptId(),
                storedFinal.sequenceNo(),
                storedFinal.eventType(),
                storedFinal.audience(),
                storedFinal.occurredAt(),
                new AgentStreamEvent.Payload(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        reconciliation.resultRef(),
                        reconciliation.resultHash(),
                        null,
                        null));
        AtomicReference<AgentRunReconciledFinalStore.Request> appendRequest =
                new AtomicReference<>();
        AgentStreamEvent finalEvent = storedFinal;
        List<Long> streamWrites = new ArrayList<>();
        AgentRunReconciledFinalStore finalStore = candidate -> {
            appendRequest.set(candidate);
            return new AgentRunReconciledFinalStore.Receipt(finalEvent, true, 4, true);
        };
        List<AgentRunProgress> progress = new ArrayList<>();
        var gateway = new DurableAgentRunExecutionGateway(
                commandClient,
                reconciliationClient,
                recordingStore(streamWrites),
                finalStore);

        var completion = gateway.execute(
                request,
                ExecutionMode.RECONCILE_ONLY,
                progress::add,
                new AgentRunCancellationToken());

        assertThat(executionCalls).hasValue(0);
        assertThat(reconciliationCalls).hasValue(1);
        assertThat(streamWrites).isEmpty();
        assertThat(appendRequest.get()).isEqualTo(new AgentRunReconciledFinalStore.Request(
                request.logicalRunId(),
                request.attemptId(),
                request.command().actorScope().audience(),
                reconciliation.resultRef(),
                reconciliation.resultHash()));
        assertThat(progress).containsExactly(new AgentRunProgress(4, true, true));
        assertThat(completion.graphResult()).isEqualTo(reconciliation.result());
        assertThat(completion.lastSequenceNo()).isEqualTo(4);
        assertThat(completion.publicOutputEmitted()).isTrue();
    }

    @Test
    void responseLossRetryReturnsTheSameStoredFinalWithoutPublishingProgressAgain()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphReconcileResponse reconciliation = reconciliation(request);
        AgentStreamEvent storedFinal = new AgentStreamEvent(
                "agent-stream.v2",
                request.logicalRunId(),
                request.attemptId(),
                2,
                StreamEventType.FINAL,
                request.command().actorScope().audience(),
                NOW,
                new AgentStreamEvent.Payload(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        reconciliation.resultRef(),
                        reconciliation.resultHash(),
                        null,
                        null));
        AtomicInteger appends = new AtomicInteger();
        AgentRunReconciledFinalStore finalStore = candidate ->
                new AgentRunReconciledFinalStore.Receipt(
                        storedFinal,
                        appends.getAndIncrement() == 0,
                        2,
                        false);
        AgentGraphCommandClient forbidden =
                (actualRequest, mode, sink, token) -> {
                    throw new AssertionError("stream client must not be called");
                };
        var gateway = new DurableAgentRunExecutionGateway(
                forbidden,
                (actualRequest, token) -> reconciliation,
                recordingStore(new ArrayList<>()),
                finalStore);
        List<AgentRunProgress> firstProgress = new ArrayList<>();
        List<AgentRunProgress> retryProgress = new ArrayList<>();

        var first = gateway.execute(
                request,
                ExecutionMode.RECONCILE_ONLY,
                firstProgress::add,
                new AgentRunCancellationToken());
        var retry = gateway.execute(
                request,
                ExecutionMode.RECONCILE_ONLY,
                retryProgress::add,
                new AgentRunCancellationToken());

        assertThat(first).isEqualTo(retry);
        assertThat(firstProgress).containsExactly(new AgentRunProgress(2, false, true));
        assertThat(retryProgress).isEmpty();
        assertThat(appends).hasValue(2);
        assertThat(storedFinal.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void mismatchedReconciliationNeverReachesEitherDurableStreamStore()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphReconcileResponse exact = reconciliation(request);
        GraphReconcileResponse mismatched = new GraphReconcileResponse(
                exact.schemaVersion(),
                exact.disposition(),
                exact.threadId(),
                exact.commandId(),
                "0".repeat(64),
                exact.logicalRunId(),
                exact.attemptId(),
                exact.graphKey(),
                exact.graphVersion(),
                exact.checkpointSchemaVersion(),
                exact.checkpointNs(),
                exact.checkpointId(),
                exact.resultRef(),
                exact.resultHash(),
                exact.registryBindingHash(),
                exact.toolPolicyVersion(),
                exact.result());
        List<Long> streamWrites = new ArrayList<>();
        AtomicInteger finalStoreCalls = new AtomicInteger();

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(
                                (actualRequest, mode, sink, token) -> {
                                    throw new AssertionError("stream client must not be called");
                                },
                                (actualRequest, token) -> mismatched,
                                recordingStore(streamWrites),
                                candidate -> {
                                    finalStoreCalls.incrementAndGet();
                                    throw new AssertionError("invalid result must not be persisted");
                                })
                        .execute(
                                request,
                                ExecutionMode.RECONCILE_ONLY,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_RECONCILIATION_RESULT_INVALID");
        assertThat(failure.retryable()).isFalse();
        assertThat(streamWrites).isEmpty();
        assertThat(finalStoreCalls).hasValue(0);
    }

    @Test
    void reconciledFinalConflictFailsClosedWhilePersistenceLossIsReplaySafe()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphReconcileResponse reconciliation = reconciliation(request);
        AgentGraphCommandClient forbidden =
                (actualRequest, mode, sink, token) -> {
                    throw new AssertionError("stream client must not be called");
                };

        AgentRunExecutionException conflict = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(
                                forbidden,
                                (actualRequest, token) -> reconciliation,
                                recordingStore(new ArrayList<>()),
                                candidate -> {
                                    throw new AgentRunReconciledFinalStore.ConflictException(
                                            "terminal already differs");
                                })
                        .execute(
                                request,
                                ExecutionMode.RECONCILE_ONLY,
                                ignored -> {},
                                new AgentRunCancellationToken()));
        assertThat(conflict.errorCode()).isEqualTo("AGENT_RUN_RECONCILED_FINAL_CONFLICT");
        assertThat(conflict.retryable()).isFalse();

        AgentRunExecutionException transientFailure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(
                                forbidden,
                                (actualRequest, token) -> reconciliation,
                                recordingStore(new ArrayList<>()),
                                candidate -> {
                                    throw new IllegalStateException("postgres unavailable");
                                })
                        .execute(
                                request,
                                ExecutionMode.RECONCILE_ONLY,
                                ignored -> {},
                                new AgentRunCancellationToken()));
        assertThat(transientFailure.errorCode())
                .isEqualTo("AGENT_RUN_RECONCILED_FINAL_APPEND_FAILED");
        assertThat(transientFailure.retryable()).isTrue();
        assertThat(transientFailure.commandReplaySafe()).isTrue();
        assertThat(transientFailure.recoveryAction().name())
                .isEqualTo("RECONCILE_TERMINAL");
    }

    @Test
    void reconciliationRecoveryActionsAreMappedWithoutOpeningAStreamOrWritingAFinal()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        AtomicInteger streamCalls = new AtomicInteger();
        AtomicInteger finalStoreCalls = new AtomicInteger();
        AgentGraphCommandClient forbidden =
                (actualRequest, mode, sink, token) -> {
                    streamCalls.incrementAndGet();
                    throw new AssertionError("reconciliation must not open a stream");
                };
        AgentRunReconciledFinalStore finalStore = candidate -> {
            finalStoreCalls.incrementAndGet();
            throw new AssertionError("a rejected reconciliation has no final to persist");
        };

        for (AgentRunRecoveryAction action : AgentRunRecoveryAction.values()) {
            boolean remoteRetryable = action == AgentRunRecoveryAction.RETRY_SAME_COMMAND;
            String remoteCode = action == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT
                    ? "GRAPH_NEW_AGENT_ATTEMPT_REQUIRED"
                    : "GRAPH_RECONCILIATION_" + action.name();
            GraphReconciliationException remoteFailure = new GraphReconciliationException(
                    remoteCode,
                    remoteRetryable ? 503 : 409,
                    remoteRetryable,
                    action,
                    "private remote detail",
                    null);
            var gateway = new DurableAgentRunExecutionGateway(
                    forbidden,
                    (actualRequest, token) -> {
                        throw remoteFailure;
                    },
                    recordingStore(new ArrayList<>()),
                    finalStore);

            AgentRunExecutionException mapped = catchThrowableOfType(
                    AgentRunExecutionException.class,
                    () -> gateway.execute(
                            request,
                            ExecutionMode.RECONCILE_ONLY,
                            ignored -> {},
                            new AgentRunCancellationToken()));

            switch (action) {
                case RETRY_SAME_COMMAND -> {
                    assertThat(mapped.errorCode()).isEqualTo(remoteCode);
                    assertThat(mapped.retryable()).isTrue();
                    assertThat(mapped.commandReplaySafe()).isTrue();
                    assertThat(mapped.recoveryAction().name())
                            .isEqualTo("RETRY_SAME_COMMAND");
                }
                case CREATE_NEXT_ATTEMPT -> {
                    assertThat(mapped.errorCode()).isEqualTo(remoteCode);
                    assertThat(mapped.retryable()).isTrue();
                    assertThat(mapped.commandReplaySafe()).isFalse();
                    assertThat(mapped.recoveryAction().name())
                            .isEqualTo("CREATE_NEXT_ATTEMPT");
                }
                case FAIL_LOGICAL_RUN -> {
                    assertThat(mapped.errorCode()).isEqualTo(remoteCode);
                    assertThat(mapped.retryable()).isFalse();
                    assertThat(mapped.commandReplaySafe()).isFalse();
                    assertThat(mapped.recoveryAction().name())
                            .isEqualTo("FAIL_LOGICAL_RUN");
                }
                case RECONCILE_TERMINAL -> {
                    assertThat(mapped.errorCode())
                            .isEqualTo("AGENT_RUN_RECONCILIATION_ACTION_INVALID");
                    assertThat(mapped.retryable()).isFalse();
                    assertThat(mapped.commandReplaySafe()).isFalse();
                    assertThat(mapped.recoveryAction().name())
                            .isEqualTo("FAIL_LOGICAL_RUN");
                }
            }
            assertThat(mapped.getCause()).isSameAs(remoteFailure);
            assertThat(mapped.getMessage()).doesNotContain("private remote detail");
        }

        assertThat(streamCalls).hasValue(0);
        assertThat(finalStoreCalls).hasValue(0);
    }

    @Test
    void reconciliationCancellationPropagatesWithoutOpeningAStreamOrWritingAFinal()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        ActivityCanceledException cancellation = new ActivityCanceledException();
        AtomicInteger streamCalls = new AtomicInteger();
        AtomicInteger finalStoreCalls = new AtomicInteger();
        var gateway = new DurableAgentRunExecutionGateway(
                (actualRequest, mode, sink, token) -> {
                    streamCalls.incrementAndGet();
                    throw new AssertionError("reconciliation must not open a stream");
                },
                (actualRequest, token) -> {
                    throw cancellation;
                },
                recordingStore(new ArrayList<>()),
                candidate -> {
                    finalStoreCalls.incrementAndGet();
                    throw new AssertionError("cancelled reconciliation has no final");
                });

        assertThatThrownBy(() -> gateway.execute(
                        request,
                        ExecutionMode.RECONCILE_ONLY,
                        ignored -> {},
                        new AgentRunCancellationToken()))
                .isSameAs(cancellation);
        assertThat(streamCalls).hasValue(0);
        assertThat(finalStoreCalls).hasValue(0);
    }

    @Test
    void reconciledFinalMustBeTheDurableHighWatermark() throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphReconcileResponse reconciliation = reconciliation(request);
        AgentStreamEvent storedFinal = reconciledFinal(request, reconciliation, 2, NOW);
        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(
                                (actualRequest, mode, sink, token) -> {
                                    throw new AssertionError("stream client must not be called");
                                },
                                (actualRequest, token) -> reconciliation,
                                recordingStore(new ArrayList<>()),
                                candidate -> new AgentRunReconciledFinalStore.Receipt(
                                        storedFinal, false, 3, false))
                        .execute(
                                request,
                                ExecutionMode.RECONCILE_ONLY,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_RECONCILED_FINAL_CONFLICT");
        assertThat(failure.retryable()).isFalse();

        AgentRunExecutionException missingReceipt = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(
                                (actualRequest, mode, sink, token) -> {
                                    throw new AssertionError("stream client must not be called");
                                },
                                (actualRequest, token) -> reconciliation,
                                recordingStore(new ArrayList<>()),
                                candidate -> null)
                        .execute(
                                request,
                                ExecutionMode.RECONCILE_ONLY,
                                ignored -> {},
                                new AgentRunCancellationToken()));
        assertThat(missingReceipt.errorCode())
                .isEqualTo("AGENT_RUN_RECONCILED_FINAL_CONFLICT");
        assertThat(missingReceipt.retryable()).isFalse();
    }

    @Test
    void reconcileOnlyFailsBeforeTheStreamClientWhenDependenciesAreMissing()
            throws Exception {
        AtomicInteger streamCalls = new AtomicInteger();
        RoomGraphResult result = graphResult();
        AgentGraphCommandClient commandClient =
                (request, mode, sink, token) -> {
                    streamCalls.incrementAndGet();
                    return result;
                };

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(
                                commandClient, recordingStore(new ArrayList<>()))
                        .execute(
                                request(),
                                ExecutionMode.RECONCILE_ONLY,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_RECONCILIATION_NOT_CONFIGURED");
        assertThat(streamCalls).hasValue(0);
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
        return batchStore(events -> {
            persisted.addAll(sequences(events));
            return receipt(events, true, events.getLast().sequenceNo());
        });
    }

    private static AgentRunV2StreamStore batchStore(BatchAppender appender) {
        return new AgentRunV2StreamStore() {
            @Override
            public AppendReceipt append(AgentStreamEvent event) {
                BatchAppendReceipt receipt = appendBatch(List.of(event));
                return new AppendReceipt(
                        receipt.inserted().getFirst(), receipt.durableHighWatermark());
            }

            @Override
            public BatchAppendReceipt appendBatch(List<AgentStreamEvent> events) {
                return appender.append(List.copyOf(events));
            }
        };
    }

    private static BatchAppendReceipt receipt(
            List<AgentStreamEvent> events, boolean inserted, long highWatermark) {
        return new BatchAppendReceipt(
                events.stream().map(ignored -> inserted).toList(), highWatermark);
    }

    private static List<Long> sequences(List<AgentStreamEvent> events) {
        return events.stream().map(AgentStreamEvent::sequenceNo).toList();
    }

    private static AgentStreamEvent event(
            ExecuteAgentRunRequest request,
            long sequenceNo,
            StreamEventType eventType,
            String finalHash) {
        return event(request, sequenceNo, eventType, finalHash, "public");
    }

    private static AgentStreamEvent event(
            ExecuteAgentRunRequest request,
            long sequenceNo,
            StreamEventType eventType,
            String finalHash,
            String delta) {
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
                        eventType == StreamEventType.VISIBLE_DELTA ? delta : null,
                        null,
                        null,
                        null,
                        eventType == StreamEventType.FINAL ? "urn:result:1" : null,
                        finalHash,
                        null,
                null));
    }

    private static AgentStreamEvent reconciledFinal(
            ExecuteAgentRunRequest request,
            GraphReconcileResponse reconciliation,
            long sequenceNo,
            Instant occurredAt) {
        return new AgentStreamEvent(
                "agent-stream.v2",
                request.logicalRunId(),
                request.attemptId(),
                sequenceNo,
                StreamEventType.FINAL,
                request.command().actorScope().audience(),
                occurredAt,
                new AgentStreamEvent.Payload(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        reconciliation.resultRef(),
                        reconciliation.resultHash(),
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

    private static GraphReconcileResponse reconciliation(ExecuteAgentRunRequest request)
            throws Exception {
        GraphReconcileResponse template = fixture(
                "graph-reconcile-response-valid.json",
                GraphReconcileResponse.class);
        return new GraphReconcileResponse(
                template.schemaVersion(),
                template.disposition(),
                request.command().threadId(),
                request.command().commandId(),
                request.command().requestHash(),
                request.logicalRunId(),
                request.attemptId(),
                request.command().graphKey(),
                request.command().graphVersion(),
                request.command().checkpointSchemaVersion(),
                template.checkpointNs(),
                template.checkpointId(),
                template.resultRef(),
                template.resultHash(),
                template.registryBindingHash(),
                template.toolPolicyVersion(),
                template.result());
    }

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }

    @FunctionalInterface
    private interface BatchAppender {
        BatchAppendReceipt append(List<AgentStreamEvent> events);
    }
}
