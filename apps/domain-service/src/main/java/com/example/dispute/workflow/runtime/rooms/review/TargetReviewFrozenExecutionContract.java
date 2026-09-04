package com.example.dispute.workflow.runtime.rooms.review;

import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * The immutable action and operation-set facts frozen before a human review decision. Both the
 * Outcome start and every later decision receipt must use this exact contract.
 */
public record TargetReviewFrozenExecutionContract(
    String actionSnapshotRef,
    String actionSnapshotHash,
    String requiredOperationSetRef,
    String requiredOperationSetHash,
    long requiredOperationCount,
    long kernelRevision) {
  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

  public TargetReviewFrozenExecutionContract {
    requireRef(actionSnapshotRef, "actionSnapshotRef");
    requireHash(actionSnapshotHash, "actionSnapshotHash");
    requireRef(requiredOperationSetRef, "requiredOperationSetRef");
    requireHash(requiredOperationSetHash, "requiredOperationSetHash");
    if (requiredOperationCount < 0 || requiredOperationCount > MAX_SAFE_INTEGER
        || kernelRevision < 0 || kernelRevision >= MAX_SAFE_INTEGER) {
      throw new IllegalArgumentException("frozen Review execution contract has invalid counts or revision");
    }
  }

  public static TargetReviewFrozenExecutionContract fromFrozenPacket(
      ReviewPacketEntity packet, ObjectMapper mapper, long roomRevision) {
    Objects.requireNonNull(packet, "packet");
    return fromFrozenFacts(packet.getId(), packet.getActionHash(), packet.getRemedyJson(), mapper, roomRevision);
  }

  /** JDBC callers use this form so the same canonical construction applies without a JPA entity. */
  public static TargetReviewFrozenExecutionContract fromFrozenFacts(
      String packetId, String actionHash, String frozenRemedyJson, ObjectMapper mapper, long roomRevision) {
    requireRef(packetId, "packetId");
    requireHash(actionHash, "actionHash");
    Objects.requireNonNull(mapper, "mapper");
    try {
      JsonNode remedy = mapper.readTree(frozenRemedyJson);
      if (!remedy.isObject() || !remedy.path("actions").isArray() || !remedy.path("notifications").isArray()) {
        throw new IllegalStateException("frozen Review remedy lacks the exact actions and notifications set");
      }
      long operationCount = (long) remedy.path("actions").size() + remedy.path("notifications").size();
      ObjectNode operationSet = mapper.createObjectNode();
      operationSet.put("packet_id", packetId);
      operationSet.set("actions", remedy.path("actions").deepCopy());
      operationSet.set("notifications", remedy.path("notifications").deepCopy());
      return new TargetReviewFrozenExecutionContract(
          "review-packet:" + packetId + ":action", actionHash,
          "review-packet:" + packetId + ":operations", ContractJson.sha256Hex(operationSet),
          operationCount, roomRevision);
    } catch (IllegalArgumentException | IllegalStateException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("frozen Review remedy is not canonical JSON", failure);
    }
  }

  public long decisionSourceRevision() {
    return kernelRevision;
  }

  public long decisionRevision() {
    return kernelRevision + 1;
  }

  private static void requireRef(String value, String field) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")) {
      throw new IllegalArgumentException(field + " is invalid");
    }
  }

  private static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
    }
  }
}
