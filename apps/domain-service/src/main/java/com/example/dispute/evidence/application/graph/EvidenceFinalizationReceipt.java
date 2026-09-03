package com.example.dispute.evidence.application.graph;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.temporal.room.evidence.EvidenceFinalizationReceiptRef;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable Java-ledger receipt for one Evidence finalization operation. */
public record EvidenceFinalizationReceipt(
    String schemaVersion,
    String receiptId,
    String receiptHash,
    OperationType operationType,
    String operationKey,
    String requestHash,
    String resultHash,
    String commitScope,
    String status,
    boolean formalDomainWrite,
    boolean formalSinkEligible,
    String tenantSurrogate,
    String caseId,
    long roomEpoch,
    long fencingToken,
    long sourceRevision,
    long processRevision,
    long roomRevision,
    OperationBinding operationBinding,
    int mergeCount,
    List<String> domainEventIds,
    List<String> outboxIds,
    boolean hearingOpened,
    Instant committedAt) {

  public static final String SCHEMA_VERSION = "evidence-finalization-receipt.v1";
  public static final String ISOLATED_SYNTHETIC_LEDGER = "ISOLATED_SYNTHETIC_LEDGER";
  public static final String COMMITTED = "COMMITTED";

  private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
  private static final Pattern SYNTHETIC_TENANT =
      Pattern.compile("^TENANT_P5_SYNTHETIC_[A-Za-z0-9._:-]{1,104}$");
  private static final Pattern SYNTHETIC_CASE =
      Pattern.compile("^CASE_P5_SYNTHETIC_[A-Za-z0-9._:-]{1,106}$");
  private static final Pattern OPERATION_KEY =
      Pattern.compile(
          "^evidence[.](?:manifest[.]issue|graph[.]request|party[.]complete|deadline[.](?:warn|expire)|batch[.]merge|dossier[.]freeze|hearing[.]open):[A-Za-z0-9._:-]{1,448}$");
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  public EvidenceFinalizationReceipt {
    requireEqual(schemaVersion, SCHEMA_VERSION, "schemaVersion");
    identifier(receiptId, "receiptId");
    hash(receiptHash, "receiptHash");
    Objects.requireNonNull(operationType, "operationType");
    boundedOperationKey(operationKey);
    hash(requestHash, "requestHash");
    hash(resultHash, "resultHash");
    requireEqual(commitScope, ISOLATED_SYNTHETIC_LEDGER, "commitScope");
    requireEqual(status, COMMITTED, "status");
    if (formalDomainWrite || formalSinkEligible) {
      throw new IllegalArgumentException("synthetic receipt cannot authorize a formal write");
    }
    if (tenantSurrogate == null || !SYNTHETIC_TENANT.matcher(tenantSurrogate).matches()) {
      throw new IllegalArgumentException("tenantSurrogate is not a Phase 5 synthetic tenant");
    }
    if (caseId == null || !SYNTHETIC_CASE.matcher(caseId).matches()) {
      throw new IllegalArgumentException("caseId is not a Phase 5 synthetic case");
    }
    if (roomEpoch < 0
        || fencingToken < 1
        || sourceRevision < 1
        || processRevision < 0
        || roomRevision < 0
        || roomEpoch > MAX_SAFE_INTEGER
        || fencingToken > MAX_SAFE_INTEGER
        || sourceRevision > MAX_SAFE_INTEGER
        || processRevision > MAX_SAFE_INTEGER
        || roomRevision > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("receipt epoch, fence, and revisions are invalid");
    }
    operationBinding = Objects.requireNonNull(operationBinding, "operationBinding");
    if (operationBinding.operationType() != operationType) {
      throw new IllegalArgumentException("operationBinding does not match operationType");
    }
    requireEqual(operationKey, operationBinding.operationKey(caseId, roomEpoch), "operationKey");
    if (mergeCount != 0
        || !List.copyOf(domainEventIds).isEmpty()
        || !List.copyOf(outboxIds).isEmpty()
        || hearingOpened) {
      throw new IllegalArgumentException(
          "isolated synthetic receipt must record zero formal side effects");
    }
    domainEventIds = List.copyOf(domainEventIds);
    outboxIds = List.copyOf(outboxIds);
    Objects.requireNonNull(committedAt, "committedAt");
    String canonicalHash =
        canonicalReceiptHash(
            schemaVersion,
            receiptId,
            operationType,
            operationKey,
            requestHash,
            resultHash,
            commitScope,
            status,
            formalDomainWrite,
            formalSinkEligible,
            tenantSurrogate,
            caseId,
            roomEpoch,
            fencingToken,
            sourceRevision,
            processRevision,
            roomRevision,
            operationBinding,
            mergeCount,
            domainEventIds,
            outboxIds,
            hearingOpened,
            committedAt);
    if (!receiptHash.equals(canonicalHash)) {
      throw new IllegalArgumentException("receiptHash is not canonical");
    }
    ObjectNode contract =
        receiptPreimage(
            schemaVersion,
            receiptId,
            operationType,
            operationKey,
            requestHash,
            resultHash,
            commitScope,
            status,
            formalDomainWrite,
            formalSinkEligible,
            tenantSurrogate,
            caseId,
            roomEpoch,
            fencingToken,
            sourceRevision,
            processRevision,
            roomRevision,
            operationBinding,
            mergeCount,
            domainEventIds,
            outboxIds,
            hearingOpened,
            committedAt);
    contract.put("receipt_hash", receiptHash);
    if (ContractJson.canonicalize(contract).length > 65_536) {
      throw new IllegalArgumentException("receipt exceeds the frozen encoded size");
    }
  }

  public static EvidenceFinalizationReceipt committedSyntheticBatchMerge(
      String receiptId,
      String requestHash,
      String resultHash,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long fencingToken,
      long sourceRevision,
      long processRevision,
      long roomRevision,
      BatchMergeBinding operationBinding,
      Instant committedAt) {
    String operationKey = operationBinding.operationKey(caseId, roomEpoch);
    String receiptHash =
        canonicalReceiptHash(
            SCHEMA_VERSION,
            receiptId,
            OperationType.BATCH_MERGE,
            operationKey,
            requestHash,
            resultHash,
            ISOLATED_SYNTHETIC_LEDGER,
            COMMITTED,
            false,
            false,
            tenantSurrogate,
            caseId,
            roomEpoch,
            fencingToken,
            sourceRevision,
            processRevision,
            roomRevision,
            operationBinding,
            0,
            List.of(),
            List.of(),
            false,
            committedAt);
    return new EvidenceFinalizationReceipt(
        SCHEMA_VERSION,
        receiptId,
        receiptHash,
        OperationType.BATCH_MERGE,
        operationKey,
        requestHash,
        resultHash,
        ISOLATED_SYNTHETIC_LEDGER,
        COMMITTED,
        false,
        false,
        tenantSurrogate,
        caseId,
        roomEpoch,
        fencingToken,
        sourceRevision,
        processRevision,
        roomRevision,
        operationBinding,
        0,
        List.of(),
        List.of(),
        false,
        committedAt);
  }

  /**
   * Converts the isolated batch receipt to B2's frozen orchestration reference. Other receipt
   * operations deliberately require a separate adapter because B2 mandates a manifest hash.
   */
  public EvidenceFinalizationReceiptRef toSyntheticReceiptRef() {
    if (!(operationBinding instanceof BatchMergeBinding batch)) {
      throw new IllegalStateException(
          "B2 receipt reference can only represent a manifest-bound batch receipt here");
    }
    return new EvidenceFinalizationReceiptRef(
        "evidence-finalization-receipt-ref.v1",
        receiptId,
        receiptHash,
        EvidenceFinalizationReceiptRef.OperationType.BATCH_MERGE,
        operationKey,
        requestHash,
        resultHash,
        tenantSurrogate,
        caseId,
        roomEpoch,
        fencingToken,
        batch.manifestHash,
        processRevision,
        roomRevision,
        commitScope,
        status,
        formalDomainWrite,
        formalSinkEligible);
  }

  public ObjectNode toContractJson() {
    ObjectNode value =
        receiptPreimage(
            schemaVersion,
            receiptId,
            operationType,
            operationKey,
            requestHash,
            resultHash,
            commitScope,
            status,
            formalDomainWrite,
            formalSinkEligible,
            tenantSurrogate,
            caseId,
            roomEpoch,
            fencingToken,
            sourceRevision,
            processRevision,
            roomRevision,
            operationBinding,
            mergeCount,
            domainEventIds,
            outboxIds,
            hearingOpened,
            committedAt);
    value.put("receipt_hash", receiptHash);
    return value;
  }

  private static String canonicalReceiptHash(
      String schemaVersion,
      String receiptId,
      OperationType operationType,
      String operationKey,
      String requestHash,
      String resultHash,
      String commitScope,
      String status,
      boolean formalDomainWrite,
      boolean formalSinkEligible,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long fencingToken,
      long sourceRevision,
      long processRevision,
      long roomRevision,
      OperationBinding operationBinding,
      int mergeCount,
      List<String> domainEventIds,
      List<String> outboxIds,
      boolean hearingOpened,
      Instant committedAt) {
    return ContractJson.sha256Hex(
        receiptPreimage(
            schemaVersion,
            receiptId,
            operationType,
            operationKey,
            requestHash,
            resultHash,
            commitScope,
            status,
            formalDomainWrite,
            formalSinkEligible,
            tenantSurrogate,
            caseId,
            roomEpoch,
            fencingToken,
            sourceRevision,
            processRevision,
            roomRevision,
            operationBinding,
            mergeCount,
            domainEventIds,
            outboxIds,
            hearingOpened,
            committedAt));
  }

  private static ObjectNode receiptPreimage(
      String schemaVersion,
      String receiptId,
      OperationType operationType,
      String operationKey,
      String requestHash,
      String resultHash,
      String commitScope,
      String status,
      boolean formalDomainWrite,
      boolean formalSinkEligible,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long fencingToken,
      long sourceRevision,
      long processRevision,
      long roomRevision,
      OperationBinding operationBinding,
      int mergeCount,
      List<String> domainEventIds,
      List<String> outboxIds,
      boolean hearingOpened,
      Instant committedAt) {
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    value.put("schema_version", schemaVersion);
    value.put("receipt_id", receiptId);
    value.put("operation_type", operationType.name());
    value.put("operation_key", operationKey);
    value.put("request_hash", requestHash);
    value.put("result_hash", resultHash);
    value.put("commit_scope", commitScope);
    value.put("status", status);
    value.put("formal_domain_write", formalDomainWrite);
    value.put("formal_sink_eligible", formalSinkEligible);
    value.put("tenant_surrogate", tenantSurrogate);
    value.put("case_id", caseId);
    value.put("room_epoch", roomEpoch);
    value.put("fencing_token", fencingToken);
    value.put("source_revision", sourceRevision);
    value.put("process_revision", processRevision);
    value.put("room_revision", roomRevision);
    value.set("operation_binding", operationBinding.toContractJson());
    value.put("merge_count", mergeCount);
    ArrayNode events = value.putArray("domain_event_ids");
    domainEventIds.forEach(events::add);
    ArrayNode outbox = value.putArray("outbox_ids");
    outboxIds.forEach(outbox::add);
    value.put("hearing_opened", hearingOpened);
    value.put("committed_at", committedAt.toString());
    return value;
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
  }

  public sealed interface OperationBinding
      permits ManifestIssueBinding,
          GraphRequestBinding,
          PartyCompleteBinding,
          DeadlineBinding,
          BatchMergeBinding,
          DossierFreezeBinding,
          HearingOpenBinding {

    OperationType operationType();

    String operationKey(String caseId, long roomEpoch);

    ObjectNode toContractJson();
  }

  public record ManifestIssueBinding(
      String submissionBatchId, long submissionRevision, String manifestId, String manifestHash)
      implements OperationBinding {
    public ManifestIssueBinding {
      identifier(submissionBatchId, "submissionBatchId");
      positive(submissionRevision, "submissionRevision");
      identifier(manifestId, "manifestId");
      hash(manifestHash, "manifestHash");
    }

    @Override
    public OperationType operationType() {
      return OperationType.MANIFEST_ISSUE;
    }

    @Override
    public String operationKey(String caseId, long roomEpoch) {
      return prefix(operationType(), caseId, roomEpoch)
          + submissionBatchId
          + ":"
          + submissionRevision;
    }

    @Override
    public ObjectNode toContractJson() {
      ObjectNode value = JsonNodeFactory.instance.objectNode();
      value.put("submission_batch_id", submissionBatchId);
      value.put("submission_revision", submissionRevision);
      value.put("manifest_id", manifestId);
      value.put("manifest_hash", manifestHash);
      return value;
    }
  }

  public record GraphRequestBinding(
      String manifestHash, String logicalRunId, String commandId, String attemptId, String threadId)
      implements OperationBinding {
    public GraphRequestBinding {
      hash(manifestHash, "manifestHash");
      identifier(logicalRunId, "logicalRunId");
      identifier(commandId, "commandId");
      identifier(attemptId, "attemptId");
      EvidenceFinalizationReceipt.threadId(threadId);
    }

    @Override
    public OperationType operationType() {
      return OperationType.GRAPH_REQUEST;
    }

    @Override
    public String operationKey(String caseId, long roomEpoch) {
      return prefix(operationType(), caseId, roomEpoch) + manifestHash + ":" + logicalRunId;
    }

    @Override
    public ObjectNode toContractJson() {
      return graphFields(manifestHash, logicalRunId, commandId, attemptId, threadId);
    }
  }

  public record PartyCompleteBinding(String participantId, String completionRequestId)
      implements OperationBinding {
    public PartyCompleteBinding {
      identifier(participantId, "participantId");
      identifier(completionRequestId, "completionRequestId");
    }

    @Override
    public OperationType operationType() {
      return OperationType.PARTY_COMPLETE;
    }

    @Override
    public String operationKey(String caseId, long roomEpoch) {
      return prefix(operationType(), caseId, roomEpoch) + participantId + ":" + completionRequestId;
    }

    @Override
    public ObjectNode toContractJson() {
      ObjectNode value = JsonNodeFactory.instance.objectNode();
      value.put("participant_id", participantId);
      value.put("completion_request_id", completionRequestId);
      return value;
    }
  }

  public record DeadlineBinding(OperationType operationType, long deadlineRevision)
      implements OperationBinding {
    public DeadlineBinding {
      if (operationType != OperationType.DEADLINE_WARN
          && operationType != OperationType.DEADLINE_EXPIRE) {
        throw new IllegalArgumentException("deadline binding operation is invalid");
      }
      positive(deadlineRevision, "deadlineRevision");
    }

    @Override
    public String operationKey(String caseId, long roomEpoch) {
      return prefix(operationType, caseId, roomEpoch) + deadlineRevision;
    }

    @Override
    public ObjectNode toContractJson() {
      ObjectNode value = JsonNodeFactory.instance.objectNode();
      value.put("deadline_revision", deadlineRevision);
      return value;
    }
  }

  public record BatchMergeBinding(
      String manifestHash,
      long dossierTargetVersion,
      String proposalHash,
      String logicalRunId,
      String commandId,
      String attemptId,
      String threadId)
      implements OperationBinding {
    public BatchMergeBinding {
      hash(manifestHash, "manifestHash");
      positive(dossierTargetVersion, "dossierTargetVersion");
      hash(proposalHash, "proposalHash");
      identifier(logicalRunId, "logicalRunId");
      identifier(commandId, "commandId");
      identifier(attemptId, "attemptId");
      EvidenceFinalizationReceipt.threadId(threadId);
    }

    @Override
    public OperationType operationType() {
      return OperationType.BATCH_MERGE;
    }

    @Override
    public String operationKey(String caseId, long roomEpoch) {
      return prefix(operationType(), caseId, roomEpoch) + manifestHash + ":" + dossierTargetVersion;
    }

    @Override
    public ObjectNode toContractJson() {
      ObjectNode value = graphFields(manifestHash, logicalRunId, commandId, attemptId, threadId);
      value.put("dossier_target_version", dossierTargetVersion);
      value.put("proposal_hash", proposalHash);
      return value;
    }
  }

  public record DossierFreezeBinding(long dossierTargetVersion) implements OperationBinding {
    public DossierFreezeBinding {
      positive(dossierTargetVersion, "dossierTargetVersion");
    }

    @Override
    public OperationType operationType() {
      return OperationType.DOSSIER_FREEZE;
    }

    @Override
    public String operationKey(String caseId, long roomEpoch) {
      return prefix(operationType(), caseId, roomEpoch) + dossierTargetVersion;
    }

    @Override
    public ObjectNode toContractJson() {
      ObjectNode value = JsonNodeFactory.instance.objectNode();
      value.put("dossier_target_version", dossierTargetVersion);
      return value;
    }
  }

  public record HearingOpenBinding(String freezeReceiptHash) implements OperationBinding {
    public HearingOpenBinding {
      hash(freezeReceiptHash, "freezeReceiptHash");
    }

    @Override
    public OperationType operationType() {
      return OperationType.HEARING_OPEN;
    }

    @Override
    public String operationKey(String caseId, long roomEpoch) {
      return prefix(operationType(), caseId, roomEpoch) + freezeReceiptHash;
    }

    @Override
    public ObjectNode toContractJson() {
      ObjectNode value = JsonNodeFactory.instance.objectNode();
      value.put("freeze_receipt_hash", freezeReceiptHash);
      return value;
    }
  }

  private static ObjectNode graphFields(
      String manifestHash,
      String logicalRunId,
      String commandId,
      String attemptId,
      String threadId) {
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    value.put("manifest_hash", manifestHash);
    value.put("logical_run_id", logicalRunId);
    value.put("command_id", commandId);
    value.put("attempt_id", attemptId);
    value.put("thread_id", threadId);
    return value;
  }

  private static String prefix(OperationType type, String caseId, long roomEpoch) {
    identifier(caseId, "caseId");
    if (roomEpoch < 0) {
      throw new IllegalArgumentException("roomEpoch must be non-negative");
    }
    return type.operationKeyPrefix + caseId + ":" + roomEpoch + ":";
  }

  private static void identifier(String value, String field) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a bounded identifier");
    }
  }

  private static void threadId(String value) {
    if (value == null || !value.matches("^grt[.]v1[.][0-9a-f]{32}$")) {
      throw new IllegalArgumentException("threadId is invalid");
    }
  }

  private static void hash(String value, String field) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }

  private static void positive(long value, String field) {
    if (value < 1 || value > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException(field + " must be a positive JS-safe integer");
    }
  }

  private static void boundedOperationKey(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 512
        || !OPERATION_KEY.matcher(value).matches()) {
      throw new IllegalArgumentException("operationKey must be bounded ASCII");
    }
  }

  private static void requireEqual(String actual, String expected, String field) {
    if (!Objects.equals(actual, expected)) {
      throw new IllegalArgumentException(field + " must be " + expected);
    }
  }
}
