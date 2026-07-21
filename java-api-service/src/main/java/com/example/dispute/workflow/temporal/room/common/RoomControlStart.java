package com.example.dispute.workflow.temporal.room.common;

import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Pattern;

public record RoomControlStart(
    String schemaVersion,
    String tenantSurrogate,
    String caseId,
    String epochId,
    String roomId,
    RoomType roomType,
    long roomEpoch,
    String parentWorkflowId,
    String roomWorkflowId,
    long firstCommandSequence,
    long firstCaseEventSequence,
    long fencingToken,
    long initialProcessRevision,
    long initialRoomRevision,
    String macroPhase,
    String currentRoom,
    String roomPhase,
    Instant projectedDeadlineAt,
    WriterMode writerMode,
    String selectionSchemaVersion,
    String processContractVersion,
    String workflowType,
    String temporalBuildId,
    @JsonInclude(JsonInclude.Include.NON_NULL) String roomWorkflowType,
    @JsonInclude(JsonInclude.Include.NON_NULL) String roomWorkflowBuildId,
    String graphKey,
    String graphVersion,
    String checkpointSchemaVersion,
    String streamProtocol,
    long lastCommandSequence,
    long lastCaseEventSequence,
    String projectionRef,
    String projectionSha256,
    Instant requestedAt,
    String parentWorkflowRunId,
    String provisioningSha256,
    RoomControlCarryState carryState) {

  private static final Pattern STATE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public RoomControlStart(
      String schemaVersion,
      String tenantSurrogate,
      String caseId,
      RoomType roomType,
      long roomEpoch,
      String parentWorkflowId,
      long firstCommandSequence,
      long firstCaseEventSequence) {
    this(
        schemaVersion,
        tenantSurrogate,
        caseId,
        legacyEpochId(roomType, roomEpoch),
        legacyRoomId(roomType, roomEpoch),
        roomType,
        roomEpoch,
        parentWorkflowId,
        CaseProcessWorkflowProtocol.roomWorkflowId(caseId, roomType, roomEpoch),
        firstCommandSequence,
        firstCaseEventSequence,
        0,
        0,
        0,
        "LEGACY",
        roomType.name(),
        "LEGACY",
        null,
        WriterMode.LEGACY,
        "room-epoch-selection.v1",
        "case-process-contract.v1",
        "LegacyJavaRoomState",
        "legacy-java.v1",
        null,
        null,
        roomType.name().toLowerCase(Locale.ROOT) + ".legacy",
        "legacy.v1",
        "legacy-checkpoint.v1",
        "agent_stream.v1",
        firstCommandSequence - 1,
        firstCaseEventSequence - 1,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public RoomControlStart(
      String schemaVersion,
      String tenantSurrogate,
      String caseId,
      RoomType roomType,
      long roomEpoch,
      String parentWorkflowId,
      long firstCommandSequence,
      long firstCaseEventSequence,
      RoomControlCarryState carryState) {
    this(
        schemaVersion,
        tenantSurrogate,
        caseId,
        legacyEpochId(roomType, roomEpoch),
        legacyRoomId(roomType, roomEpoch),
        roomType,
        roomEpoch,
        parentWorkflowId,
        CaseProcessWorkflowProtocol.roomWorkflowId(caseId, roomType, roomEpoch),
        firstCommandSequence,
        firstCaseEventSequence,
        0,
        0,
        0,
        "LEGACY",
        roomType.name(),
        "LEGACY",
        null,
        WriterMode.LEGACY,
        "room-epoch-selection.v1",
        "case-process-contract.v1",
        "LegacyJavaRoomState",
        "legacy-java.v1",
        null,
        null,
        roomType.name().toLowerCase(Locale.ROOT) + ".legacy",
        "legacy.v1",
        "legacy-checkpoint.v1",
        "agent_stream.v1",
        firstCommandSequence - 1,
        firstCaseEventSequence - 1,
        null,
        null,
        null,
        null,
        null,
        carryState);
  }

  public RoomControlStart(
      String schemaVersion,
      String tenantSurrogate,
      String caseId,
      String epochId,
      String roomId,
      RoomType roomType,
      long roomEpoch,
      String parentWorkflowId,
      String roomWorkflowId,
      long firstCommandSequence,
      long firstCaseEventSequence,
      long fencingToken,
      long initialProcessRevision,
      long initialRoomRevision,
      String macroPhase,
      String currentRoom,
      String roomPhase,
      Instant projectedDeadlineAt,
      WriterMode writerMode,
      String selectionSchemaVersion,
      String processContractVersion,
      String workflowType,
      String temporalBuildId,
      String graphKey,
      String graphVersion,
      String checkpointSchemaVersion,
      String streamProtocol,
      long lastCommandSequence,
      long lastCaseEventSequence,
      String projectionRef,
      String projectionSha256,
      Instant requestedAt,
      String parentWorkflowRunId,
      String provisioningSha256) {
    this(
        schemaVersion,
        tenantSurrogate,
        caseId,
        epochId,
        roomId,
        roomType,
        roomEpoch,
        parentWorkflowId,
        roomWorkflowId,
        firstCommandSequence,
        firstCaseEventSequence,
        fencingToken,
        initialProcessRevision,
        initialRoomRevision,
        macroPhase,
        currentRoom,
        roomPhase,
        projectedDeadlineAt,
        writerMode,
        selectionSchemaVersion,
        processContractVersion,
        workflowType,
        temporalBuildId,
        null,
        null,
        graphKey,
        graphVersion,
        checkpointSchemaVersion,
        streamProtocol,
        lastCommandSequence,
        lastCaseEventSequence,
        projectionRef,
        projectionSha256,
        requestedAt,
        parentWorkflowRunId,
        provisioningSha256,
        null);
  }

  public RoomControlStart(
      String schemaVersion,
      String tenantSurrogate,
      String caseId,
      String epochId,
      String roomId,
      RoomType roomType,
      long roomEpoch,
      String parentWorkflowId,
      String roomWorkflowId,
      long firstCommandSequence,
      long firstCaseEventSequence,
      long fencingToken,
      long initialProcessRevision,
      long initialRoomRevision,
      String macroPhase,
      String currentRoom,
      String roomPhase,
      Instant projectedDeadlineAt,
      WriterMode writerMode,
      String selectionSchemaVersion,
      String processContractVersion,
      String workflowType,
      String temporalBuildId,
      String roomWorkflowType,
      String roomWorkflowBuildId,
      String graphKey,
      String graphVersion,
      String checkpointSchemaVersion,
      String streamProtocol,
      long lastCommandSequence,
      long lastCaseEventSequence,
      String projectionRef,
      String projectionSha256,
      Instant requestedAt,
      String parentWorkflowRunId,
      String provisioningSha256) {
    this(
        schemaVersion,
        tenantSurrogate,
        caseId,
        epochId,
        roomId,
        roomType,
        roomEpoch,
        parentWorkflowId,
        roomWorkflowId,
        firstCommandSequence,
        firstCaseEventSequence,
        fencingToken,
        initialProcessRevision,
        initialRoomRevision,
        macroPhase,
        currentRoom,
        roomPhase,
        projectedDeadlineAt,
        writerMode,
        selectionSchemaVersion,
        processContractVersion,
        workflowType,
        temporalBuildId,
        roomWorkflowType,
        roomWorkflowBuildId,
        graphKey,
        graphVersion,
        checkpointSchemaVersion,
        streamProtocol,
        lastCommandSequence,
        lastCaseEventSequence,
        projectionRef,
        projectionSha256,
        requestedAt,
        parentWorkflowRunId,
        provisioningSha256,
        null);
  }

  public RoomControlStart {
    if (!"room-control-start.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be room-control-start.v1");
    }
    requireText(tenantSurrogate, "tenantSurrogate");
    requireText(caseId, "caseId");
    if (roomType == null) {
      throw new IllegalArgumentException("roomType must not be null");
    }
    if (roomEpoch < 0) {
      throw new IllegalArgumentException("roomEpoch must not be negative");
    }
    requireText(parentWorkflowId, "parentWorkflowId");
    if (fencingToken == 0) {
      epochId = defaultText(epochId, legacyEpochId(roomType, roomEpoch));
      roomId = defaultText(roomId, legacyRoomId(roomType, roomEpoch));
      roomWorkflowId =
          defaultText(
              roomWorkflowId,
              CaseProcessWorkflowProtocol.roomWorkflowId(caseId, roomType, roomEpoch));
      macroPhase = defaultText(macroPhase, "LEGACY");
      currentRoom = defaultText(currentRoom, roomType.name());
      roomPhase = defaultText(roomPhase, "LEGACY");
      writerMode = writerMode == null ? WriterMode.LEGACY : writerMode;
      selectionSchemaVersion = defaultText(selectionSchemaVersion, "room-epoch-selection.v1");
      processContractVersion = defaultText(processContractVersion, "case-process-contract.v1");
      workflowType = defaultText(workflowType, "LegacyJavaRoomState");
      temporalBuildId = defaultText(temporalBuildId, "legacy-java.v1");
      graphKey = defaultText(graphKey, roomType.name().toLowerCase(Locale.ROOT) + ".legacy");
      graphVersion = defaultText(graphVersion, "legacy.v1");
      checkpointSchemaVersion = defaultText(checkpointSchemaVersion, "legacy-checkpoint.v1");
      streamProtocol = defaultText(streamProtocol, "agent_stream.v1");
      if (firstCommandSequence > 0 && lastCommandSequence == 0) {
        lastCommandSequence = firstCommandSequence - 1;
      }
      if (firstCaseEventSequence > 0 && lastCaseEventSequence == 0) {
        lastCaseEventSequence = firstCaseEventSequence - 1;
      }
    }
    requireText(epochId, "epochId");
    requireText(roomId, "roomId");
    requireText(roomWorkflowId, "roomWorkflowId");
    if (!roomWorkflowId.equals(
        CaseProcessWorkflowProtocol.roomWorkflowId(caseId, roomType, roomEpoch))) {
      throw new IllegalArgumentException("roomWorkflowId does not match room scope");
    }
    if (initialProcessRevision < 0 || initialRoomRevision < 0) {
      throw new IllegalArgumentException("initial revisions must not be negative");
    }
    requireState(macroPhase, "macroPhase");
    requireState(currentRoom, "currentRoom");
    requireState(roomPhase, "roomPhase");
    if (projectedDeadlineAt != null) {
      projectedDeadlineAt = projectedDeadlineAt.truncatedTo(ChronoUnit.MICROS);
    }
    if (lastCommandSequence < 0
        || lastCaseEventSequence < 0
        || lastCommandSequence == Long.MAX_VALUE
        || lastCaseEventSequence == Long.MAX_VALUE
        || firstCommandSequence != lastCommandSequence + 1
        || firstCaseEventSequence != lastCaseEventSequence + 1) {
      throw new IllegalArgumentException("room sequence boundary is invalid");
    }
    if (fencingToken < 0) {
      throw new IllegalArgumentException("fencingToken must not be negative");
    }
    if (writerMode == null
        || (fencingToken == 0 && writerMode != WriterMode.LEGACY)
        || (fencingToken > 0 && writerMode == WriterMode.LEGACY)) {
      throw new IllegalArgumentException("writerMode does not match the fencing mode");
    }
    requireText(selectionSchemaVersion, "selectionSchemaVersion");
    requireText(processContractVersion, "processContractVersion");
    requireText(workflowType, "workflowType");
    requireText(temporalBuildId, "temporalBuildId");
    requireSelectionBinding(
        selectionSchemaVersion,
        roomType,
        writerMode,
        workflowType,
        roomWorkflowType,
        roomWorkflowBuildId);
    requireText(graphKey, "graphKey");
    requireText(graphVersion, "graphVersion");
    requireText(checkpointSchemaVersion, "checkpointSchemaVersion");
    requireText(streamProtocol, "streamProtocol");
    requireReference(projectionRef, projectionSha256);
    if (fencingToken > 0) {
      if (requestedAt == null) {
        throw new IllegalArgumentException("requestedAt must not be null");
      }
      requestedAt = requestedAt.truncatedTo(ChronoUnit.MICROS);
      requireText(parentWorkflowRunId, "parentWorkflowRunId");
      if (provisioningSha256 == null || !provisioningSha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("provisioningSha256 is invalid");
      }
    }
  }

  public RoomControlStart withCarryState(RoomControlCarryState carryState) {
    return new RoomControlStart(
        schemaVersion,
        tenantSurrogate,
        caseId,
        epochId,
        roomId,
        roomType,
        roomEpoch,
        parentWorkflowId,
        roomWorkflowId,
        firstCommandSequence,
        firstCaseEventSequence,
        fencingToken,
        initialProcessRevision,
        initialRoomRevision,
        macroPhase,
        currentRoom,
        roomPhase,
        projectedDeadlineAt,
        writerMode,
        selectionSchemaVersion,
        processContractVersion,
        workflowType,
        temporalBuildId,
        roomWorkflowType,
        roomWorkflowBuildId,
        graphKey,
        graphVersion,
        checkpointSchemaVersion,
        streamProtocol,
        lastCommandSequence,
        lastCaseEventSequence,
        projectionRef,
        projectionSha256,
        requestedAt,
        parentWorkflowRunId,
        provisioningSha256,
        carryState);
  }

  private static void requireSelectionBinding(
      String selectionSchemaVersion,
      RoomType roomType,
      WriterMode writerMode,
      String caseWorkflowType,
      String roomWorkflowType,
      String roomWorkflowBuildId) {
    if ("room-epoch-selection.v1".equals(selectionSchemaVersion)) {
      if (roomWorkflowType != null || roomWorkflowBuildId != null) {
        throw new IllegalArgumentException(
            "v1 room start cannot contain a room Workflow binding");
      }
      return;
    }
    if (!"room-epoch-selection.v2".equals(selectionSchemaVersion)) {
      throw new IllegalArgumentException("unsupported selectionSchemaVersion");
    }
    requireText(roomWorkflowType, 128, "roomWorkflowType");
    requireText(roomWorkflowBuildId, 128, "roomWorkflowBuildId");
    if (!CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE.equals(caseWorkflowType)) {
      throw new IllegalArgumentException(
          "v2 room start requires the CaseProcessWorkflow case binding");
    }
    if (writerMode != WriterMode.LEGACY
        && (roomType != RoomType.INTAKE || !"IntakeRoomWorkflow".equals(roomWorkflowType))) {
      throw new IllegalArgumentException(
          "non-LEGACY v2 room start requires the IntakeRoomWorkflow binding");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static void requireText(String value, int maxLength, String field) {
    if (value == null || value.isBlank() || value.length() > maxLength) {
      throw new IllegalArgumentException(field + " is invalid");
    }
  }

  private static void requireState(String value, String field) {
    if (value == null || !STATE.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " is invalid");
    }
  }

  private static void requireReference(String uri, String hash) {
    if (uri == null && hash == null) {
      return;
    }
    if (uri == null
        || uri.length() > 1024
        || !(uri.startsWith("s3:") || uri.startsWith("minio:") || uri.startsWith("urn:"))
        || hash == null
        || !SHA256.matcher(hash).matches()) {
      throw new IllegalArgumentException("projection reference is invalid");
    }
  }

  private static String legacyEpochId(RoomType roomType, long roomEpoch) {
    return "legacy-" + roomType.name().toLowerCase(Locale.ROOT) + "-" + roomEpoch;
  }

  private static String legacyRoomId(RoomType roomType, long roomEpoch) {
    return "legacy-" + roomType.name().toLowerCase(Locale.ROOT) + "-" + roomEpoch;
  }

  private static String defaultText(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
