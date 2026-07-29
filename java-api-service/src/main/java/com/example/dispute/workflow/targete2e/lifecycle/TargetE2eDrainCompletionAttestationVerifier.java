package com.example.dispute.workflow.targete2e.lifecycle;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.ActivationIdentity;
import com.example.dispute.workflow.targete2e.TargetE2eActivationLifecycleStore.DrainCompletionProof;
import com.example.dispute.workflow.targete2e.lifecycle.TargetE2eActivationLifecycleControl.DeploymentBinding;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Verifies the independent harness measurement required before DRAINED can be persisted. */
public final class TargetE2eDrainCompletionAttestationVerifier {

  public static final String JWT_TYPE = "target-e2e-drain-completion-proof+jwt";
  private static final String SCHEMA_VERSION = "target-e2e-drain-completion-proof.v1";
  private static final String EXECUTION_LANE = "TARGET_E2E_CANDIDATE";
  private static final String AUTHORITY = "HARNESS_DRAIN_MEASURER";
  private static final Set<String> DETACHED_EXECUTION_SERVICES =
      Set.of("java-agent-worker", "java-control-worker");
  private static final int MAXIMUM_JWS_CHARACTERS = 24 * 1024;
  private static final int MAXIMUM_HEADER_BYTES = 2 * 1024;
  private static final int MAXIMUM_PAYLOAD_BYTES = 16 * 1024;
  private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");
  private static final Pattern IDENTIFIER =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/\\-]{0,127}");
  private static final Pattern ACTIVATION_ID = Pattern.compile("p9act\\.v1\\.[0-9a-f]{32}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
  private static final Base64.Encoder BASE64_URL_ENCODER =
      Base64.getUrlEncoder().withoutPadding();
  private static final Set<String> HEADER_FIELDS = Set.of("alg", "kid", "typ");
  private static final Set<String> CLAIM_FIELDS =
      Set.of("iss", "aud", "sub", "iat", "nbf", "exp", "jti", "proof");
  private static final Set<String> PROOF_FIELDS =
      Set.of(
          "schemaVersion",
          "executionLane",
          "authority",
          "activationId",
          "environmentId",
          "environmentGeneration",
          "manifestHash",
          "runtimeContextHash",
          "unresolvedAcceptedWork",
          "detachedExecutionServices",
          "evidenceSealed",
          "evidenceLedgerHeadHash",
          "forensicManifestHash",
          "completedAt",
          "proofHash");

  private final ECPublicKey publicKey;
  private final String expectedKeyId;
  private final String expectedKeyFingerprint;
  private final DeploymentBinding deploymentBinding;
  private final Clock clock;
  private final ObjectMapper mapper;

  public TargetE2eDrainCompletionAttestationVerifier(
      ECPublicKey publicKey,
      String expectedKeyId,
      String expectedKeyFingerprint,
      DeploymentBinding deploymentBinding,
      Clock clock) {
    this.publicKey = requireP256(publicKey);
    this.expectedKeyId = requireIdentifier(expectedKeyId, "drain attestation key ID");
    this.expectedKeyFingerprint = requireHash(expectedKeyFingerprint, "drain attestation key");
    if (!same(this.expectedKeyFingerprint, fingerprint(this.publicKey))) {
      throw new IllegalArgumentException("drain attestation public key fingerprint is invalid");
    }
    this.deploymentBinding = Objects.requireNonNull(deploymentBinding, "deploymentBinding");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  }

  public VerifiedDrainCompletion verify(String compactJws, ActivationIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    deploymentBinding.requireExact(identity);
    if (compactJws == null
        || compactJws.isBlank()
        || compactJws.length() > MAXIMUM_JWS_CHARACTERS) {
      throw new IllegalArgumentException("drain completion attestation is absent or oversized");
    }
    String[] segments = compactJws.split("\\.", -1);
    if (segments.length != 3) {
      throw new IllegalArgumentException("drain completion attestation is not compact JWS");
    }
    ObjectNode header = parseCanonicalObject(decode(segments[0], MAXIMUM_HEADER_BYTES));
    ObjectNode claims = parseCanonicalObject(decode(segments[1], MAXIMUM_PAYLOAD_BYTES));
    requireExactFields(header, HEADER_FIELDS);
    requireExactFields(claims, CLAIM_FIELDS);
    if (!"ES256".equals(text(header, "alg"))
        || !JWT_TYPE.equals(text(header, "typ"))
        || !same(expectedKeyId, requireIdentifier(text(header, "kid"), "key ID"))) {
      throw new IllegalArgumentException("drain completion attestation header is invalid");
    }
    verifySignature(segments);
    if (!"target-e2e-harness".equals(text(claims, "iss"))
        || !"java-api-service".equals(text(claims, "aud"))
        || !"target-e2e-drain-completion".equals(text(claims, "sub"))) {
      throw new IllegalArgumentException("drain completion attestation claims are invalid");
    }
    requireIdentifier(text(claims, "jti"), "JWS ID");
    long issuedAt = positiveEpoch(claims, "iat");
    long notBefore = positiveEpoch(claims, "nbf");
    long expiresAt = positiveEpoch(claims, "exp");
    long now = clock.instant().getEpochSecond();
    if (issuedAt > now
        || notBefore > now
        || expiresAt <= now
        || notBefore < issuedAt
        || expiresAt < notBefore
        || expiresAt - issuedAt > 60) {
      throw new IllegalArgumentException("drain completion attestation time window is invalid");
    }

    ObjectNode proof = object(claims, "proof");
    requireExactFields(proof, PROOF_FIELDS);
    String proofHash = requireHash(text(proof, "proofHash"), "drain proof hash");
    ObjectNode hashSource = proof.deepCopy();
    hashSource.remove("proofHash");
    if (!same(proofHash, ContractJson.sha256Hex(hashSource))) {
      throw new IllegalArgumentException("drain completion attestation self-hash is invalid");
    }
    Instant completedAt = instant(proof, "completedAt");
    if (completedAt.isAfter(clock.instant()) || completedAt.getEpochSecond() > issuedAt) {
      throw new IllegalArgumentException("drain completion timestamp is invalid");
    }
    if (!SCHEMA_VERSION.equals(text(proof, "schemaVersion"))
        || !EXECUTION_LANE.equals(text(proof, "executionLane"))
        || !AUTHORITY.equals(text(proof, "authority"))
        || !same(identity.activationId(), activationId(proof, "activationId"))
        || !same(identity.environmentId(), identifier(proof, "environmentId"))
        || identity.environmentGeneration() != positiveSafeInteger(proof, "environmentGeneration")
        || !same(identity.manifestHash(), hash(proof, "manifestHash"))
        || !same(deploymentBinding.runtimeContextHash(), hash(proof, "runtimeContextHash"))
        || nonNegativeInteger(proof, "unresolvedAcceptedWork") != 0
        || !bool(proof, "evidenceSealed")
        || !detachedServices(proof)) {
      throw new IllegalArgumentException("drain completion attestation binding is invalid");
    }
    DrainCompletionProof verified =
        new DrainCompletionProof(
            0,
            0,
            true,
            completedAt,
            proofHash,
            hash(proof, "evidenceLedgerHeadHash"),
            hash(proof, "forensicManifestHash"),
            expectedKeyFingerprint);
    return new VerifiedDrainCompletion(verified, expectedKeyId);
  }

  public static ECPublicKey loadPublicKey(Path path) {
    Objects.requireNonNull(path, "path");
    try {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
          || Files.size(path) <= 0
          || Files.size(path) > 16 * 1024) {
        throw new IllegalArgumentException("drain attestation public key file is invalid");
      }
      byte[] pem = Files.readAllBytes(path);
      byte[] encoded = null;
      try {
        String document = new String(pem, StandardCharsets.US_ASCII);
        String begin = "-----BEGIN PUBLIC KEY-----";
        String end = "-----END PUBLIC KEY-----";
        if (!document.startsWith(begin) || !document.stripTrailing().endsWith(end)) {
          throw new IllegalArgumentException("drain attestation public key PEM is invalid");
        }
        String body =
            document.substring(begin.length(), document.lastIndexOf(end)).replaceAll("\\s", "");
        encoded = Base64.getDecoder().decode(body);
        return requireP256(
            (ECPublicKey)
                KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded)));
      } finally {
        Arrays.fill(pem, (byte) 0);
        if (encoded != null) {
          Arrays.fill(encoded, (byte) 0);
        }
      }
    } catch (ClassCastException | GeneralSecurityException | IOException failure) {
      throw new IllegalArgumentException("drain attestation public key is invalid", failure);
    }
  }

  private void verifySignature(String[] segments) {
    byte[] signature = decode(segments[2], 64);
    if (signature.length != 64) {
      throw new IllegalArgumentException("drain completion attestation signature is invalid");
    }
    try {
      Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
      verifier.initVerify(publicKey);
      verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
      if (!verifier.verify(signature)) {
        throw new IllegalArgumentException("drain completion attestation signature is invalid");
      }
    } catch (GeneralSecurityException failure) {
      throw new IllegalArgumentException("drain completion attestation signature is invalid", failure);
    } finally {
      Arrays.fill(signature, (byte) 0);
    }
  }

  private ObjectNode parseCanonicalObject(byte[] bytes) {
    try (JsonParser parser = mapper.createParser(bytes)) {
      JsonNode parsed = mapper.readTree(parser);
      if (!(parsed instanceof ObjectNode object)
          || parser.nextToken() != null
          || !MessageDigest.isEqual(bytes, ContractJson.canonicalize(parsed))) {
        throw new IllegalArgumentException("drain completion attestation JSON is not canonical");
      }
      return object;
    } catch (IOException failure) {
      throw new IllegalArgumentException("drain completion attestation JSON is invalid", failure);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private static byte[] decode(String segment, int maximumBytes) {
    if (segment == null || !BASE64_URL.matcher(segment).matches()) {
      throw new IllegalArgumentException("drain completion attestation encoding is invalid");
    }
    try {
      byte[] decoded = BASE64_URL_DECODER.decode(segment);
      if (decoded.length == 0
          || decoded.length > maximumBytes
          || !same(segment, BASE64_URL_ENCODER.encodeToString(decoded))) {
        throw new IllegalArgumentException("drain completion attestation encoding is invalid");
      }
      return decoded;
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("drain completion attestation encoding is invalid", failure);
    }
  }

  private static boolean detachedServices(ObjectNode proof) {
    JsonNode value = proof.get("detachedExecutionServices");
    if (!(value instanceof ArrayNode array) || array.size() != DETACHED_EXECUTION_SERVICES.size()) {
      return false;
    }
    Set<String> services = new HashSet<>();
    array.forEach(item -> {
      if (item.isTextual()) {
        services.add(item.textValue());
      }
    });
    return services.equals(DETACHED_EXECUTION_SERVICES) && services.size() == array.size();
  }

  private static void requireExactFields(ObjectNode object, Set<String> expected) {
    Set<String> actual = new HashSet<>();
    object.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException("drain completion attestation schema is invalid");
    }
  }

  private static ObjectNode object(ObjectNode parent, String field) {
    JsonNode value = parent.get(field);
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException("drain completion attestation object is invalid");
    }
    return object;
  }

  private static String text(ObjectNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("drain completion attestation field is invalid");
    }
    return value.textValue();
  }

  private static boolean bool(ObjectNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException("drain completion attestation boolean is invalid");
    }
    return value.booleanValue();
  }

  private static long positiveEpoch(ObjectNode parent, String field) {
    long value = positiveSafeInteger(parent, field);
    if (value > 253_402_300_799L) {
      throw new IllegalArgumentException("drain completion attestation epoch is invalid");
    }
    return value;
  }

  private static long positiveSafeInteger(ObjectNode parent, String field) {
    long value = nonNegativeInteger(parent, field);
    if (value < 1) {
      throw new IllegalArgumentException("drain completion attestation integer is invalid");
    }
    return value;
  }

  private static long nonNegativeInteger(ObjectNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("drain completion attestation integer is invalid");
    }
    long parsed = value.longValue();
    if (parsed < 0 || parsed > 9_007_199_254_740_991L) {
      throw new IllegalArgumentException("drain completion attestation integer is invalid");
    }
    return parsed;
  }

  private static Instant instant(ObjectNode parent, String field) {
    try {
      return Instant.parse(text(parent, field));
    } catch (DateTimeParseException failure) {
      throw new IllegalArgumentException("drain completion attestation timestamp is invalid", failure);
    }
  }

  private static String activationId(ObjectNode parent, String field) {
    String value = text(parent, field);
    if (!ACTIVATION_ID.matcher(value).matches()) {
      throw new IllegalArgumentException("drain completion activation ID is invalid");
    }
    return value;
  }

  private static String identifier(ObjectNode parent, String field) {
    return requireIdentifier(text(parent, field), field);
  }

  private static String hash(ObjectNode parent, String field) {
    return requireHash(text(parent, field), field);
  }

  private static String requireIdentifier(String value, String field) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return value;
  }

  private static String requireHash(String value, String field) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
    return value;
  }

  private static ECPublicKey requireP256(ECPublicKey key) {
    Objects.requireNonNull(key, "publicKey");
    if (key.getParams() == null
        || key.getParams().getOrder() == null
        || key.getParams().getOrder().bitLength() != 256
        || key.getParams().getCurve().getField().getFieldSize() != 256) {
      throw new IllegalArgumentException("drain attestation key must use P-256");
    }
    return key;
  }

  private static String fingerprint(ECPublicKey key) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
    } catch (GeneralSecurityException failure) {
      throw new IllegalArgumentException("drain attestation key fingerprint failed", failure);
    }
  }

  private static boolean same(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
    byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
    try {
      return MessageDigest.isEqual(leftBytes, rightBytes);
    } finally {
      Arrays.fill(leftBytes, (byte) 0);
      Arrays.fill(rightBytes, (byte) 0);
    }
  }

  public record VerifiedDrainCompletion(DrainCompletionProof proof, String keyId) {
    public VerifiedDrainCompletion {
      Objects.requireNonNull(proof, "proof");
      requireIdentifier(keyId, "keyId");
    }
  }
}
