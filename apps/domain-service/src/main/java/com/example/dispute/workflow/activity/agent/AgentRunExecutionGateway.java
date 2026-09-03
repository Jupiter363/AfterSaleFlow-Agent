package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import java.util.Optional;
import java.util.regex.Pattern;

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

    /**
     * Closes only the external technical execution after the Activity has exhausted every legal
     * same-command retry.
     *
     * <p>The default legacy lane has no separate external terminal authority. The parallel Intake
     * implementation must first obtain its immutable Graph receipt and must not write local
     * AgentRun or business state from this method.
     */
    default Optional<FailureTerminationReceipt> terminateUncommittedFailure(
            ExecuteAgentRunRequest request,
            String failureCode,
            AgentRunCancellationToken cancellationToken) {
        return Optional.empty();
    }

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

    /** Immutable, self-hashed receipt returned by an external technical execution owner. */
    record FailureTerminationReceipt(
            String schemaVersion,
            String receiptId,
            String receiptHash,
            byte[] canonicalReceiptBytes) {

        private static final Pattern IDENTIFIER =
                Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
        private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

        public FailureTerminationReceipt {
            if (!"intake.parallel-failure-termination.v1".equals(schemaVersion)
                    || receiptId == null
                    || !IDENTIFIER.matcher(receiptId).matches()
                    || receiptHash == null
                    || !SHA256.matcher(receiptHash).matches()
                    || canonicalReceiptBytes == null
                    || canonicalReceiptBytes.length < 2
                    || canonicalReceiptBytes.length > 65_536) {
                throw new IllegalArgumentException("failure termination receipt is invalid");
            }
            canonicalReceiptBytes = canonicalReceiptBytes.clone();
        }

        @Override
        public byte[] canonicalReceiptBytes() {
            return canonicalReceiptBytes.clone();
        }
    }
}
