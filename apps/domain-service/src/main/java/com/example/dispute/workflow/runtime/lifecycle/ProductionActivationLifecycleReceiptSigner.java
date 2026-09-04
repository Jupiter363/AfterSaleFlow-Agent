package com.example.dispute.workflow.runtime.lifecycle;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import com.example.dispute.workflow.runtime.ProductionActivationLifecycleStore.ActivationIdentity;
import com.example.dispute.workflow.runtime.ProductionActivationLifecycleStore.LifecycleState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Issues the short-lived Java-authoritative lifecycle delivery credential. */
public final class ProductionActivationLifecycleReceiptSigner {

  public static final String JWT_TYPE = "production-runtime-activation-lifecycle-receipt+jwt";
  private static final String SUBJECT = "production-runtime-lifecycle-reconcile";
  private static final Pattern IDENTIFIER =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/\\-]{0,127}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

  private final GraphEnvelopeSigningKey signingKey;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final long lifetimeSeconds;

  public ProductionActivationLifecycleReceiptSigner(
      GraphEnvelopeSigningKey signingKey, ObjectMapper mapper, Clock clock) {
    this(signingKey, mapper, clock, Duration.ofSeconds(60));
  }

  public ProductionActivationLifecycleReceiptSigner(
      GraphEnvelopeSigningKey signingKey, ObjectMapper mapper, Clock clock, Duration lifetime) {
    this.signingKey = Objects.requireNonNull(signingKey, "signingKey");
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    this.clock = Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(lifetime, "lifetime");
    if (lifetime.isZero()
        || lifetime.isNegative()
        || lifetime.compareTo(Duration.ofSeconds(60)) > 0
        || lifetime.getNano() != 0) {
      throw new IllegalArgumentException("lifecycle credential lifetime must be 1..60 seconds");
    }
    this.lifetimeSeconds = lifetime.getSeconds();
  }

  public String issue(
      ActivationIdentity identity,
      String runtimeContextHash,
      LifecycleState fromState,
      LifecycleState toState,
      Instant transitionedAt) {
    Objects.requireNonNull(identity, "identity");
    requireHash(runtimeContextHash, "runtimeContextHash");
    Objects.requireNonNull(fromState, "fromState");
    Objects.requireNonNull(toState, "toState");
    Objects.requireNonNull(transitionedAt, "transitionedAt");
    requireAdjacent(fromState, toState);

    Instant now = clock.instant();
    Instant issuedAt = now.truncatedTo(ChronoUnit.SECONDS);
    Instant normalizedTransition = transitionedAt.truncatedTo(ChronoUnit.MICROS);
    if (normalizedTransition.isAfter(now)) {
      throw new IllegalArgumentException("lifecycle transition cannot be in the future");
    }
    String keyId = requireIdentifier(signingKey.keyId(), "signing key ID");
    ObjectNode receipt = mapper.createObjectNode();
    receipt.put("schemaVersion", "production-runtime-activation-lifecycle-receipt.v1");
    receipt.put("executionLane", "PRODUCTION");
    receipt.put("authority", "JAVA_CONTROL_PLANE");
    receipt.put("activationId", identity.activationId());
    receipt.put("environmentId", identity.environmentId());
    receipt.put("environmentGeneration", identity.environmentGeneration());
    receipt.put("manifestHash", identity.manifestHash());
    receipt.put("runtimeContextHash", runtimeContextHash);
    receipt.put("fromState", fromState.name());
    receipt.put("toState", toState.name());
    receipt.put("transitionedAt", normalizedTransition.toString());
    receipt.put("receiptHash", ContractJson.sha256Hex(receipt.deepCopy()));

    ObjectNode header = mapper.createObjectNode();
    header.put("alg", "ES256");
    header.put("kid", keyId);
    header.put("typ", JWT_TYPE);
    ObjectNode claims = mapper.createObjectNode();
    claims.put("iss", "java-api-service");
    claims.put("aud", "python-agent-service");
    claims.put("sub", SUBJECT);
    claims.put("iat", issuedAt.getEpochSecond());
    claims.put("nbf", issuedAt.getEpochSecond());
    claims.put("exp", issuedAt.plusSeconds(lifetimeSeconds).getEpochSecond());
    claims.put("jti", "target-lifecycle-" + UUID.randomUUID());
    claims.set("receipt", receipt);

    byte[] signature = null;
    try {
      String encodedHeader = encode(header);
      String encodedClaims = encode(claims);
      byte[] signingInput = (encodedHeader + "." + encodedClaims).getBytes(StandardCharsets.US_ASCII);
      signature = Objects.requireNonNull(signingKey.signSha256(signingInput), "signature");
      if (signature.length != 64) {
        throw new IllegalStateException("ES256 lifecycle signature must be 64-byte P1363");
      }
      return encodedHeader + "." + encodedClaims + "." + BASE64_URL.encodeToString(signature);
    } finally {
      if (signature != null) {
        Arrays.fill(signature, (byte) 0);
      }
    }
  }

  private String encode(ObjectNode node) {
    try {
      return BASE64_URL.encodeToString(ContractJson.canonicalize(node));
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("lifecycle receipt is not serializable", failure);
    }
  }

  private static void requireAdjacent(LifecycleState from, LifecycleState to) {
    if (!((from == LifecycleState.ACTIVE && to == LifecycleState.DRAIN_ONLY)
        || (from == LifecycleState.DRAIN_ONLY && to == LifecycleState.DRAINED)
        || (from == LifecycleState.DRAINED && to == LifecycleState.REVOKED_TERMINAL))) {
      throw new IllegalArgumentException("lifecycle receipt transition is not adjacent");
    }
  }

  private static String requireIdentifier(String value, String field) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return value;
  }

  private static void requireHash(String value, String field) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }
}
