package com.example.dispute.workflow.targete2e.ingress.rooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomExchangeContract.Authority;
import com.example.dispute.workflow.targete2e.exchange.rooms.TargetE2eRoomObjectIndex;
import com.example.dispute.workflow.targete2e.ingress.materialization.TargetIntakeRuntimePins;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TargetE2eEvidenceManifestPublisherCrossLanguageTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String KEY_ID = "java-target-evidence-es256-1";

  @Test
  public void javaPublishedManifestAndAssetPassThePythonCandidateVerifier() throws Exception {
    KeyPairGenerator keyGenerator = KeyPairGenerator.getInstance("EC");
    keyGenerator.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair keys = keyGenerator.generateKeyPair();
    Map<String, byte[]> objects = new LinkedHashMap<>();
    MinioClient minio = mock(MinioClient.class);
    doAnswer(invocation -> {
      PutObjectArgs args = invocation.getArgument(0);
      objects.put(args.object(), args.stream().readAllBytes());
      return null;
    }).when(minio).putObject(any(PutObjectArgs.class));
    TargetE2eRoomObjectIndex index = mock(TargetE2eRoomObjectIndex.class);
    List<TargetE2eRoomObjectIndex.StoredObject> indexed = new ArrayList<>();
    doAnswer(invocation -> {
      indexed.add(invocation.getArgument(2));
      return null;
    }).when(index).bindInput(any(), any(), any(), any());
    var publisher = new MinioTargetE2eRoomCommandPayloadPublisher(
        minio, MAPPER, "target-e2e", "candidate", index);
    var subject = new TargetE2eEvidenceManifestPublisher(
        publisher, index, signingKey(keys), MAPPER);
    Instant now = Instant.parse("2026-07-28T09:15:00Z");
    var published = subject.publish(new TargetE2eEvidenceManifestPublisher.Input(
        "p9act.v1.0123456789abcdef0123456789abcdef", "tenant-1", "case-1", 7L, 23L,
        "command-1", "logical-run-1", "attempt-1", "grt.v1.0123456789abcdef0123456789abcdef",
        "actor-1", "USER", now.plusSeconds(300), now, pins()));

    byte[] manifestPayload = objects.get(published.manifest().key());
    byte[] assetPayload = objects.get(published.asset().key());
    assertThat(manifestPayload).isNotNull();
    assertThat(assetPayload).isNotNull();
    ObjectNode manifest = (ObjectNode) MAPPER.readTree(manifestPayload);
    assertThat(ContractJson.canonicalize(manifest)).isEqualTo(manifestPayload);
    assertThat(published.manifest().reference().sha256())
        .isEqualTo(ContractJson.sha256Hex(manifest));
    assertThat(published.manifest().reference().sizeBytes()).isEqualTo(manifestPayload.length);
    assertThat(published.asset().reference().sizeBytes()).isEqualTo(assetPayload.length);
    subject.bind(authority(), roomCommand(published.manifest().reference(), now.plusSeconds(300)), published);
    assertThat(indexed).extracting(TargetE2eRoomObjectIndex.StoredObject::objectRef)
        .containsExactlyInAnyOrder(published.manifest().reference().uri(), published.asset().reference().uri());
    assertThat(indexed).allSatisfy(stored -> {
      assertThat(stored.storageKey()).endsWith(stored.sha256() + ".json");
      assertThat(stored.sizeBytes()).isPositive();
    });

    ObjectNode command = targetCommand(published.manifest().reference(), now.plusSeconds(300));
    Path fixture = Files.createTempFile("java-target-evidence-manifest-", ".json");
    try {
      ObjectNode payload = MAPPER.createObjectNode();
      payload.put("manifest_payload_b64", Base64.getEncoder().encodeToString(manifestPayload));
      payload.put("asset_payload_b64", Base64.getEncoder().encodeToString(assetPayload));
      payload.set("command", command);
      payload.put("manifest_hash", manifest.path("manifest_hash").asText());
      payload.put("graph_lease_fencing_token", 29);
      payload.set("jwk", publicJwk((ECPublicKey) keys.getPublic()));
      Files.write(fixture, ContractJson.canonicalize(payload));

      ProcessBuilder processBuilder = new ProcessBuilder(
          "D:\\miniconda\\python.exe", "-m", "pytest", "-q",
          "tests/graph_runtime/test_evidence_manifest_cross_language.py")
          .directory(Path.of("..", "python-agent-service").toFile())
          .redirectErrorStream(true);
      processBuilder.environment().put("TARGET_E2E_JAVA_EVIDENCE_FIXTURE", fixture.toString());
      Process process = processBuilder.start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.waitFor()).withFailMessage(output).isZero();
    } finally {
      Files.deleteIfExists(fixture);
    }
  }

  private static TargetIntakeRuntimePins pins() {
    return new TargetIntakeRuntimePins("case-build", "agent-build", "a".repeat(64), "graph-build",
        "b".repeat(64), "agent-v1", "prompt-v1", "model-v1", "litellm", "policy-v1", "guardrail-v1",
        "tools-v1", "memory-v1", KEY_ID);
  }

  private static GraphEnvelopeSigningKey signingKey(KeyPair keys) {
    return new GraphEnvelopeSigningKey() {
      @Override public String keyId() { return KEY_ID; }
      @Override public byte[] signSha256(byte[] input) {
        try {
          Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
          signer.initSign(keys.getPrivate());
          signer.update(input);
          return signer.sign();
        } catch (Exception error) {
          throw new IllegalStateException(error);
        }
      }
    };
  }

  private static ObjectNode targetCommand(RoomGraphCommand.SnapshotRef snapshot, Instant deadline) {
    ObjectNode command = MAPPER.createObjectNode();
    command.put("schema_version", "room-graph-command.v1"); command.put("command_id", "command-1");
    command.put("logical_run_id", "logical-run-1"); command.put("attempt_id", "attempt-1");
    command.put("tenant_surrogate", "tenant-1"); command.put("case_id", "case-1"); command.put("room_type", "EVIDENCE");
    command.put("room_epoch", 7); command.put("graph_key", "all-rooms.target-e2e.v1");
    command.put("graph_version", "target-e2e-graph.2026-07-27.1"); command.put("checkpoint_schema_version", "target-e2e-checkpoint.v1");
    command.put("thread_id", "grt.v1.0123456789abcdef0123456789abcdef");
    ObjectNode actor = command.putObject("actor_scope"); actor.put("actor_id", "actor-1"); actor.put("actor_role", "USER"); actor.put("audience", "USER"); actor.putArray("capabilities").add("case:case-1:command:EVIDENCE_SUBMIT");
    command.put("process_revision", 3); command.put("stage_code", "EVIDENCE_SUBMIT"); command.put("stage_sequence", 3);
    command.set("domain_snapshot_ref", MAPPER.valueToTree(snapshot));
    ObjectNode invocation = command.putObject("invocation_context"); invocation.put("agent_profile_id", "all-rooms-agent.target-e2e.v1"); invocation.put("prompt_profile_id", "prompt-v1"); invocation.put("model_profile_id", "model-v1"); invocation.put("output_schema_version", "target-e2e-room-proposal-source.v1"); invocation.put("policy_version", "policy-v1"); invocation.put("guardrail_version", "guardrail-v1"); invocation.putArray("tool_capabilities").add("tools-v1"); invocation.put("envelope_key_id", KEY_ID); invocation.put("envelope_nonce", "nonce-1");
    ObjectNode retry = command.putObject("retry_budget"); retry.put("provider_attempts_remaining", 2); retry.put("activity_attempts_remaining", 3); retry.put("repairs_remaining", 1);
    command.put("deadline_at", deadline.toString()); command.put("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
    command.put("request_hash", ContractJson.sha256Hex(command));
    return command;
  }

  private static RoomGraphCommand roomCommand(RoomGraphCommand.SnapshotRef snapshot, Instant deadline) {
    return new RoomGraphCommand("room-graph-command.v1", "command-1", "logical-run-1", "attempt-1",
        "tenant-1", "case-1", RoomType.EVIDENCE, 7L, "all-rooms.target-e2e.v1",
        "target-e2e-graph.2026-07-27.1", "target-e2e-checkpoint.v1",
        "grt.v1.0123456789abcdef0123456789abcdef",
        new RoomGraphCommand.ActorScope("actor-1", ActorRole.USER, Audience.USER,
            List.of("case:case-1:command:EVIDENCE_SUBMIT")),
        3L, "EVIDENCE_SUBMIT", 3L, snapshot, null,
        new RoomGraphCommand.InvocationContext("all-rooms-agent.target-e2e.v1", "prompt-v1", "model-v1",
            "target-e2e-room-proposal-source.v1", "policy-v1", "guardrail-v1", List.of("tools-v1"), KEY_ID,
            "nonce-1"),
        new RoomGraphCommand.RetryBudget(2, 3, 1), deadline,
        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01", "c".repeat(64));
  }

  private static Authority authority() {
    return new Authority("target-e2e-room-exchange-authority.v1", "p9act.v1.0123456789abcdef0123456789abcdef",
        23L, "a".repeat(64), "b".repeat(64), "tenant-1", "case-1", "EVIDENCE", 7L,
        "grt.v1.0123456789abcdef0123456789abcdef", "command-1", "logical-run-1", "attempt-1",
        "c".repeat(64), "all-rooms.target-e2e.v1", "target-e2e-graph.2026-07-27.1",
        "target-e2e-checkpoint.v1", 3L, "EVIDENCE_SUBMIT", 3L);
  }

  private static ObjectNode publicJwk(ECPublicKey key) {
    ObjectNode jwk = MAPPER.createObjectNode();
    jwk.put("kty", "EC"); jwk.put("crv", "P-256"); jwk.put("kid", KEY_ID); jwk.put("use", "sig"); jwk.put("alg", "ES256");
    jwk.put("x", fixedWidth(key.getW().getAffineX().toByteArray()));
    jwk.put("y", fixedWidth(key.getW().getAffineY().toByteArray()));
    return jwk;
  }

  private static String fixedWidth(byte[] value) {
    byte[] result = new byte[32];
    System.arraycopy(value, Math.max(0, value.length - 32), result, Math.max(0, 32 - value.length), Math.min(32, value.length));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(result);
  }
}
