package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;

/**
 * Versioned Python execution port used by the Temporal Activity.
 *
 * <p>The implementation must use {@code request.command().commandId()} as the durable command-ledger
 * key. It must persist stream progress before notifying the listener and register its open response
 * with the cancellation token so cancellation closes the transport promptly.
 */
public interface AgentRunExecutionGateway {

    Completion execute(
            ExecuteAgentRunRequest request,
            ProgressListener progressListener,
            AgentRunCancellationToken cancellationToken);

    @FunctionalInterface
    interface ProgressListener {
        void onProgress(AgentRunProgress progress);
    }

    record Completion(
            RoomGraphResult graphResult,
            long lastSequenceNo,
            boolean publicOutputEmitted) {

        public Completion {
            if (graphResult == null) {
                throw new IllegalArgumentException("graphResult must not be null");
            }
            if (lastSequenceNo < 0) {
                throw new IllegalArgumentException("lastSequenceNo must not be negative");
            }
        }
    }
}
