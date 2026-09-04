package com.example.dispute.workflow.runtime;

/** Call sites that must independently obtain the deployment-scoped activation grant. */
public enum ActivationScope {
  ROOM_SELECTOR,
  GRAPH_CLIENT,
  AGENT_RUN,
  FINALIZER
}
