package com.example.dispute.workflow.targete2e.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.ActivationIdentity;
import com.example.dispute.workflow.targete2e.lifecycle.TargetE2eActivationLifecycleControl.DeploymentBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TargetE2eDrainCompletionAttestationVerifierTest {

  private static final Instant NOW = Instant.parse("2026-07-30T08:00:00Z");
  private static final String KEY_ID = "p9-harness-912432252e06";
  private static final String ENVIRONMENT_ID = "p9-isolated-run-1";
  private static final long GENERATION = 7;
  private static final String ACTIVATION_ID = "p9act.v1." + "a".repeat(32);
  private static final String MANIFEST_HASH = "b".repeat(64);
  private static final String RUNTIME_CONTEXT_HASH = "c".repeat(64);
  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

  private KeyPair keyPair;
  private TargetE2eDrainCompletionAttestationVerifier verifier;
  private ActivationIdentity identity;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    keyPair = generator.generateKeyPair();
    identity =
        new ActivationIdentity(ENVIRONMENT_ID, GENERATION, ACTIVATION_ID, MANIFEST_HASH);
    verifier =
        new TargetE2eDrainCompletionAttestationVerifier(
            (ECPublicKey) keyPair.getPublic(),
            KEY_ID,
            fingerprint((ECPublicKey) keyPair.getPublic()),
            new DeploymentBinding(
                true,
                ENVIRONMENT_ID,
                GENERATION,
                ACTIVATION_ID,
                MANIFEST_HASH,
                RUNTIME_CONTEXT_HASH),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void verifiesIndependentExactDrainEvidence() throws Exception {
    var verified = verifier.verify(compact(proof -> {}), identity);

    assertThat(verified.keyId()).isEqualTo(KEY_ID);
    assertThat(verified.proof().complete()).isTrue();
    assertThat(verified.proof().completedAt()).isEqualTo(NOW.minusSeconds(1));
    assertThat(verified.proof().evidenceLedgerHeadHash()).isEqualTo("d".repeat(64));
    assertThat(verified.proof().forensicManifestHash()).isEqualTo("e".repeat(64));
  }

  @Test
  void rejectsCallerEquivalentCountersWithoutExactDetachedAndSealedEvidence() throws Exception {
    assertThatThrownBy(
            () ->
                verifier.verify(
                    compact(proof -> proof.put("unresolvedAcceptedWork", 1)), identity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("binding");
    assertThatThrownBy(
            () -> verifier.verify(compact(proof -> proof.put("evidenceSealed", false)), identity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("binding");
    assertThatThrownBy(
            () ->
                verifier.verify(
                    compact(
                        proof ->
                            proof.set(
                                "detachedExecutionServices",
                                JsonMapper.builder()
                                    .build()
                                    .createArrayNode()
                                    .add("java-agent-worker"))),
                    identity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("binding");
  }

  @Test
  void rejectsWrongSignatureDeploymentBindingAndExpiredCredential() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair other = generator.generateKeyPair();

    assertThatThrownBy(() -> verifier.verify(compact(other, proof -> {}, 30), identity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("signature");
    assertThatThrownBy(
            () ->
                verifier.verify(
                    compact(proof -> proof.put("runtimeContextHash", "f".repeat(64))), identity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("binding");
    assertThatThrownBy(() -> verifier.verify(compact(keyPair, proof -> {}, 0), identity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("time window");
  }

  private String compact(Consumer<ObjectNode> mutateProof) throws Exception {
    return compact(keyPair, mutateProof, 30);
  }

  private String compact(KeyPair signingKey, Consumer<ObjectNode> mutateProof, int lifetimeSeconds)
      throws Exception {
    ObjectMapper mapper = JsonMapper.builder().build();
    ObjectNode proof = mapper.createObjectNode();
    proof.put("schemaVersion", "target-e2e-drain-completion-proof.v1");
    proof.put("executionLane", "TARGET_E2E_CANDIDATE");
    proof.put("authority", "HARNESS_DRAIN_MEASURER");
    proof.put("activationId", ACTIVATION_ID);
    proof.put("environmentId", ENVIRONMENT_ID);
    proof.put("environmentGeneration", GENERATION);
    proof.put("manifestHash", MANIFEST_HASH);
    proof.put("runtimeContextHash", RUNTIME_CONTEXT_HASH);
    proof.put("unresolvedAcceptedWork", 0);
    ArrayNode services = proof.putArray("detachedExecutionServices");
    services.add("java-agent-worker");
    services.add("java-control-worker");
    proof.put("evidenceSealed", true);
    proof.put("evidenceLedgerHeadHash", "d".repeat(64));
    proof.put("forensicManifestHash", "e".repeat(64));
    proof.put("completedAt", NOW.minusSeconds(1).toString());
    mutateProof.accept(proof);
    proof.put("proofHash", ContractJson.sha256Hex(proof.deepCopy()));

    ObjectNode claims = mapper.createObjectNode();
    claims.put("iss", "target-e2e-harness");
    claims.put("aud", "java-api-service");
    claims.put("sub", "target-e2e-drain-completion");
    claims.put("iat", NOW.getEpochSecond());
    claims.put("nbf", NOW.getEpochSecond());
    claims.put("exp", NOW.plusSeconds(lifetimeSeconds).getEpochSecond());
    claims.put("jti", "p9-drain-proof-1");
    claims.set("proof", proof);
    ObjectNode header = mapper.createObjectNode();
    header.put("alg", "ES256");
    header.put("kid", KEY_ID);
    header.put("typ", TargetE2eDrainCompletionAttestationVerifier.JWT_TYPE);
    String encodedHeader = BASE64_URL.encodeToString(ContractJson.canonicalize(header));
    String encodedClaims = BASE64_URL.encodeToString(ContractJson.canonicalize(claims));
    byte[] signingInput =
        (encodedHeader + "." + encodedClaims).getBytes(StandardCharsets.US_ASCII);
    Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
    signature.initSign(signingKey.getPrivate());
    signature.update(signingInput);
    return encodedHeader
        + "."
        + encodedClaims
        + "."
        + BASE64_URL.encodeToString(signature.sign());
  }

  private static String fingerprint(ECPublicKey key) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
  }
}
