package com.example.dispute.workflow.targete2e.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.ActivationIdentity;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.LifecycleState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TargetE2eActivationLifecycleReceiptSignerTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
  private static final Instant NOW = Instant.parse("2026-07-30T08:00:00.900000Z");
  private static final ActivationIdentity IDENTITY =
      new ActivationIdentity(
          "isolated-preprod-cn-1",
          12,
          "p9act.v1." + "a".repeat(32),
          "b".repeat(64));
  private static KeyPair keyPair;

  @BeforeAll
  static void generateKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    keyPair = generator.generateKeyPair();
  }

  @Test
  void issuesStrictSelfHashedReceiptWithValidEs256Signature() throws Exception {
    TargetE2eActivationLifecycleReceiptSigner signer =
        new TargetE2eActivationLifecycleReceiptSigner(
            signingKey("java-target-key-1"), MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));

    String compact =
        signer.issue(
            IDENTITY,
            "c".repeat(64),
            LifecycleState.DRAIN_ONLY,
            LifecycleState.DRAINED,
            NOW.minusNanos(1));
    String[] segments = compact.split("\\.", -1);
    ObjectNode header = decodeObject(segments[0]);
    ObjectNode claims = decodeObject(segments[1]);
    ObjectNode receipt = (ObjectNode) claims.required("receipt");

    assertThat(segments).hasSize(3);
    assertThat(header.properties().stream().map(java.util.Map.Entry::getKey).toList())
        .containsExactlyInAnyOrder("alg", "kid", "typ");
    assertThat(header.path("alg").asText()).isEqualTo("ES256");
    assertThat(header.path("kid").asText()).isEqualTo("java-target-key-1");
    assertThat(header.path("typ").asText())
        .isEqualTo(TargetE2eActivationLifecycleReceiptSigner.JWT_TYPE);
    assertThat(
            claims.properties().stream()
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()))
        .isEqualTo(Set.of("iss", "aud", "sub", "iat", "nbf", "exp", "jti", "receipt"));
    assertThat(claims.path("iss").asText()).isEqualTo("java-api-service");
    assertThat(claims.path("aud").asText()).isEqualTo("python-agent-service");
    assertThat(claims.path("sub").asText()).isEqualTo("target-e2e-lifecycle-reconcile");
    assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond());
    assertThat(claims.path("exp").asLong()).isEqualTo(NOW.plusSeconds(60).getEpochSecond());
    assertThat(receipt.path("activationId").asText()).isEqualTo(IDENTITY.activationId());
    assertThat(receipt.path("manifestHash").asText()).isEqualTo(IDENTITY.manifestHash());
    assertThat(receipt.path("runtimeContextHash").asText()).isEqualTo("c".repeat(64));
    assertThat(receipt.path("fromState").asText()).isEqualTo("DRAIN_ONLY");
    assertThat(receipt.path("toState").asText()).isEqualTo("DRAINED");
    ObjectNode unhashed = receipt.deepCopy();
    String receiptHash = unhashed.remove("receiptHash").asText();
    assertThat(receiptHash).isEqualTo(ContractJson.sha256Hex(unhashed));

    Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
    verifier.initVerify(keyPair.getPublic());
    verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(verifier.verify(Base64.getUrlDecoder().decode(segments[2]))).isTrue();
  }

  @Test
  void rejectsFutureAndNonAdjacentLifecycleStatements() {
    TargetE2eActivationLifecycleReceiptSigner signer =
        new TargetE2eActivationLifecycleReceiptSigner(
            signingKey("java-target-key-1"), MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(
            () ->
                signer.issue(
                    IDENTITY,
                    "c".repeat(64),
                    LifecycleState.DRAIN_ONLY,
                    LifecycleState.DRAINED,
                    NOW.plusNanos(1_000)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("future");
    assertThatThrownBy(
            () ->
                signer.issue(
                    IDENTITY,
                    "c".repeat(64),
                    LifecycleState.ACTIVE,
                    LifecycleState.DRAINED,
                    NOW.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not adjacent");
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
          Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
          signature.initSign(keyPair.getPrivate());
          signature.update(signingInput);
          return signature.sign();
        } catch (Exception failure) {
          throw new IllegalStateException(failure);
        }
      }
    };
  }

  private static ObjectNode decodeObject(String segment) throws Exception {
    JsonNode node = MAPPER.readTree(Base64.getUrlDecoder().decode(segment));
    assertThat(node).isInstanceOf(ObjectNode.class);
    return (ObjectNode) node;
  }
}
