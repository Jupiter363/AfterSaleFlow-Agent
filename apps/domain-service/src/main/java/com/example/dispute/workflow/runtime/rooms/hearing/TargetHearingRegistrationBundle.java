package com.example.dispute.workflow.runtime.rooms.hearing;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.workflow.runtime.finalization.ProductionRoomFinalizationStrategy;
import java.util.List;
import java.util.Objects;

/** Explicit production-only registration bundle; ordinary runtime scanning must not discover it. */
public record TargetHearingRegistrationBundle(
    TargetHearingCommandBridgeActivities bridgeActivity,
    TargetHearingFormalCompletion formalCompletion,
    ProductionRoomFinalizationStrategy finalizationStrategy,
    AgentRunDomainResultCommitter domainCommitter) {
  public TargetHearingRegistrationBundle {
    bridgeActivity = Objects.requireNonNull(bridgeActivity, "bridgeActivity");
    formalCompletion = Objects.requireNonNull(formalCompletion, "formalCompletion");
    finalizationStrategy = Objects.requireNonNull(finalizationStrategy, "finalizationStrategy");
    domainCommitter = Objects.requireNonNull(domainCommitter, "domainCommitter");
  }
  public List<Object> controlActivities() { return List.of(bridgeActivity); }
}
