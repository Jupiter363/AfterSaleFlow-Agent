package com.example.dispute.workflow.runtime;

/** Reusable deployment-scoped authority; absence of an armed implementation always denies. */
@FunctionalInterface
public interface ProductionActivationAuthority {

  ActivationDecision authorize(ActivationRequest request);

  static ProductionActivationAuthority denyAll() {
    return request -> ActivationDecision.denied(ActivationDecision.Reason.DEFAULT_DENY);
  }
}
