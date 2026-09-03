package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Candidate-only proposal execution port, deliberately unrelated to AgentGraphCommandClient. */
@FunctionalInterface
public interface TargetE2EGraphProposalClient {

  TargetE2EGraphResultEnvelope execute(
      TargetE2ESealedGraphCommand command,
      Map<String, Set<String>> visibleFieldsByNode,
      Consumer<AgentStreamEvent> eventSink,
      AgentRunCancellationToken cancellationToken);
}
