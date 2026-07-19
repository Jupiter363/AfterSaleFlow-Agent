package com.example.dispute.workflow.activity.agent;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.BatchAppendReceipt;
import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Persists bounded public-event batches before exposing their progress to the Activity. */
public final class DurableAgentRunExecutionGateway implements AgentRunExecutionGateway {

    // The command sink is synchronous, so ADR 0003's size arm bounds batching without a timer
    // thread racing cancellation or final-result validation.
    static final int TARGET_DELTA_BATCH_BYTES = 1_024;
    static final int MAX_BATCH_EVENTS = 32;

    private final AgentGraphCommandClient commandClient;
    private final AgentGraphReconciliationClient reconciliationClient;
    private final AgentRunV2StreamStore streamStore;
    private final AgentRunReconciledFinalStore reconciledFinalStore;

    public DurableAgentRunExecutionGateway(
            AgentGraphCommandClient commandClient,
            AgentRunV2StreamStore streamStore) {
        this(commandClient, null, streamStore, null);
    }

    public DurableAgentRunExecutionGateway(
            AgentGraphCommandClient commandClient,
            AgentGraphReconciliationClient reconciliationClient,
            AgentRunV2StreamStore streamStore,
            AgentRunReconciledFinalStore reconciledFinalStore) {
        this.commandClient = Objects.requireNonNull(commandClient, "commandClient");
        this.reconciliationClient = reconciliationClient;
        this.streamStore = Objects.requireNonNull(streamStore, "streamStore");
        this.reconciledFinalStore = reconciledFinalStore;
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
        if (executionMode == ExecutionMode.RECONCILE_ONLY) {
            return reconcileOnly(request, progressListener, cancellationToken);
        }

        ProgressState state = new ProgressState(request);
        PendingBatch batch = new PendingBatch();
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
                        if (batch.shouldFlushBefore(event)) {
                            flushBatch(
                                    batch,
                                    state,
                                    false,
                                    progressListener,
                                    cancellationToken);
                        }
                        state.accept(event);
                        batch.add(event);
                        if (event.eventType() == StreamEventType.ATTEMPT_STARTED
                                || terminal(event.eventType())
                                || batch.shouldFlush()) {
                            flushBatch(
                                    batch,
                                    state,
                                    false,
                                    progressListener,
                                    cancellationToken);
                        }
                    },
                    cancellationToken);
        } catch (RuntimeException failure) {
            cancellationToken.throwIfCancellationRequested();
            if (durableAppendFailure(failure)) {
                throw failure;
            }
            flushBatch(
                    batch,
                    state,
                    state.hasPendingFinal(),
                    progressListener,
                    cancellationToken);
            if (state.hasPendingFinal()
                    && !(failure instanceof AgentRunExecutionException)) {
                throw AgentRunExecutionException.reconcileTerminal(
                        "AGENT_RUN_RESULT_AFTER_FINAL_UNAVAILABLE",
                        "graph result was unavailable after its final frame",
                        state.durableSequence(),
                        state.publicOutputEmitted,
                        failure);
            }
            throw failure;
        }

        cancellationToken.throwIfCancellationRequested();
        AgentStreamEvent finalEvent;
        try {
            finalEvent = state.validatedFinal(result);
        } catch (RuntimeException failure) {
            flushBatch(
                    batch,
                    state,
                    false,
                    progressListener,
                    cancellationToken);
            throw failure;
        }
        batch.add(finalEvent);
        flushBatch(
                batch,
                state,
                true,
                progressListener,
                cancellationToken);
        return new Completion(result, state.lastSequence, state.publicOutputEmitted);
    }

    private Completion reconcileOnly(
            ExecuteAgentRunRequest request,
            ProgressListener progressListener,
            AgentRunCancellationToken cancellationToken) {
        if (reconciliationClient == null || reconciledFinalStore == null) {
            throw AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_RECONCILIATION_NOT_CONFIGURED",
                    "result-only reconciliation dependencies are unavailable",
                    0,
                    false,
                    null);
        }
        cancellationToken.throwIfCancellationRequested();
        GraphReconcileResponse response;
        try {
            response = reconciliationClient.reconcile(request, cancellationToken);
        } catch (GraphReconciliationException failure) {
            cancellationToken.throwIfCancellationRequested();
            throw reconciliationFailure(failure);
        }
        requireReconciliationMatches(request, response);
        cancellationToken.throwIfCancellationRequested();

        AgentRunReconciledFinalStore.Receipt receipt;
        try {
            receipt = reconciledFinalStore.appendOrLoad(
                    new AgentRunReconciledFinalStore.Request(
                            request.logicalRunId(),
                            request.attemptId(),
                            request.command().actorScope().audience(),
                            response.resultRef(),
                            response.resultHash()));
        } catch (AgentRunReconciledFinalStore.ConflictException failure) {
            cancellationToken.throwIfCancellationRequested();
            throw AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_RECONCILED_FINAL_CONFLICT",
                    "durable reconciled final conflicts with public history",
                    0,
                    false,
                    failure);
        } catch (RuntimeException failure) {
            cancellationToken.throwIfCancellationRequested();
            throw AgentRunExecutionException.reconcileTerminal(
                    "AGENT_RUN_RECONCILED_FINAL_APPEND_FAILED",
                    "durable reconciled final append failed",
                    0,
                    false,
                    failure);
        }
        if (receipt == null) {
            throw AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_RECONCILED_FINAL_CONFLICT",
                    "durable reconciled final store returned no receipt",
                    0,
                    false,
                    null);
        }
        AgentStreamEvent finalEvent = receipt.finalEvent();
        if (!request.logicalRunId().equals(finalEvent.runId())
                || !request.attemptId().equals(finalEvent.attemptId())
                || request.command().actorScope().audience() != finalEvent.audience()
                || !response.resultRef().equals(finalEvent.payload().finalResultRef())
                || !response.resultHash().equals(finalEvent.payload().finalResultHash())
                || receipt.durableHighWatermark() != finalEvent.sequenceNo()) {
            throw AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_RECONCILED_FINAL_CONFLICT",
                    "durable reconciled final differs from the exact result",
                    receipt.durableHighWatermark(),
                    receipt.publicOutputEmitted(),
                    null);
        }
        cancellationToken.throwIfCancellationRequested();
        if (receipt.inserted()) {
            progressListener.onProgress(new AgentRunProgress(
                    finalEvent.sequenceNo(),
                    receipt.publicOutputEmitted(),
                    true));
        }
        return new Completion(
                response.result(),
                finalEvent.sequenceNo(),
                receipt.publicOutputEmitted());
    }

    private static AgentRunExecutionException reconciliationFailure(
            GraphReconciliationException failure) {
        return switch (failure.recoveryAction()) {
            case RETRY_SAME_COMMAND -> AgentRunExecutionException.retrySameCommand(
                    failure.errorCode(),
                    "result-only reconciliation may retry the same command",
                    0,
                    false,
                    failure);
            case CREATE_NEXT_ATTEMPT -> AgentRunExecutionException.createNextAttempt(
                    failure.errorCode(),
                    "result-only reconciliation requires a new AgentRun attempt",
                    0,
                    false,
                    failure);
            case FAIL_LOGICAL_RUN -> AgentRunExecutionException.failLogicalRun(
                    failure.errorCode(),
                    "result-only reconciliation rejected the logical run",
                    0,
                    false,
                    failure);
            case RECONCILE_TERMINAL -> AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_RECONCILIATION_ACTION_INVALID",
                    "result-only reconciliation returned a recursive recovery action",
                    0,
                    false,
                    failure);
        };
    }

    private static void requireReconciliationMatches(
            ExecuteAgentRunRequest request,
            GraphReconcileResponse response) {
        if (response == null) {
            throw AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_RECONCILIATION_RESULT_MISSING",
                    "result-only reconciliation returned no response",
                    0,
                    false,
                    null);
        }
        RoomGraphResult result = response.result();
        var command = request.command();
        var invocation = command.invocationContext();
        RoomGraphResult.ExecutionMetadata metadata = result.executionMetadata();
        if (!command.threadId().equals(response.threadId())
                || !command.commandId().equals(response.commandId())
                || !command.requestHash().equals(response.requestHash())
                || !request.logicalRunId().equals(response.logicalRunId())
                || !request.attemptId().equals(response.attemptId())
                || !command.graphKey().equals(response.graphKey())
                || !command.graphVersion().equals(response.graphVersion())
                || !command.checkpointSchemaVersion().equals(
                        response.checkpointSchemaVersion())
                || !response.resultHash().equals(result.outputHash())
                || !response.checkpointId().equals(result.checkpointId())
                || metadata == null
                || !invocation.promptProfileId().equals(metadata.promptVersion())
                || !invocation.modelProfileId().equals(metadata.modelProfileId())
                || !invocation.outputSchemaVersion().equals(metadata.schemaVersion())
                || !invocation.policyVersion().equals(metadata.policyVersion())
                || !invocation.guardrailVersion().equals(metadata.guardrailVersion())) {
            throw AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_RECONCILIATION_RESULT_INVALID",
                    "result-only reconciliation differs from its command",
                    0,
                    false,
                    null);
        }
    }

    private void flushBatch(
            PendingBatch batch,
            ProgressState state,
            boolean reconcileTerminal,
            ProgressListener progressListener,
            AgentRunCancellationToken cancellationToken) {
        if (batch.isEmpty()) {
            return;
        }
        List<AgentStreamEvent> events = batch.events();
        BatchAppendReceipt receipt;
        try {
            receipt = Objects.requireNonNull(
                    streamStore.appendBatch(events), "durable batch append receipt");
            if (receipt.inserted().size() != events.size()) {
                throw new IllegalStateException(
                        "durable batch receipt does not describe every event");
            }
        } catch (RuntimeException failure) {
            throw durableAppendFailure(
                    reconcileTerminal,
                    "AGENT_RUN_DURABLE_APPEND_FAILED",
                    "durable stream batch append failed",
                    state,
                    failure);
        }

        long maximumSequence =
                events.stream().mapToLong(AgentStreamEvent::sequenceNo).max().orElseThrow();
        if (receipt.durableHighWatermark() < maximumSequence) {
            throw durableAppendFailure(
                    reconcileTerminal,
                    "AGENT_RUN_DURABLE_APPEND_LAGGED",
                    "durable stream high-watermark did not reach the batch",
                    state,
                    null);
        }

        boolean inserted = false;
        for (int index = 0; index < events.size(); index++) {
            state.commit(events.get(index));
            inserted |= receipt.inserted().get(index);
        }
        AgentRunProgress notification = inserted ? state.snapshot() : null;
        batch.clear();
        if (notification != null) {
            cancellationToken.throwIfCancellationRequested();
            progressListener.onProgress(notification);
        }
    }

    private static boolean durableAppendFailure(RuntimeException failure) {
        if (!(failure instanceof AgentRunExecutionException executionFailure)) {
            return false;
        }
        return "AGENT_RUN_DURABLE_APPEND_FAILED".equals(executionFailure.errorCode())
                || "AGENT_RUN_DURABLE_APPEND_LAGGED".equals(executionFailure.errorCode());
    }

    private static AgentRunExecutionException durableAppendFailure(
            boolean reconcileTerminal,
            String errorCode,
            String message,
            ProgressState state,
            Throwable cause) {
        if (reconcileTerminal) {
            return AgentRunExecutionException.reconcileTerminal(
                    errorCode,
                    message,
                    state.durableSequence(),
                    state.publicOutputEmitted,
                    cause);
        }
        return AgentRunExecutionException.retrySameCommand(
                errorCode,
                message,
                state.durableSequence(),
                state.publicOutputEmitted,
                cause);
    }

    private static boolean terminal(StreamEventType eventType) {
        return eventType == StreamEventType.ERROR
                || eventType == StreamEventType.ATTEMPT_ABORTED;
    }

    private static final class PendingBatch {
        private final List<AgentStreamEvent> events = new ArrayList<>();
        private int deltaBytes;

        private boolean shouldFlushBefore(AgentStreamEvent event) {
            return !events.isEmpty()
                    && deltaBytes(event) >= TARGET_DELTA_BATCH_BYTES;
        }

        private void add(AgentStreamEvent event) {
            events.add(event);
            deltaBytes += deltaBytes(event);
        }

        private boolean shouldFlush() {
            return deltaBytes >= TARGET_DELTA_BATCH_BYTES
                    || events.size() >= MAX_BATCH_EVENTS;
        }

        private boolean isEmpty() {
            return events.isEmpty();
        }

        private List<AgentStreamEvent> events() {
            return List.copyOf(events);
        }

        private void clear() {
            events.clear();
            deltaBytes = 0;
        }

        private static int deltaBytes(AgentStreamEvent event) {
            String delta = event.payload().delta();
            return delta == null ? 0 : delta.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private static final class ProgressState {
        private final ExecuteAgentRunRequest request;
        private long lastSequence = -1;
        private long acceptedSequence = -1;
        private boolean acceptedStarted;
        private boolean acceptedTerminal;
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
            if (acceptedTerminal
                    || pendingFinal != null
                    || event.sequenceNo() <= acceptedSequence) {
                throw protocolFailure("stream event sequence or terminal state is invalid");
            }
            if (!acceptedStarted) {
                if (event.sequenceNo() != 0
                        || event.eventType() != StreamEventType.ATTEMPT_STARTED) {
                    throw protocolFailure("stream must begin with attempt_started sequence zero");
                }
            } else if (event.eventType() == StreamEventType.ATTEMPT_STARTED) {
                throw protocolFailure("stream cannot contain another attempt_started event");
            }
        }

        private void accept(AgentStreamEvent event) {
            acceptedStarted = true;
            acceptedSequence = event.sequenceNo();
            acceptedTerminal = event.eventType() == StreamEventType.FINAL
                    || terminal(event.eventType());
        }

        private void stageFinal(AgentStreamEvent event) {
            accept(event);
            pendingFinal = event;
        }

        private void commit(AgentStreamEvent event) {
            lastSequence = event.sequenceNo();
            publicOutputEmitted |= event.eventType() == StreamEventType.VISIBLE_DELTA;
            if (event.eventType() == StreamEventType.FINAL) {
                finalObserved = true;
                pendingFinal = null;
            }
        }

        private AgentRunProgress snapshot() {
            return new AgentRunProgress(lastSequence, publicOutputEmitted, finalObserved);
        }

        private AgentStreamEvent validatedFinal(RoomGraphResult result) {
            if (result == null
                    || !acceptedStarted
                    || pendingFinal == null
                    || !request.command().commandId().equals(result.commandId())
                    || !request.logicalRunId().equals(result.logicalRunId())
                    || !request.attemptId().equals(result.attemptId())
                    || !request.command().graphKey().equals(result.graphKey())
                    || !request.command().graphVersion().equals(result.graphVersion())
                    || !executionMetadataMatches(result.executionMetadata())
                    || !Objects.equals(
                            pendingFinal.payload().finalResultHash(), result.outputHash())) {
                throw protocolFailure(
                        "cached or executed graph result does not match the final stream");
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
            return AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_STREAM_V2_INVALID",
                    message,
                    Math.max(0, lastSequence),
                    publicOutputEmitted,
                    null);
        }
    }
}
