package com.example.dispute.workflow.targete2e;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.DatabaseIdentities;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.DatabaseIdentity;
import com.example.dispute.workflow.targete2e.TargetE2eActivationExpectedRuntime.ImageDigests;
import com.example.dispute.workflow.targete2e.TargetE2eRuntimeMeasurementProvider.DatabasePrivilegeEvidence;
import com.example.dispute.workflow.targete2e.TargetE2eRuntimeMeasurementProvider.MeasurementChallenge;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Verifies the independently signed deployment/network/database measurement attestation. */
public final class TargetE2eIsolationAttestationVerifier {

  static final String JWS_TYPE = "target-e2e-runtime-measurement+jwt";
  static final String SCHEMA_VERSION = "target-e2e-runtime-measurement.v1";

  private static final int MAXIMUM_COMPACT_CHARACTERS = 24 * 1024;
  private static final int MAXIMUM_HEADER_BYTES = 2 * 1024;
  private static final int MAXIMUM_PAYLOAD_BYTES = 16 * 1024;
  private static final Duration MAXIMUM_LIFETIME = Duration.ofMinutes(15);
  private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Set<String> HEADER_FIELDS = Set.of("alg", "kid", "typ");
  private static final Set<String> PAYLOAD_FIELDS =
      Set.of(
          "attestationHash",
          "attestationNonce",
          "artifactDigest",
          "candidateSha",
          "databaseMeasurementHash",
          "environmentGeneration",
          "environmentId",
          "expiresAt",
          "externalEffectEndpointsEnabled",
          "graphDomainCredentialsPresent",
          "graphDomainPrivilegesPresent",
          "imageDigestsHash",
          "issuedAt",
          "networkIsolationEnforced",
          "schemaVersion");

  private final TargetE2eIsolationAttestationPublicKeySet publicKeys;
  private final Clock clock;
  private final ObjectMapper mapper;

  public TargetE2eIsolationAttestationVerifier(
      TargetE2eIsolationAttestationPublicKeySet publicKeys, Clock clock) {
    this.publicKeys = Objects.requireNonNull(publicKeys, "publicKeys");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  }

  VerifiedAttestation verify(
      String compactJws,
      MeasurementChallenge challenge,
      TargetE2eActivationExpectedRuntime measured,
      String measuredArtifactDigest,
      DatabasePrivilegeEvidence domainPrivileges,
      DatabasePrivilegeEvidence graphPrivileges) {
    Objects.requireNonNull(challenge, "challenge");
    Objects.requireNonNull(measured, "measured");
    TargetE2eActivationContract.sha256(measuredArtifactDigest, "measuredArtifactDigest");
    if (compactJws == null
        || compactJws.isBlank()
        || compactJws.length() > MAXIMUM_COMPACT_CHARACTERS) {
      throw new IllegalArgumentException("runtime isolation attestation is absent or oversized");
    }
    String[] segments = compactJws.split("\\.", -1);
    if (segments.length != 3) {
      throw new IllegalArgumentException("runtime isolation attestation is not compact JWS");
    }
    ObjectNode header = parseCanonicalObject(decode(segments[0], MAXIMUM_HEADER_BYTES));
    ObjectNode payload = parseCanonicalObject(decode(segments[1], MAXIMUM_PAYLOAD_BYTES));
    requireExactFields(header, HEADER_FIELDS);
    requireExactFields(payload, PAYLOAD_FIELDS);
    if (!"ES256".equals(text(header, "alg")) || !JWS_TYPE.equals(text(header, "typ"))) {
      throw new IllegalArgumentException(
          "runtime isolation attestation protected header is invalid");
    }
    String keyId = TargetE2eActivationContract.keyId(text(header, "kid"));
    if (TargetE2eActivationContract.same(keyId, challenge.activationKeyId())) {
      throw new IllegalArgumentException(
          "runtime and activation attestations must use different keys");
    }
    ECPublicKey publicKey =
        publicKeys
            .resolve(keyId)
            .orElseThrow(() -> new IllegalArgumentException("untrusted isolation attestation key"));
    if (TargetE2eActivationContract.same(
        publicKeyFingerprint(publicKey), challenge.activationPublicKeyFingerprint())) {
      throw new IllegalArgumentException(
          "runtime and activation attestations must use different key material");
    }
    verifySignature(segments, publicKey);
    String hash =
        TargetE2eActivationContract.sha256(text(payload, "attestationHash"), "attestationHash");
    ObjectNode hashSource = payload.deepCopy();
    hashSource.remove("attestationHash");
    if (!TargetE2eActivationContract.same(hash, ContractJson.sha256Hex(hashSource))) {
      throw new IllegalArgumentException("runtime isolation attestation hash is invalid");
    }
    String nonce = TargetE2eActivationContract.nonce(text(payload, "attestationNonce"));
    if (TargetE2eActivationContract.same(nonce, challenge.activationNonce())) {
      throw new IllegalArgumentException(
          "runtime and activation attestations must use different nonces");
    }
    Instant issuedAt = instant(payload, "issuedAt");
    Instant expiresAt = instant(payload, "expiresAt");
    Instant now = clock.instant();
    Duration lifetime = Duration.between(issuedAt, expiresAt);
    if (!expiresAt.isAfter(issuedAt)
        || lifetime.compareTo(MAXIMUM_LIFETIME) > 0
        || issuedAt.isAfter(now)
        || !now.isBefore(expiresAt)) {
      throw new IllegalArgumentException("runtime isolation attestation time window is invalid");
    }
    if (!SCHEMA_VERSION.equals(text(payload, "schemaVersion"))
        || !TargetE2eActivationContract.same(
            text(payload, "artifactDigest"), measuredArtifactDigest)
        || !TargetE2eActivationContract.same(
            text(payload, "environmentId"), measured.environmentId())
        || integer(payload, "environmentGeneration") != measured.environmentGeneration()
        || !TargetE2eActivationContract.same(text(payload, "candidateSha"), measured.candidateSha())
        || !TargetE2eActivationContract.same(
            text(payload, "imageDigestsHash"), imageDigestsHash(measured.imageDigests()))
        || !TargetE2eActivationContract.same(
            text(payload, "databaseMeasurementHash"),
            databaseMeasurementHash(
                measured.databaseIdentities(), domainPrivileges, graphPrivileges))
        || bool(payload, "graphDomainCredentialsPresent")
        || bool(payload, "graphDomainPrivilegesPresent")
        || bool(payload, "externalEffectEndpointsEnabled")
        || !bool(payload, "networkIsolationEnforced")) {
      throw new IllegalArgumentException("runtime isolation attestation bindings are invalid");
    }
    return new VerifiedAttestation(keyId, nonce, hash, issuedAt, expiresAt);
  }

  static String imageDigestsHash(ImageDigests images) {
    ObjectMapper mapper = JsonMapper.builder().build();
    ObjectNode source = mapper.createObjectNode();
    source.put("frontend", images.frontend());
    source.put("javaApi", images.javaApi());
    source.put("pythonAgent", images.pythonAgent());
    source.put("temporalAgentWorker", images.temporalAgentWorker());
    source.put("temporalControlWorker", images.temporalControlWorker());
    return ContractJson.sha256Hex(source);
  }

  static String databaseMeasurementHash(
      DatabaseIdentities databases,
      DatabasePrivilegeEvidence domainPrivileges,
      DatabasePrivilegeEvidence graphPrivileges) {
    ObjectMapper mapper = JsonMapper.builder().build();
    ObjectNode source = mapper.createObjectNode();
    source.set("domain", database(mapper, databases.domain(), domainPrivileges));
    source.set("graph", database(mapper, databases.graph(), graphPrivileges));
    return ContractJson.sha256Hex(source);
  }

  private static ObjectNode database(
      ObjectMapper mapper, DatabaseIdentity identity, DatabasePrivilegeEvidence privileges) {
    ObjectNode value = mapper.createObjectNode();
    value.put("bypassRowLevelSecurity", privileges.bypassRowLevelSecurity());
    value.put("clusterIdentity", identity.clusterIdentity());
    value.put("createDatabase", privileges.createDatabase());
    value.put("createRole", privileges.createRole());
    value.put("databaseIdentity", identity.databaseIdentity());
    value.put("peerPrincipalCanConnect", privileges.peerPrincipalCanConnect());
    value.put("replication", privileges.replication());
    value.put("runtimePrincipalIdentity", identity.runtimePrincipalIdentity());
    value.put("superuser", privileges.superuser());
    return value;
  }

  private ObjectNode parseCanonicalObject(byte[] bytes) {
    try (JsonParser parser = mapper.createParser(bytes)) {
      JsonNode parsed = mapper.readTree(parser);
      if (!(parsed instanceof ObjectNode object)
          || parser.nextToken() != null
          || !MessageDigest.isEqual(bytes, ContractJson.canonicalize(parsed))) {
        throw new IllegalArgumentException("runtime isolation attestation JSON is not canonical");
      }
      return object;
    } catch (IOException failure) {
      throw new IllegalArgumentException("runtime isolation attestation JSON is invalid", failure);
    }
  }

  private static byte[] decode(String segment, int maximumBytes) {
    if (segment == null || !BASE64_URL.matcher(segment).matches()) {
      throw new IllegalArgumentException("runtime isolation attestation encoding is invalid");
    }
    try {
      byte[] decoded = DECODER.decode(segment);
      if (decoded.length == 0
          || decoded.length > maximumBytes
          || !TargetE2eActivationContract.same(segment, ENCODER.encodeToString(decoded))) {
        throw new IllegalArgumentException("runtime isolation attestation encoding is invalid");
      }
      return decoded;
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException(
          "runtime isolation attestation encoding is invalid", failure);
    }
  }

  private static void verifySignature(String[] segments, ECPublicKey key) {
    byte[] jose = decode(segments[2], 64);
    if (jose.length != 64) {
      throw new IllegalArgumentException("runtime isolation attestation signature is invalid");
    }
    try {
      Signature verifier = Signature.getInstance("SHA256withECDSA");
      verifier.initVerify(key);
      verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
      if (!verifier.verify(joseToDer(jose))) {
        throw new IllegalArgumentException("runtime isolation attestation signature is invalid");
      }
    } catch (GeneralSecurityException failure) {
      throw new IllegalArgumentException(
          "runtime isolation attestation signature is invalid", failure);
    }
  }

  private static String publicKeyFingerprint(ECPublicKey publicKey) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
    } catch (GeneralSecurityException failure) {
      throw new IllegalArgumentException(
          "runtime isolation attestation key fingerprint failed", failure);
    }
  }

  private static byte[] joseToDer(byte[] jose) {
    byte[] r = unsignedInteger(jose, 0, 32);
    byte[] s = unsignedInteger(jose, 32, 32);
    int sequenceLength = 2 + r.length + 2 + s.length;
    byte[] der = new byte[2 + sequenceLength];
    der[0] = 0x30;
    der[1] = (byte) sequenceLength;
    der[2] = 0x02;
    der[3] = (byte) r.length;
    System.arraycopy(r, 0, der, 4, r.length);
    int offset = 4 + r.length;
    der[offset] = 0x02;
    der[offset + 1] = (byte) s.length;
    System.arraycopy(s, 0, der, offset + 2, s.length);
    return der;
  }

  private static byte[] unsignedInteger(byte[] source, int offset, int length) {
    int first = offset;
    int end = offset + length;
    while (first < end - 1 && source[first] == 0) {
      first++;
    }
    int contentLength = end - first;
    boolean leadingZero = (source[first] & 0x80) != 0;
    byte[] result = new byte[contentLength + (leadingZero ? 1 : 0)];
    System.arraycopy(source, first, result, leadingZero ? 1 : 0, contentLength);
    return result;
  }

  private static void requireExactFields(ObjectNode object, Set<String> expected) {
    Set<String> actual = new HashSet<>();
    object.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException("runtime isolation attestation schema is invalid");
    }
  }

  private static String text(ObjectNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("runtime isolation attestation field is invalid");
    }
    return value.textValue();
  }

  private static long integer(ObjectNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("runtime isolation attestation integer is invalid");
    }
    return TargetE2eActivationContract.generation(value.longValue());
  }

  private static boolean bool(ObjectNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException("runtime isolation attestation boolean is invalid");
    }
    return value.booleanValue();
  }

  private static Instant instant(ObjectNode parent, String field) {
    try {
      return Instant.parse(text(parent, field));
    } catch (DateTimeParseException failure) {
      throw new IllegalArgumentException(
          "runtime isolation attestation timestamp is invalid", failure);
    }
  }

  record VerifiedAttestation(
      String keyId, String nonce, String attestationHash, Instant issuedAt, Instant expiresAt) {

    VerifiedAttestation {
      TargetE2eActivationContract.keyId(keyId);
      TargetE2eActivationContract.nonce(nonce);
      TargetE2eActivationContract.sha256(attestationHash, "attestationHash");
      Objects.requireNonNull(issuedAt, "issuedAt");
      Objects.requireNonNull(expiresAt, "expiresAt");
    }
  }
}
