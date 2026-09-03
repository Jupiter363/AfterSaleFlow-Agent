package com.example.dispute.workflow.temporal.room.evidence;

import java.util.regex.Pattern;

public final class EvidenceOperationKeys {

  private static final String IDENTIFIER = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
  private static final String HASH = "[0-9a-f]{64}";
  private static final Pattern VALID_KEY =
      Pattern.compile(
          "^(?:"
              + "evidence\\.manifest\\.issue:"
              + IDENTIFIER
              + ":\\d+:"
              + IDENTIFIER
              + ":\\d+"
              + "|evidence\\.graph\\.request:"
              + IDENTIFIER
              + ":\\d+:"
              + HASH
              + ":"
              + IDENTIFIER
              + "|evidence\\.party\\.complete:"
              + IDENTIFIER
              + ":\\d+:"
              + IDENTIFIER
              + ":"
              + IDENTIFIER
              + "|evidence\\.deadline\\.(?:warn|expire):"
              + IDENTIFIER
              + ":\\d+:\\d+"
              + "|evidence\\.batch\\.merge:"
              + IDENTIFIER
              + ":\\d+:"
              + HASH
              + ":\\d+"
              + "|evidence\\.dossier\\.freeze:"
              + IDENTIFIER
              + ":\\d+:\\d+"
              + "|evidence\\.hearing\\.open:"
              + IDENTIFIER
              + ":\\d+:"
              + HASH
              + ")$");

  private EvidenceOperationKeys() {}

  public static String manifestIssue(
      String caseId, long roomEpoch, String submissionBatchId, long submissionRevision) {
    requireCommon(caseId, roomEpoch);
    requireIdentifier(submissionBatchId, "submissionBatchId");
    requirePositive(submissionRevision, "submissionRevision");
    return key(
        "evidence.manifest.issue:"
            + caseId
            + ":"
            + roomEpoch
            + ":"
            + submissionBatchId
            + ":"
            + submissionRevision);
  }

  public static String graphRequest(
      String caseId, long roomEpoch, String manifestHash, String logicalRunId) {
    requireCommon(caseId, roomEpoch);
    requireHash(manifestHash, "manifestHash");
    requireIdentifier(logicalRunId, "logicalRunId");
    return key(
        "evidence.graph.request:"
            + caseId
            + ":"
            + roomEpoch
            + ":"
            + manifestHash
            + ":"
            + logicalRunId);
  }

  public static String partyComplete(
      String caseId, long roomEpoch, String participantId, String completionRequestId) {
    requireCommon(caseId, roomEpoch);
    requireIdentifier(participantId, "participantId");
    requireIdentifier(completionRequestId, "completionRequestId");
    return key(
        "evidence.party.complete:"
            + caseId
            + ":"
            + roomEpoch
            + ":"
            + participantId
            + ":"
            + completionRequestId);
  }

  public static String deadlineWarn(String caseId, long roomEpoch, long deadlineRevision) {
    requireCommon(caseId, roomEpoch);
    requirePositive(deadlineRevision, "deadlineRevision");
    return key(
        "evidence.deadline.warn:" + caseId + ":" + roomEpoch + ":" + deadlineRevision);
  }

  public static String deadlineExpire(String caseId, long roomEpoch, long deadlineRevision) {
    requireCommon(caseId, roomEpoch);
    requirePositive(deadlineRevision, "deadlineRevision");
    return key(
        "evidence.deadline.expire:" + caseId + ":" + roomEpoch + ":" + deadlineRevision);
  }

  public static String batchMerge(
      String caseId, long roomEpoch, String manifestHash, long dossierTargetVersion) {
    requireCommon(caseId, roomEpoch);
    requireHash(manifestHash, "manifestHash");
    requirePositive(dossierTargetVersion, "dossierTargetVersion");
    return key(
        "evidence.batch.merge:"
            + caseId
            + ":"
            + roomEpoch
            + ":"
            + manifestHash
            + ":"
            + dossierTargetVersion);
  }

  public static String dossierFreeze(
      String caseId, long roomEpoch, long dossierTargetVersion) {
    requireCommon(caseId, roomEpoch);
    requirePositive(dossierTargetVersion, "dossierTargetVersion");
    return key(
        "evidence.dossier.freeze:" + caseId + ":" + roomEpoch + ":" + dossierTargetVersion);
  }

  public static String hearingOpen(String caseId, long roomEpoch, String freezeReceiptHash) {
    requireCommon(caseId, roomEpoch);
    requireHash(freezeReceiptHash, "freezeReceiptHash");
    return key(
        "evidence.hearing.open:" + caseId + ":" + roomEpoch + ":" + freezeReceiptHash);
  }

  public static String requireValid(String operationKey) {
    if (operationKey == null
        || operationKey.isEmpty()
        || operationKey.length() > 512
        || !operationKey.chars().allMatch(character -> character >= 0x20 && character <= 0x7e)
        || !VALID_KEY.matcher(operationKey).matches()) {
      throw new IllegalArgumentException(
          "operationKey must be a frozen Evidence operation key of at most 512 characters");
    }
    return operationKey;
  }

  private static void requireCommon(String caseId, long roomEpoch) {
    requireIdentifier(caseId, "caseId");
    if (roomEpoch < 0) {
      throw new IllegalArgumentException("roomEpoch must not be negative");
    }
  }

  static String requireIdentifier(String value, String field) {
    if (value == null || !value.matches(IDENTIFIER)) {
      throw new IllegalArgumentException(field + " must be a bounded identifier");
    }
    return value;
  }

  static String requireHash(String value, String field) {
    if (value == null || !value.matches(HASH)) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256 hash");
    }
    return value;
  }

  private static void requirePositive(long value, String field) {
    if (value < 1) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  private static String key(String value) {
    return requireValid(value);
  }
}
