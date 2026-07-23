package com.example.dispute.workflow.temporal.room.hearing;

import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingWriterMode;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded Java-authority receipt carried in Temporal History. */
public record HearingCommittedReceipt(
    String schemaVersion,
    String receiptId,
    String receiptHash,
    HearingAuthorityCommit.OperationType operationType,
    String operationKey,
    String requestHash,
    String tenantSurrogate,
    String caseId,
    String flowInstanceId,
    String epochId,
    long roomEpoch,
    HearingWriterMode writerMode,
    long fencingToken,
    HearingWorkflowStage sourceStage,
    int sourceStageSequence,
    long sourceProcessRevision,
    long sourceRoomRevision,
    HearingWorkflowStage stage,
    int stageSequence,
    Instant stageDeadlineAt,
    long processRevision,
    long roomRevision,
    String resultRef,
    String resultHash,
    long committedEventSequence,
    Long temporalHistoryEventId) {

  public static final String SCHEMA_VERSION = "hearing-committed-receipt.v1";
  private static final Pattern IDENTIFIER =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern KEY_COMPONENT =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  private static final Pattern RESULT_REF = Pattern.compile("(?:urn|s3|minio):[^\\s]{1,1019}");
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  public HearingCommittedReceipt {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
    }
    requireIdentifier(receiptId, "receiptId");
    HearingStageReceipt.requireHash(receiptHash, "receiptHash");
    Objects.requireNonNull(operationType, "operationType must not be null");
    if (operationKey == null || operationKey.length() > 512) {
      throw new IllegalArgumentException("operationKey must be bounded");
    }
    HearingStageReceipt.requireHash(requestHash, "requestHash");
    requireComponent(tenantSurrogate, "tenantSurrogate");
    requireComponent(caseId, "caseId");
    requireIdentifier(flowInstanceId, "flowInstanceId");
    requireIdentifier(epochId, "epochId");
    Objects.requireNonNull(writerMode, "writerMode must not be null");
    Objects.requireNonNull(sourceStage, "sourceStage must not be null");
    Objects.requireNonNull(stage, "stage must not be null");
    if (roomEpoch < 1
        || fencingToken < 1
        || roomEpoch > MAX_SAFE_INTEGER
        || fencingToken > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("roomEpoch and fencingToken must identify a fenced epoch");
    }
    if (sourceStageSequence != sourceStage.sequence() || stageSequence != stage.sequence()) {
      throw new IllegalArgumentException("receipt stages must use their durable sequence");
    }
    boolean sameStage = stage == sourceStage;
    boolean adjacentStage = sourceStage.next() == stage;
    if (!sameStage && !adjacentStage) {
      throw new IllegalArgumentException("receipt can bind only the same or adjacent Hearing stage");
    }
    if (stage.isPartyWait() != (stageDeadlineAt != null)) {
      throw new IllegalArgumentException("stageDeadlineAt is required only for a party-wait stage");
    }
    if (sourceProcessRevision < 0
        || sourceRoomRevision < 0
        || processRevision < 0
        || roomRevision < 0
        || sourceProcessRevision > MAX_SAFE_INTEGER
        || sourceRoomRevision > MAX_SAFE_INTEGER
        || processRevision > MAX_SAFE_INTEGER
        || roomRevision > MAX_SAFE_INTEGER
        || processRevision != sourceProcessRevision + 1
        || roomRevision != sourceRoomRevision + 1) {
      throw new IllegalArgumentException("receipt revisions must advance exactly once");
    }
    if (resultRef == null || !RESULT_REF.matcher(resultRef).matches()) {
      throw new IllegalArgumentException("resultRef must be an immutable reference");
    }
    HearingStageReceipt.requireHash(resultHash, "resultHash");
    if (committedEventSequence < 1 || committedEventSequence > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("committedEventSequence must be a positive safe integer");
    }
    if (temporalHistoryEventId != null
        && (temporalHistoryEventId < 1 || temporalHistoryEventId > MAX_SAFE_INTEGER)) {
      throw new IllegalArgumentException("temporalHistoryEventId must be positive");
    }

    String prefix = operationPrefix(operationType)
        + tenantSurrogate + ':' + caseId + ':' + roomEpoch + ':';
    if (!operationKey.startsWith(prefix)) {
      throw new IllegalArgumentException("operationKey does not bind the receipt authority");
    }
    if (operationType == HearingAuthorityCommit.OperationType.STAGE) {
      String expected = prefix + sourceStageSequence + ':' + sourceStage.name();
      if (!expected.equals(operationKey)) {
        throw new IllegalArgumentException("stage operationKey does not bind the source stage");
      }
    } else if (operationType == HearingAuthorityCommit.OperationType.PARTY_TERMINAL
        || operationType == HearingAuthorityCommit.OperationType.AGENT_RESULT
        || operationType == HearingAuthorityCommit.OperationType.FINALIZE) {
      String stagePrefix = prefix + sourceStageSequence + ':';
      if (!operationKey.startsWith(stagePrefix) || operationKey.length() == stagePrefix.length()) {
        throw new IllegalArgumentException("operationKey does not bind the source stage sequence");
      }
      String suffix = operationKey.substring(stagePrefix.length());
      boolean validSuffix = switch (operationType) {
        case PARTY_TERMINAL -> suffix.matches(keyComponentPattern() + ':' + keyComponentPattern());
        case AGENT_RESULT -> suffix.matches(keyComponentPattern() + ":[0-9a-f]{64}");
        case FINALIZE -> suffix.matches(keyComponentPattern() + ':' + requestHash);
        default -> throw new IllegalStateException("unreachable Hearing operation type");
      };
      if (!validSuffix) {
        throw new IllegalArgumentException("operationKey has an invalid operation suffix");
      }
    } else {
      String suffix = operationKey.substring(prefix.length());
      boolean validSuffix = switch (operationType) {
        case HANDOFF -> suffix.matches(keyComponentPattern() + ":[0-9a-f]{64}");
        case CLOSE -> suffix.matches("[0-9a-f]{64}");
        default -> throw new IllegalStateException("unreachable Hearing operation type");
      };
      if (!validSuffix) {
        throw new IllegalArgumentException("operationKey has an invalid operation suffix");
      }
    }
  }

  public boolean matches(HearingRoomStart start) {
    return tenantSurrogate.equals(start.tenantSurrogate())
        && caseId.equals(start.caseId())
        && flowInstanceId.equals(start.flowInstanceId())
        && epochId.equals(start.epochId())
        && roomEpoch == start.roomEpoch()
        && writerMode == start.writerMode()
        && fencingToken == start.fencingToken();
  }

  private static String operationPrefix(HearingAuthorityCommit.OperationType type) {
    return switch (type) {
      case STAGE -> "hearing.stage:";
      case PARTY_TERMINAL -> "hearing.party:";
      case AGENT_RESULT -> "hearing.agent:";
      case FINALIZE -> "hearing.finalize:";
      case HANDOFF -> "hearing.handoff:";
      case CLOSE -> "hearing.close:";
    };
  }

  private static void requireComponent(String value, String field) {
    if (value == null || !KEY_COMPONENT.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a bounded operation-key component");
    }
  }

  private static void requireIdentifier(String value, String field) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a bounded identifier");
    }
  }

  private static String keyComponentPattern() {
    return "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";
  }
}
