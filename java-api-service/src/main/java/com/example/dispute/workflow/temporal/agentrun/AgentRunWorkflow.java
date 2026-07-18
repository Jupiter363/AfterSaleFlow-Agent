package com.example.dispute.workflow.temporal.agentrun;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;

import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Recoverable orchestration wrapper for every ordered attempt of one logical AgentRun. */
@WorkflowInterface
public interface AgentRunWorkflow {

    String WORKFLOW_TYPE = "AgentRunV2Workflow";
    String ATTEMPT_UPDATE = "executeAgentRunAttempt";

    @WorkflowMethod(name = WORKFLOW_TYPE)
    ExecuteAgentRunResult run(ExecuteAgentRunRequest request);

    @UpdateMethod(name = ATTEMPT_UPDATE)
    ExecuteAgentRunResult executeAttempt(ExecuteAgentRunRequest request);

    @UpdateValidatorMethod(updateName = ATTEMPT_UPDATE)
    void validateAttempt(ExecuteAgentRunRequest request);
}
