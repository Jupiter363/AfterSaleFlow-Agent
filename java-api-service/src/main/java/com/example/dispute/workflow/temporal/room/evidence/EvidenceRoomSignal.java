package com.example.dispute.workflow.temporal.room.evidence;

import java.time.Instant;
import java.util.Objects;

public record EvidenceRoomSignal(
    String schemaVersion,
    String participantId,
    String completionRequestId,
    String operationKey,
    String requestHash,
    Instant acceptedAt) {

  public EvidenceRoomSignal {
    if (!"evidence-room-party-completion.v1".equals(schemaVersion)
        && !"evidence-room-party-completion.v2".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be evidence-room-party-completion.v1 or v2");
    }
    EvidenceOperationKeys.requireIdentifier(participantId, "participantId");
    EvidenceOperationKeys.requireIdentifier(completionRequestId, "completionRequestId");
    EvidenceOperationKeys.requireValid(operationKey);
    EvidenceOperationKeys.requireHash(requestHash, "requestHash");
    if ("evidence-room-party-completion.v2".equals(schemaVersion)) {
      acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt must not be null for v2");
    } else if (acceptedAt != null) {
      throw new IllegalArgumentException("acceptedAt must be absent for v1");
    }
  }
}
