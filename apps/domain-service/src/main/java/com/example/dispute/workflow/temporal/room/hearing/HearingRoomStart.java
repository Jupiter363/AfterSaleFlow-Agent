package com.example.dispute.workflow.temporal.room.hearing;

import com.example.dispute.hearing.domain.HearingWriterMode;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, reference-only start payload for one Hearing epoch. */
public record HearingRoomStart(
    String schemaVersion,
    String tenantSurrogate,
    String caseId,
    String roomId,
    String flowInstanceId,
    String epochId,
    HearingWriterMode writerMode,
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

  private static final Pattern IDENTIFIER =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern OPERATION_COMPONENT =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  public HearingRoomStart {
    requireExact(schemaVersion, "hearing-room-start.v1", "schemaVersion");
    requireOperationComponent(tenantSurrogate, "tenantSurrogate");
    requireOperationComponent(caseId, "caseId");
    requireIdentifier(roomId, "roomId");
    requireIdentifier(flowInstanceId, "flowInstanceId");
    requireIdentifier(epochId, "epochId");
    Objects.requireNonNull(writerMode, "writerMode must not be null");
    if (writerMode != HearingWriterMode.TEMPORAL) {
      throw new IllegalArgumentException("HearingRoomWorkflow accepts only a pinned TEMPORAL epoch");
    }
    requireNonNegative(roomEpoch, "roomEpoch");
    requirePositive(fencingToken, "fencingToken");
    requireOperationComponent(initiatorParticipantId, "initiatorParticipantId");
    requireOperationComponent(respondentParticipantId, "respondentParticipantId");
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
    if (initialProcessRevision < 0
        || initialRoomRevision < 0
        || initialProcessRevision > MAX_SAFE_INTEGER
        || initialRoomRevision > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("initial revisions must be safe non-negative integers");
    }
    requireIdentifier(workflowBuildId, "workflowBuildId");
  }

  private static void requireExact(String value, String expected, String field) {
    if (!expected.equals(value)) {
      throw new IllegalArgumentException(field + " must be " + expected);
    }
  }

  private static void requireIdentifier(String value, String field) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a bounded identifier");
    }
  }

  private static void requireOperationComponent(String value, String field) {
    if (value == null || !OPERATION_COMPONENT.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a bounded operation-key component");
    }
  }

  private static void requirePositive(long value, String field) {
    if (value < 1 || value > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException(field + " must be a positive safe integer");
    }
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0 || value > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException(field + " must be a non-negative safe integer");
    }
  }
}
