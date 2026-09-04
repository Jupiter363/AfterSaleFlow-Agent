package com.example.dispute.workflow.runtime.graph;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;

/** Result-only target port; it cannot execute a model or register a formal writer. */
@FunctionalInterface
public interface ProductionGraphReconciliationClient {

  ProductionGraphResultEnvelope reconcile(
      ProductionSealedGraphCommand command,
      String resultRef,
      String resultHash,
      AgentRunCancellationToken cancellationToken);
}
