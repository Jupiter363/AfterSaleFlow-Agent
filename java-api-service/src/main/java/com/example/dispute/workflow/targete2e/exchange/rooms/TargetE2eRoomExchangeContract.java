package com.example.dispute.workflow.targete2e.exchange.rooms;

import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Objects;
import java.util.regex.Pattern;

/** Frozen HTTP contract for the isolated non-Intake target-room object exchange. */
public final class TargetE2eRoomExchangeContract {
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern ACTIVATION = Pattern.compile("p9act\\.v1\\.[0-9a-f]{32}");
  private static final Pattern ROOM = Pattern.compile("EVIDENCE|HEARING|REVIEW");
  private TargetE2eRoomExchangeContract() {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Authority(String schemaVersion, String activationId, long roomFencingToken,
      String commandHash, String commandEnvelopeHash, String tenantSurrogate, String caseId,
      String roomType, long roomEpoch, String threadId, String commandId, String logicalRunId,
      String attemptId, String requestHash, String graphKey, String graphVersion,
      String checkpointSchemaVersion, long processRevision, String stageCode, long stageSequence) {
    public Authority {
      exact(schemaVersion, "target-e2e-room-exchange-authority.v1", "authority schema");
      if (activationId == null || !ACTIVATION.matcher(activationId).matches()) invalid("activationId");
      if (roomFencingToken < 1 || roomEpoch < 0 || processRevision < 0 || stageSequence < 0) invalid("authority revision");
      hash(commandHash, "commandHash"); hash(commandEnvelopeHash, "commandEnvelopeHash"); hash(requestHash, "requestHash");
      id(tenantSurrogate, "tenantSurrogate"); id(caseId, "caseId"); id(threadId, "threadId"); id(commandId, "commandId");
      id(logicalRunId, "logicalRunId"); id(attemptId, "attemptId"); id(graphKey, "graphKey");
      id(graphVersion, "graphVersion"); id(checkpointSchemaVersion, "checkpointSchemaVersion"); id(stageCode, "stageCode");
      if (roomType == null || !ROOM.matcher(roomType).matches()) invalid("roomType");
      exact(graphKey, TargetTypedRoomProtocol.GRAPH_KEY, "graphKey");
      if (!TargetTypedRoomProtocol.supportsGraphVersion(graphVersion)) invalid("graphVersion");
      exact(checkpointSchemaVersion, TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION, "checkpointSchemaVersion");
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ObjectRef(String artifactId, String schemaVersion, String uri, String sha256, long sizeBytes) {
    public ObjectRef { id(artifactId, "artifactId"); id(schemaVersion, "schemaVersion"); opaqueObjectRef(uri, schemaVersion); hash(sha256, "sha256"); if (sizeBytes < 1 || sizeBytes > 524288) invalid("sizeBytes"); }
  }
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record LoadRequest(String schemaVersion, Authority authority, ObjectRef objectRef) {
    public LoadRequest { exact(schemaVersion, "target-e2e-room-object-load-request.v1", "load schema"); authority = Objects.requireNonNull(authority); objectRef = Objects.requireNonNull(objectRef); }
  }
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record LoadReceipt(String artifactId, String schemaVersion, String uri, String sha256, long sizeBytes) {
    public LoadReceipt { id(artifactId, "artifactId"); id(schemaVersion, "schemaVersion"); opaqueObjectRef(uri, schemaVersion); hash(sha256, "sha256"); if (sizeBytes < 1 || sizeBytes > 524288) invalid("sizeBytes"); }
  }
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record LoadResponse(String schemaVersion, Authority authority, LoadReceipt receipt, String canonicalPayloadBase64) {
    public LoadResponse { exact(schemaVersion, "target-e2e-room-object-load-response.v1", "load response schema"); authority = Objects.requireNonNull(authority); receipt = Objects.requireNonNull(receipt); if (canonicalPayloadBase64 == null || canonicalPayloadBase64.length() > 700000) invalid("payload"); }
  }
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Proposal(String proposalId, String schemaVersion, String sha256, long sizeBytes, String canonicalPayloadBase64) {
    public Proposal { id(proposalId, "proposalId"); id(schemaVersion, "schemaVersion"); hash(sha256, "sha256"); if (sizeBytes < 1 || sizeBytes > 65536) invalid("proposal size"); if (canonicalPayloadBase64 == null || canonicalPayloadBase64.length() > 90000) invalid("proposal payload"); }
  }
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PutRequest(String schemaVersion, Authority authority, Proposal proposal, String checkpointNs, String checkpointId, long cognitiveRevision) {
    public PutRequest { exact(schemaVersion, "target-e2e-room-proposal-put-request.v1", "put schema"); authority = Objects.requireNonNull(authority); proposal = Objects.requireNonNull(proposal); if (checkpointNs == null || checkpointNs.length() > 128 || checkpointId == null || checkpointId.isBlank() || checkpointId.length() > 128 || cognitiveRevision < 1) invalid("checkpoint"); }
  }
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ProposalReceipt(String proposalId, String schemaVersion, String sha256, long sizeBytes, String payloadRef) {
    public ProposalReceipt { id(proposalId, "proposalId"); id(schemaVersion, "schemaVersion"); hash(sha256, "sha256"); if (sizeBytes < 1 || sizeBytes > 65536 || payloadRef == null || !payloadRef.matches("urn:target-e2e:proposal:(evidence|hearing|review):[0-9a-f]{64}")) invalid("proposal receipt"); }
  }
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PutResponse(String schemaVersion, Authority authority, ProposalReceipt receipt) {
    public PutResponse { exact(schemaVersion, "target-e2e-room-proposal-put-response.v1", "put response schema"); authority = Objects.requireNonNull(authority); receipt = Objects.requireNonNull(receipt); }
  }
  static void id(String value, String field) { if (value == null || !ID.matcher(value).matches()) invalid(field); }
  static void hash(String value, String field) { if (value == null || !HASH.matcher(value).matches()) invalid(field); }
  static void exact(String value, String expected, String field) { if (!expected.equals(value)) invalid(field); }
  static void opaqueObjectRef(String value, String schemaVersion) {
    boolean commandObject = value != null && value.matches("urn:target-e2e:object:[A-Za-z0-9._:-]{1,480}");
    boolean verifiedEvidenceParse = "target-e2e-evidence-asset.v1".equals(schemaVersion)
        && value != null && value.matches("urn:synthetic-evidence-parse:[A-Za-z0-9._:-]{1,480}");
    if (!commandObject && !verifiedEvidenceParse) invalid("uri");
  }
  static void invalid(String field) { throw new IllegalArgumentException("target E2E room exchange has invalid " + field); }
}
