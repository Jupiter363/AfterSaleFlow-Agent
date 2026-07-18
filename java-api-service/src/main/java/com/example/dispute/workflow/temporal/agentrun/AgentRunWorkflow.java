package com.example.dispute.workflow.temporal.agentrun;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Recoverable orchestration wrapper for a logical AgentRun attempt. */
@WorkflowInterface
public interface AgentRunWorkflow {

    String WORKFLOW_TYPE = "AgentRunV2Workflow";

    @WorkflowMethod(name = WORKFLOW_TYPE)
    ExecuteAgentRunResult run(ExecuteAgentRunRequest request);
}
