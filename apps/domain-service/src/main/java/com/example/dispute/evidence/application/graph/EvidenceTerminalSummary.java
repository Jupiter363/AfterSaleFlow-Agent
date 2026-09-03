package com.example.dispute.evidence.application.graph;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, validated terminal sidecar derived only from a committed Java receipt transaction. */
public record EvidenceTerminalSummary(
    String schemaVersion,
    String summaryHash,
    String receiptId,
    String receiptHash,
    String tenantSurrogate,
    String caseId,
    long roomEpoch,
    long javaRoomFencingToken,
    long graphLeaseFencingToken,
    long javaFinalizationFencingToken,
    long sourceRevision,
    long processRevision,
    long roomRevision,
    String authoritySnapshotHash,
    String graphThreadId,
    String manifestHash,
    String proposalHash,
    String resultHash,
    List<String> currentFactIds,
    List<String> currentSourceRefs,
    Instant committedAt) {

  public static final String SCHEMA_VERSION = "evidence-terminal-summary.v1";
  private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  public EvidenceTerminalSummary {
    if (!SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
    }
    requireText(receiptId, "receiptId");
    hash(summaryHash, "summaryHash");
    hash(receiptHash, "receiptHash");
    requireText(tenantSurrogate, "tenantSurrogate");
    requireText(caseId, "caseId");
    hash(authoritySnapshotHash, "authoritySnapshotHash");
    if (graphThreadId == null || !graphThreadId.matches("^grt[.]v1[.][0-9a-f]{32}$")) {
      throw new IllegalArgumentException("graphThreadId is invalid");
    }
    hash(manifestHash, "manifestHash");
    hash(proposalHash, "proposalHash");
    hash(resultHash, "resultHash");
    if (roomEpoch < 0
        || javaRoomFencingToken < 1
        || graphLeaseFencingToken < 1
        || javaFinalizationFencingToken < 1
        || sourceRevision < 1
        || processRevision < 0
        || roomRevision < 0
        || roomEpoch > MAX_SAFE_INTEGER
        || javaRoomFencingToken > MAX_SAFE_INTEGER
        || graphLeaseFencingToken > MAX_SAFE_INTEGER
        || javaFinalizationFencingToken > MAX_SAFE_INTEGER
        || sourceRevision > MAX_SAFE_INTEGER
        || processRevision > MAX_SAFE_INTEGER
        || roomRevision > MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("terminal summary epoch, fences, or revisions are invalid");
    }
    if (javaRoomFencingToken == graphLeaseFencingToken
        || javaRoomFencingToken == javaFinalizationFencingToken
        || graphLeaseFencingToken == javaFinalizationFencingToken) {
      throw new IllegalArgumentException("Java room, Graph lease, and finalization fences differ");
    }
    currentFactIds = canonicalReferences(currentFactIds, "currentFactIds");
    currentSourceRefs = canonicalReferences(currentSourceRefs, "currentSourceRefs");
    Objects.requireNonNull(committedAt, "committedAt");
    if (!summaryHash.equals(
        canonicalHash(
            receiptId,
            receiptHash,
            tenantSurrogate,
            caseId,
            roomEpoch,
            javaRoomFencingToken,
            graphLeaseFencingToken,
            javaFinalizationFencingToken,
            sourceRevision,
            processRevision,
            roomRevision,
            authoritySnapshotHash,
            graphThreadId,
            manifestHash,
            proposalHash,
            resultHash,
            currentFactIds,
            currentSourceRefs,
            committedAt))) {
      throw new IllegalArgumentException("summaryHash is not canonical");
    }
  }

  public static EvidenceTerminalSummary create(
      EvidenceFinalizationReceipt receipt,
      EvidenceCurrentAuthoritySnapshot authority,
      long graphLeaseFencingToken,
      long javaFinalizationFencingToken) {
    Objects.requireNonNull(receipt, "receipt");
    Objects.requireNonNull(authority, "authority");
    if (!(receipt.operationBinding()
        instanceof EvidenceFinalizationReceipt.BatchMergeBinding binding)) {
      throw new IllegalArgumentException("terminal summary requires a batch-merge receipt");
    }
    String hash =
        canonicalHash(
            receipt.receiptId(),
            receipt.receiptHash(),
            receipt.tenantSurrogate(),
            receipt.caseId(),
            receipt.roomEpoch(),
            receipt.fencingToken(),
            graphLeaseFencingToken,
            javaFinalizationFencingToken,
            receipt.sourceRevision(),
            receipt.processRevision(),
            receipt.roomRevision(),
            authority.authoritySnapshotHash(),
            binding.threadId(),
            binding.manifestHash(),
            binding.proposalHash(),
            receipt.resultHash(),
            authority.currentFactIds(),
            authority.currentSourceRefs(),
            receipt.committedAt());
    return new EvidenceTerminalSummary(
        SCHEMA_VERSION,
        hash,
        receipt.receiptId(),
        receipt.receiptHash(),
        receipt.tenantSurrogate(),
        receipt.caseId(),
        receipt.roomEpoch(),
        receipt.fencingToken(),
        graphLeaseFencingToken,
        javaFinalizationFencingToken,
        receipt.sourceRevision(),
        receipt.processRevision(),
        receipt.roomRevision(),
        authority.authoritySnapshotHash(),
        binding.threadId(),
        binding.manifestHash(),
        binding.proposalHash(),
        receipt.resultHash(),
        authority.currentFactIds(),
        authority.currentSourceRefs(),
        receipt.committedAt());
  }

  private static String canonicalHash(
      String receiptId,
      String receiptHash,
      String tenantSurrogate,
      String caseId,
      long roomEpoch,
      long javaRoomFencingToken,
      long graphLeaseFencingToken,
      long javaFinalizationFencingToken,
      long sourceRevision,
      long processRevision,
      long roomRevision,
      String authoritySnapshotHash,
      String graphThreadId,
      String manifestHash,
      String proposalHash,
      String resultHash,
      List<String> currentFactIds,
      List<String> currentSourceRefs,
      Instant committedAt) {
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    value.put("schema_version", SCHEMA_VERSION);
    value.put("receipt_id", receiptId);
    value.put("receipt_hash", receiptHash);
    value.put("tenant_surrogate", tenantSurrogate);
    value.put("case_id", caseId);
    value.put("room_epoch", roomEpoch);
    value.put("java_room_fencing_token", javaRoomFencingToken);
    value.put("graph_lease_fencing_token", graphLeaseFencingToken);
    value.put("java_finalization_fencing_token", javaFinalizationFencingToken);
    value.put("source_revision", sourceRevision);
    value.put("process_revision", processRevision);
    value.put("room_revision", roomRevision);
    value.put("authority_snapshot_hash", authoritySnapshotHash);
    value.put("graph_thread_id", graphThreadId);
    value.put("manifest_hash", manifestHash);
    value.put("proposal_hash", proposalHash);
    value.put("result_hash", resultHash);
    ArrayNode facts = value.putArray("current_fact_ids");
    currentFactIds.forEach(facts::add);
    ArrayNode sources = value.putArray("current_source_refs");
    currentSourceRefs.forEach(sources::add);
    value.put("committed_at", committedAt.toString());
    return ContractJson.sha256Hex(value);
  }

  private static List<String> canonicalReferences(List<String> values, String field) {
    Objects.requireNonNull(values, field);
    List<String> copy = List.copyOf(values);
    if (!copy.equals(copy.stream().sorted().toList())
        || copy.size() != copy.stream().distinct().count()
        || copy.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException(field + " must be sorted, unique, and non-blank");
    }
    return copy;
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static void hash(String value, String field) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }
}
