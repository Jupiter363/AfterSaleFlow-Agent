package com.example.dispute.workflow.temporal.room.evidence;

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
    String protocolErrorCode) {

  public EvidenceRoomSnapshot {
    if (!"evidence-room-snapshot.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be evidence-room-snapshot.v1");
    }
    orderedOperationKeys = orderedOperationKeys == null ? List.of() : List.copyOf(orderedOperationKeys);
  }
}
