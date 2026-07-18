package com.example.dispute.workflow.temporal.agentrun;

import com.example.dispute.workflow.activity.agent.ExecuteAgentRunActivity;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import io.temporal.workflow.Workflow;

public class AgentRunWorkflowImpl implements AgentRunWorkflow {

    private final ExecuteAgentRunActivity activity =
            Workflow.newActivityStub(
                    ExecuteAgentRunActivity.class,
                    AgentRunTemporalPolicy.activityOptions());

    @Override
    public ExecuteAgentRunResult run(ExecuteAgentRunRequest request) {
        return activity.execute(request);
    }
}
