package com.example.dispute.workflow.application;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;

/** Starts the one Temporal execution owned by a logical AgentRun V2 identity. */
public interface AgentRunV2WorkflowLauncher {

    StartReceipt start(ExecuteAgentRunRequest request);

    record StartReceipt(String workflowId, String runId, StartDisposition disposition) {
        public StartReceipt {
            if (workflowId == null || workflowId.isBlank()) {
                throw new IllegalArgumentException("workflowId is required");
            }
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("runId is required");
            }
            if (disposition == null) {
                throw new IllegalArgumentException("disposition is required");
            }
        }
    }

    enum StartDisposition {
        STARTED,
        ALREADY_STARTED,
        ATTEMPT_ACCEPTED
    }
}
