package com.example.dispute.workflow.temporal.room.hearing;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Bounded query/result projection of process state. */
public record HearingRoomSnapshot(
    String schemaVersion,
    String tenantSurrogate,
    String caseId,
    String roomId,
    long roomEpoch,
    long fencingToken,
    HearingWorkflowStage stage,
    int stageSequence,
    String status,
    Instant stageOpenedAt,
    Instant stageDeadlineAt,
    boolean deadlineReached,
    Map<String, HearingPartyTerminalReceipt.TerminalStatus> partyTerminals,
    List<String> timeoutRequiredParticipantIds,
    List<String> orderedOperationKeys,
    long processRevision,
    long roomRevision,
    long lastCommittedEventSequence,
    long duplicateSignalCount,
    long rejectedSignalCount,
    String protocolErrorCode) {

  public HearingRoomSnapshot {
    partyTerminals = partyTerminals == null ? Map.of() : Map.copyOf(partyTerminals);
    timeoutRequiredParticipantIds =
        timeoutRequiredParticipantIds == null
            ? List.of()
            : List.copyOf(timeoutRequiredParticipantIds);
    orderedOperationKeys =
        orderedOperationKeys == null ? List.of() : List.copyOf(orderedOperationKeys);
  }
}
