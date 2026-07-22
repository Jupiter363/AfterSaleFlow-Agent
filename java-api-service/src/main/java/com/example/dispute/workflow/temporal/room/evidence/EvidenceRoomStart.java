package com.example.dispute.workflow.temporal.room.evidence;

import java.time.Instant;
import java.util.Objects;

public record EvidenceRoomStart(
    String schemaVersion,
    String tenantSurrogate,
    String caseId,
    String roomId,
    long roomEpoch,
    long fencingToken,
    String initiatorParticipantId,
    String respondentParticipantId,
    Instant openedAt,
    Instant originalDeadlineAt,
    long deadlineRevision,
    long initialProcessRevision,
    long initialRoomRevision,
    String workflowBuildId) {

  public EvidenceRoomStart {
    if (!"evidence-room-start.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be evidence-room-start.v1");
    }
    EvidenceOperationKeys.requireIdentifier(tenantSurrogate, "tenantSurrogate");
    EvidenceOperationKeys.requireIdentifier(caseId, "caseId");
    EvidenceOperationKeys.requireIdentifier(roomId, "roomId");
    EvidenceOperationKeys.requireIdentifier(initiatorParticipantId, "initiatorParticipantId");
    EvidenceOperationKeys.requireIdentifier(respondentParticipantId, "respondentParticipantId");
    EvidenceOperationKeys.requireIdentifier(workflowBuildId, "workflowBuildId");
    if (initiatorParticipantId.equals(respondentParticipantId)) {
      throw new IllegalArgumentException("party participant IDs must be distinct");
    }
    if (roomEpoch < 0 || fencingToken < 1 || deadlineRevision < 1) {
      throw new IllegalArgumentException("epoch, fence, and deadline revision must be valid");
    }
    if (initialProcessRevision < 0 || initialRoomRevision < 0) {
      throw new IllegalArgumentException("initial revisions must not be negative");
    }
    Objects.requireNonNull(openedAt, "openedAt must not be null");
    Objects.requireNonNull(originalDeadlineAt, "originalDeadlineAt must not be null");
    if (!originalDeadlineAt.isAfter(openedAt)) {
      throw new IllegalArgumentException("originalDeadlineAt must be after openedAt");
    }
  }
}
