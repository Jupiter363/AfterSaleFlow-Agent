package com.example.dispute.workflow.temporal.room.intake;

public record IntakeRoomSnapshot(
    String schemaVersion,
    String tenantSurrogate,
    String caseId,
    long roomEpoch,
    long fencingToken,
    String initiatorActorScopeHash,
    String respondentActorScopeHash,
    IntakeRoomPhase roomPhase,
    IntakeParty activeParty,
    long nextCommandSequence,
    long nextEventSequence,
    long processedCommandCount,
    long processedEventCount,
    boolean initiatorComplete,
    boolean respondentUnlocked,
    boolean respondentComplete,
    IntakeParty readinessParty,
    IntakePendingCommand pendingCommand,
    String lastEventId,
    String lastEventRef,
    String lastEventHash,
    IntakeAgentRunRef lastAgentRunRef,
    IntakeGraphExecutionRef lastGraphExecutionRef,
    IntakeTerminalReason terminalReason,
    long processRevision,
    long roomRevision,
    String protocolErrorCode,
    int runGeneration,
    IntakeActivityExecutionState activityExecution) {

  public IntakeRoomSnapshot {
    if (!"intake-room-snapshot.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be intake-room-snapshot.v1");
    }
  }

  public String pendingCommandId() {
    return pendingCommand == null ? null : pendingCommand.commandId();
  }

  public String pendingOperationKey() {
    return pendingCommand == null ? null : pendingCommand.operationKey();
  }

  public String rootCorrelationKey() {
    return pendingOperationKey();
  }

  public String currentActivityOperationKey() {
    return activityExecution == null ? null : activityExecution.stageOperationKey();
  }
}
