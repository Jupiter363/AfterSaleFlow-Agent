package com.example.dispute.agentstream.application;

import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;

/** Resolves runtime facts that are intentionally absent from the graph result contract. */
@FunctionalInterface
public interface AgentRunV2FinalizationFactsProvider {

    AgentRunV2ManifestFactory.FinalizationFacts resolve(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result);
}
