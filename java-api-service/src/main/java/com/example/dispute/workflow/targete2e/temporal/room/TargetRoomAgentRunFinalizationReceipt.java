package com.example.dispute.workflow.targete2e.temporal.room;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.util.Objects;

/**
 * Immutable lifecycle acknowledgement emitted only after {@code AgentRunWorkflow.run} returns a
 * completed result. It records completion provenance for a target room, but does not carry Graph
 * output and is not a Java-domain authority.
 */
public record TargetRoomAgentRunFinalizationReceipt(
    String schemaVersion,
    String tenantSurrogate,
    String caseId,
    RoomType roomType,
    long roomEpoch,
    long fencingToken,
    long processRevision,
    long roomRevision,
    long stageSequence,
    String commandId,
    String logicalRunId,
    String attemptId,
    long attemptNo,
    String resultHash) {

  public static final String SCHEMA_VERSION = "target-room-agent-run-finalization-receipt.v1";
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  public TargetRoomAgentRunFinalizationReceipt {
    requireExact(schemaVersion, SCHEMA_VERSION, "schemaVersion");
    requireIdentifier(tenantSurrogate, "tenantSurrogate");
    requireIdentifier(caseId, "caseId");
    roomType = Objects.requireNonNull(roomType, "roomType");
    if (roomType != RoomType.EVIDENCE && roomType != RoomType.HEARING) {
      throw new IllegalArgumentException("roomType must be EVIDENCE or HEARING");
    }
    requireSafeNonNegative(roomEpoch, "roomEpoch");
    requireSafePositive(fencingToken, "fencingToken");
    requireSafeNonNegative(processRevision, "processRevision");
    requireSafeNonNegative(roomRevision, "roomRevision");
    requireSafeNonNegative(stageSequence, "stageSequence");
    requireIdentifier(commandId, "commandId");
    requireIdentifier(logicalRunId, "logicalRunId");
    requireIdentifier(attemptId, "attemptId");
    requireSafePositive(attemptNo, "attemptNo");
    requireHash(resultHash, "resultHash");
  }

  /** Creates the room acknowledgement from the completed AgentRun result returned by Temporal. */
  public static TargetRoomAgentRunFinalizationReceipt completed(
      ExecuteAgentRunRequest request,
      ExecuteAgentRunResult result,
      long fencingToken,
      long roomRevision) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(result, "result");
    if (result.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED
        || !request.agentRunId().equals(result.agentRunId())
        || !request.logicalRunId().equals(result.logicalRunId())
        || result.attemptNo() < request.attemptNo()
        || result.attemptNo() > request.attemptLimit()
        || (result.attemptNo() == request.attemptNo()
            && !request.attemptId().equals(result.attemptId()))
        || (result.attemptNo() > request.attemptNo()
            && request.attemptId().equals(result.attemptId()))
        || result.graphResult() == null
        || (result.attemptNo() == request.attemptNo()
            && !request.command().commandId().equals(result.graphResult().commandId()))
        || (result.attemptNo() > request.attemptNo()
            && request.command().commandId().equals(result.graphResult().commandId()))
        || !request.command().logicalRunId().equals(result.graphResult().logicalRunId())
        || !result.attemptId().equals(result.graphResult().attemptId())
        || !request.command().graphKey().equals(result.graphResult().graphKey())
        || !request.command().graphVersion().equals(result.graphResult().graphVersion())
        || !result.resultHash().equals(result.graphResult().outputHash())) {
      throw new IllegalArgumentException("completed AgentRun result does not bind its request");
    }
    return new TargetRoomAgentRunFinalizationReceipt(
        SCHEMA_VERSION,
        request.command().tenantSurrogate(),
        request.command().caseId(),
        request.command().roomType(),
        request.command().roomEpoch(),
        fencingToken,
        request.command().processRevision(),
        roomRevision,
        request.command().stageSequence(),
        request.command().commandId(),
        request.logicalRunId(),
        result.attemptId(),
        result.attemptNo(),
        result.resultHash());
  }

  public boolean matchesEvidenceRoom(
      String expectedTenant, String expectedCaseId, long expectedEpoch, long expectedFence) {
    return roomType == RoomType.EVIDENCE
        && tenantSurrogate.equals(expectedTenant)
        && caseId.equals(expectedCaseId)
        && roomEpoch == expectedEpoch
        && fencingToken == expectedFence;
  }

  public boolean matchesHearingRoom(
      String expectedTenant, String expectedCaseId, long expectedEpoch, long expectedFence) {
    return roomType == RoomType.HEARING
        && tenantSurrogate.equals(expectedTenant)
        && caseId.equals(expectedCaseId)
        && roomEpoch == expectedEpoch
        && fencingToken == expectedFence;
  }

  private static void requireExact(String value, String expected, String field) {
    if (!expected.equals(value)) {
      throw new IllegalArgumentException(field + " must be " + expected);
    }
  }

  private static void requireIdentifier(String value, String field) {
    if (value == null
        || value.length() > 128
        || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
      throw new IllegalArgumentException(field + " is invalid");
    }
  }

  private static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
    }
  }

  private static void requireSafeNonNegative(long value, String field) {
    if (value < 0 || value > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException(field + " must be a safe non-negative integer");
    }
  }

  private static void requireSafePositive(long value, String field) {
    if (value < 1 || value > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException(field + " must be a positive safe integer");
    }
  }
}
