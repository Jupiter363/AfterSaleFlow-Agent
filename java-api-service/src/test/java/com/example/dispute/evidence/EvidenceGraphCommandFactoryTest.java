package com.example.dispute.evidence.application.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.evidence.application.graph.EvidenceAssetAuthorization.ActualLoadReceipt;
import com.example.dispute.evidence.application.graph.EvidenceGraphCommandFactory.CommandRequest;
import com.example.dispute.evidence.application.graph.EvidenceGraphCommandFactory.CurrentAuthority;
import com.example.dispute.evidence.application.graph.EvidenceGraphCommandFactory.RuntimeMode;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceGraphCommandFactoryTest {

  static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
  static final Path EVIDENCE_FIXTURES =
      Path.of("..", "contracts", "agent-platform", "evidence", "v2", "fixtures", "valid");
  static final Path COMMAND_FIXTURE =
      Path.of(
          "..",
          "contracts",
          "agent-platform",
          "v1",
          "fixtures",
          "valid",
          "room-graph-command-evidence-valid.json");
  static final Instant NOW = Instant.parse("2026-07-22T12:05:00Z");
  static final long GRAPH_LEASE_FENCE = 7001L;

  @Test
  void createsProposalOnlyCommandFromExactJavaAuthorityWithoutAJavaFenceField() throws Exception {
    Fixture fixture = fixture();

    RoomGraphCommand command = fixture.command();
    JsonNode json = MAPPER.valueToTree(command);

    assertThat(command.roomType().name()).isEqualTo("EVIDENCE");
    assertThat(command.actorScope().actorId()).isEqualTo(fixture.authority().actorId());
    assertThat(command.invocationContext().outputSchemaVersion())
        .isEqualTo("evidence-batch-proposal.v1");
    assertThat(command.domainSnapshotRef().sha256()).isEqualTo(fixture.manifest().payloadSha256());
    assertThat(json.has("fencing_token")).isFalse();
    assertThat(json.has("graph_lease_fencing_token")).isFalse();
  }

  @Test
  void disabledAndStaleJavaRoomAuthorityFailBeforeCommandCreation() throws Exception {
    Fixture fixture = fixture();
    CurrentAuthority authority = fixture.authority();
    CurrentAuthority disabled =
        authorityWith(authority, RuntimeMode.DISABLED, authority.javaRoomFencingToken());
    CurrentAuthority stale =
        authorityWith(
            authority, RuntimeMode.SIGNED_SYNTHETIC_SHADOW, authority.javaRoomFencingToken() + 1);

    assertThatThrownBy(
            () ->
                new EvidenceGraphCommandFactory()
                    .create(commandRequest(fixture.manifest(), fixture.binding(), disabled)))
        .hasMessage("EVIDENCE_GRAPH_DISABLED");
    assertThatThrownBy(
            () ->
                new EvidenceGraphCommandFactory()
                    .create(commandRequest(fixture.manifest(), fixture.binding(), stale)))
        .hasMessage("EVIDENCE_AUTHORITY_MISMATCH:Java room fence");
  }

  @Test
  void commandRequestRejectsUnpinnedProfilesInvalidCapabilitiesAndUnsafeSequences()
      throws Exception {
    Fixture fixture = fixture();
    CommandRequest base =
        commandRequest(fixture.manifest(), fixture.binding(), fixture.authority());

    assertThatThrownBy(
            () ->
                copyRequest(
                    base,
                    List.of("evidence_parser.read", "evidence_parser.read"),
                    base.agentProfileId(),
                    base.stageSequence()))
        .hasMessage("actorCapabilities must be unique sorted bounded identifiers");
    assertThatThrownBy(
            () ->
                copyRequest(
                    base,
                    List.of("invalid capability"),
                    base.agentProfileId(),
                    base.stageSequence()))
        .hasMessage("actorCapabilities must be unique sorted bounded identifiers");
    List<String> tooManyCapabilities =
        java.util.stream.IntStream.range(0, 33)
            .mapToObj(index -> "capability." + String.format("%02d", index))
            .toList();
    assertThatThrownBy(
            () ->
                copyRequest(base, tooManyCapabilities, base.agentProfileId(), base.stageSequence()))
        .hasMessage("actorCapabilities must be unique sorted bounded identifiers");
    assertThatThrownBy(
            () ->
                copyRequest(
                    base,
                    base.actorCapabilities(),
                    "caller-selected-profile",
                    base.stageSequence()))
        .hasMessage("agentProfileId must be evidence-clerk.v2");
    assertThatThrownBy(
            () ->
                copyRequest(
                    base, base.actorCapabilities(), base.agentProfileId(), 9_007_199_254_740_992L))
        .hasMessage("stage or retry budget is invalid");
  }

  static Fixture fixture() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair keyPair = generator.generateKeyPair();

    ObjectNode manifestClaims =
        (ObjectNode)
            MAPPER.readTree(
                EVIDENCE_FIXTURES
                    .resolve("evidence-batch-manifest-synthetic-1-valid.json")
                    .toFile());
    byte[] payload =
        EvidenceBatchManifest.issueCanonicalPayload(
            manifestClaims, "KEY_P5_SYNTHETIC_ES256_1", keyPair.getPrivate());
    String payloadHash = EvidenceBatchManifest.sha256(payload);
    String uri =
        "s3://evidence-synthetic-manifests/CASE_P5_SYNTHETIC_1/epoch-1/" + payloadHash + ".json";
    EvidenceBatchManifest.SnapshotReference snapshot =
        new EvidenceBatchManifest.SnapshotReference(
            "MANIFEST_P5_SYNTHETIC_ONE",
            EvidenceBatchManifest.SCHEMA_VERSION,
            uri,
            payloadHash,
            payload.length);

    ObjectNode commandDocument =
        (ObjectNode) MAPPER.readTree(COMMAND_FIXTURE.toFile()).required("instance");
    ObjectNode snapshotDocument = (ObjectNode) commandDocument.required("domain_snapshot_ref");
    snapshotDocument.put("uri", uri);
    snapshotDocument.put("sha256", payloadHash);
    snapshotDocument.put("size_bytes", payload.length);
    commandDocument.remove("request_hash");
    commandDocument.put("request_hash", ContractJson.sha256Hex(commandDocument));
    EvidenceBatchManifest manifest =
        EvidenceBatchManifest.verifySignedPayload(
            snapshot,
            payload,
            commandDocument,
            7L,
            GRAPH_LEASE_FENCE,
            GRAPH_LEASE_FENCE,
            NOW,
            ignored -> keyPair.getPublic());
    EvidenceGraphBinding binding =
        EvidenceGraphBinding.create("BINDING_P5_C2", manifest, snapshot, NOW);
    CurrentAuthority authority = authority(manifest);
    EvidenceGraphResultFinalizer.AuthoritySnapshot authoritySnapshot =
        EvidenceGraphResultFinalizer.AuthoritySnapshot.create(
            authority,
            EvidenceGraphCommandFactory.AGENT_PROFILE_ID,
            java.util.Set.of("FACT_ORDER_DAMAGE"),
            java.util.Set.of("SOURCE_SYNTHETIC_001"));
    RoomGraphCommand command =
        new EvidenceGraphCommandFactory().create(commandRequest(manifest, binding, authority));
    ObjectNode terminal =
        (ObjectNode)
            MAPPER.readTree(
                EVIDENCE_FIXTURES.resolve("evidence-terminal-proposal-valid.json").toFile());
    ObjectNode assessment =
        (ObjectNode)
            MAPPER.readTree(
                EVIDENCE_FIXTURES.resolve("evidence-item-proposal-valid.json").toFile());
    ActualLoadReceipt actualLoadReceipt = actualLoadReceipt(manifest);
    assessment.put("asset_load_receipt_hash", actualLoadReceipt.receiptHash());
    assessment = rehash(assessment, "assessment_hash");
    ObjectNode assessmentRef = (ObjectNode) terminal.required("assessment_refs").required(0);
    assessmentRef.put("assessment_hash", assessment.required("assessment_hash").textValue());
    terminal = rehash(terminal, "proposal_hash");
    return new Fixture(
        manifest,
        binding,
        authority,
        authoritySnapshot,
        command,
        terminal,
        assessment,
        actualLoadReceipt);
  }

  static CommandRequest commandRequest(
      EvidenceBatchManifest manifest, EvidenceGraphBinding binding, CurrentAuthority authority) {
    return new CommandRequest(
        "COMMAND_P5_ONE",
        "RUN_P5_ONE",
        "ATTEMPT_P5_ONE_1",
        manifest,
        binding,
        authority,
        List.of("evidence_parser.read"),
        "EVIDENCE_ACTIVE",
        1L,
        "evidence-clerk.v2",
        2,
        3,
        1,
        Instant.parse("2026-07-23T12:00:00Z"),
        "00-cd6153b05b81e9362cced88872f596be-a85b862404e1932f-01",
        "JAVA_GRAPH_COMMAND_ES256_P5_1",
        "NONCE_COMMAND_P5_ONE");
  }

  private static CommandRequest copyRequest(
      CommandRequest source, List<String> capabilities, String agentProfileId, long stageSequence) {
    return new CommandRequest(
        source.commandId(),
        source.logicalRunId(),
        source.attemptId(),
        source.manifest(),
        source.binding(),
        source.currentAuthority(),
        capabilities,
        source.stageCode(),
        stageSequence,
        agentProfileId,
        source.providerAttemptsRemaining(),
        source.activityAttemptsRemaining(),
        source.repairsRemaining(),
        source.deadlineAt(),
        source.traceparent(),
        source.envelopeKeyId(),
        source.envelopeNonce());
  }

  static CurrentAuthority authority(EvidenceBatchManifest manifest) {
    return new CurrentAuthority(
        RuntimeMode.SIGNED_SYNTHETIC_SHADOW,
        manifest.text("tenant_surrogate"),
        manifest.text("case_id"),
        manifest.text("room_id"),
        manifest.number("room_epoch"),
        manifest.number("fencing_token"),
        manifest.text("actor_id"),
        manifest.text("actor_role"),
        manifest.text("participant_id"),
        manifest.text("actor_scope_hash"),
        manifest.text("agent_session_id"),
        manifest.number("submission_revision"),
        4L,
        6L);
  }

  static CurrentAuthority authorityWith(
      CurrentAuthority source, RuntimeMode mode, long javaRoomFence) {
    return new CurrentAuthority(
        mode,
        source.tenantSurrogate(),
        source.caseId(),
        source.roomId(),
        source.roomEpoch(),
        javaRoomFence,
        source.actorId(),
        source.actorRole(),
        source.participantId(),
        source.actorScopeHash(),
        source.agentSessionId(),
        source.sourceRevision(),
        source.processRevision(),
        source.roomRevision());
  }

  static ActualLoadReceipt actualLoadReceipt(EvidenceBatchManifest manifest) {
    ObjectNode item = manifest.requireItem("EVIDENCE_SYNTH_001");
    String receiptId = "ASSET_RECEIPT_P5_001";
    String capabilityId = "CAPABILITY_P5_001";
    String capabilityHash = "a".repeat(64);
    String capabilityNonce = "NONCE_P5_001";
    List<String> modalities = List.of("TEXT", "PDF_METADATA");
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    value.put("receipt_id", receiptId);
    value.put("capability_id", capabilityId);
    value.put("capability_hash", capabilityHash);
    value.put("capability_nonce", capabilityNonce);
    value.put("manifest_id", manifest.manifestId());
    value.put("manifest_hash", manifest.manifestHash());
    value.put("evidence_id", item.required("evidence_id").textValue());
    value.put("item_hash", item.required("item_hash").textValue());
    value.put("object_ref", item.required("object_ref").textValue());
    value.put("immutable_object_version", item.required("immutable_object_version").textValue());
    value.put("object_sha256", item.required("object_sha256").textValue());
    value.put("content_type", item.required("content_type").textValue());
    value.put("byte_size", item.required("byte_size").longValue());
    value.put("java_room_fencing_token", manifest.number("fencing_token"));
    value.put("graph_lease_fencing_token", GRAPH_LEASE_FENCE);
    value.put("load_status", "LOADED");
    value.putPOJO("loaded_modalities", modalities);
    value.put("loaded_at", NOW.toString());
    String receiptHash = ContractJson.sha256Hex(value);
    return new ActualLoadReceipt(
        receiptId,
        receiptHash,
        capabilityId,
        capabilityHash,
        capabilityNonce,
        manifest.manifestId(),
        manifest.manifestHash(),
        item.required("evidence_id").textValue(),
        item.required("item_hash").textValue(),
        item.required("object_ref").textValue(),
        item.required("immutable_object_version").textValue(),
        item.required("object_sha256").textValue(),
        item.required("content_type").textValue(),
        item.required("byte_size").longValue(),
        manifest.number("fencing_token"),
        GRAPH_LEASE_FENCE,
        "LOADED",
        modalities,
        NOW);
  }

  static ObjectNode rehash(ObjectNode value, String field) {
    ObjectNode result = value.deepCopy();
    result.remove(field);
    result.put(field, ContractJson.sha256Hex(result));
    return result;
  }

  record Fixture(
      EvidenceBatchManifest manifest,
      EvidenceGraphBinding binding,
      CurrentAuthority authority,
      EvidenceGraphResultFinalizer.AuthoritySnapshot authoritySnapshot,
      RoomGraphCommand command,
      ObjectNode terminal,
      ObjectNode assessment,
      ActualLoadReceipt actualLoadReceipt) {}
}
