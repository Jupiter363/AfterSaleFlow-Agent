package com.example.dispute.workflow.temporal.room.hearing;

import java.time.Instant;
import java.util.Objects;

/** Immutable, reference-only start payload for one Hearing epoch. */
public record HearingRoomStart(
    String schemaVersion,
    String tenantSurrogate,
    String caseId,
    String roomId,
    long roomEpoch,
    long fencingToken,
    String initiatorParticipantId,
    String respondentParticipantId,
    Instant openedAt,
    Instant hearingDeadlineAt,
    long partyStageWindowSeconds,
    long initialProcessRevision,
    long initialRoomRevision,
    String workflowBuildId) {

  public HearingRoomStart {
    requireExact(schemaVersion, "hearing-room-start.v1", "schemaVersion");
    requireText(tenantSurrogate, "tenantSurrogate");
    requireText(caseId, "caseId");
    requireText(roomId, "roomId");
    requirePositive(roomEpoch, "roomEpoch");
    requirePositive(fencingToken, "fencingToken");
    requireText(initiatorParticipantId, "initiatorParticipantId");
    requireText(respondentParticipantId, "respondentParticipantId");
    if (initiatorParticipantId.equals(respondentParticipantId)) {
      throw new IllegalArgumentException("Hearing participants must be distinct");
    }
    Objects.requireNonNull(openedAt, "openedAt must not be null");
    Objects.requireNonNull(hearingDeadlineAt, "hearingDeadlineAt must not be null");
    if (!hearingDeadlineAt.isAfter(openedAt)) {
      throw new IllegalArgumentException("hearingDeadlineAt must be after openedAt");
    }
    if (partyStageWindowSeconds < 1 || partyStageWindowSeconds > 1_200) {
      throw new IllegalArgumentException("partyStageWindowSeconds must be between 1 and 1200");
    }
    if (initialProcessRevision < 0 || initialRoomRevision < 0) {
      throw new IllegalArgumentException("initial revisions must be non-negative");
    }
    requireText(workflowBuildId, "workflowBuildId");
  }

  private static void requireExact(String value, String expected, String field) {
    if (!expected.equals(value)) {
      throw new IllegalArgumentException(field + " must be " + expected);
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static void requirePositive(long value, String field) {
    if (value < 1) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }
}
