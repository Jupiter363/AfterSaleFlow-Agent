package com.example.dispute.workflow.contract.v1;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public record ProvisionRoomEpoch(
    String schemaVersion,
    String epochId,
    String tenantSurrogate,
    String caseId,
    String roomId,
    RoomType roomType,
    long roomEpoch,
    long initialProcessRevision,
    long initialRoomRevision,
    long fencingToken,
    String macroPhase,
    String currentRoom,
    String roomPhase,
    WriterMode writerMode,
    String caseWorkflowId,
    String roomWorkflowId,
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
    long firstCommandSequence,
    long firstCaseEventSequence,
    Instant projectedDeadlineAt,
    String projectionRef,
    String projectionSha256,
    Instant requestedAt) {

  public static final String SCHEMA_VERSION = "provision-room-epoch.v1";
  private static final Pattern STATE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public ProvisionRoomEpoch(
      String schemaVersion,
      String epochId,
      String tenantSurrogate,
      String caseId,
      String roomId,
      RoomType roomType,
      long roomEpoch,
      long initialProcessRevision,
      long initialRoomRevision,
      long fencingToken,
      String macroPhase,
      String currentRoom,
      String roomPhase,
      WriterMode writerMode,
      String caseWorkflowId,
      String roomWorkflowId,
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
      long firstCommandSequence,
      long firstCaseEventSequence,
      Instant projectedDeadlineAt,
      String projectionRef,
      String projectionSha256,
      Instant requestedAt) {
    this(
        schemaVersion,
        epochId,
        tenantSurrogate,
        caseId,
        roomId,
        roomType,
        roomEpoch,
        initialProcessRevision,
        initialRoomRevision,
        fencingToken,
        macroPhase,
        currentRoom,
        roomPhase,
        writerMode,
        caseWorkflowId,
        roomWorkflowId,
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
        firstCommandSequence,
        firstCaseEventSequence,
        projectedDeadlineAt,
        projectionRef,
        projectionSha256,
        requestedAt);
  }

  public ProvisionRoomEpoch {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
    }
    requireText(epochId, 64, "epochId");
    requireText(tenantSurrogate, 128, "tenantSurrogate");
    requireText(caseId, 64, "caseId");
    requireText(roomId, 64, "roomId");
    Objects.requireNonNull(roomType, "roomType must not be null");
    if (roomEpoch < 0
        || initialProcessRevision < 0
        || initialRoomRevision < 0
        || fencingToken < 1) {
      throw new IllegalArgumentException("room epoch revisions or fencing token are invalid");
    }
    requireState(macroPhase, "macroPhase");
    requireState(currentRoom, "currentRoom");
    requireState(roomPhase, "roomPhase");
    if (writerMode != WriterMode.SHADOW && writerMode != WriterMode.TEMPORAL) {
      throw new IllegalArgumentException("bootstrap requires SHADOW or TEMPORAL writer mode");
    }
    requireText(caseWorkflowId, 128, "caseWorkflowId");
    requireText(roomWorkflowId, 128, "roomWorkflowId");
    requireText(selectionSchemaVersion, 64, "selectionSchemaVersion");
    requireText(processContractVersion, 64, "processContractVersion");
    requireText(workflowType, 128, "workflowType");
    requireText(temporalBuildId, 128, "temporalBuildId");
    requireSelectionBinding(
        selectionSchemaVersion,
        roomType,
        writerMode,
        workflowType,
        roomWorkflowType,
        roomWorkflowBuildId);
    requireText(graphKey, 128, "graphKey");
    requireText(graphVersion, 128, "graphVersion");
    requireText(checkpointSchemaVersion, 128, "checkpointSchemaVersion");
    requireText(streamProtocol, 64, "streamProtocol");
    if (lastCommandSequence < 0
        || lastCaseEventSequence < 0
        || lastCommandSequence == Long.MAX_VALUE
        || lastCaseEventSequence == Long.MAX_VALUE
        || firstCommandSequence != lastCommandSequence + 1
        || firstCaseEventSequence != lastCaseEventSequence + 1) {
      throw new IllegalArgumentException(
          "first sequences must be exactly one greater than last sequences");
    }
    if (projectedDeadlineAt != null) {
      projectedDeadlineAt = projectedDeadlineAt.truncatedTo(ChronoUnit.MICROS);
    }
    requireReference(projectionRef, projectionSha256);
    Objects.requireNonNull(requestedAt, "requestedAt must not be null");
    requestedAt = requestedAt.truncatedTo(ChronoUnit.MICROS);
    if (!caseWorkflowId.equals(CaseProcessWorkflowProtocol.caseWorkflowId(tenantSurrogate, caseId))
        || !roomWorkflowId.equals(
            CaseProcessWorkflowProtocol.roomWorkflowId(caseId, roomType, roomEpoch))) {
      throw new IllegalArgumentException("workflow identity does not match epoch scope");
    }
  }

  public String updateId() {
    return RoomEpochProvisioningProtocol.updateId(epochId, fencingToken);
  }

  public String payloadSha256() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      update(digest, schemaVersion);
      update(digest, epochId);
      update(digest, tenantSurrogate);
      update(digest, caseId);
      update(digest, roomId);
      update(digest, roomType.name());
      update(digest, roomEpoch);
      update(digest, initialProcessRevision);
      update(digest, initialRoomRevision);
      update(digest, fencingToken);
      update(digest, macroPhase);
      update(digest, currentRoom);
      update(digest, roomPhase);
      update(digest, writerMode.name());
      update(digest, caseWorkflowId);
      update(digest, roomWorkflowId);
      update(digest, selectionSchemaVersion);
      update(digest, processContractVersion);
      update(digest, workflowType);
      update(digest, temporalBuildId);
      if (isV2Selection(selectionSchemaVersion)) {
        update(digest, roomWorkflowType);
        update(digest, roomWorkflowBuildId);
      }
      update(digest, graphKey);
      update(digest, graphVersion);
      update(digest, checkpointSchemaVersion);
      update(digest, streamProtocol);
      update(digest, lastCommandSequence);
      update(digest, lastCaseEventSequence);
      update(digest, firstCommandSequence);
      update(digest, firstCaseEventSequence);
      updateNullable(digest, projectedDeadlineAt == null ? null : projectedDeadlineAt.toString());
      updateNullable(digest, projectionRef);
      updateNullable(digest, projectionSha256);
      update(digest, requestedAt.toString());
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  @JsonIgnore
  public String caseWorkflowType() {
    return workflowType;
  }

  @JsonIgnore
  public String caseWorkflowBuildId() {
    return temporalBuildId;
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
            "v1 bootstrap cannot contain a room Workflow binding");
      }
      return;
    }
    if (!isV2Selection(selectionSchemaVersion)) {
      throw new IllegalArgumentException("unsupported selectionSchemaVersion");
    }
    requireText(roomWorkflowType, 128, "roomWorkflowType");
    requireText(roomWorkflowBuildId, 128, "roomWorkflowBuildId");
    if (!CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE.equals(caseWorkflowType)) {
      throw new IllegalArgumentException(
          "v2 bootstrap requires the CaseProcessWorkflow case binding");
    }
    if (writerMode != WriterMode.LEGACY
        && (roomType != RoomType.INTAKE || !"IntakeRoomWorkflow".equals(roomWorkflowType))) {
      throw new IllegalArgumentException(
          "non-LEGACY v2 bootstrap requires the IntakeRoomWorkflow binding");
    }
  }

  private static boolean isV2Selection(String selectionSchemaVersion) {
    return "room-epoch-selection.v2".equals(selectionSchemaVersion);
  }

  private static void update(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static void updateNullable(MessageDigest digest, String value) {
    digest.update((byte) (value == null ? 0 : 1));
    if (value != null) {
      update(digest, value);
    }
  }

  private static void update(MessageDigest digest, long value) {
    digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
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
}
