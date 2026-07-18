package com.example.dispute.workflow.contract.v1;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

public record ProvisionRoomEpochReceipt(
    String schemaVersion,
    String epochId,
    String tenantSurrogate,
    String caseId,
    String roomId,
    RoomType roomType,
    long roomEpoch,
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
    long firstCommandSequence,
    long firstCaseEventSequence,
    String projectionRef,
    String projectionSha256,
    Instant requestedAt,
    String caseWorkflowId,
    String caseWorkflowRunId,
    String roomWorkflowId,
    String roomWorkflowRunId,
    String provisioningSha256) {

  private static final Pattern STATE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  public ProvisionRoomEpochReceipt {
    if (!"provision-room-epoch-receipt.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be provision-room-epoch-receipt.v1");
    }
    requireText(epochId, 64, "epochId");
    requireText(tenantSurrogate, 128, "tenantSurrogate");
    requireText(caseId, 64, "caseId");
    requireText(roomId, 64, "roomId");
    if (roomType == null || roomEpoch < 0 || fencingToken < 1) {
      throw new IllegalArgumentException("provisioned room epoch is invalid");
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
    if (writerMode != WriterMode.SHADOW && writerMode != WriterMode.TEMPORAL) {
      throw new IllegalArgumentException("provisioned writerMode must be SHADOW or TEMPORAL");
    }
    requireText(selectionSchemaVersion, 64, "selectionSchemaVersion");
    requireText(processContractVersion, 64, "processContractVersion");
    requireText(workflowType, 128, "workflowType");
    requireText(temporalBuildId, 128, "temporalBuildId");
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
    requireReference(projectionRef, projectionSha256);
    if (requestedAt == null) {
      throw new IllegalArgumentException("requestedAt must not be null");
    }
    requestedAt = requestedAt.truncatedTo(ChronoUnit.MICROS);
    requireText(caseWorkflowId, 128, "caseWorkflowId");
    requireText(caseWorkflowRunId, 128, "caseWorkflowRunId");
    requireText(roomWorkflowId, 128, "roomWorkflowId");
    requireText(roomWorkflowRunId, 128, "roomWorkflowRunId");
    if (!caseWorkflowId.equals(
        CaseProcessWorkflowProtocol.caseWorkflowId(tenantSurrogate, caseId))) {
      throw new IllegalArgumentException("caseWorkflowId does not match the case scope");
    }
    if (!roomWorkflowId.equals(
        CaseProcessWorkflowProtocol.roomWorkflowId(caseId, roomType, roomEpoch))) {
      throw new IllegalArgumentException("roomWorkflowId does not match the room scope");
    }
    if (provisioningSha256 == null || !provisioningSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("provisioningSha256 is invalid");
    }
  }

  public boolean matches(ProvisionRoomEpoch request) {
    return request != null
        && epochId.equals(request.epochId())
        && tenantSurrogate.equals(request.tenantSurrogate())
        && caseId.equals(request.caseId())
        && roomId.equals(request.roomId())
        && roomType == request.roomType()
        && roomEpoch == request.roomEpoch()
        && fencingToken == request.fencingToken()
        && initialProcessRevision == request.initialProcessRevision()
        && initialRoomRevision == request.initialRoomRevision()
        && macroPhase.equals(request.macroPhase())
        && currentRoom.equals(request.currentRoom())
        && roomPhase.equals(request.roomPhase())
        && java.util.Objects.equals(projectedDeadlineAt, request.projectedDeadlineAt())
        && writerMode == request.writerMode()
        && selectionSchemaVersion.equals(request.selectionSchemaVersion())
        && processContractVersion.equals(request.processContractVersion())
        && workflowType.equals(request.workflowType())
        && temporalBuildId.equals(request.temporalBuildId())
        && graphKey.equals(request.graphKey())
        && graphVersion.equals(request.graphVersion())
        && checkpointSchemaVersion.equals(request.checkpointSchemaVersion())
        && streamProtocol.equals(request.streamProtocol())
        && lastCommandSequence == request.lastCommandSequence()
        && lastCaseEventSequence == request.lastCaseEventSequence()
        && firstCommandSequence == request.firstCommandSequence()
        && firstCaseEventSequence == request.firstCaseEventSequence()
        && java.util.Objects.equals(projectionRef, request.projectionRef())
        && java.util.Objects.equals(projectionSha256, request.projectionSha256())
        && requestedAt.equals(request.requestedAt())
        && caseWorkflowId.equals(request.caseWorkflowId())
        && roomWorkflowId.equals(request.roomWorkflowId())
        && provisioningSha256.equals(request.payloadSha256());
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
