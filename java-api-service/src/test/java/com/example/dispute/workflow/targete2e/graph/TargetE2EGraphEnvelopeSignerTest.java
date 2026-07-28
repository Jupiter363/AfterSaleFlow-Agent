package com.example.dispute.workflow.targete2e.graph;

import static com.example.dispute.workflow.targete2e.graph.TargetE2EGraphTestFixtures.ACTIVATION_ID;
import static com.example.dispute.workflow.targete2e.graph.TargetE2EGraphTestFixtures.MAPPER;
import static com.example.dispute.workflow.targete2e.graph.TargetE2EGraphTestFixtures.REGISTRY_BINDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TargetE2EGraphEnvelopeSignerTest {

  private static final String KEY_ID = "java-invocation-es256-1";
  private static final Instant NOW = Instant.parse("2026-07-27T08:00:00.500Z");
  private static KeyPair keyPair;

  @BeforeAll
  static void generateKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    keyPair = generator.generateKeyPair();
  }

  @Test
  void signsExactTargetClaimsWithValidEs256AndNoActivationCredentialMaterial() throws Exception {
    var codec = TargetE2EGraphTestFixtures.codec();
    var command = codec.wrapCommand(ACTIVATION_ID, 7L, TargetE2EGraphTestFixtures.command());
    var signed = signer(KEY_ID).sign(command, REGISTRY_BINDING);
    String[] segments = signed.compactJws().split("\\.", -1);
    ObjectNode header = decode(segments[0]);
    ObjectNode claims = decode(segments[1]);

    assertThat(header.properties().stream().map(java.util.Map.Entry::getKey).toList())
        .containsExactlyInAnyOrder("alg", "kid", "typ");
    assertThat(header.path("alg").asText()).isEqualTo("ES256");
    assertThat(header.path("kid").asText()).isEqualTo(KEY_ID);
    assertThat(header.path("typ").asText()).isEqualTo("target-e2e-graph-command+jwt");
    assertThat(
            claims.properties().stream()
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()))
        .isEqualTo(
            Set.of(
                "iss",
                "aud",
                "sub",
                "iat",
                "nbf",
                "exp",
                "jti",
                "command_id",
                "command_nonce",
                "request_hash",
                "tenant_surrogate",
                "case_id",
                "room_epoch",
                "thread_id",
                "graph_key",
                "graph_version",
                "checkpoint_schema_version",
                "actor_scope_hash",
                "capabilities_hash",
                "profile_bindings_hash",
                "execution_lane",
                "activation_id",
                "room_fencing_token",
                "command_hash",
                "command_envelope_hash"));
    assertThat(claims.path("execution_lane").asText())
        .isEqualTo(TargetE2EGraphCommandEnvelope.EXECUTION_LANE);
    assertThat(claims.path("activation_id").asText()).isEqualTo(ACTIVATION_ID);
    assertThat(claims.path("room_fencing_token").asLong()).isEqualTo(7L);
    assertThat(claims.path("command_hash").asText()).isEqualTo(command.commandHash());
    assertThat(claims.path("command_envelope_hash").asText())
        .isEqualTo(command.commandEnvelopeHash());
    assertThat(claims.path("room_epoch").asLong()).isEqualTo(command.command().roomEpoch());
    assertThat(claims.path("request_hash").asText()).isEqualTo(command.command().requestHash());
    assertThat(claims.has("activation_jws")).isFalse();
    assertThat(claims.has("manifest_hash")).isFalse();
    assertThat(claims.has("candidate_sha")).isFalse();
    assertThat(claims.has("environment_id")).isFalse();

    Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
    verifier.initVerify(keyPair.getPublic());
    verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(verifier.verify(Base64.getUrlDecoder().decode(segments[2]))).isTrue();
    assertThat(signed.issuedAt()).isEqualTo(Instant.parse("2026-07-27T08:00:00Z"));
    assertThat(signed.expiresAt()).isEqualTo(Instant.parse("2026-07-27T08:00:45Z"));
  }

  @Test
  void refusesToSignTamperedWrapperOrMismatchedKey() {
    var codec = TargetE2EGraphTestFixtures.codec();
    var valid = codec.wrapCommand(ACTIVATION_ID, 7L, TargetE2EGraphTestFixtures.command());
    var tampered =
        new TargetE2EGraphCommandEnvelope(
            valid.schemaVersion(),
            valid.executionLane(),
            valid.activationId(),
            valid.roomFencingToken(),
            "0".repeat(64),
            valid.commandEnvelopeHash(),
            valid.command());

    assertThatThrownBy(() -> signer(KEY_ID).sign(tampered, REGISTRY_BINDING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hash mismatch");
    assertThatThrownBy(() -> signer("other-key").sign(valid, REGISTRY_BINDING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
  }

  @Test
  void signsTheExactJavaIssuedAgentSessionWhenTheTargetResolverSuppliesOne()
      throws Exception {
    var codec = TargetE2EGraphTestFixtures.codec();
    var command = codec.wrapCommand(ACTIVATION_ID, 7L, TargetE2EGraphTestFixtures.command());
    var signer =
        new Es256TargetE2EGraphEnvelopeSigner(
            requestedKey -> signingKey(requestedKey),
            MAPPER,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofSeconds(45),
            () -> "target-command-jti-001",
            ignored -> "AGENT_SESSION_java_issued_001");

    var signed = signer.sign(command, REGISTRY_BINDING);
    String[] segments = signed.compactJws().split("\\.", -1);
    ObjectNode claims = decode(segments[1]);

    assertThat(claims.path("agent_session_id").asText())
        .isEqualTo("AGENT_SESSION_java_issued_001");
    Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
    verifier.initVerify(keyPair.getPublic());
    verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(verifier.verify(Base64.getUrlDecoder().decode(segments[2]))).isTrue();
  }

  private static Es256TargetE2EGraphEnvelopeSigner signer(String keyId) {
    return new Es256TargetE2EGraphEnvelopeSigner(
        signingKey(keyId),
        MAPPER,
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofSeconds(45),
        () -> "target-command-jti-001");
  }

  private static GraphEnvelopeSigningKey signingKey(String keyId) {
    return new GraphEnvelopeSigningKey() {
      @Override
      public String keyId() {
        return keyId;
      }

      @Override
      public byte[] signSha256(byte[] signingInput) {
        try {
          Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
          signer.initSign(keyPair.getPrivate());
          signer.update(signingInput);
          return signer.sign();
        } catch (Exception exception) {
          throw new IllegalStateException(exception);
        }
      }
    };
  }

  private static ObjectNode decode(String segment) throws Exception {
    return (ObjectNode) MAPPER.readTree(Base64.getUrlDecoder().decode(segment));
  }
}
