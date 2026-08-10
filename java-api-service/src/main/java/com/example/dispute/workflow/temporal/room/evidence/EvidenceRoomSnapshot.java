package com.example.dispute.workflow.temporal.room.evidence;

import com.example.dispute.workflow.targete2e.temporal.room.TargetRoomAgentRunFinalizationReceipt;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

public record EvidenceRoomSnapshot(
    String schemaVersion,
    String tenantSurrogate,
    String caseId,
    String roomId,
    long roomEpoch,
    long fencingToken,
    EvidenceRoomPhase roomPhase,
    String terminalReason,
    Instant openedAt,
    Instant originalDeadlineAt,
    long deadlineRevision,
    Instant warningAt,
    boolean warningSent,
    Instant warningSentAt,
    boolean deadlineExpired,
    boolean initiatorCompleted,
    boolean respondentCompleted,
    String initiatorCompletionRequestId,
    String respondentCompletionRequestId,
    List<String> orderedOperationKeys,
    String pendingOperationKey,
    long processRevision,
    long roomRevision,
    long duplicateSignalCount,
    long rejectedSignalCount,
    String protocolErrorCode,
    List<TargetRoomAgentRunFinalizationReceipt> agentRunFinalizationReceipts,
    @JsonInclude(JsonInclude.Include.NON_NULL) String projectionRef,
    @JsonInclude(JsonInclude.Include.NON_NULL) String projectionSha256) {

  public static final String LEGACY_SCHEMA_VERSION = "evidence-room-snapshot.v1";
  public static final String FROZEN_SUBMISSION_SCHEMA_VERSION = "evidence-room-snapshot.v2";

  /** Replay-safe constructor for snapshots recorded before frozen projection authority existed. */
  public EvidenceRoomSnapshot(
      String schemaVersion,
      String tenantSurrogate,
      String caseId,
      String roomId,
      long roomEpoch,
      long fencingToken,
      EvidenceRoomPhase roomPhase,
      String terminalReason,
      Instant openedAt,
      Instant originalDeadlineAt,
      long deadlineRevision,
      Instant warningAt,
      boolean warningSent,
      Instant warningSentAt,
      boolean deadlineExpired,
      boolean initiatorCompleted,
      boolean respondentCompleted,
      String initiatorCompletionRequestId,
      String respondentCompletionRequestId,
      List<String> orderedOperationKeys,
      String pendingOperationKey,
      long processRevision,
      long roomRevision,
      long duplicateSignalCount,
      long rejectedSignalCount,
      String protocolErrorCode,
      List<TargetRoomAgentRunFinalizationReceipt> agentRunFinalizationReceipts) {
    this(
        schemaVersion,
        tenantSurrogate,
        caseId,
        roomId,
        roomEpoch,
        fencingToken,
        roomPhase,
        terminalReason,
        openedAt,
        originalDeadlineAt,
        deadlineRevision,
        warningAt,
        warningSent,
        warningSentAt,
        deadlineExpired,
        initiatorCompleted,
        respondentCompleted,
        initiatorCompletionRequestId,
        respondentCompletionRequestId,
        orderedOperationKeys,
        pendingOperationKey,
        processRevision,
        roomRevision,
        duplicateSignalCount,
        rejectedSignalCount,
        protocolErrorCode,
        agentRunFinalizationReceipts,
        null,
        null);
  }

  public EvidenceRoomSnapshot {
    boolean frozenSubmission = FROZEN_SUBMISSION_SCHEMA_VERSION.equals(schemaVersion);
    if (!LEGACY_SCHEMA_VERSION.equals(schemaVersion) && !frozenSubmission) {
      throw new IllegalArgumentException("unsupported Evidence room snapshot schemaVersion");
    }
    orderedOperationKeys = orderedOperationKeys == null ? List.of() : List.copyOf(orderedOperationKeys);
    agentRunFinalizationReceipts =
        agentRunFinalizationReceipts == null ? List.of() : List.copyOf(agentRunFinalizationReceipts);
    if ((projectionRef == null) != (projectionSha256 == null)) {
      throw new IllegalArgumentException(
          "projectionRef and projectionSha256 must both be absent or present");
    }
    if (frozenSubmission != (projectionRef != null)) {
      throw new IllegalArgumentException(
          "Evidence snapshot schema does not match frozen projection authority");
    }
    if (projectionSha256 != null && !projectionSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("projectionSha256 must be lowercase SHA-256");
    }
  }

  public boolean freezeBound() {
    return FROZEN_SUBMISSION_SCHEMA_VERSION.equals(schemaVersion);
  }
}
