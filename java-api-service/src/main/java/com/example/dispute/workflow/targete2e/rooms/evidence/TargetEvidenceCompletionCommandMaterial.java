package com.example.dispute.workflow.targete2e.rooms.evidence;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable non-Graph authority material for one Evidence party-completion command. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TargetEvidenceCompletionCommandMaterial(
    String schemaVersion,
    String executionLane,
    String activationId,
    String activationManifestHash,
    String isolatedDomainDbBindingHash,
    String tenantSurrogate,
    String caseId,
    String commandId,
    CommandType commandType,
    RoomType roomType,
    long roomEpoch,
    long roomFencingToken,
    long expectedProcessRevision,
    long expectedRoomRevision,
    ActorRef actorRef,
    PayloadRef payloadRef,
    Instant deadlineAt,
    String traceId,
    String caseCommandRequestHash,
    String commandHash,
    String commandEnvelopeHash) {

  public static final String SCHEMA_VERSION = "target-e2e-evidence-completion-command-material.v1";
  public static final String TARGET_LANE = "TARGET_E2E_CANDIDATE";
  private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

  public TargetEvidenceCompletionCommandMaterial {
    require(SCHEMA_VERSION.equals(schemaVersion), "schemaVersion");
    require(TARGET_LANE.equals(executionLane), "executionLane");
    requireText(activationId, "activationId");
    requireHash(activationManifestHash, "activationManifestHash");
    requireHash(isolatedDomainDbBindingHash, "isolatedDomainDbBindingHash");
    requireText(tenantSurrogate, "tenantSurrogate");
    requireText(caseId, "caseId");
    requireText(commandId, "commandId");
    require(commandType == CommandType.PARTY_EVIDENCE_COMPLETE, "commandType");
    require(roomType == RoomType.EVIDENCE, "roomType");
    if (roomEpoch < 0 || roomFencingToken < 1
        || expectedProcessRevision < 0 || expectedRoomRevision < 0) {
      throw new IllegalArgumentException("target Evidence completion coordinates are invalid");
    }
    actorRef = Objects.requireNonNull(actorRef, "actorRef");
    payloadRef = Objects.requireNonNull(payloadRef, "payloadRef");
    deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
    if (traceId == null || !traceId.matches("[0-9a-f]{32}") || traceId.chars().allMatch(c -> c == '0')) {
      throw new IllegalArgumentException("target Evidence completion traceId is invalid");
    }
    requireHash(caseCommandRequestHash, "caseCommandRequestHash");
    requireHash(commandHash, "commandHash");
    requireHash(commandEnvelopeHash, "commandEnvelopeHash");
    String expectedCommandHash = commandHash(
        tenantSurrogate, caseId, commandId, roomEpoch, roomFencingToken,
        expectedProcessRevision, expectedRoomRevision, actorRef, payloadRef, deadlineAt,
        traceId, caseCommandRequestHash);
    require(expectedCommandHash.equals(commandHash), "commandHash");
    require(envelopeHash(activationId, activationManifestHash, isolatedDomainDbBindingHash,
        roomFencingToken, expectedCommandHash).equals(commandEnvelopeHash), "commandEnvelopeHash");
  }

  public static TargetEvidenceCompletionCommandMaterial create(
      String activationId,
      String activationManifestHash,
      String isolatedDomainDbBindingHash,
      String tenantSurrogate,
      String caseId,
      String commandId,
      long roomEpoch,
      long roomFencingToken,
      long expectedProcessRevision,
      long expectedRoomRevision,
      ActorRef actorRef,
      PayloadRef payloadRef,
      Instant deadlineAt,
      String traceId,
      String caseCommandRequestHash) {
    String commandHash = commandHash(
        tenantSurrogate, caseId, commandId, roomEpoch, roomFencingToken,
        expectedProcessRevision, expectedRoomRevision, actorRef, payloadRef, deadlineAt,
        traceId, caseCommandRequestHash);
    String envelopeHash = envelopeHash(
        activationId, activationManifestHash, isolatedDomainDbBindingHash,
        roomFencingToken, commandHash);
    return new TargetEvidenceCompletionCommandMaterial(
        SCHEMA_VERSION, TARGET_LANE, activationId, activationManifestHash,
        isolatedDomainDbBindingHash, tenantSurrogate, caseId, commandId,
        CommandType.PARTY_EVIDENCE_COMPLETE, RoomType.EVIDENCE, roomEpoch,
        roomFencingToken, expectedProcessRevision, expectedRoomRevision, actorRef,
        payloadRef, deadlineAt, traceId, caseCommandRequestHash, commandHash, envelopeHash);
  }

  private static String commandHash(
      String tenantSurrogate,
      String caseId,
      String commandId,
      long roomEpoch,
      long roomFencingToken,
      long expectedProcessRevision,
      long expectedRoomRevision,
      ActorRef actorRef,
      PayloadRef payloadRef,
      Instant deadlineAt,
      String traceId,
      String caseCommandRequestHash) {
    var value = new TreeMap<String, Object>();
    value.put("schema_version", "target-e2e-evidence-completion-command.v1");
    value.put("tenant_surrogate", tenantSurrogate);
    value.put("case_id", caseId);
    value.put("command_id", commandId);
    value.put("command_type", CommandType.PARTY_EVIDENCE_COMPLETE.name());
    value.put("room_type", RoomType.EVIDENCE.name());
    value.put("room_epoch", roomEpoch);
    value.put("room_fencing_token", roomFencingToken);
    value.put("expected_process_revision", expectedProcessRevision);
    value.put("expected_room_revision", expectedRoomRevision);
    value.put("actor_ref", actorRef);
    value.put("payload_ref", payloadRef);
    value.put("deadline_at", deadlineAt);
    value.put("trace_id", traceId);
    value.put("case_command_request_hash", caseCommandRequestHash);
    return ContractJson.sha256Hex(MAPPER.valueToTree(value));
  }

  private static String envelopeHash(
      String activationId,
      String activationManifestHash,
      String databaseBindingHash,
      long roomFencingToken,
      String commandHash) {
    var value = new TreeMap<String, Object>();
    value.put("schema_version", "target-e2e-evidence-completion-command-envelope.v1");
    value.put("execution_lane", TARGET_LANE);
    value.put("activation_id", activationId);
    value.put("activation_manifest_hash", activationManifestHash);
    value.put("isolated_domain_db_binding_hash", databaseBindingHash);
    value.put("room_fencing_token", roomFencingToken);
    value.put("command_hash", commandHash);
    return ContractJson.sha256Hex(MAPPER.valueToTree(value));
  }

  private static void require(boolean value, String field) {
    if (!value) throw new IllegalArgumentException("target Evidence completion material has invalid " + field);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
  }

  private static void requireHash(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }
}
