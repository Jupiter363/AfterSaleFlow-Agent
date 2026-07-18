package com.example.dispute.workflow.activity.agent;

import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;

/** Transactional Java-domain boundary below the Temporal Finalizer Activity. */
@FunctionalInterface
public interface AgentRunFinalizationGateway {

    AgentRunFinalizationReceipt finalizeResult(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result);
}
