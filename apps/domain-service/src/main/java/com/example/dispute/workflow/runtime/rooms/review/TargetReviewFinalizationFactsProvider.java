package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.agentstream.application.AgentRunV2ManifestFactory;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;

/** Loads persisted AgentRun and immutable-output facts for the Review manifest. */
@FunctionalInterface
public interface TargetReviewFinalizationFactsProvider {
  AgentRunV2ManifestFactory.FinalizationFacts create(
      TargetReviewFinalizationRequest finalization, ExecuteAgentRunRequest request,
      ExecuteAgentRunResult result);
}
