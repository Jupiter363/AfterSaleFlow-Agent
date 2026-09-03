package com.example.dispute.workflow.targete2e.ingress.rooms;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.Authority;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomObjectIndex;
import com.example.dispute.workflow.targete2e.ingress.materialization.TargetIntakeRuntimePins;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Builds the exact Python-verified Evidence manifest with the target Graph signing identity. */
public final class TargetE2eEvidenceManifestPublisher {
  private final MinioTargetE2eRoomCommandPayloadPublisher publisher;
  private final TargetE2eRoomObjectIndex index;
  private final GraphEnvelopeSigningKey signingKey;
  private final ObjectMapper mapper;

  public TargetE2eEvidenceManifestPublisher(MinioTargetE2eRoomCommandPayloadPublisher publisher,
      TargetE2eRoomObjectIndex index, GraphEnvelopeSigningKey signingKey, ObjectMapper mapper) {
    this.publisher = Objects.requireNonNull(publisher); this.index = Objects.requireNonNull(index);
    this.signingKey = Objects.requireNonNull(signingKey); this.mapper = Objects.requireNonNull(mapper).copy();
  }

  public Published publish(Input input) {
    if (!"USER".equals(input.actorRole()) && !"MERCHANT".equals(input.actorRole())) {
      throw new IllegalArgumentException("Evidence manifest requires a submitting party actor");
    }
    ObjectNode asset = mapper.createObjectNode();
    asset.put("schema_version", "target-e2e-evidence-asset.v1"); asset.put("content", "Synthetic browser evidence " + input.commandId());
    asset.putArray("source_refs").add("SOURCE_" + token(input.commandId()));
    asset.putArray("inspected_modalities").add("TEXT"); asset.put("receipt_ref", "RECEIPT_" + token(input.commandId()));
    asset.put("receipt_hash", ContractJson.sha256Hex(mapper.createObjectNode().put("command_id", input.commandId())));
    String assetId = "asset:" + token(input.commandId());
    var rawAsset = publisher.publishCanonical(assetId, "EVIDENCE", asset);
    String parseRef = rawAsset.reference().uri();
    var assetObject = rawAsset;

    ObjectNode item = mapper.createObjectNode();
    item.put("schema_version", "evidence-item-manifest.v1"); item.put("evidence_id", "EVIDENCE_" + token(input.commandId()));
    item.put("owner_participant_id", input.actorId()); item.put("owner_role", input.actorRole()); item.put("visibility", "PARTIES");
    item.put("object_ref", "urn:synthetic-evidence:object:" + token(input.commandId())); item.put("immutable_object_version", "v1");
    item.put("object_sha256", rawAsset.reference().sha256()); item.put("content_type", "text/plain"); item.put("byte_size", rawAsset.reference().sizeBytes());
    item.put("original_filename", "fixture-" + token(input.commandId()) + ".txt"); item.put("parse_ref", parseRef); item.put("parse_hash", rawAsset.reference().sha256());
    item.put("parse_status", "AVAILABLE"); item.put("privacy_basis", "SIGNED_SYNTHETIC_FIXTURE"); item.putArray("permitted_modalities").add("TEXT");
    item.put("formal_evidence_revision", 1); item.put("display_order", 0);
    ObjectNode itemPreimage = item.deepCopy(); itemPreimage.remove("item_hash"); item.put("item_hash", ContractJson.sha256Hex(itemPreimage));

    ObjectNode actorScope = mapper.createObjectNode(); actorScope.put("actor_id", input.actorId()); actorScope.put("actor_role", input.actorRole());
    actorScope.put("audience", input.actorRole()); actorScope.putArray("capabilities").add("case:" + input.caseId() + ":command:EVIDENCE_SUBMIT");
    String now = input.now().toString();
    ObjectNode manifest = mapper.createObjectNode();
    manifest.put("schema_version", "evidence-batch-manifest.v1"); manifest.put("manifest_id", "manifest:" + token(input.commandId()));
    manifest.put("execution_scope", "TARGET_E2E_CANDIDATE"); manifest.put("writer_mode", "PROPOSAL_ONLY"); manifest.put("formal_sink_eligible", false); manifest.put("graph_execution_allowed", true);
    manifest.put("synthetic_fixture_id", "fixture:" + token(input.commandId())); manifest.put("registration_id", input.activationId()); manifest.put("tenant_surrogate", input.tenant()); manifest.put("case_id", input.caseId());
    manifest.put("room_id", "room:" + input.caseId() + ":evidence"); manifest.put("room_type", "EVIDENCE"); manifest.put("room_epoch", input.roomEpoch()); manifest.put("fencing_token", input.fence());
    manifest.put("thread_id", input.threadId()); manifest.put("actor_id", input.actorId()); manifest.put("actor_role", input.actorRole()); manifest.put("participant_id", input.actorId());
    manifest.put("actor_scope_hash", ContractJson.sha256Hex(actorScope)); manifest.put("agent_session_id", "session:" + token(input.commandId()));
    ObjectNode binding = manifest.putObject("command_binding"); binding.put("schema_version", "evidence-room-command.v1"); binding.put("command_id", input.commandId()); binding.put("logical_run_id", input.logicalRunId()); binding.put("attempt_id", input.attemptId()); binding.put("command_type", "EVIDENCE_ASSESS_BATCH"); binding.put("submitted_at", now); binding.put("deadline_at", input.deadline().toString());
    manifest.put("submission_batch_id", "batch:" + token(input.commandId())); manifest.put("submission_revision", 1); manifest.put("dossier_target_version", 1);
    ObjectNode profiles = manifest.putObject("profile_versions"); profiles.put("graph_version", TargetTypedRoomProtocol.GRAPH_VERSION); profiles.put("checkpoint_schema_version", TargetTypedRoomProtocol.CHECKPOINT_SCHEMA_VERSION); profiles.put("state_schema_version", "evidence-graph-state.v2"); profiles.put("prompt_version", input.pins().promptVersion()); profiles.put("model_profile_id", input.pins().modelProfileId()); profiles.put("assessment_output_schema_version", "evidence-item-assessment.v1"); profiles.put("terminal_output_schema_version", "evidence-batch-proposal.v1"); profiles.put("policy_version", input.pins().policyVersion()); profiles.put("guardrail_version", input.pins().guardrailVersion()); profiles.put("tool_policy_version", input.pins().toolPolicyVersion());
    manifest.put("issued_at", now); manifest.put("not_before", now); manifest.put("expires_at", input.deadline().toString()); manifest.put("item_count", 1); manifest.putArray("ordered_item_keys").add(item.path("evidence_id").asText()); manifest.putArray("items").add(item);
    manifest.put("signature_algorithm", "ES256"); manifest.put("signing_key_id", signingKey.keyId());
    manifest.put("manifest_hash", ContractJson.sha256Hex(manifest));
    byte[] signature = signingKey.signSha256(manifest.path("manifest_hash").asText().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    if (signature == null || signature.length != 64) throw new IllegalStateException("target Graph signer did not return ES256 P1363");
    manifest.put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
    var manifestObject = publisher.publishCanonical(manifest.path("manifest_id").asText(), "EVIDENCE", manifest);
    return new Published(manifestObject, assetObject);
  }

  public void bind(Authority authority, RoomGraphCommand command, Published published) {
    publisher.bind(authority, command, published.manifest(), TargetE2eRoomObjectIndex.Kind.COMMAND_INPUT);
    publisher.bind(authority, command, published.asset(), TargetE2eRoomObjectIndex.Kind.MANIFEST_ASSET);
  }
  public record Input(String activationId, String tenant, String caseId, long roomEpoch, long fence, String commandId,
      String logicalRunId, String attemptId, String threadId, String actorId, String actorRole, Instant deadline, Instant now, TargetIntakeRuntimePins pins) {}
  public record Published(MinioTargetE2eRoomCommandPayloadPublisher.PublishedObject manifest, MinioTargetE2eRoomCommandPayloadPublisher.PublishedObject asset) {}
  private static String token(String value) { return java.util.UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", ""); }
}
