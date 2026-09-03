package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.contract.v1.RoomGraphCommand;

/** Resolves the Java-owned AgentSession bound to one immutable target command. */
@FunctionalInterface
public interface TargetE2EAgentSessionResolver {

  String resolve(RoomGraphCommand command);
}
