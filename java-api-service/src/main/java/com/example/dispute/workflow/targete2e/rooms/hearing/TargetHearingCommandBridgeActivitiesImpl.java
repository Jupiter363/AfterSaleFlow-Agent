package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import java.util.Objects;

/** Fails closed on a stale case-process reference before any AgentRun request is released. */
public final class TargetHearingCommandBridgeActivitiesImpl implements TargetHearingCommandBridgeActivities {
  private final TargetHearingCommandMaterialStore store;
  public TargetHearingCommandBridgeActivitiesImpl(TargetHearingCommandMaterialStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }
  @Override public TargetHearingAgentRunTrigger bind(BindRequest request) {
    CaseCommandRef command = request.command();
    if (command.roomType().name().equals("HEARING") == false
        || (command.commandType() != CommandType.HEARING_STATEMENT
            && command.commandType() != CommandType.HEARING_EVIDENCE_BATCH)
        || command.expectedProcessRevision() < 0) {
      throw new IllegalArgumentException("unsupported target Hearing command");
    }
    TargetHearingCommandMaterialStore.Snapshot snapshot = store.readByRoute(
        new TargetHearingCommandMaterialStore.Route(command.tenantSurrogate(), command.caseId(),
            command.commandId(), command.roomEpoch(), request.roomFencingToken()))
        .orElseThrow(() -> new IllegalStateException("target Hearing command material is missing"));
    if (snapshot.admission().roomFencingToken() != request.roomFencingToken()
        || command.expectedProcessRevision() < request.expectedRoomRevision()
        || !command.requestHash().equals(snapshot.material().request().command().requestHash())
        || !command.payloadRef().sha256().equals(snapshot.material().request().command().eventRef().sha256())) {
      throw new IllegalStateException("target Hearing command does not match persisted material");
    }
    return new TargetHearingAgentRunTrigger(TargetHearingAgentRunTrigger.SCHEMA_VERSION, command,
        snapshot.material().request(), request.expectedRoomRevision(), snapshot.materialSha256());
  }
}
