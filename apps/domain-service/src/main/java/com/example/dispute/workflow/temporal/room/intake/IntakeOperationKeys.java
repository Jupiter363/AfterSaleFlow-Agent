package com.example.dispute.workflow.temporal.room.intake;

import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireHash;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireIdentifier;
import static com.example.dispute.workflow.temporal.room.intake.IntakeProtocolValidation.requireThreadId;

import java.util.regex.Pattern;

public final class IntakeOperationKeys {

  private static final String KEY_IDENTIFIER = "[A-Za-z0-9][A-Za-z0-9._:-]*";
  private static final Pattern VALID_OPERATION_KEY =
      Pattern.compile(
          "^(?:"
              + "intake\\.snapshot\\.publish:"
              + KEY_IDENTIFIER
              + ":\\d+:[0-9a-f]{64}:\\d+"
              + "|intake\\.graph\\.execute:"
              + KEY_IDENTIFIER
              + ":\\d+:grt\\.v1\\.[0-9a-f]{32}:"
              + KEY_IDENTIFIER
              + "|intake\\.turn\\.finalize:"
              + KEY_IDENTIFIER
              + ":\\d+:grt\\.v1\\.[0-9a-f]{32}:"
              + KEY_IDENTIFIER
              + ":[0-9a-f]{64}"
              + "|intake\\.initiator\\.(?:accept|reject):"
              + KEY_IDENTIFIER
              + ":\\d+:"
              + KEY_IDENTIFIER
              + "|intake\\.cancel:"
              + KEY_IDENTIFIER
              + ":\\d+:"
              + KEY_IDENTIFIER
              + "|intake\\.respondent\\.confirm:"
              + KEY_IDENTIFIER
              + ":\\d+:"
              + KEY_IDENTIFIER
              + ")$");
  private static final Pattern TRANSITIONAL_ROOT_CORRELATION =
      Pattern.compile("^intake\\.operation:" + KEY_IDENTIFIER + ":" + KEY_IDENTIFIER + "$");

  private IntakeOperationKeys() {}

  public static String snapshotPublish(
      String caseId, long roomEpoch, String actorScopeHash, long domainRevision) {
    requireIdentifier(caseId, "caseId");
    requireNonNegative(roomEpoch, "roomEpoch");
    requireHash(actorScopeHash, "actorScopeHash");
    requireNonNegative(domainRevision, "domainRevision");
    return key(
        "intake.snapshot.publish:"
            + caseId
            + ":"
            + roomEpoch
            + ":"
            + actorScopeHash
            + ":"
            + domainRevision);
  }

  public static String graphExecute(
      String caseId, long roomEpoch, String threadId, String commandId) {
    requireCommon(caseId, roomEpoch, commandId);
    requireThreadId(threadId, "threadId");
    return key(
        "intake.graph.execute:" + caseId + ":" + roomEpoch + ":" + threadId + ":" + commandId);
  }

  public static String turnFinalize(
      String caseId, long roomEpoch, String threadId, String commandId, String resultHash) {
    requireCommon(caseId, roomEpoch, commandId);
    requireThreadId(threadId, "threadId");
    requireHash(resultHash, "resultHash");
    return key(
        "intake.turn.finalize:"
            + caseId
            + ":"
            + roomEpoch
            + ":"
            + threadId
            + ":"
            + commandId
            + ":"
            + resultHash);
  }

  public static String initiatorAccept(String caseId, long roomEpoch, String commandId) {
    requireCommon(caseId, roomEpoch, commandId);
    return key("intake.initiator.accept:" + caseId + ":" + roomEpoch + ":" + commandId);
  }

  public static String initiatorReject(String caseId, long roomEpoch, String commandId) {
    requireCommon(caseId, roomEpoch, commandId);
    return key("intake.initiator.reject:" + caseId + ":" + roomEpoch + ":" + commandId);
  }

  public static String cancel(String caseId, long roomEpoch, String commandId) {
    requireCommon(caseId, roomEpoch, commandId);
    return key("intake.cancel:" + caseId + ":" + roomEpoch + ":" + commandId);
  }

  public static String respondentConfirm(String caseId, long roomEpoch, String commandId) {
    requireCommon(caseId, roomEpoch, commandId);
    return key("intake.respondent.confirm:" + caseId + ":" + roomEpoch + ":" + commandId);
  }

  public static String requireValid(String operationKey) {
    requireBoundedAscii(operationKey);
    if (!VALID_OPERATION_KEY.matcher(operationKey).matches()) {
      throw new IllegalArgumentException(
          "operationKey must be a frozen Intake operation key of at most 512 characters");
    }
    return operationKey;
  }

  static String requireEventCorrelationKey(String operationKey) {
    requireBoundedAscii(operationKey);
    if (!VALID_OPERATION_KEY.matcher(operationKey).matches()
        && !TRANSITIONAL_ROOT_CORRELATION.matcher(operationKey).matches()) {
      throw new IllegalArgumentException(
          "operationKey must be a frozen Intake operation key or transitional root correlation");
    }
    return operationKey;
  }

  private static void requireCommon(String caseId, long roomEpoch, String commandId) {
    requireIdentifier(caseId, "caseId");
    requireNonNegative(roomEpoch, "roomEpoch");
    requireIdentifier(commandId, "commandId");
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must not be negative");
    }
  }

  private static String key(String value) {
    return requireValid(value);
  }

  private static void requireBoundedAscii(String operationKey) {
    if (operationKey == null
        || operationKey.isEmpty()
        || operationKey.length() > 512
        || !operationKey.chars().allMatch(character -> character >= 0x20 && character <= 0x7e)) {
      throw new IllegalArgumentException(
          "operationKey must be non-empty ASCII of at most 512 characters");
    }
  }
}
