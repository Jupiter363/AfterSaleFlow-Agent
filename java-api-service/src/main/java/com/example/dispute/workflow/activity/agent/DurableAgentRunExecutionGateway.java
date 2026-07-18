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
        RoomGraphResult result =
                commandClient.execute(
                        request,
                        executionMode,
                        event -> {
                            cancellationToken.throwIfCancellationRequested();
                            state.accept(event);
                            AgentRunV2StreamStore.AppendReceipt receipt =
                                    streamStore.append(event);
                            if (receipt.durableHighWatermark() < event.sequenceNo()) {
                                throw AgentRunExecutionException.retryable(
                                        "AGENT_RUN_DURABLE_APPEND_LAGGED",
                                        "durable stream high-watermark did not reach the event",
                                        state.finalObserved,
                                        state.lastSequence,
                                        state.publicOutputEmitted,
                                        null);
                            }
                            progressListener.onProgress(
                                    new AgentRunProgress(
                                            state.lastSequence,
                                            state.publicOutputEmitted,
                                            state.finalObserved));
                        },
                        cancellationToken);
        cancellationToken.throwIfCancellationRequested();
        state.validateCompletion(result);
        return new Completion(result, state.lastSequence, state.publicOutputEmitted);
    }

    private static final class ProgressState {
        private final ExecuteAgentRunRequest request;
        private long lastSequence = -1;
        private boolean started;
        private boolean terminal;
        private boolean finalObserved;
        private boolean publicOutputEmitted;
        private String finalHash;

        private ProgressState(ExecuteAgentRunRequest request) {
            this.request = request;
        }

        private void accept(AgentStreamEvent event) {
            if (event == null
                    || !request.agentRunId().equals(event.runId())
                    || !request.attemptId().equals(event.attemptId())
                    || request.command().actorScope().audience() != event.audience()) {
                throw protocolFailure("stream event identity or audience does not match");
            }
            if (terminal || event.sequenceNo() <= lastSequence) {
                throw protocolFailure("stream event sequence or terminal state is invalid");
            }
            if (!started) {
                if (event.sequenceNo() != 0
                        || event.eventType() != StreamEventType.ATTEMPT_STARTED) {
                    throw protocolFailure("stream must begin with attempt_started sequence zero");
                }
                started = true;
            }
            lastSequence = event.sequenceNo();
            publicOutputEmitted |= event.eventType() == StreamEventType.VISIBLE_DELTA;
            terminal = event.eventType() == StreamEventType.FINAL
                    || event.eventType() == StreamEventType.ERROR
                    || event.eventType() == StreamEventType.ATTEMPT_ABORTED;
            if (event.eventType() == StreamEventType.FINAL) {
                finalObserved = true;
                finalHash = event.payload().finalResultHash();
            }
        }

        private void validateCompletion(RoomGraphResult result) {
            if (result == null
                    || !started
                    || !finalObserved
                    || !terminal
                    || !request.command().commandId().equals(result.commandId())
                    || !request.logicalRunId().equals(result.logicalRunId())
                    || !request.attemptId().equals(result.attemptId())
                    || !executionMetadataMatches(result.executionMetadata())
                    || !Objects.equals(finalHash, result.outputHash())) {
                throw protocolFailure("cached or executed graph result does not match the final stream");
            }
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
