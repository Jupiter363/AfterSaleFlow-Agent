package com.example.dispute.workflow.temporal.room.common;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public record RoomControlSnapshot(
    String schemaVersion,
    String tenantSurrogate,
    String caseId,
    RoomType roomType,
    long roomEpoch,
    String workflowRunId,
    int runGeneration,
    long processedCommandCount,
    long processedEventCount,
    int pendingCommandCount,
    int pendingEventCount,
    List<String> recentCommandIds,
    List<String> recentEventIds,
    boolean closeRequested,
    String closeReason,
    String protocolErrorCode,
    String workflowId,
    long fencingToken,
    WriterMode writerMode,
    String selectionSchemaVersion,
    String processContractVersion,
    String workflowType,
    String temporalBuildId,
    String graphKey,
    String graphVersion,
    String checkpointSchemaVersion,
    String streamProtocol,
    String parentWorkflowId,
    String parentWorkflowRunId,
    String provisioningSha256,
    String epochId,
    String roomId,
    long initialProcessRevision,
    long initialRoomRevision,
    String macroPhase,
    String currentRoom,
    String roomPhase,
    Instant projectedDeadlineAt,
    long lastCommandSequence,
    long lastCaseEventSequence,
    long firstCommandSequence,
    long firstCaseEventSequence,
    String projectionRef,
    String projectionSha256,
    Instant requestedAt) {

  public RoomControlSnapshot(
      String schemaVersion,
      String tenantSurrogate,
      String caseId,
      RoomType roomType,
      long roomEpoch,
      String workflowRunId,
      int runGeneration,
      long processedCommandCount,
      long processedEventCount,
      int pendingCommandCount,
      int pendingEventCount,
      List<String> recentCommandIds,
      List<String> recentEventIds,
      boolean closeRequested,
      String closeReason,
      String protocolErrorCode) {
    this(
        schemaVersion,
        tenantSurrogate,
        caseId,
        roomType,
        roomEpoch,
        workflowRunId,
        runGeneration,
        processedCommandCount,
        processedEventCount,
        pendingCommandCount,
        pendingEventCount,
        recentCommandIds,
        recentEventIds,
        closeRequested,
        closeReason,
        protocolErrorCode,
        null,
        0,
        WriterMode.LEGACY,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0,
        0,
        null,
        null,
        null,
        null,
        0,
        0,
        1,
        1,
        null,
        null,
        null);
  }

  public RoomControlSnapshot {
    if (!"room-control-snapshot.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be room-control-snapshot.v1");
    }
    // workflowRunId was added to v1 after the initial rollout. Old pinned workers
    // legitimately return it as null while clients and workers are being upgraded.
    if ((workflowRunId != null && workflowRunId.isBlank())
        || (workflowId != null && workflowId.isBlank())
        || runGeneration < 0
        || fencingToken < 0) {
      throw new IllegalArgumentException("room workflow run identity is invalid");
    }
    if (writerMode == null && fencingToken == 0) {
      writerMode = WriterMode.LEGACY;
    }
    if (fencingToken == 0) {
      selectionSchemaVersion = defaultText(selectionSchemaVersion, "room-epoch-selection.v1");
      processContractVersion = defaultText(processContractVersion, "case-process-contract.v1");
      workflowType = defaultText(workflowType, "LegacyJavaRoomState");
      temporalBuildId = defaultText(temporalBuildId, "legacy-java.v1");
      graphKey =
          defaultText(
              graphKey,
              roomType == null
                  ? "legacy.unknown"
                  : roomType.name().toLowerCase(Locale.ROOT) + ".legacy");
      graphVersion = defaultText(graphVersion, "legacy.v1");
      checkpointSchemaVersion = defaultText(checkpointSchemaVersion, "legacy-checkpoint.v1");
      streamProtocol = defaultText(streamProtocol, "agent_stream.v1");
      epochId = defaultText(epochId, "legacy-room-epoch");
      roomId = defaultText(roomId, "legacy-room");
      macroPhase = defaultText(macroPhase, "LEGACY");
      currentRoom = defaultText(currentRoom, roomType == null ? "LEGACY" : roomType.name());
      roomPhase = defaultText(roomPhase, "LEGACY");
    }
    recentCommandIds = List.copyOf(recentCommandIds);
    recentEventIds = List.copyOf(recentEventIds);
  }

  private static String defaultText(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
