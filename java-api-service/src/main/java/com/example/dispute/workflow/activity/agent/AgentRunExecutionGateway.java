package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;

/**
 * Versioned Python execution port used by the Temporal Activity.
 *
 * <p>The implementation must look up {@code request.command().commandId()} and its request hash in
 * the durable command ledger before any graph, model, or tool execution. It must persist stream
 * progress before notifying the listener and register its open response with the cancellation token
 * so cancellation closes the transport promptly.
 */
public interface AgentRunExecutionGateway {

    /**
     * Executes or reconciles one attempt and returns only after its public {@code final} is durable.
     * Cancellation observed before that append must fail the call; cancellation racing after the
     * append cannot replace the returned completion.
     */
    Completion execute(
            ExecuteAgentRunRequest request,
            ExecutionMode executionMode,
            ProgressListener progressListener,
            AgentRunCancellationToken cancellationToken);

    /** Controls whether a command-ledger miss may start or resume graph execution. */
    enum ExecutionMode {
        /** Return a cached command result when present; otherwise execution may start or resume. */
        EXECUTE_OR_RECONCILE,

        /**
         * Return only a committed cached result for the exact command and request hash.
         *
         * <p>A miss or hash conflict must fail closed. This mode must never create a command,
         * checkpoint, provider call, model call, or tool side effect.
         */
        RECONCILE_ONLY
    }

    @FunctionalInterface
    interface ProgressListener {
        void onProgress(AgentRunProgress progress);
    }

    /**
     * Proof that the exact graph result and its public {@code final} are durably bound.
     *
     * <p>Implementations must honor cancellation before appending {@code final}. Once this value is
     * returned, later cancellation cannot reverse the attempt to {@code CANCELLED}; callers recover
     * any subsequent persistence loss through terminal reconciliation.
     */
    record Completion(
            RoomGraphResult graphResult,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            ExecuteAgentRunResult durableResult) {

        public Completion(
                RoomGraphResult graphResult,
                long lastSequenceNo,
                boolean publicOutputEmitted) {
            this(graphResult, lastSequenceNo, publicOutputEmitted, null);
        }

        public Completion {
            if (graphResult == null) {
                throw new IllegalArgumentException("graphResult must not be null");
            }
            if (lastSequenceNo < 0) {
                throw new IllegalArgumentException("lastSequenceNo must not be negative");
            }
            if (durableResult != null
                    && (durableResult.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED
                            || !graphResult.equals(durableResult.graphResult())
                            || !graphResult.outputHash().equals(durableResult.resultHash())
                            || lastSequenceNo != durableResult.lastSequenceNo()
                            || publicOutputEmitted != durableResult.publicOutputEmitted())) {
                throw new IllegalArgumentException(
                        "durableResult must match the terminal completion");
            }
        }
    }
}
