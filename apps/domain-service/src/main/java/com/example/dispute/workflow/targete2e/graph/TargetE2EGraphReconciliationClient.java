package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;

/** Result-only target port; it cannot execute a model or register a formal writer. */
@FunctionalInterface
public interface TargetE2EGraphReconciliationClient {

  TargetE2EGraphResultEnvelope reconcile(
      TargetE2ESealedGraphCommand command,
      String resultRef,
      String resultHash,
      AgentRunCancellationToken cancellationToken);
}
