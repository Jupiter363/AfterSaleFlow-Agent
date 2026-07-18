package com.example.dispute.workflow.activity.agent;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import java.util.Objects;

/** Persists every public event before exposing its progress to the Temporal Activity. */
public final class DurableAgentRunExecutionGateway implements AgentRunExecutionGateway {

    private final AgentGraphCommandClient commandClient;
    private final AgentRunV2StreamStore streamStore;

    public DurableAgentRunExecutionGateway(
            AgentGraphCommandClient commandClient,
            AgentRunV2StreamStore streamStore) {
        this.commandClient = Objects.requireNonNull(commandClient, "commandClient");
        this.streamStore = Objects.requireNonNull(streamStore, "streamStore");
    }

    @Override
    public Completion execute(
            ExecuteAgentRunRequest request,
            ExecutionMode executionMode,
            ProgressListener progressListener,
            AgentRunCancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(executionMode, "executionMode");
        Objects.requireNonNull(progressListener, "progressListener");
        Objects.requireNonNull(cancellationToken, "cancellationToken");

        ProgressState state = new ProgressState(request);
        RoomGraphResult result;
        try {
            result = commandClient.execute(
                    request,
                    executionMode,
                    event -> {
                        cancellationToken.throwIfCancellationRequested();
                        state.validateCandidate(event);
                        if (event.eventType() == StreamEventType.FINAL) {
                            state.stageFinal(event);
                            return;
                        }
                        appendAndPublish(
                                state,
                                event,
                                false,
                                progressListener,
                                cancellationToken);
                    },
                    cancellationToken);
        } catch (RuntimeException failure) {
            cancellationToken.throwIfCancellationRequested();
            if (state.hasPendingFinal()
                    && !(failure instanceof AgentRunExecutionException)) {
                throw AgentRunExecutionException.retryable(
                        "AGENT_RUN_RESULT_AFTER_FINAL_UNAVAILABLE",
                        "graph result was unavailable after its final frame",
                        true,
                        state.durableSequence(),
                        state.publicOutputEmitted,
                        failure);
            }
            throw failure;
        }
        cancellationToken.throwIfCancellationRequested();
        AgentStreamEvent finalEvent = state.validatedFinal(result);
        appendAndPublish(
                state,
                finalEvent,
                true,
                progressListener,
                cancellationToken);
        return new Completion(result, state.lastSequence, state.publicOutputEmitted);
    }

    private void appendAndPublish(
            ProgressState state,
            AgentStreamEvent event,
            boolean commandReplaySafe,
            ProgressListener progressListener,
            AgentRunCancellationToken cancellationToken) {
        AgentRunV2StreamStore.AppendReceipt receipt;
        try {
            receipt = Objects.requireNonNull(
                    streamStore.append(event), "durable append receipt");
        } catch (RuntimeException failure) {
            throw AgentRunExecutionException.retryable(
                    "AGENT_RUN_DURABLE_APPEND_FAILED",
                    "durable stream append failed",
                    commandReplaySafe,
                    state.durableSequence(),
                    state.publicOutputEmitted,
                    failure);
        }
        if (receipt.durableHighWatermark() < event.sequenceNo()) {
            throw AgentRunExecutionException.retryable(
                    "AGENT_RUN_DURABLE_APPEND_LAGGED",
                    "durable stream high-watermark did not reach the event",
                    commandReplaySafe,
                    state.durableSequence(),
                    state.publicOutputEmitted,
                    null);
        }
        state.commit(event);
        if (!receipt.inserted()) {
            return;
        }
        cancellationToken.throwIfCancellationRequested();
        progressListener.onProgress(
                new AgentRunProgress(
                        state.lastSequence,
                        state.publicOutputEmitted,
                        state.finalObserved));
    }

    private static final class ProgressState {
        private final ExecuteAgentRunRequest request;
        private long lastSequence = -1;
        private boolean started;
        private boolean terminal;
        private boolean finalObserved;
        private boolean publicOutputEmitted;
        private AgentStreamEvent pendingFinal;

        private ProgressState(ExecuteAgentRunRequest request) {
            this.request = request;
        }

        private void validateCandidate(AgentStreamEvent event) {
            if (event == null
                    || !request.agentRunId().equals(event.runId())
                    || !request.attemptId().equals(event.attemptId())
                    || request.command().actorScope().audience() != event.audience()) {
                throw protocolFailure("stream event identity or audience does not match");
            }
            if (terminal || pendingFinal != null || event.sequenceNo() <= lastSequence) {
                throw protocolFailure("stream event sequence or terminal state is invalid");
            }
            if (!started) {
                if (event.sequenceNo() != 0
                        || event.eventType() != StreamEventType.ATTEMPT_STARTED) {
                    throw protocolFailure("stream must begin with attempt_started sequence zero");
                }
            } else if (event.eventType() == StreamEventType.ATTEMPT_STARTED) {
                throw protocolFailure("stream cannot contain another attempt_started event");
            }
        }

        private void stageFinal(AgentStreamEvent event) {
            pendingFinal = event;
        }

        private void commit(AgentStreamEvent event) {
            started = true;
            lastSequence = event.sequenceNo();
            publicOutputEmitted |= event.eventType() == StreamEventType.VISIBLE_DELTA;
            terminal = event.eventType() == StreamEventType.FINAL
                    || event.eventType() == StreamEventType.ERROR
                    || event.eventType() == StreamEventType.ATTEMPT_ABORTED;
            if (event.eventType() == StreamEventType.FINAL) {
                finalObserved = true;
                pendingFinal = null;
            }
        }

        private AgentStreamEvent validatedFinal(RoomGraphResult result) {
            if (result == null
                    || !started
                    || pendingFinal == null
                    || !request.command().commandId().equals(result.commandId())
                    || !request.logicalRunId().equals(result.logicalRunId())
                    || !request.attemptId().equals(result.attemptId())
                    || !request.command().graphKey().equals(result.graphKey())
                    || !request.command().graphVersion().equals(result.graphVersion())
                    || !executionMetadataMatches(result.executionMetadata())
                    || !Objects.equals(
                            pendingFinal.payload().finalResultHash(), result.outputHash())) {
                throw protocolFailure("cached or executed graph result does not match the final stream");
            }
            return pendingFinal;
        }

        private boolean hasPendingFinal() {
            return pendingFinal != null;
        }

        private long durableSequence() {
            return Math.max(0, lastSequence);
        }

        private boolean executionMetadataMatches(RoomGraphResult.ExecutionMetadata metadata) {
            if (metadata == null) {
                return false;
            }
            var invocation = request.command().invocationContext();
            return invocation.promptProfileId().equals(metadata.promptVersion())
                    && invocation.modelProfileId().equals(metadata.modelProfileId())
                    && invocation.outputSchemaVersion().equals(metadata.schemaVersion())
                    && invocation.policyVersion().equals(metadata.policyVersion())
                    && invocation.guardrailVersion().equals(metadata.guardrailVersion());
        }

        private AgentRunExecutionException protocolFailure(String message) {
            return AgentRunExecutionException.nonRetryable(
                    "AGENT_RUN_STREAM_V2_INVALID",
                    message,
                    Math.max(0, lastSequence),
                    publicOutputEmitted,
                    null);
        }
    }
}
