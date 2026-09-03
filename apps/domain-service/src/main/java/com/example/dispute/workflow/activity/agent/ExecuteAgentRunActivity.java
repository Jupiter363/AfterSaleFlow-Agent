package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Temporal execution boundary for one stable AgentRun V2 attempt. */
@ActivityInterface
public interface ExecuteAgentRunActivity {

    String ACTIVITY_TYPE = "ExecuteAgentRunActivity";

    @ActivityMethod(name = ACTIVITY_TYPE)
    ExecuteAgentRunResult execute(ExecuteAgentRunRequest request);
}
