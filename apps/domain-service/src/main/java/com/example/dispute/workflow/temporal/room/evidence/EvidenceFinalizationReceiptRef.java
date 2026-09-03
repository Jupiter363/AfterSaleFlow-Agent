package com.example.dispute.workflow.temporal.room.evidence;

import java.util.Objects;

/**
 * Reference to a Java-ledger receipt. It is evidence of a completed isolated synthetic operation,
 * never a request to perform a formal write.
 */
public record EvidenceFinalizationReceiptRef(
    String schemaVersion,
    String receiptId,
    String receiptHash,
    OperationType operationType,
    String operationKey,
    String requestHash,
    String resultHash,
    String tenantSurrogate,
    String caseId,
    long roomEpoch,
    long fencingToken,
    String manifestHash,
    long processRevision,
    long roomRevision,
    String commitScope,
    String status,
    boolean formalDomainWrite,
    boolean formalSinkEligible) {

  public static final String ISOLATED_SYNTHETIC_LEDGER = "ISOLATED_SYNTHETIC_LEDGER";

  public EvidenceFinalizationReceiptRef {
    if (!"evidence-finalization-receipt-ref.v1".equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "schemaVersion must be evidence-finalization-receipt-ref.v1");
    }
    EvidenceOperationKeys.requireIdentifier(receiptId, "receiptId");
    EvidenceOperationKeys.requireHash(receiptHash, "receiptHash");
    Objects.requireNonNull(operationType, "operationType must not be null");
    EvidenceOperationKeys.requireValid(operationKey);
    EvidenceOperationKeys.requireHash(requestHash, "requestHash");
    EvidenceOperationKeys.requireHash(resultHash, "resultHash");
    EvidenceOperationKeys.requireIdentifier(tenantSurrogate, "tenantSurrogate");
    EvidenceOperationKeys.requireIdentifier(caseId, "caseId");
    EvidenceOperationKeys.requireHash(manifestHash, "manifestHash");
    if (roomEpoch < 0 || fencingToken < 1 || processRevision < 0 || roomRevision < 0) {
      throw new IllegalArgumentException("receipt epoch, fence, and revisions must be valid");
    }
    if (!operationKey.startsWith(operationType.operationKeyPrefix())) {
      throw new IllegalArgumentException("receipt operationType must match operationKey");
    }
    requireOperationBinding(operationType, operationKey, caseId, roomEpoch, manifestHash);
    if (!ISOLATED_SYNTHETIC_LEDGER.equals(commitScope)
        || !"COMMITTED".equals(status)
        || formalDomainWrite
        || formalSinkEligible) {
      throw new IllegalArgumentException("receipt must be a committed isolated synthetic receipt");
    }
  }

  public boolean matches(EvidenceActivityProtocol.ActivityRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    return operationType == request.operationType()
        && operationKey.equals(request.operationKey())
        && requestHash.equals(request.requestHash())
        && tenantSurrogate.equals(request.tenantSurrogate())
        && caseId.equals(request.caseId())
        && roomEpoch == request.roomEpoch()
        && fencingToken == request.fencingToken()
        && manifestHash.equals(request.manifestHash())
        && processRevision == request.processRevision()
        && roomRevision == request.roomRevision();
  }

  static void requireOperationBinding(
      OperationType operationType,
      String operationKey,
      String caseId,
      long roomEpoch,
      String manifestHash) {
    String caseEpochPrefix = operationType.operationKeyPrefix() + caseId + ":" + roomEpoch + ":";
    if (!operationKey.startsWith(caseEpochPrefix)) {
      throw new IllegalArgumentException("operationKey must bind its case and room epoch");
    }
    if ((operationType == OperationType.GRAPH_REQUEST || operationType == OperationType.BATCH_MERGE)
        && !operationKey.startsWith(caseEpochPrefix + manifestHash + ":")) {
      throw new IllegalArgumentException("operationKey must bind its manifest hash");
    }
  }

  public enum OperationType {
    MANIFEST_ISSUE("evidence.manifest.issue:"),
    GRAPH_REQUEST("evidence.graph.request:"),
    PARTY_COMPLETE("evidence.party.complete:"),
    DEADLINE_WARN("evidence.deadline.warn:"),
    DEADLINE_EXPIRE("evidence.deadline.expire:"),
    BATCH_MERGE("evidence.batch.merge:"),
    DOSSIER_FREEZE("evidence.dossier.freeze:"),
    HEARING_OPEN("evidence.hearing.open:");

    private final String operationKeyPrefix;

    OperationType(String operationKeyPrefix) {
      this.operationKeyPrefix = operationKeyPrefix;
    }

    public String operationKeyPrefix() {
      return operationKeyPrefix;
    }
  }
}
