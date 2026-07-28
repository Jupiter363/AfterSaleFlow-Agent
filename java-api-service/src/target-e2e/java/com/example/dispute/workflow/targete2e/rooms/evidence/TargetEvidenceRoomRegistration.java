package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.agentstream.application.AgentRunDomainResultCommitter;
import com.example.dispute.workflow.targete2e.finalization.TargetE2eRoomFinalizationStrategy;
import java.util.List;
import java.util.Objects;

/** Small target-artifact registration bundle consumed by the isolated worker configuration. */
public record TargetEvidenceRoomRegistration(
    TargetEvidenceCommandMaterialStore materialStore,
    TargetEvidenceCommandBridgeActivities commandBridge,
    AgentRunDomainResultCommitter domainCommitter,
    TargetE2eRoomFinalizationStrategy finalizationStrategy) {
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
