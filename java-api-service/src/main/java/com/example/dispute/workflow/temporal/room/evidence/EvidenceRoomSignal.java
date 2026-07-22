package com.example.dispute.workflow.temporal.room.evidence;

public record EvidenceRoomSignal(
    String schemaVersion,
    String participantId,
    String completionRequestId,
    String operationKey,
    String requestHash) {

  public EvidenceRoomSignal {
    if (!"evidence-room-party-completion.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be evidence-room-party-completion.v1");
    }
    EvidenceOperationKeys.requireIdentifier(participantId, "participantId");
    EvidenceOperationKeys.requireIdentifier(completionRequestId, "completionRequestId");
    EvidenceOperationKeys.requireValid(operationKey);
    EvidenceOperationKeys.requireHash(requestHash, "requestHash");
  }
}
