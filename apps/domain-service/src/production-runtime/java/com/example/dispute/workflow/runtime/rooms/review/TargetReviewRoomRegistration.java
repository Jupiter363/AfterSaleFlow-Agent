package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.workflow.runtime.finalization.ProductionRoomFinalizationStrategy;
import java.util.List;
import java.util.Objects;

/** Explicit target-artifact bundle; ordinary component scanning must not discover it. */
public record TargetReviewRoomRegistration(TargetReviewCommandMaterialStore materialStore,
    TargetReviewCommandBridgeActivities commandBridge, AgentRunDomainResultCommitter domainCommitter,
    ProductionRoomFinalizationStrategy finalizationStrategy,
    TargetReviewOutcomeHandoffActivities outcomeHandoffRelay) {
  public TargetReviewRoomRegistration {
    Objects.requireNonNull(materialStore, "materialStore");
    Objects.requireNonNull(commandBridge, "commandBridge");
    Objects.requireNonNull(domainCommitter, "domainCommitter");
    Objects.requireNonNull(finalizationStrategy, "finalizationStrategy");
    Objects.requireNonNull(outcomeHandoffRelay, "outcomeHandoffRelay");
  }
  public List<Object> controlActivities() { return List.of(commandBridge, outcomeHandoffRelay); }
}
