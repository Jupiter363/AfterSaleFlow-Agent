package com.example.dispute.workflow.targete2e;

/** Call sites that must independently obtain the deployment-scoped activation grant. */
public enum ActivationScope {
  ROOM_SELECTOR,
  GRAPH_CLIENT,
  AGENT_RUN,
  FINALIZER
}
