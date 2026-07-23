package com.example.dispute.workflow.temporal.room.hearing;

import java.util.Objects;

/** Exact cross-runtime Hearing operation-key formulas. */
public final class HearingOperationKeys {

  private HearingOperationKeys() {}

  public static String stageCompletion(
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      HearingWorkflowStage stage,
      int sequence) {
    HearingWorkflowStage requiredStage = Objects.requireNonNull(stage, "stage must not be null");
    requireStageSequence(requiredStage, sequence);
    return prefix("stage", tenantSurrogate, caseId, roomEpoch)
        + sequence + ':' + requiredStage.name();
  }

  public static String partyTerminal(
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      HearingWorkflowStage stage,
      int sequence,
      String participantId,
      String requestId) {
    HearingWorkflowStage requiredStage = Objects.requireNonNull(stage, "stage must not be null");
    requireStageSequence(requiredStage, sequence);
    if (!requiredStage.isPartyWait()) {
      throw new IllegalArgumentException("party operation requires a party-wait stage");
    }
    return prefix("party", tenantSurrogate, caseId, roomEpoch)
        + sequence + ':' + component(participantId, "participantId")
        + ':' + component(requestId, "requestId");
  }

  public static String agent(
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      int sequence,
      String operation,
      String commandHash) {
    return prefix("agent", tenantSurrogate, caseId, roomEpoch)
        + positive(sequence, "sequence") + ':' + component(operation, "operation")
        + ':' + hash(commandHash, "commandHash");
  }

  public static boolean matchesAgent(
      String operationKey,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      int sequence,
      String operation) {
    String requiredPrefix = prefix("agent", tenantSurrogate, caseId, roomEpoch)
        + positive(sequence, "sequence") + ':' + component(operation, "operation") + ':';
    if (operationKey == null
        || operationKey.length() != requiredPrefix.length() + 64
        || !operationKey.startsWith(requiredPrefix)) {
      return false;
    }
    return operationKey.substring(requiredPrefix.length()).matches("[0-9a-f]{64}");
  }

  public static String finalizeArtifact(
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      int sequence,
      String artifactType,
      String requestHash) {
    return prefix("finalize", tenantSurrogate, caseId, roomEpoch)
        + positive(sequence, "sequence") + ':' + component(artifactType, "artifactType")
        + ':' + hash(requestHash, "requestHash");
  }

  public static String handoff(
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      String judgeV2Id,
      String judgeV2Hash) {
    return prefix("handoff", tenantSurrogate, caseId, roomEpoch)
        + component(judgeV2Id, "judgeV2Id") + ':' + hash(judgeV2Hash, "judgeV2Hash");
  }

  public static String close(
      String tenantSurrogate, String caseId, long roomEpoch, String handoffReceiptHash) {
    return prefix("close", tenantSurrogate, caseId, roomEpoch)
        + hash(handoffReceiptHash, "handoffReceiptHash");
  }

  public static String partyDeadline(
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      HearingWorkflowStage stage,
      int sequence) {
    HearingWorkflowStage requiredStage = Objects.requireNonNull(stage, "stage must not be null");
    requireStageSequence(requiredStage, sequence);
    return "hearing.timer.party-deadline:"
        + component(tenantSurrogate, "tenantSurrogate") + ':'
        + component(caseId, "caseId") + ':' + nonNegative(roomEpoch, "roomEpoch") + ':'
        + sequence + ':' + requiredStage.name();
  }

  private static String prefix(
      String operation, String tenantSurrogate, String caseId, long roomEpoch) {
    return "hearing." + operation + ':'
        + component(tenantSurrogate, "tenantSurrogate") + ':'
        + component(caseId, "caseId") + ':' + nonNegative(roomEpoch, "roomEpoch") + ':';
  }

  private static void requireStageSequence(HearingWorkflowStage stage, int sequence) {
    if (stage.sequence() != sequence) {
      throw new IllegalArgumentException("sequence must match the Hearing stage");
    }
  }

  private static String component(String value, String field) {
    if (value == null
        || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
      throw new IllegalArgumentException(field + " must be a bounded operation-key component");
    }
    return value;
  }

  private static String hash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
    return value;
  }

  private static long positive(long value, String field) {
    if (value < 1) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return value;
  }

  private static long nonNegative(long value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must be non-negative");
    }
    return value;
  }
}
