package com.example.dispute.workflow.temporal.room.evidence;

import com.example.dispute.workflow.contract.v1.FrozenIntakeSubmissionAuthority;
import com.fasterxml.jackson.annotation.JsonInclude;
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
    String workflowBuildId,
    @JsonInclude(
        value = JsonInclude.Include.CUSTOM,
        valueFilter = LegacyExecutionLaneFilter.class)
    ExecutionLane executionLane,
    @JsonInclude(JsonInclude.Include.NON_NULL) String projectionRef,
    @JsonInclude(JsonInclude.Include.NON_NULL) String projectionSha256) {

  public static final String LEGACY_SCHEMA_VERSION = "evidence-room-start.v1";
  public static final String FROZEN_SUBMISSION_SCHEMA_VERSION = "evidence-room-start.v2";

  public enum ExecutionLane {
    LEGACY,
    TARGET_E2E_CANDIDATE
  }

  /** Omits LEGACY from JSON so pre-field Workflow and Activity payload bytes remain stable. */
  public static final class LegacyExecutionLaneFilter {
    @Override
    public boolean equals(Object value) {
      return value == ExecutionLane.LEGACY;
    }

    @Override
    public int hashCode() {
      return ExecutionLane.LEGACY.hashCode();
    }
  }

  /** Replay-safe constructor for v1 histories and callers recorded before executionLane existed. */
  public EvidenceRoomStart(
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
    this(
        schemaVersion,
        tenantSurrogate,
        caseId,
        roomId,
        roomEpoch,
        fencingToken,
        initiatorParticipantId,
        respondentParticipantId,
        openedAt,
        originalDeadlineAt,
        deadlineRevision,
        initialProcessRevision,
        initialRoomRevision,
        workflowBuildId,
        ExecutionLane.LEGACY,
        null,
        null);
  }

  /** Replay-safe constructor for histories recorded before frozen projection authority existed. */
  public EvidenceRoomStart(
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
      String workflowBuildId,
      ExecutionLane executionLane) {
    this(
        schemaVersion,
        tenantSurrogate,
        caseId,
        roomId,
        roomEpoch,
        fencingToken,
        initiatorParticipantId,
        respondentParticipantId,
        openedAt,
        originalDeadlineAt,
        deadlineRevision,
        initialProcessRevision,
        initialRoomRevision,
        workflowBuildId,
        executionLane,
        null,
        null);
  }

  public EvidenceRoomStart {
    boolean frozenSubmission = FROZEN_SUBMISSION_SCHEMA_VERSION.equals(schemaVersion);
    if (!LEGACY_SCHEMA_VERSION.equals(schemaVersion) && !frozenSubmission) {
      throw new IllegalArgumentException("unsupported Evidence room start schemaVersion");
    }
    EvidenceOperationKeys.requireIdentifier(tenantSurrogate, "tenantSurrogate");
    EvidenceOperationKeys.requireIdentifier(caseId, "caseId");
    EvidenceOperationKeys.requireIdentifier(roomId, "roomId");
    EvidenceOperationKeys.requireIdentifier(initiatorParticipantId, "initiatorParticipantId");
    EvidenceOperationKeys.requireIdentifier(respondentParticipantId, "respondentParticipantId");
    EvidenceOperationKeys.requireIdentifier(workflowBuildId, "workflowBuildId");
    executionLane = executionLane == null ? ExecutionLane.LEGACY : executionLane;
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
    if ((projectionRef == null) != (projectionSha256 == null)) {
      throw new IllegalArgumentException(
          "projectionRef and projectionSha256 must both be absent or present");
    }
    if (!frozenSubmission && projectionRef != null) {
      throw new IllegalArgumentException(
          "legacy Evidence room start cannot contain frozen projection authority");
    }
    if (frozenSubmission) {
      if (executionLane != ExecutionLane.TARGET_E2E_CANDIDATE
          || projectionRef == null
          || projectionRef.isBlank()
          || projectionRef.length() > 1024
          || !projectionRef.endsWith(
              "#" + FrozenIntakeSubmissionAuthority.FROZEN_MATRIX_RESULT_POINTER)
          || !projectionSha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException(
            "freeze-bound Evidence room start authority is invalid");
      }
    }
  }

  public boolean targetE2eCandidate() {
    return executionLane == ExecutionLane.TARGET_E2E_CANDIDATE;
  }

  /** Legacy target marker retained only behind the Workflow version gate for old histories. */
  public boolean legacyTargetBuildMarker() {
    return workflowBuildId.startsWith("target-e2e");
  }

  public boolean freezeBound() {
    return FROZEN_SUBMISSION_SCHEMA_VERSION.equals(schemaVersion);
  }
}
