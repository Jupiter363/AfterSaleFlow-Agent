package com.example.dispute.workflow.runtime.graph;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Candidate-only proposal execution port, deliberately unrelated to AgentGraphCommandClient. */
@FunctionalInterface
public interface ProductionGraphProposalClient {

  ProductionGraphResultEnvelope execute(
      ProductionSealedGraphCommand command,
      Map<String, Set<String>> visibleFieldsByNode,
      Consumer<AgentStreamEvent> eventSink,
      AgentRunCancellationToken cancellationToken);
}
