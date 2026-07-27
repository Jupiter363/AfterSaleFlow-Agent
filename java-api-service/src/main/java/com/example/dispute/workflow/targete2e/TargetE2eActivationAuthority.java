package com.example.dispute.workflow.targete2e;

/** Reusable deployment-scoped authority; absence of an armed implementation always denies. */
@FunctionalInterface
public interface TargetE2eActivationAuthority {

  ActivationDecision authorize(ActivationRequest request);

  static TargetE2eActivationAuthority denyAll() {
    return request -> ActivationDecision.denied(ActivationDecision.Reason.DEFAULT_DENY);
  }
}
