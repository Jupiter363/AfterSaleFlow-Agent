package com.example.dispute.workflow.temporal.agentrun;

import com.example.dispute.workflow.activity.agent.ExecuteAgentRunActivity;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import io.temporal.workflow.Workflow;

public class AgentRunWorkflowImpl implements AgentRunWorkflow {

    @Override
    public ExecuteAgentRunResult run(ExecuteAgentRunRequest request) {
        int remainingAttempts = request == null
                ? 0
                : request.command().retryBudget().activityAttemptsRemaining();
        ExecuteAgentRunActivity activity = Workflow.newActivityStub(
                ExecuteAgentRunActivity.class,
                AgentRunTemporalPolicy.activityOptions(remainingAttempts));
        return activity.execute(request);
    }
}
