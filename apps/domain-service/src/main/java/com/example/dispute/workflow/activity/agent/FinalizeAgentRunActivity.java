package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Idempotent formal-result commit boundary, deliberately separate from model execution. */
@ActivityInterface
public interface FinalizeAgentRunActivity {

    String ACTIVITY_TYPE = "FinalizeAgentRunActivity";

    @ActivityMethod(name = ACTIVITY_TYPE)
    AgentRunFinalizationReceipt finalizeResult(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result);
}
