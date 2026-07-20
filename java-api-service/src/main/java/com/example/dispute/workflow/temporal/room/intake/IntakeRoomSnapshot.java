package com.example.dispute.workflow.temporal.room.intake;

public record IntakeRoomSnapshot(
    String schemaVersion,
    String tenantSurrogate,
    String caseId,
    long roomEpoch,
    long fencingToken,
    IntakeRoomPhase roomPhase,
    long nextCommandSequence,
    long nextEventSequence,
    long processedCommandCount,
    long processedEventCount,
    boolean initiatorComplete,
    boolean respondentUnlocked,
    boolean respondentComplete,
    IntakeParty readinessParty,
    String pendingCommandId,
    String pendingOperationKey,
    IntakeTerminalReason terminalReason,
    long processRevision,
    long roomRevision,
    String protocolErrorCode) {

  public IntakeRoomSnapshot {
    if (!"intake-room-snapshot.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be intake-room-snapshot.v1");
    }
  }
}
