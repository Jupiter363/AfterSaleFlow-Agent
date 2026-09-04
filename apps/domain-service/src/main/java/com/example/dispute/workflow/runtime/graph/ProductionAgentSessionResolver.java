package com.example.dispute.workflow.runtime.graph;

import com.example.dispute.workflow.contract.v1.RoomGraphCommand;

/** Resolves the Java-owned AgentSession bound to one immutable target command. */
@FunctionalInterface
public interface ProductionAgentSessionResolver {

  String resolve(RoomGraphCommand command);
}
