package com.example.dispute.workflow.runtime.rooms.evidence;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.workflow.runtime.finalization.ProductionRoomFinalizationStrategy;
import java.util.List;
import java.util.Objects;

/** Small target-artifact registration bundle consumed by the isolated worker configuration. */
public record TargetEvidenceRoomRegistration(
    TargetEvidenceCommandMaterialStore materialStore,
    TargetEvidenceCommandBridgeActivities commandBridge,
    AgentRunDomainResultCommitter domainCommitter,
    ProductionRoomFinalizationStrategy finalizationStrategy) {
  public TargetEvidenceRoomRegistration {
    Objects.requireNonNull(materialStore, "materialStore");
    Objects.requireNonNull(commandBridge, "commandBridge");
    Objects.requireNonNull(domainCommitter, "domainCommitter");
    Objects.requireNonNull(finalizationStrategy, "finalizationStrategy");
  }

  public List<Object> controlActivities() {
    return List.of(commandBridge);
  }
}
