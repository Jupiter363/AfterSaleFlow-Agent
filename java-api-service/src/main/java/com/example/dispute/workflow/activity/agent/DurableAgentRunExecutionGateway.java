package com.example.dispute.workflow.activity.agent;

import com.example.dispute.agentstream.application.AgentRunV2StreamStore;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.BatchAppendReceipt;
import com.example.dispute.agentstream.application.AgentRunV2StreamStore.NonRunningAttemptException;
import com.example.dispute.agentstream.application.AgentRunReconciledFinalStore;
import com.example.dispute.agentstream.application.AgentRunTransientStreamPublisher;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.GraphReconcileResponse;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
    private final AgentRunTransientStreamPublisher transientPublisher;

    public DurableAgentRunExecutionGateway(
            AgentGraphCommandClient commandClient,
            AgentRunV2StreamStore streamStore) {
        this(commandClient, null, streamStore, null, AgentRunTransientStreamPublisher.noOp());
    }

    public DurableAgentRunExecutionGateway(
            AgentGraphCommandClient commandClient,
            AgentGraphReconciliationClient reconciliationClient,
            AgentRunV2StreamStore streamStore,
            AgentRunReconciledFinalStore reconciledFinalStore) {
        this(
                commandClient,
                reconciliationClient,
                streamStore,
                reconciledFinalStore,
                AgentRunTransientStreamPublisher.noOp());
    }

    public DurableAgentRunExecutionGateway(
            AgentGraphCommandClient commandClient,
            AgentGraphReconciliationClient reconciliationClient,
            AgentRunV2StreamStore streamStore,
            AgentRunReconciledFinalStore reconciledFinalStore,
            AgentRunTransientStreamPublisher transientPublisher) {
        this.commandClient = Objects.requireNonNull(commandClient, "commandClient");
        this.reconciliationClient = reconciliationClient;
        this.streamStore = Objects.requireNonNull(streamStore, "streamStore");
        this.reconciledFinalStore = reconciledFinalStore;
        this.transientPublisher =
                Objects.requireNonNull(transientPublisher, "transientPublisher");
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
        V3FrameAccumulator v3Frames = new V3FrameAccumulator();
        RoomGraphResult result;
        try {
            result = commandClient.execute(
                    request,
                    executionMode,
                    event -> {
                        cancellationToken.throwIfCancellationRequested();
                        ProjectedCandidate candidate = state.projectCandidate(event);
                        if (candidate == null) {
                            return;
                        }
                        AgentStreamEvent publicEvent = candidate.publicEvent();
                        if (publicEvent.eventType() == StreamEventType.FINAL) {
                            state.stageFinal(candidate);
                            return;
                        }
                        if (v3FrameEvent(publicEvent.eventType())) {
                            switch (publicEvent.eventType()) {
                                case PUBLIC_FRAME_START -> {
                                    v3Frames.start(publicEvent);
                                    if (!state.publicOutputStartMarked()) {
                                        streamStore.markPublicOutputStarted(
                                                publicEvent.runId(), publicEvent.attemptId());
                                        state.markPublicOutputStart();
                                    }
                                    state.accept(candidate);
                                    state.observeTransientPublicOutput();
                                    transientPublisher.publish(publicEvent);
                                }
                                case PUBLIC_TEXT_DELTA -> {
                                    v3Frames.append(publicEvent);
                                    state.accept(candidate);
                                    state.observeTransientPublicOutput();
                                    transientPublisher.publish(publicEvent);
                                }
                                case ACTIVE_FRAME_SNAPSHOT -> {
                                    v3Frames.snapshot(publicEvent);
                                    state.accept(candidate);
                                    state.observeTransientPublicOutput();
                                    transientPublisher.publish(publicEvent);
                                }
                                case PUBLIC_FRAME_COMMITTED, PUBLIC_FRAME_INTERRUPTED -> {
                                    for (AgentStreamEvent durable :
                                            v3Frames.finish(publicEvent, state)) {
                                        batch.add(durable);
                                    }
                                    state.accept(candidate);
                                    flushBatch(
                                            batch,
                                            state,
                                            false,
                                            progressListener,
                                            cancellationToken);
                                }
                                default -> throw new IllegalStateException(
                                        "unreachable v3 frame event");
                            }
                            return;
                        }
                        AgentStreamEvent durablePublicEvent =
                                state.allocateDurable(publicEvent);
                        if (batch.shouldFlushBefore(publicEvent)) {
                            flushBatch(
                                    batch,
                                    state,
                                    false,
                                    progressListener,
                                    cancellationToken);
                        }
                        state.accept(candidate);
                        batch.add(durablePublicEvent);
                        if (publicEvent.eventType() == StreamEventType.VISIBLE_DELTA
                                || terminal(publicEvent.eventType())
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
            boolean terminalReconciliation = failure instanceof AgentRunExecutionException typed
                    && typed.recoveryAction() == AgentRunRecoveryAction.RECONCILE_TERMINAL;
            boolean pendingFinalLogicalFailure = state.hasPendingFinal()
                    && failure instanceof AgentRunExecutionException typed
                    && typed.recoveryAction() == AgentRunRecoveryAction.FAIL_LOGICAL_RUN;
            flushBatch(
                    batch,
                    state,
                    state.hasPendingFinal() || terminalReconciliation,
                    progressListener,
                    cancellationToken);
            if ((terminalReconciliation || pendingFinalLogicalFailure)
                    && reconciliationClient != null
                    && reconciledFinalStore != null) {
                return reconcileOnly(
                        request,
                        progressListener,
                        cancellationToken,
                        state.pendingFinal(),
                        state.durableSequence(),
                        state.publicOutputEmitted,
                        true);
            }
            if (pendingFinalLogicalFailure) {
                throw AgentRunExecutionException.reconcileTerminal(
                        "AGENT_RUN_RECONCILIATION_NOT_CONFIGURED",
                        "a graph failure after an observed final requires result reconciliation",
                        state.durableSequence(),
                        state.publicOutputEmitted,
                        failure);
            }
            if (state.hasPendingFinal()
                    && !(failure instanceof AgentRunExecutionException)) {
                throw AgentRunExecutionException.reconcileTerminal(
                        "AGENT_RUN_RESULT_AFTER_FINAL_UNAVAILABLE",
                        "graph result was unavailable after its final frame",
                        state.durableSequence(),
                        state.publicOutputEmitted,
                        failure);
            }
            if (failure instanceof AgentRunExecutionException typed
                    && shouldMaterializeLocalFailureTerminal(typed, state)) {
                throw materializeLocalFailureTerminal(
                        typed,
                        state,
                        progressListener,
                        cancellationToken);
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
                    state.hasPendingFinal(),
                    progressListener,
                    cancellationToken);
            if (state.hasPendingFinal()) {
                if (reconciliationClient != null && reconciledFinalStore != null) {
                    return reconcileOnly(
                            request,
                            progressListener,
                            cancellationToken,
                            state.pendingFinal(),
                            state.durableSequence(),
                            state.publicOutputEmitted,
                            true);
                }
                throw AgentRunExecutionException.reconcileTerminal(
                        "AGENT_RUN_RECONCILIATION_NOT_CONFIGURED",
                        "a graph result failure after an observed final requires result reconciliation",
                        state.durableSequence(),
                        state.publicOutputEmitted,
                        failure);
            }
            if (failure instanceof AgentRunExecutionException typed
                    && shouldMaterializeLocalFailureTerminal(typed, state)) {
                throw materializeLocalFailureTerminal(
                        typed,
                        state,
                        progressListener,
                        cancellationToken);
            }
            throw failure;
        }
        batch.add(state.allocateDurable(finalEvent));
        flushBatch(
                batch,
                state,
                true,
                progressListener,
                cancellationToken);
        return new Completion(result, state.lastSequence, state.publicOutputEmitted);
    }

    private static boolean shouldMaterializeLocalFailureTerminal(
            AgentRunExecutionException failure, ProgressState state) {
        return failure.recoveryAction() == AgentRunRecoveryAction.FAIL_LOGICAL_RUN
                && !state.hasObservedTerminal();
    }

    private AgentRunExecutionException materializeLocalFailureTerminal(
            AgentRunExecutionException failure,
            ProgressState state,
            ProgressListener progressListener,
            AgentRunCancellationToken cancellationToken) {
        PendingBatch terminalBatch = new PendingBatch();
        terminalBatch.add(state.localFailureError(failure.errorCode()));
        flushBatch(
                terminalBatch,
                state,
                false,
                progressListener,
                cancellationToken);
        return AgentRunExecutionException.failLogicalRun(
                failure.errorCode(),
                "agent run failed after a non-recoverable graph error",
                state.durableSequence(),
                state.publicOutputEmitted,
                failure);
    }

    private Completion reconcileOnly(
            ExecuteAgentRunRequest request,
            ProgressListener progressListener,
            AgentRunCancellationToken cancellationToken) {
        return reconcileOnly(
                request,
                progressListener,
                cancellationToken,
                null,
                request.publicSequenceOffset(),
                false,
                false);
    }

    private Completion reconcileOnly(
            ExecuteAgentRunRequest request,
            ProgressListener progressListener,
            AgentRunCancellationToken cancellationToken,
            AgentStreamEvent expectedFinal,
            long baselineSequence,
            boolean publicOutputEmitted,
            boolean preserveTerminalRecovery) {
        if (reconciliationClient == null || reconciledFinalStore == null) {
            throw AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_RECONCILIATION_NOT_CONFIGURED",
                    "result-only reconciliation dependencies are unavailable",
                    baselineSequence,
                    publicOutputEmitted,
                    null);
        }
        cancellationToken.throwIfCancellationRequested();
        GraphReconcileResponse response;
        try {
            response = reconciliationClient.reconcile(request, cancellationToken);
        } catch (GraphReconciliationException failure) {
            cancellationToken.throwIfCancellationRequested();
            throw reconciliationFailure(
                    failure,
                    baselineSequence,
                    publicOutputEmitted,
                    preserveTerminalRecovery,
                    expectedFinal != null);
        }
        requireReconciliationMatches(
                request,
                response,
                expectedFinal,
                baselineSequence,
                publicOutputEmitted);
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
        } catch (NonRunningAttemptException failure) {
            if (staleAttemptStatus(failure.attemptStatus())) {
                throw staleAttemptFinal(baselineSequence, publicOutputEmitted, failure);
            }
            throw AgentRunExecutionException.reconcileTerminal(
                    "AGENT_RUN_RECONCILED_FINAL_APPEND_FAILED",
                    "durable reconciled final append failed",
                    baselineSequence,
                    publicOutputEmitted,
                    failure);
        } catch (AgentRunReconciledFinalStore.ConflictException failure) {
            throw AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_RECONCILED_FINAL_CONFLICT",
                    "durable reconciled final conflicts with public history",
                    baselineSequence,
                    publicOutputEmitted,
                    failure);
        } catch (RuntimeException failure) {
            throw AgentRunExecutionException.reconcileTerminal(
                    "AGENT_RUN_RECONCILED_FINAL_APPEND_FAILED",
                    "durable reconciled final append failed",
                    baselineSequence,
                    publicOutputEmitted,
                    failure);
        }
        if (receipt == null) {
            throw AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_RECONCILED_FINAL_CONFLICT",
                    "durable reconciled final store returned no receipt",
                    baselineSequence,
                    publicOutputEmitted,
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
        return new Completion(
                response.result(),
                finalEvent.sequenceNo(),
                receipt.publicOutputEmitted());
    }

    private static AgentRunExecutionException reconciliationFailure(
            GraphReconciliationException failure,
            long baselineSequence,
            boolean publicOutputEmitted,
            boolean preserveTerminalRecovery,
            boolean observedFinal) {
        return switch (failure.recoveryAction()) {
            case RETRY_SAME_COMMAND -> preserveTerminalRecovery
                    ? AgentRunExecutionException.reconcileTerminal(
                            failure.errorCode(),
                            "terminal result reconciliation must be retried without execution",
                            baselineSequence,
                            publicOutputEmitted,
                            failure)
                    : AgentRunExecutionException.retrySameCommand(
                            failure.errorCode(),
                            "result-only reconciliation may retry the same command",
                            baselineSequence,
                            publicOutputEmitted,
                            failure);
            case CREATE_NEXT_ATTEMPT -> observedFinal
                    ? AgentRunExecutionException.failLogicalRun(
                            "AGENT_RUN_OBSERVED_FINAL_RECONCILIATION_CONFLICT",
                            "an observed final cannot be replaced by a new AgentRun attempt",
                            baselineSequence,
                            publicOutputEmitted,
                            failure)
                    : AgentRunExecutionException.createNextAttempt(
                            failure.errorCode(),
                            "result-only reconciliation requires a new AgentRun attempt",
                            baselineSequence,
                            publicOutputEmitted,
                            failure);
            case FAIL_LOGICAL_RUN -> AgentRunExecutionException.failLogicalRun(
                    failure.errorCode(),
                    "result-only reconciliation rejected the logical run",
                    baselineSequence,
                    publicOutputEmitted,
                    failure);
            case RECONCILE_TERMINAL -> AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_RECONCILIATION_ACTION_INVALID",
                    "result-only reconciliation returned a recursive recovery action",
                    baselineSequence,
                    publicOutputEmitted,
                    failure);
        };
    }

    private static void requireReconciliationMatches(
            ExecuteAgentRunRequest request,
            GraphReconcileResponse response,
            AgentStreamEvent expectedFinal,
            long baselineSequence,
            boolean publicOutputEmitted) {
        if (response == null) {
            throw AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_RECONCILIATION_RESULT_MISSING",
                    "result-only reconciliation returned no response",
                    baselineSequence,
                    publicOutputEmitted,
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
                    baselineSequence,
                    publicOutputEmitted,
                    null);
        }
        if (expectedFinal != null
                && (expectedFinal.eventType() != StreamEventType.FINAL
                        || !request.logicalRunId().equals(expectedFinal.runId())
                        || !request.attemptId().equals(expectedFinal.attemptId())
                        || request.command().actorScope().audience() != expectedFinal.audience()
                        || !response.resultRef().equals(
                                expectedFinal.payload().finalResultRef())
                        || !response.resultHash().equals(
                                expectedFinal.payload().finalResultHash()))) {
            throw AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_RECONCILIATION_RESULT_INVALID",
                    "result-only reconciliation differs from the observed final",
                    baselineSequence,
                    publicOutputEmitted,
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
        boolean commitsFinal = events.getLast().eventType() == StreamEventType.FINAL;
        if (commitsFinal) {
            cancellationToken.throwIfCancellationRequested();
        }
        BatchAppendReceipt receipt;
        try {
            receipt = Objects.requireNonNull(
                    streamStore.appendBatch(events), "durable batch append receipt");
            if (receipt.inserted().size() != events.size()) {
                throw new IllegalStateException(
                        "durable batch receipt does not describe every event");
            }
        } catch (NonRunningAttemptException failure) {
            if (events.getLast().eventType() == StreamEventType.FINAL
                    && staleAttemptStatus(failure.attemptStatus())) {
                throw staleAttemptFinal(
                        state.durableSequence(), state.publicOutputEmitted, failure);
            }
            throw durableAppendFailure(
                    reconcileTerminal,
                    "AGENT_RUN_DURABLE_APPEND_FAILED",
                    "durable stream batch append failed",
                    state,
                    failure);
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
        AgentRunProgress notification = inserted && !commitsFinal ? state.snapshot() : null;
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

    private static boolean staleAttemptStatus(AgentRunAttemptStatus status) {
        return status == AgentRunAttemptStatus.FAILED
                || status == AgentRunAttemptStatus.ABORTED
                || status == AgentRunAttemptStatus.CANCELLED;
    }

    private static AgentRunExecutionException staleAttemptFinal(
            long lastSequenceNo, boolean publicOutputEmitted, Throwable cause) {
        return AgentRunExecutionException.failLogicalRun(
                "AGENT_RUN_STALE_ATTEMPT_FINAL",
                "a superseded attempt cannot publish a final event",
                lastSequenceNo,
                publicOutputEmitted,
                cause);
    }

    private static boolean terminal(StreamEventType eventType) {
        return eventType == StreamEventType.ERROR
                || eventType == StreamEventType.ATTEMPT_ABORTED;
    }

    private static boolean v3FrameEvent(StreamEventType eventType) {
        return eventType == StreamEventType.PUBLIC_FRAME_START
                || eventType == StreamEventType.PUBLIC_TEXT_DELTA
                || eventType == StreamEventType.ACTIVE_FRAME_SNAPSHOT
                || eventType == StreamEventType.PUBLIC_FRAME_COMMITTED
                || eventType == StreamEventType.PUBLIC_FRAME_INTERRUPTED;
    }

    /** Buffers only one public frame. Provider deltas stay in memory and are collapsed into one
     * durable snapshot transaction when the matching commit arrives. */
    private static final class V3FrameAccumulator {
        private AgentStreamEvent start;
        private final StringBuilder text = new StringBuilder();
        private int nextDeltaIndex;
        private int lastFrameSequence;

        private void start(AgentStreamEvent event) {
            if (start != null
                    || event.eventType() != StreamEventType.PUBLIC_FRAME_START
                    || event.payload().frameId() == null
                    || event.payload().frameSequence() == null
                    || event.payload().frameType() == null
                    || event.payload().publicHeader() == null) {
                throw invalid("public frame start is invalid or overlaps another frame");
            }
            if (event.payload().frameSequence() != lastFrameSequence + 1) {
                throw invalid(
                        "public frame sequence is not contiguous: expected "
                                + (lastFrameSequence + 1)
                                + " but received "
                                + event.payload().frameSequence());
            }
            start = event;
            text.setLength(0);
            nextDeltaIndex = 0;
        }

        private void append(AgentStreamEvent event) {
            requireActive(event, StreamEventType.PUBLIC_TEXT_DELTA);
            if (event.payload().deltaIndex() == null
                    || event.payload().deltaIndex() != nextDeltaIndex
                    || event.payload().delta() == null
                    || event.payload().delta().isEmpty()) {
                throw invalid(
                        "public frame delta is not contiguous: expected index "
                                + nextDeltaIndex
                                + " but received "
                                + event.payload().deltaIndex());
            }
            text.append(event.payload().delta());
            nextDeltaIndex++;
        }

        private void snapshot(AgentStreamEvent event) {
            requireActive(event, StreamEventType.ACTIVE_FRAME_SNAPSHOT);
            if (event.payload().deltaIndex() == null
                    || event.payload().deltaIndex() < nextDeltaIndex
                    || event.payload().publicText() == null) {
                throw invalid("active frame snapshot is invalid");
            }
            text.setLength(0);
            text.append(event.payload().publicText());
            nextDeltaIndex = event.payload().deltaIndex();
        }

        private List<AgentStreamEvent> finish(
                AgentStreamEvent terminalEvent, ProgressState progress) {
            StreamEventType type = terminalEvent.eventType();
            if (type != StreamEventType.PUBLIC_FRAME_COMMITTED
                    && type != StreamEventType.PUBLIC_FRAME_INTERRUPTED) {
                throw invalid("public frame terminal type is invalid");
            }
            requireActive(terminalEvent, type);
            String publicText = text.toString();
            if (type == StreamEventType.PUBLIC_FRAME_COMMITTED) {
                requireCommittedHashes(terminalEvent, publicText);
            } else if (!Objects.equals(terminalEvent.payload().publicText(), publicText)) {
                throw invalid("interrupted frame text differs from relayed bytes");
            }

            AgentStreamEvent durableStart = progress.allocateDurable(start);
            AgentStreamEvent snapshot = progress.allocateDurable(
                    new AgentStreamEvent(
                            terminalEvent.schemaVersion(),
                            terminalEvent.runId(),
                            terminalEvent.attemptId(),
                            terminalEvent.sequenceNo(),
                            StreamEventType.ACTIVE_FRAME_SNAPSHOT,
                            terminalEvent.audience(),
                            terminalEvent.occurredAt(),
                            new AgentStreamEvent.Payload(
                                    null, null, null, null, null, null, null, null, null, null,
                                    start.payload().frameId(),
                                    start.payload().frameSequence(),
                                    null,
                                    null,
                                    nextDeltaIndex,
                                    publicText,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null)));
            AgentStreamEvent durableTerminal = progress.allocateDurable(terminalEvent);
            lastFrameSequence = start.payload().frameSequence();
            start = null;
            text.setLength(0);
            nextDeltaIndex = 0;
            return List.of(durableStart, snapshot, durableTerminal);
        }

        private void requireCommittedHashes(AgentStreamEvent event, String publicText) {
            String headerHash = ContractJson.sha256Hex(start.payload().publicHeader());
            String textHash = sha256(publicText.getBytes(StandardCharsets.UTF_8));
            int characters = publicText.codePointCount(0, publicText.length());
            if (!Objects.equals(headerHash, event.payload().headerSha256())
                    || !Objects.equals(textHash, event.payload().publicTextSha256())
                    || !Objects.equals(characters, event.payload().publicTextChars())) {
                throw invalid("committed frame hashes differ from relayed bytes");
            }
            ObjectNode preimage = JsonNodeFactory.instance.objectNode();
            preimage.put("frame_id", start.payload().frameId());
            preimage.put("frame_sequence", start.payload().frameSequence());
            preimage.put("frame_type", start.payload().frameType());
            preimage.set("header", start.payload().publicHeader());
            preimage.put("header_sha256", headerHash);
            preimage.put("public_text", publicText);
            preimage.put("public_text_sha256", textHash);
            preimage.put("public_text_length", characters);
            if (!Objects.equals(
                    ContractJson.sha256Hex(preimage), event.payload().frameSha256())) {
                throw invalid("committed frame authority hash differs");
            }
        }

        private void requireActive(AgentStreamEvent event, StreamEventType expectedType) {
            if (start == null
                    || event.eventType() != expectedType
                    || !Objects.equals(start.runId(), event.runId())
                    || !Objects.equals(start.attemptId(), event.attemptId())
                    || !Objects.equals(start.payload().frameId(), event.payload().frameId())
                    || !Objects.equals(
                            start.payload().frameSequence(), event.payload().frameSequence())) {
                throw invalid("public frame identity differs from its active frame");
            }
        }

        private static AgentRunExecutionException invalid(String message) {
            return AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_STREAM_V3_FRAME_INVALID", message, 0, true, null);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
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

    private record ProjectedCandidate(long candidateSequence, AgentStreamEvent publicEvent) {}

    private static final class ProgressState {
        private final ExecuteAgentRunRequest request;
        private long lastSequence;
        private long allocatedSequence;
        private long acceptedCandidateSequence = -1;
        private boolean handshakeAccepted;
        private boolean acceptedTerminal;
        private boolean finalObserved;
        private boolean publicOutputEmitted;
        private boolean publicOutputStartMarked;
        private AgentStreamEvent pendingFinal;

        private ProgressState(ExecuteAgentRunRequest request) {
            this.request = request;
            this.lastSequence = request.publicSequenceOffset();
            this.allocatedSequence = request.publicSequenceOffset();
        }

        private ProjectedCandidate projectCandidate(AgentStreamEvent event) {
            if (event == null
                    || !request.agentRunId().equals(event.runId())
                    || !request.attemptId().equals(event.attemptId())
                    || request.command().actorScope().audience() != event.audience()) {
                throw protocolFailure("stream event identity or audience does not match");
            }
            if (event.eventType() == StreamEventType.ATTEMPT_RESET) {
                throw AgentRunExecutionException.failLogicalRun(
                        "AGENT_RUN_STREAM_RESET_AUTHORITY_VIOLATION",
                        "Python cannot publish an attempt_reset event",
                        durableSequence(),
                        publicOutputEmitted,
                        null);
            }
            long expectedCandidateSequence;
            try {
                expectedCandidateSequence = Math.addExact(acceptedCandidateSequence, 1);
            } catch (ArithmeticException failure) {
                throw protocolFailure("stream event sequence exceeds the candidate sequence range");
            }
            if (acceptedTerminal
                    || pendingFinal != null
                    || event.sequenceNo() != expectedCandidateSequence) {
                throw protocolFailure("stream event sequence or terminal state is invalid");
            }
            if (!handshakeAccepted) {
                if (event.sequenceNo() != 0
                        || event.eventType() != StreamEventType.ATTEMPT_STARTED) {
                    throw protocolFailure("stream must begin with attempt_started sequence zero");
                }
                handshakeAccepted = true;
                acceptedCandidateSequence = 0;
                return null;
            }
            if (event.eventType() == StreamEventType.ATTEMPT_STARTED) {
                throw protocolFailure("stream cannot contain another attempt_started event");
            }
            long publicSequence = event.sequenceNo();
            if (!"agent-stream.v3".equals(event.schemaVersion())) {
                try {
                    publicSequence = Math.addExact(
                            event.sequenceNo(), (long) request.publicSequenceOffset());
                } catch (ArithmeticException failure) {
                    throw protocolFailure(
                            "stream event sequence exceeds the public sequence range");
                }
            }
            return new ProjectedCandidate(
                    event.sequenceNo(),
                    new AgentStreamEvent(
                            event.schemaVersion(),
                            event.runId(),
                            event.attemptId(),
                            publicSequence,
                            event.eventType(),
                            event.audience(),
                            event.occurredAt(),
                            event.payload()));
        }

        private void accept(ProjectedCandidate candidate) {
            acceptedCandidateSequence = candidate.candidateSequence();
            acceptedTerminal = candidate.publicEvent().eventType() == StreamEventType.FINAL
                    || terminal(candidate.publicEvent().eventType());
        }

        private void stageFinal(ProjectedCandidate candidate) {
            accept(candidate);
            pendingFinal = candidate.publicEvent();
        }

        private void commit(AgentStreamEvent event) {
            lastSequence = event.sequenceNo();
            allocatedSequence = Math.max(allocatedSequence, event.sequenceNo());
            publicOutputEmitted |= event.eventType() == StreamEventType.VISIBLE_DELTA
                    || event.eventType() == StreamEventType.PUBLIC_FRAME_START
                    || event.eventType() == StreamEventType.ACTIVE_FRAME_SNAPSHOT
                    || event.eventType() == StreamEventType.PUBLIC_FRAME_COMMITTED;
            if (event.eventType() == StreamEventType.FINAL) {
                finalObserved = true;
                pendingFinal = null;
            }
        }

        private void observeTransientPublicOutput() {
            publicOutputEmitted = true;
        }

        private AgentStreamEvent allocateDurable(AgentStreamEvent event) {
            if (!"agent-stream.v3".equals(event.schemaVersion())) {
                allocatedSequence = Math.max(allocatedSequence, event.sequenceNo());
                return event;
            }
            long sequence;
            try {
                sequence = Math.incrementExact(allocatedSequence);
            } catch (ArithmeticException failure) {
                throw protocolFailure("durable v3 stream sequence is exhausted");
            }
            allocatedSequence = sequence;
            return new AgentStreamEvent(
                    event.schemaVersion(),
                    event.runId(),
                    event.attemptId(),
                    sequence,
                    event.eventType(),
                    event.audience(),
                    event.occurredAt(),
                    event.payload());
        }

        private AgentRunProgress snapshot() {
            return new AgentRunProgress(lastSequence, publicOutputEmitted, finalObserved);
        }

        private AgentStreamEvent validatedFinal(RoomGraphResult result) {
            if (result == null
                    || !handshakeAccepted
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

        private boolean publicOutputStartMarked() {
            return publicOutputStartMarked;
        }

        private void markPublicOutputStart() {
            publicOutputStartMarked = true;
        }

        private boolean hasObservedTerminal() {
            return acceptedTerminal || finalObserved;
        }

        private AgentStreamEvent pendingFinal() {
            return pendingFinal;
        }

        private long durableSequence() {
            return Math.max(0, lastSequence);
        }

        private AgentStreamEvent localFailureError(String errorCode) {
            long nextSequence;
            try {
                nextSequence = Math.addExact(durableSequence(), 1L);
            } catch (ArithmeticException failure) {
                throw AgentRunExecutionException.failLogicalRun(
                        "AGENT_RUN_STREAM_SEQUENCE_EXHAUSTED",
                        "agent run stream cannot append a terminal error",
                        durableSequence(),
                        publicOutputEmitted,
                        failure);
            }
            return new AgentStreamEvent(
                    request.streamProtocol(),
                    request.agentRunId(),
                    request.attemptId(),
                    nextSequence,
                    StreamEventType.ERROR,
                    request.command().actorScope().audience(),
                    Instant.now(),
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
                    "agent-stream.v3".equals(request.streamProtocol())
                            ? "AGENT_RUN_STREAM_V3_INVALID"
                            : "AGENT_RUN_STREAM_V2_INVALID",
                    message,
                    Math.max(0, lastSequence),
                    publicOutputEmitted,
                    null);
        }
    }
}
