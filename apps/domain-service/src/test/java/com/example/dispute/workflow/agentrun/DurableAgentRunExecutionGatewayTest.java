package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.AppendReceipt;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.BatchAppendReceipt;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.NonRunningAttemptException;
import com.example.dispute.workflow.activity.agent.AgentGraphCommandClient;
import com.example.dispute.workflow.activity.agent.AgentGraphReconciliationClient;
import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.activity.agent.DurableAgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.GraphReconciliationException;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DurableAgentRunExecutionGatewayTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES =
            Path.of("..", "..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @Test
    void persistsEveryVisibleDeltaAndNotifiesProgressBeforeCommandReturns() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        List<String> order = new ArrayList<>();
        List<List<Long>> batches = new ArrayList<>();
        AtomicReference<ExecutionMode> observedMode = new AtomicReference<>();
        AtomicBoolean commandReturned = new AtomicBoolean();
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
            assertThat(commandReturned).isFalse();
            assertThat(batches).containsExactly(List.of(1L));
            assertThat(order).containsExactly("persist-[1]", "progress-1");
            eventSink.accept(
                    event(
                            request,
                            2,
                            StreamEventType.VISIBLE_DELTA,
                            null,
                            "b".repeat(600)));
            assertThat(commandReturned).isFalse();
            assertThat(batches).containsExactly(List.of(1L), List.of(2L));
            assertThat(order)
                    .containsExactly("persist-[1]", "progress-1", "persist-[2]", "progress-2");
            eventSink.accept(event(request, 3, StreamEventType.FINAL, result.outputHash()));
            assertThat(batches).containsExactly(List.of(1L), List.of(2L));
            commandReturned.set(true);
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
        assertThat(batches).containsExactly(List.of(1L), List.of(2L), List.of(3L));
        assertThat(order)
                .containsExactly(
                        "persist-[1]",
                        "progress-1",
                        "persist-[2]",
                        "progress-2",
                        "persist-[3]");
        assertThat(commandReturned).isTrue();
        assertThat(completion.graphResult()).isEqualTo(result);
        assertThat(completion.lastSequenceNo()).isEqualTo(3);
        assertThat(completion.publicOutputEmitted()).isTrue();
    }

    @Test
    void finalRemainsStagedUntilCommandResultValidatesAfterATrailingVisibleDelta() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        List<List<Long>> batches = new ArrayList<>();
        AtomicBoolean commandReturned = new AtomicBoolean();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(event(request, 1, StreamEventType.VISIBLE_DELTA, null));
            assertThat(batches).containsExactly(List.of(1L));
            eventSink.accept(event(request, 2, StreamEventType.FINAL, result.outputHash()));
            assertThat(commandReturned).isFalse();
            assertThat(batches).containsExactly(List.of(1L));
            commandReturned.set(true);
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

        assertThat(batches).containsExactly(List.of(1L), List.of(2L));
        assertThat(commandReturned).isTrue();
        assertThat(completion.lastSequenceNo()).isEqualTo(2);
    }

    @Test
    void advancesEveryDossierVisibleDeltaBeforeTheCommandClientReturns() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        List<List<Long>> batches = new ArrayList<>();
        List<Long> progress = new ArrayList<>();
        AtomicBoolean commandReturned = new AtomicBoolean();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));

            eventSink.accept(visibleEvent(request, 1, "room_utterance", "您好，我来协助核实争议。"));
            assertThat(commandReturned).isFalse();
            assertThat(batches).containsExactly(List.of(1L));
            assertThat(progress).containsExactly(1L);

            eventSink.accept(
                    visibleEvent(
                            request,
                            2,
                            "case_detail.case_story",
                            "订单延迟送达，用户主张补偿。"));
            assertThat(commandReturned).isFalse();
            assertThat(batches).containsExactly(List.of(1L), List.of(2L));
            assertThat(progress).containsExactly(1L, 2L);

            eventSink.accept(
                    visibleEvent(
                            request,
                            3,
                            "case_detail.references",
                            "已关联订单、物流与售后记录。"));
            assertThat(commandReturned).isFalse();
            assertThat(batches).containsExactly(List.of(1L), List.of(2L), List.of(3L));
            assertThat(progress).containsExactly(1L, 2L, 3L);

            eventSink.accept(event(request, 4, StreamEventType.FINAL, result.outputHash()));
            assertThat(batches).containsExactly(List.of(1L), List.of(2L), List.of(3L));
            commandReturned.set(true);
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
                        frame -> progress.add(frame.lastSequenceNo()),
                        new AgentRunCancellationToken());

        assertThat(commandReturned).isTrue();
        assertThat(batches)
                .containsExactly(List.of(1L), List.of(2L), List.of(3L), List.of(4L));
        assertThat(progress).containsExactly(1L, 2L, 3L);
        assertThat(completion.lastSequenceNo()).isEqualTo(4);
        assertThat(completion.publicOutputEmitted()).isTrue();
    }

    @Test
    void cancellationRacingASuccessfulFinalAppendCannotReverseCompletion() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        AtomicBoolean finalCommitted = new AtomicBoolean();
        ActivityCanceledException cancellation = new ActivityCanceledException();
        AgentRunCancellationToken token = mock(AgentRunCancellationToken.class);
        doAnswer(invocation -> {
                    if (finalCommitted.get()) {
                        throw cancellation;
                    }
                    return null;
                })
                .when(token)
                .throwIfCancellationRequested();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(event(request, 1, StreamEventType.FINAL, result.outputHash()));
            return result;
        };
        AgentRunV2StreamStore store = batchStore(events -> {
            assertThat(events.getLast().eventType()).isEqualTo(StreamEventType.FINAL);
            finalCommitted.set(true);
            return receipt(events, true, events.getLast().sequenceNo());
        });

        var completion = new DurableAgentRunExecutionGateway(client, store)
                .execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {
                            throw new AssertionError("durable final must not invoke progress");
                        },
                        token);

        assertThat(finalCommitted).isTrue();
        assertThat(completion.graphResult()).isEqualTo(result);
        assertThat(completion.lastSequenceNo()).isEqualTo(1);
    }

    @Test
    void consumesPythonHandshakeAndMapsOnlyCanonicalEventsAfterAJavaResetPrelude()
            throws Exception {
        ExecuteAgentRunRequest request = requestWithReset();
        RoomGraphResult result = graphResult();
        AgentStreamEvent handshake = event(request, 0, StreamEventType.ATTEMPT_STARTED, null);
        AgentStreamEvent delta = event(request, 1, StreamEventType.VISIBLE_DELTA, null);
        AgentStreamEvent finalCandidate =
                event(request, 2, StreamEventType.FINAL, result.outputHash());
        List<AgentStreamEvent> persisted = new ArrayList<>();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(handshake);
            eventSink.accept(delta);
            eventSink.accept(finalCandidate);
            return result;
        };
        AgentRunV2StreamStore store = batchStore(events -> {
            persisted.addAll(events);
            return receipt(events, true, events.getLast().sequenceNo());
        });
        List<Long> progress = new ArrayList<>();

        var completion = new DurableAgentRunExecutionGateway(client, store)
                .execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        frame -> progress.add(frame.lastSequenceNo()),
                        new AgentRunCancellationToken());

        assertThat(sequences(persisted)).containsExactly(2L, 3L);
        assertThat(persisted)
                .extracting(AgentStreamEvent::eventType)
                .containsExactly(StreamEventType.VISIBLE_DELTA, StreamEventType.FINAL);
        assertThat(persisted.getFirst().occurredAt()).isEqualTo(delta.occurredAt());
        assertThat(persisted.getFirst().payload()).isSameAs(delta.payload());
        assertThat(persisted.getLast().occurredAt()).isEqualTo(finalCandidate.occurredAt());
        assertThat(persisted.getLast().payload()).isSameAs(finalCandidate.payload());
        assertThat(handshake.sequenceNo()).isZero();
        assertThat(delta.sequenceNo()).isEqualTo(1);
        assertThat(finalCandidate.sequenceNo()).isEqualTo(2);
        assertThat(progress).containsExactly(2L);
        assertThat(completion.lastSequenceNo()).isEqualTo(3);
        assertThat(completion.publicOutputEmitted()).isTrue();
    }

    @Test
    void rejectsPythonAttemptResetAndMaterializesOnlyASanitizedTerminalError() throws Exception {
        ExecuteAgentRunRequest request = requestWithReset();
        List<AgentStreamEvent> persisted = new ArrayList<>();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(event(request, 1, StreamEventType.ATTEMPT_RESET, null));
            throw new AssertionError("attempt_reset must fail in the governed gateway");
        };
        AgentRunV2StreamStore store = batchStore(events -> {
            persisted.addAll(events);
            return receipt(events, true, events.getLast().sequenceNo());
        });

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(client, store)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(failure.errorCode())
                .isEqualTo("AGENT_RUN_STREAM_RESET_AUTHORITY_VIOLATION");
        assertThat(failure.retryable()).isFalse();
        assertThat(failure.commandReplaySafe()).isFalse();
        assertThat(failure.lastSequenceNo()).isEqualTo(2);
        assertThat(persisted)
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.sequenceNo()).isEqualTo(2);
                    assertThat(error.eventType()).isEqualTo(StreamEventType.ERROR);
                    assertThat(error.payload().errorCode())
                            .isEqualTo("AGENT_RUN_STREAM_RESET_AUTHORITY_VIOLATION");
                    assertThat(error.payload().retryable()).isFalse();
                    assertThat(error.payload().resetAttemptId()).isNull();
                });
    }

    @Test
    void materializesFirstAttemptLocalLogicalFailureAsDurableSanitizedError()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        AgentRunExecutionException original = AgentRunExecutionException.failLogicalRun(
                "PRODUCTION_RUNTIME_GRAPH_PROTOCOL_REJECTED",
                "private target protocol detail",
                0,
                false,
                null);
        List<AgentStreamEvent> persisted = new ArrayList<>();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            throw original;
        };
        AgentRunV2StreamStore store = batchStore(events -> {
            persisted.addAll(events);
            return receipt(events, true, events.getLast().sequenceNo());
        });

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(client, store)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(failure).isNotSameAs(original);
        assertThat(failure.getCause()).isSameAs(original);
        assertThat(failure.errorCode()).isEqualTo("PRODUCTION_RUNTIME_GRAPH_PROTOCOL_REJECTED");
        assertThat(failure.recoveryAction()).isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        assertThat(failure.lastSequenceNo()).isEqualTo(1);
        assertThat(failure.publicOutputEmitted()).isFalse();
        assertThat(persisted)
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.schemaVersion()).isEqualTo("agent-stream.v2");
                    assertThat(error.runId()).isEqualTo(request.agentRunId());
                    assertThat(error.attemptId()).isEqualTo(request.attemptId());
                    assertThat(error.sequenceNo()).isEqualTo(1);
                    assertThat(error.eventType()).isEqualTo(StreamEventType.ERROR);
                    assertThat(error.audience()).isEqualTo(request.command().actorScope().audience());
                    assertThat(error.occurredAt()).isNotNull();
                    assertThat(error.payload().node()).isNull();
                    assertThat(error.payload().field()).isNull();
                    assertThat(error.payload().delta()).isNull();
                    assertThat(error.payload().usage()).isNull();
                    assertThat(error.payload().reasonCode()).isNull();
                    assertThat(error.payload().resetAttemptId()).isNull();
                    assertThat(error.payload().finalResultRef()).isNull();
                    assertThat(error.payload().finalResultHash()).isNull();
                    assertThat(error.payload().errorCode())
                            .isEqualTo("PRODUCTION_RUNTIME_GRAPH_PROTOCOL_REJECTED");
                    assertThat(error.payload().retryable()).isFalse();
                });
    }

    @Test
    void doesNotDuplicateAValidRemoteErrorTerminal() throws Exception {
        ExecuteAgentRunRequest request = request();
        AgentStreamEvent remoteError = errorEvent(request, 1, "PRODUCTION_RUNTIME_GRAPH_PROTOCOL_REJECTED");
        AgentRunExecutionException original = AgentRunExecutionException.failLogicalRun(
                "PRODUCTION_RUNTIME_GRAPH_PROTOCOL_REJECTED",
                "target Graph returned a terminal error",
                1,
                false,
                null);
        List<AgentStreamEvent> persisted = new ArrayList<>();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(remoteError);
            throw original;
        };
        AgentRunV2StreamStore store = batchStore(events -> {
            persisted.addAll(events);
            return receipt(events, true, events.getLast().sequenceNo());
        });

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(client, store)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(failure).isSameAs(original);
        assertThat(persisted).containsExactly(remoteError);
    }

    @Test
    void reconcilesAnObservedFinalWhenTheCommandRaisesALogicalFailure() throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphReconcileResponse reconciliation = reconciliation(request);
        AgentStreamEvent observedFinal = reconciledFinal(request, reconciliation, 1, NOW);
        AgentRunExecutionException original = AgentRunExecutionException.failLogicalRun(
                "PRODUCTION_RUNTIME_GRAPH_PROTOCOL_REJECTED",
                "the result was unavailable after its final frame",
                0,
                false,
                null);
        AtomicInteger reconciliationCalls = new AtomicInteger();
        AtomicInteger finalStoreCalls = new AtomicInteger();
        List<Long> streamWrites = new ArrayList<>();
        var gateway = new DurableAgentRunExecutionGateway(
                (actualRequest, mode, eventSink, cancellationToken) -> {
                    eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
                    eventSink.accept(observedFinal);
                    throw original;
                },
                (actualRequest, cancellationToken) -> {
                    reconciliationCalls.incrementAndGet();
                    return reconciliation;
                },
                recordingStore(streamWrites),
                candidate -> {
                    finalStoreCalls.incrementAndGet();
                    return new AgentRunReconciledFinalStore.Receipt(observedFinal, true, 1, false);
                });

        var completion = gateway.execute(
                request,
                ExecutionMode.EXECUTE_OR_RECONCILE,
                ignored -> {},
                new AgentRunCancellationToken());

        assertThat(reconciliationCalls).hasValue(1);
        assertThat(finalStoreCalls).hasValue(1);
        assertThat(streamWrites).isEmpty();
        assertThat(completion.graphResult()).isEqualTo(reconciliation.result());
        assertThat(completion.lastSequenceNo()).isEqualTo(1);
    }

    @Test
    void exposesAnExplicitReconciliationFailureForLogicalFailureAfterObservedFinal()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphReconcileResponse reconciliation = reconciliation(request);
        AgentStreamEvent observedFinal = reconciledFinal(request, reconciliation, 1, NOW);
        AgentRunExecutionException original = AgentRunExecutionException.failLogicalRun(
                "PRODUCTION_RUNTIME_GRAPH_PROTOCOL_REJECTED",
                "the result was unavailable after its final frame",
                0,
                false,
                null);
        List<Long> streamWrites = new ArrayList<>();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(observedFinal);
            throw original;
        };

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(client, recordingStore(streamWrites))
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_RECONCILIATION_NOT_CONFIGURED");
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RECONCILE_TERMINAL);
        assertThat(failure.lastSequenceNo()).isZero();
        assertThat(failure.getCause()).isSameAs(original);
        assertThat(streamWrites).isEmpty();
    }

    @Test
    void doesNotMaterializeGlobalErrorForNonTerminalRecoveryActions() throws Exception {
        ExecuteAgentRunRequest request = request();
        for (AgentRunExecutionException original : List.of(
                AgentRunExecutionException.retrySameCommand(
                        "PRODUCTION_RUNTIME_GRAPH_RETRY",
                        "retry the sealed command",
                        0,
                        false,
                        null),
                AgentRunExecutionException.createNextAttempt(
                        "PRODUCTION_RUNTIME_GRAPH_ABORTED",
                        "the remote terminal authorizes a successor attempt",
                        0,
                        false,
                        null),
                AgentRunExecutionException.reconcileTerminal(
                        "PRODUCTION_RUNTIME_GRAPH_RECONCILE",
                        "the remote terminal requires reconciliation",
                        0,
                        false,
                        null))) {
            AtomicInteger appendCalls = new AtomicInteger();
            AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
                eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
                throw original;
            };
            AgentRunV2StreamStore store = batchStore(events -> {
                appendCalls.incrementAndGet();
                throw new AssertionError("non-terminal recovery actions must not append global errors");
            });

            AgentRunExecutionException failure = catchThrowableOfType(
                    AgentRunExecutionException.class,
                    () -> new DurableAgentRunExecutionGateway(client, store)
                            .execute(
                                    request,
                                    ExecutionMode.EXECUTE_OR_RECONCILE,
                                    ignored -> {},
                                    new AgentRunCancellationToken()));

            assertThat(failure).isSameAs(original);
            assertThat(appendCalls).hasValue(0);
        }
    }

    @Test
    void preservesDurableAppendFailureWhenSyntheticErrorWasNotCommitted() throws Exception {
        ExecuteAgentRunRequest request = request();
        AgentRunExecutionException original = AgentRunExecutionException.failLogicalRun(
                "PRODUCTION_RUNTIME_GRAPH_PROTOCOL_REJECTED",
                "private target protocol detail",
                0,
                false,
                null);
        List<AgentStreamEvent> attempted = new ArrayList<>();
        IllegalStateException appendFailure = new IllegalStateException("postgres unavailable");
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            throw original;
        };
        AgentRunV2StreamStore store = batchStore(events -> {
            attempted.addAll(events);
            throw appendFailure;
        });

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(client, store)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(attempted)
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.eventType()).isEqualTo(StreamEventType.ERROR);
                    assertThat(error.sequenceNo()).isEqualTo(1);
                });
        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_DURABLE_APPEND_FAILED");
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RETRY_SAME_COMMAND);
        assertThat(failure.lastSequenceNo()).isZero();
        assertThat(failure.publicOutputEmitted()).isFalse();
        assertThat(failure.getCause()).isSameAs(appendFailure);
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

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> gateway.execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {},
                        new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_RECONCILIATION_NOT_CONFIGURED");
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RECONCILE_TERMINAL);
        assertThat(failure.getCause())
                .isInstanceOf(AgentRunExecutionException.class)
                .extracting(cause -> ((AgentRunExecutionException) cause).errorCode())
                .isEqualTo("AGENT_RUN_STREAM_V2_INVALID");
        assertThat(persisted).isEmpty();
    }

    @Test
    void rejectsFinalHashThatDoesNotMatchGraphResult() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();
        List<Long> persisted = new ArrayList<>();
        var gateway = gatewayReturning(
                request, result, "0".repeat(64), recordingStore(persisted));

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> gateway.execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {},
                        new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_RECONCILIATION_NOT_CONFIGURED");
        assertThat(failure.getCause())
                .isInstanceOf(AgentRunExecutionException.class)
                .hasMessageContaining("does not match the final stream");
        assertThat(persisted).isEmpty();
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

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> gateway.execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {},
                        new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_RECONCILIATION_NOT_CONFIGURED");
        assertThat(failure.getCause())
                .isInstanceOf(AgentRunExecutionException.class)
                .hasMessageContaining("does not match the final stream");
        assertThat(persisted).isEmpty();
    }

    @Test
    void reconcilesObservedFinalWhenReturnedResultFailsValidation() throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphReconcileResponse reconciliation = reconciliation(request);
        AgentStreamEvent observedFinal = reconciledFinal(request, reconciliation, 1, NOW);
        JsonNode wrapper = MAPPER.readTree(
                FIXTURES.resolve("room-graph-result-valid.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        wrapper.required("instance").required("execution_metadata"))
                .put("model_profile_id", "unauthorized-model.v1");
        RoomGraphResult mismatchedResult =
                MAPPER.treeToValue(wrapper.required("instance"), RoomGraphResult.class);
        AtomicInteger reconciliationCalls = new AtomicInteger();
        AtomicInteger finalStoreCalls = new AtomicInteger();
        List<Long> streamWrites = new ArrayList<>();
        var gateway = new DurableAgentRunExecutionGateway(
                (actualRequest, mode, eventSink, cancellationToken) -> {
                    eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
                    eventSink.accept(observedFinal);
                    return mismatchedResult;
                },
                (actualRequest, cancellationToken) -> {
                    reconciliationCalls.incrementAndGet();
                    return reconciliation;
                },
                recordingStore(streamWrites),
                candidate -> {
                    finalStoreCalls.incrementAndGet();
                    return new AgentRunReconciledFinalStore.Receipt(
                            observedFinal, true, observedFinal.sequenceNo(), false);
                });

        var completion = gateway.execute(
                request,
                ExecutionMode.EXECUTE_OR_RECONCILE,
                ignored -> {},
                new AgentRunCancellationToken());

        assertThat(reconciliationCalls).hasValue(1);
        assertThat(finalStoreCalls).hasValue(1);
        assertThat(streamWrites).isEmpty();
        assertThat(completion.graphResult()).isEqualTo(reconciliation.result());
        assertThat(completion.lastSequenceNo()).isEqualTo(observedFinal.sequenceNo());
    }

    @Test
    void returnsRetryableAppendFailureWhenAVisibleDeltaCannotBeDurablyPersistedBeforeFinal()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        JsonNode wrapper = MAPPER.readTree(
                FIXTURES.resolve("room-graph-result-valid.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        wrapper.required("instance").required("execution_metadata"))
                .put("model_profile_id", "unauthorized-model.v1");
        RoomGraphResult mismatchedResult =
                MAPPER.treeToValue(wrapper.required("instance"), RoomGraphResult.class);
        List<AgentStreamEvent> attempted = new ArrayList<>();
        IllegalStateException appendFailure = new IllegalStateException("postgres unavailable");
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(event(request, 1, StreamEventType.VISIBLE_DELTA, null));
            eventSink.accept(event(request, 2, StreamEventType.FINAL, mismatchedResult.outputHash()));
            return mismatchedResult;
        };
        AgentRunV2StreamStore store = batchStore(events -> {
            attempted.addAll(events);
            throw appendFailure;
        });

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(client, store)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(attempted)
                .singleElement()
                .satisfies(delta -> {
                    assertThat(delta.sequenceNo()).isEqualTo(1);
                    assertThat(delta.eventType()).isEqualTo(StreamEventType.VISIBLE_DELTA);
                });
        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_DURABLE_APPEND_FAILED");
        assertThat(failure.recoveryAction()).isEqualTo(AgentRunRecoveryAction.RETRY_SAME_COMMAND);
        assertThat(failure.lastSequenceNo()).isZero();
        assertThat(failure.getCause()).isSameAs(appendFailure);
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
        assertThat(failure.lastSequenceNo()).isEqualTo(2);
        assertThat(persisted).containsExactly(1L, 2L);
    }

    @Test
    void rejectsACandidateSequenceGapBeforeDurableStorage() throws Exception {
        ExecuteAgentRunRequest request = request();
        List<AgentStreamEvent> persisted = new ArrayList<>();
        AgentGraphCommandClient client = (actualRequest, mode, eventSink, cancellationToken) -> {
            eventSink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
            eventSink.accept(event(request, 2, StreamEventType.VISIBLE_DELTA, null));
            throw new AssertionError("the sequence gap must fail in the governed gateway");
        };
        AgentRunV2StreamStore store = batchStore(events -> {
            persisted.addAll(events);
            return receipt(events, true, events.getLast().sequenceNo());
        });

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> new DurableAgentRunExecutionGateway(client, store)
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_STREAM_V2_INVALID");
        assertThat(failure.retryable()).isFalse();
        assertThat(failure.lastSequenceNo()).isEqualTo(1);
        assertThat(persisted)
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.sequenceNo()).isEqualTo(1);
                    assertThat(error.eventType()).isEqualTo(StreamEventType.ERROR);
                });
    }

    @Test
    void suppressesProgressForExactDuplicateBatchReplay() throws Exception {
        ExecuteAgentRunRequest request = requestWithReset();
        RoomGraphResult result = graphResult();
        var gateway = gatewayReturning(
                request,
                result,
                result.outputHash(),
                batchStore(events -> receipt(events, false, 2)));
        List<Long> progress = new ArrayList<>();

        var completion = gateway.execute(
                request,
                ExecutionMode.EXECUTE_OR_RECONCILE,
                frame -> progress.add(frame.lastSequenceNo()),
                new AgentRunCancellationToken());

        assertThat(progress).isEmpty();
        assertThat(completion.lastSequenceNo()).isEqualTo(2);
        assertThat(completion.graphResult()).isEqualTo(result);
    }

    @Test
    void rejectsALatePythonFinalFromEverySupersededAttemptStatus() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();

        for (AgentRunAttemptStatus status : List.of(
                AgentRunAttemptStatus.FAILED,
                AgentRunAttemptStatus.ABORTED,
                AgentRunAttemptStatus.CANCELLED)) {
            NonRunningAttemptException nonRunning = new NonRunningAttemptException(status);
            AgentRunExecutionException failure = catchThrowableOfType(
                    AgentRunExecutionException.class,
                    () -> gatewayReturning(
                                    request,
                                    result,
                                    result.outputHash(),
                                    batchStore(events -> {
                                        assertThat(events.getLast().eventType())
                                                .isEqualTo(StreamEventType.FINAL);
                                        throw nonRunning;
                                    }))
                            .execute(
                                    request,
                                    ExecutionMode.EXECUTE_OR_RECONCILE,
                                    ignored -> {},
                                    new AgentRunCancellationToken()));

            assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_STALE_ATTEMPT_FINAL");
            assertThat(failure.retryable()).isFalse();
            assertThat(failure.commandReplaySafe()).isFalse();
            assertThat(failure.recoveryAction()).isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
            assertThat(failure.lastSequenceNo()).isZero();
            assertThat(failure.publicOutputEmitted()).isFalse();
            assertThat(failure.getCause()).isSameAs(nonRunning);
        }
    }

    @Test
    void doesNotMisclassifyANonSupersededAppendFailureAsAStaleFinal() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult result = graphResult();

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> gatewayReturning(
                                request,
                                result,
                                result.outputHash(),
                                batchStore(events -> {
                                    throw new NonRunningAttemptException(
                                            AgentRunAttemptStatus.PENDING);
                                }))
                        .execute(
                                request,
                                ExecutionMode.EXECUTE_OR_RECONCILE,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_DURABLE_APPEND_FAILED");
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RECONCILE_TERMINAL);
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
        assertThat(persisted).isEmpty();
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
        assertThat(progress).isEmpty();
    }

    @Test
    void visibleDeltaAppendFailureDoesNotPublishTheUncommittedDelta() throws Exception {
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
            if (invocation.incrementAndGet() == 1) {
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
        assertThat(failure.recoveryAction().name()).isEqualTo("RETRY_SAME_COMMAND");
        assertThat(failure.lastSequenceNo()).isZero();
        assertThat(failure.publicOutputEmitted()).isFalse();
        assertThat(progress).isEmpty();
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

        assertThat(batches).containsExactly(List.of(1L));
        assertThat(progress).containsExactly(1L);
    }

    @Test
    void terminalReplayFailureReconcilesInlineWithoutASecondCommandExecution()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphReconcileResponse reconciliation = reconciliation(request);
        AtomicInteger commandCalls = new AtomicInteger();
        AtomicInteger reconciliationCalls = new AtomicInteger();
        AtomicInteger finalStoreCalls = new AtomicInteger();
        List<Long> streamWrites = new ArrayList<>();
        AgentRunExecutionException replayFailure = AgentRunExecutionException.reconcileTerminal(
                "GRAPH_TERMINAL_REPLAY_REQUIRED",
                "the terminal command cannot replay its execution stream",
                request.publicSequenceOffset(),
                false,
                null);
        AgentStreamEvent storedFinal = reconciledFinal(request, reconciliation, 1, NOW);
        var gateway = new DurableAgentRunExecutionGateway(
                (actualRequest, mode, sink, token) -> {
                    commandCalls.incrementAndGet();
                    throw replayFailure;
                },
                (actualRequest, token) -> {
                    reconciliationCalls.incrementAndGet();
                    assertThat(actualRequest).isEqualTo(request);
                    return reconciliation;
                },
                recordingStore(streamWrites),
                candidate -> {
                    finalStoreCalls.incrementAndGet();
                    assertThat(candidate.resultRef()).isEqualTo(reconciliation.resultRef());
                    assertThat(candidate.resultHash()).isEqualTo(reconciliation.resultHash());
                    return new AgentRunReconciledFinalStore.Receipt(
                            storedFinal, true, storedFinal.sequenceNo(), false);
                });

        var completion = gateway.execute(
                request,
                ExecutionMode.EXECUTE_OR_RECONCILE,
                ignored -> {
                    throw new AssertionError("terminal reconciliation must not publish progress");
                },
                new AgentRunCancellationToken());

        assertThat(commandCalls).hasValue(1);
        assertThat(reconciliationCalls).hasValue(1);
        assertThat(finalStoreCalls).hasValue(1);
        assertThat(streamWrites).isEmpty();
        assertThat(completion.graphResult()).isEqualTo(reconciliation.result());
        assertThat(completion.lastSequenceNo()).isEqualTo(1);
        assertThat(completion.publicOutputEmitted()).isFalse();
    }

    @Test
    void pendingObservedFinalMustMatchTheReconciledReferenceAndHash() throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphReconcileResponse reconciliation = reconciliation(request);
        AgentStreamEvent exact = reconciledFinal(request, reconciliation, 1, NOW.plusSeconds(1));
        List<AgentStreamEvent> mismatches = List.of(
                withFinalBinding(exact, "urn:result:different", reconciliation.resultHash()),
                withFinalBinding(exact, reconciliation.resultRef(), "0".repeat(64)));
        AtomicInteger finalStoreCalls = new AtomicInteger();

        for (AgentStreamEvent mismatch : mismatches) {
            List<Long> streamWrites = new ArrayList<>();
            AgentGraphCommandClient commandClient = (actualRequest, mode, sink, token) -> {
                sink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
                sink.accept(mismatch);
                throw AgentRunExecutionException.reconcileTerminal(
                        "GRAPH_TERMINAL_REPLAY_REQUIRED",
                        "the terminal command cannot replay its execution stream",
                        request.publicSequenceOffset(),
                        false,
                        null);
            };
            var gateway = new DurableAgentRunExecutionGateway(
                    commandClient,
                    (actualRequest, token) -> reconciliation,
                    recordingStore(streamWrites),
                    candidate -> {
                        finalStoreCalls.incrementAndGet();
                        throw new AssertionError("a mismatched observed final must not be stored");
                    });

            AgentRunExecutionException failure = catchThrowableOfType(
                    AgentRunExecutionException.class,
                    () -> gateway.execute(
                            request,
                            ExecutionMode.EXECUTE_OR_RECONCILE,
                            ignored -> {},
                            new AgentRunCancellationToken()));

            assertThat(failure.errorCode())
                    .isEqualTo("AGENT_RUN_RECONCILIATION_RESULT_INVALID");
            assertThat(failure.recoveryAction())
                    .isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
            assertThat(failure.getMessage()).contains("differs from the observed final");
            assertThat(streamWrites).isEmpty();
        }
        assertThat(finalStoreCalls).hasValue(0);
    }

    @Test
    void inlineReconciliationFailurePreservesTerminalRecoveryAndDurableProgress()
            throws Exception {
        ExecuteAgentRunRequest request = requestWithReset();
        AtomicInteger commandCalls = new AtomicInteger();
        AtomicInteger reconciliationCalls = new AtomicInteger();
        AtomicInteger finalStoreCalls = new AtomicInteger();
        List<Long> streamWrites = new ArrayList<>();
        List<AgentRunProgress> progress = new ArrayList<>();
        GraphReconciliationException reconciliationFailure =
                GraphReconciliationException.transport(
                        new IllegalStateException("reconciliation transport unavailable"));
        var gateway = new DurableAgentRunExecutionGateway(
                (actualRequest, mode, sink, token) -> {
                    commandCalls.incrementAndGet();
                    sink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
                    sink.accept(event(request, 1, StreamEventType.VISIBLE_DELTA, null));
                    throw AgentRunExecutionException.reconcileTerminal(
                            "GRAPH_TERMINAL_REPLAY_REQUIRED",
                            "the remote command is already terminal",
                            request.publicSequenceOffset(),
                            false,
                            null);
                },
                (actualRequest, token) -> {
                    reconciliationCalls.incrementAndGet();
                    throw reconciliationFailure;
                },
                recordingStore(streamWrites),
                candidate -> {
                    finalStoreCalls.incrementAndGet();
                    throw new AssertionError("failed reconciliation has no final to store");
                });

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> gateway.execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        progress::add,
                        new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("GRAPH_RECONCILIATION_TRANSPORT_FAILED");
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.RECONCILE_TERMINAL);
        assertThat(failure.lastSequenceNo()).isEqualTo(2);
        assertThat(failure.publicOutputEmitted()).isTrue();
        assertThat(failure.getCause()).isSameAs(reconciliationFailure);
        assertThat(commandCalls).hasValue(1);
        assertThat(reconciliationCalls).hasValue(1);
        assertThat(finalStoreCalls).hasValue(0);
        assertThat(streamWrites).containsExactly(2L);
        assertThat(progress)
                .extracting(AgentRunProgress::lastSequenceNo)
                .containsExactly(2L);
    }

    @Test
    void observedFinalCannotBeReplacedByAReconciliationDirectedNewAttempt()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        GraphReconcileResponse reconciliation = reconciliation(request);
        AgentStreamEvent exact = reconciledFinal(request, reconciliation, 1, NOW);
        AtomicInteger finalStoreCalls = new AtomicInteger();
        GraphReconciliationException conflict = new GraphReconciliationException(
                "GRAPH_NEW_AGENT_ATTEMPT_REQUIRED",
                409,
                false,
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                "the reconciliation endpoint did not observe the final",
                null);
        var gateway = new DurableAgentRunExecutionGateway(
                (actualRequest, mode, sink, token) -> {
                    sink.accept(event(request, 0, StreamEventType.ATTEMPT_STARTED, null));
                    sink.accept(exact);
                    throw AgentRunExecutionException.reconcileTerminal(
                            "GRAPH_TERMINAL_REPLAY_REQUIRED",
                            "the execution endpoint observed a final",
                            request.publicSequenceOffset(),
                            false,
                            null);
                },
                (actualRequest, token) -> {
                    throw conflict;
                },
                recordingStore(new ArrayList<>()),
                candidate -> {
                    finalStoreCalls.incrementAndGet();
                    throw new AssertionError("a conflicting terminal must not be stored");
                });

        AgentRunExecutionException failure = catchThrowableOfType(
                AgentRunExecutionException.class,
                () -> gateway.execute(
                        request,
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ignored -> {},
                        new AgentRunCancellationToken()));

        assertThat(failure.errorCode())
                .isEqualTo("AGENT_RUN_OBSERVED_FINAL_RECONCILIATION_CONFLICT");
        assertThat(failure.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        assertThat(failure.getCause()).isSameAs(conflict);
        assertThat(finalStoreCalls).hasValue(0);
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
        assertThat(progress).isEmpty();
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
        assertThat(firstProgress).isEmpty();
        assertThat(retryProgress).isEmpty();
        assertThat(appends).hasValue(2);
        assertThat(storedFinal.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void mismatchedReconciliationNeverReachesEitherDurableStreamStore()
            throws Exception {
        ExecuteAgentRunRequest request = requestWithReset();
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
        assertThat(failure.lastSequenceNo()).isEqualTo(1);
        assertThat(streamWrites).isEmpty();
        assertThat(finalStoreCalls).hasValue(0);
    }

    @Test
    void reconciledFinalConflictFailsClosedWhilePersistenceLossIsReplaySafe()
            throws Exception {
        ExecuteAgentRunRequest request = requestWithReset();
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
        assertThat(conflict.lastSequenceNo()).isEqualTo(1);

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
        assertThat(transientFailure.lastSequenceNo()).isEqualTo(1);
    }

    @Test
    void rejectsAReconciledFinalFromEverySupersededAttemptStatus() throws Exception {
        ExecuteAgentRunRequest request = requestWithReset();
        GraphReconcileResponse reconciliation = reconciliation(request);
        AgentGraphCommandClient forbidden = (actualRequest, mode, sink, token) -> {
            throw new AssertionError("stream client must not be called");
        };

        for (AgentRunAttemptStatus status : List.of(
                AgentRunAttemptStatus.FAILED,
                AgentRunAttemptStatus.ABORTED,
                AgentRunAttemptStatus.CANCELLED)) {
            NonRunningAttemptException nonRunning = new NonRunningAttemptException(status);
            AgentRunExecutionException failure = catchThrowableOfType(
                    AgentRunExecutionException.class,
                    () -> new DurableAgentRunExecutionGateway(
                                    forbidden,
                                    (actualRequest, token) -> reconciliation,
                                    recordingStore(new ArrayList<>()),
                                    candidate -> {
                                        throw nonRunning;
                                    })
                            .execute(
                                    request,
                                    ExecutionMode.RECONCILE_ONLY,
                                    ignored -> {},
                                    new AgentRunCancellationToken()));

            assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_STALE_ATTEMPT_FINAL");
            assertThat(failure.retryable()).isFalse();
            assertThat(failure.commandReplaySafe()).isFalse();
            assertThat(failure.recoveryAction()).isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
            assertThat(failure.lastSequenceNo()).isEqualTo(1);
            assertThat(failure.publicOutputEmitted()).isFalse();
            assertThat(failure.getCause()).isSameAs(nonRunning);
        }
    }

    @Test
    void reconciliationRecoveryActionsAreMappedWithoutOpeningAStreamOrWritingAFinal()
            throws Exception {
        ExecuteAgentRunRequest request = requestWithReset();
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
            assertThat(mapped.lastSequenceNo()).isEqualTo(1);
        }

        assertThat(streamCalls).hasValue(0);
        assertThat(finalStoreCalls).hasValue(0);
    }

    @Test
    void reconciliationCancellationPropagatesWithoutOpeningAStreamOrWritingAFinal()
            throws Exception {
        ExecuteAgentRunRequest request = requestWithReset();
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
        ExecuteAgentRunRequest request = requestWithReset();
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
        assertThat(missingReceipt.lastSequenceNo()).isEqualTo(1);
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
                                requestWithReset(),
                                ExecutionMode.RECONCILE_ONLY,
                                ignored -> {},
                                new AgentRunCancellationToken()));

        assertThat(failure.errorCode()).isEqualTo("AGENT_RUN_RECONCILIATION_NOT_CONFIGURED");
        assertThat(failure.lastSequenceNo()).isEqualTo(1);
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

    private static AgentStreamEvent visibleEvent(
            ExecuteAgentRunRequest request, long sequenceNo, String field, String delta) {
        return new AgentStreamEvent(
                "agent-stream.v2",
                request.agentRunId(),
                request.attemptId(),
                sequenceNo,
                StreamEventType.VISIBLE_DELTA,
                request.command().actorScope().audience(),
                NOW.plusSeconds(sequenceNo),
                new AgentStreamEvent.Payload(
                        "intake_lcel",
                        field,
                        delta,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    private static AgentStreamEvent errorEvent(
            ExecuteAgentRunRequest request, long sequenceNo, String errorCode) {
        return new AgentStreamEvent(
                "agent-stream.v2",
                request.agentRunId(),
                request.attemptId(),
                sequenceNo,
                StreamEventType.ERROR,
                request.command().actorScope().audience(),
                NOW.plusSeconds(sequenceNo),
                new AgentStreamEvent.Payload(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        errorCode,
                        false));
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

    private static AgentStreamEvent withFinalBinding(
            AgentStreamEvent event, String resultRef, String resultHash) {
        return new AgentStreamEvent(
                event.schemaVersion(),
                event.runId(),
                event.attemptId(),
                event.sequenceNo(),
                event.eventType(),
                event.audience(),
                event.occurredAt(),
                new AgentStreamEvent.Payload(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        resultRef,
                        resultHash,
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
                "b".repeat(64),
                null,
                false,
                0,
                command);
    }

    private static ExecuteAgentRunRequest requestWithReset() throws Exception {
        RoomGraphCommand command = fixture("room-graph-command-valid.json", RoomGraphCommand.class);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                command.logicalRunId(),
                2,
                "agent-stream.v2",
                "b".repeat(64),
                "ATTEMPT_PREVIOUS_1",
                true,
                1,
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
