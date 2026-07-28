package com.example.dispute.workflow.targete2e.graph;

import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKeyResolver;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** ES256 target invocation signer backed by the existing KMS/HSM signing-key boundary. */
public final class Es256TargetE2EGraphEnvelopeSigner implements TargetE2EGraphEnvelopeSigner {

  public static final String PROTECTED_HEADER_TYPE = "target-e2e-graph-command+jwt";
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

  private final GraphEnvelopeSigningKeyResolver signingKeys;
  private final ObjectMapper mapper;
  private final TargetE2EGraphEnvelopeCodec codec;
  private final Clock clock;
  private final long lifetimeSeconds;
  private final Supplier<String> jtiSupplier;
  private final TargetE2EAgentSessionResolver agentSessions;

  public Es256TargetE2EGraphEnvelopeSigner(
      GraphEnvelopeSigningKeyResolver signingKeys, ObjectMapper objectMapper, Clock clock) {
    this(
        signingKeys,
        objectMapper,
        clock,
        Duration.ofSeconds(60),
        () -> "target-command-" + UUID.randomUUID(),
        command -> null);
  }

  public Es256TargetE2EGraphEnvelopeSigner(
      GraphEnvelopeSigningKey signingKey, ObjectMapper objectMapper, Clock clock) {
    this(singleKeyResolver(signingKey), objectMapper, clock);
  }

  public Es256TargetE2EGraphEnvelopeSigner(
      GraphEnvelopeSigningKeyResolver signingKeys,
      ObjectMapper objectMapper,
      Clock clock,
      Duration lifetime,
      Supplier<String> jtiSupplier) {
    this(signingKeys, objectMapper, clock, lifetime, jtiSupplier, command -> null);
  }

  public Es256TargetE2EGraphEnvelopeSigner(
      GraphEnvelopeSigningKeyResolver signingKeys,
      ObjectMapper objectMapper,
      Clock clock,
      Duration lifetime,
      Supplier<String> jtiSupplier,
      TargetE2EAgentSessionResolver agentSessions) {
    this.signingKeys = Objects.requireNonNull(signingKeys, "signingKeys");
    this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
    this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    this.codec = new TargetE2EGraphEnvelopeCodec(objectMapper);
    this.clock = Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(lifetime, "lifetime");
    if (lifetime.isZero()
        || lifetime.isNegative()
        || lifetime.compareTo(Duration.ofSeconds(60)) > 0
        || lifetime.getNano() != 0) {
      throw new IllegalArgumentException("credential lifetime must be 1..60 whole seconds");
    }
    this.lifetimeSeconds = lifetime.getSeconds();
    this.jtiSupplier = Objects.requireNonNull(jtiSupplier, "jtiSupplier");
    this.agentSessions = Objects.requireNonNull(agentSessions, "agentSessions");
  }

  public Es256TargetE2EGraphEnvelopeSigner(
      GraphEnvelopeSigningKey signingKey,
      ObjectMapper objectMapper,
      Clock clock,
      Duration lifetime,
      Supplier<String> jtiSupplier) {
    this(singleKeyResolver(signingKey), objectMapper, clock, lifetime, jtiSupplier);
  }

  @Override
  public SignedEnvelope sign(
      TargetE2EGraphCommandEnvelope envelope,
      GraphRegistryBindingPolicy.ExpectedBinding expectedRegistryBinding) {
    Objects.requireNonNull(envelope, "envelope");
    Objects.requireNonNull(expectedRegistryBinding, "expectedRegistryBinding");
    codec.encodeCommand(envelope);
    RoomGraphCommand command = envelope.command();
    String commandKeyId =
        identifier(command.invocationContext().envelopeKeyId(), "command envelopeKeyId");
    GraphEnvelopeSigningKey signingKey =
        Objects.requireNonNull(
            signingKeys.resolve(commandKeyId), "Graph signing key resolver returned no key");
    String keyId = identifier(signingKey.keyId(), "resolved keyId");
    if (!TargetE2EGraphEnvelopeCodec.constantTimeEquals(commandKeyId, keyId)) {
      throw new IllegalArgumentException(
          "resolved signing keyId does not match command envelopeKeyId");
    }
    String jti = identifier(jtiSupplier.get(), "jti");
    if (TargetE2EGraphEnvelopeCodec.constantTimeEquals(
        jti, command.invocationContext().envelopeNonce())) {
      throw new IllegalArgumentException("jti must not reuse command envelopeNonce");
    }
    Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt = issuedAt.plusSeconds(lifetimeSeconds);

    ObjectNode header = mapper.createObjectNode();
    header.put("alg", "ES256");
    header.put("kid", keyId);
    header.put("typ", PROTECTED_HEADER_TYPE);
    ObjectNode claims = claims(envelope, expectedRegistryBinding, jti, issuedAt, expiresAt);
    String encodedHeader = encodeJson(header);
    String encodedClaims = encodeJson(claims);
    byte[] signingInput = (encodedHeader + "." + encodedClaims).getBytes(StandardCharsets.US_ASCII);
    byte[] signature =
        Objects.requireNonNull(
            signingKey.signSha256(signingInput), "Graph signing key returned no signature");
    if (signature.length != 64) {
      throw new IllegalStateException("ES256 signing key must return a 64-byte R || S signature");
    }
    return new SignedEnvelope(
        encodedHeader + "." + encodedClaims + "." + BASE64_URL.encodeToString(signature),
        keyId,
        jti,
        issuedAt,
        expiresAt);
  }

  private ObjectNode claims(
      TargetE2EGraphCommandEnvelope envelope,
      GraphRegistryBindingPolicy.ExpectedBinding expectedRegistryBinding,
      String jti,
      Instant issuedAt,
      Instant expiresAt) {
    RoomGraphCommand command = envelope.command();
    ObjectNode commandJson = mapper.valueToTree(command);
    ObjectNode actorScope = requiredObject(commandJson, "actor_scope");
    ObjectNode capabilities = mapper.createObjectNode();
    sortedArray(capabilities.putArray("actor_capabilities"), command.actorScope().capabilities());
    sortedArray(
        capabilities.putArray("tool_capabilities"), command.invocationContext().toolCapabilities());
    RoomGraphCommand.InvocationContext invocation = command.invocationContext();
    ObjectNode profiles = mapper.createObjectNode();
    profiles.put("agent_profile_id", invocation.agentProfileId());
    profiles.put("prompt_profile_id", invocation.promptProfileId());
    profiles.put("model_profile_id", invocation.modelProfileId());
    profiles.put("output_schema_version", invocation.outputSchemaVersion());
    profiles.put("policy_version", invocation.policyVersion());
    profiles.put("guardrail_version", invocation.guardrailVersion());
    profiles.put("registry_binding_hash", expectedRegistryBinding.registryBindingHash());
    profiles.put("tool_policy_version", expectedRegistryBinding.toolPolicyVersion());

    ObjectNode claims = mapper.createObjectNode();
    claims.put("iss", "java-api-service");
    claims.put("aud", "python-agent-service");
    claims.put("sub", "graph-command");
    claims.put("iat", issuedAt.getEpochSecond());
    claims.put("nbf", issuedAt.getEpochSecond());
    claims.put("exp", expiresAt.getEpochSecond());
    claims.put("jti", jti);
    claims.put("command_id", command.commandId());
    claims.put("command_nonce", invocation.envelopeNonce());
    claims.put("request_hash", command.requestHash());
    claims.put("tenant_surrogate", command.tenantSurrogate());
    claims.put("case_id", command.caseId());
    claims.put("room_epoch", command.roomEpoch());
    claims.put("thread_id", command.threadId());
    claims.put("graph_key", command.graphKey());
    claims.put("graph_version", command.graphVersion());
    claims.put("checkpoint_schema_version", command.checkpointSchemaVersion());
    claims.put("actor_scope_hash", ContractJson.sha256Hex(actorScope));
    claims.put("capabilities_hash", ContractJson.sha256Hex(capabilities));
    claims.put("profile_bindings_hash", ContractJson.sha256Hex(profiles));
    claims.put("execution_lane", envelope.executionLane());
    claims.put("activation_id", envelope.activationId());
    claims.put("room_fencing_token", envelope.roomFencingToken());
    claims.put("command_hash", envelope.commandHash());
    claims.put("command_envelope_hash", envelope.commandEnvelopeHash());
    String agentSessionId = agentSessions.resolve(command);
    if (agentSessionId != null) {
      claims.put("agent_session_id", identifier(agentSessionId, "agentSessionId"));
    }
    return claims;
  }

  private String encodeJson(ObjectNode node) {
    try {
      return BASE64_URL.encodeToString(mapper.writeValueAsBytes(node));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("target Graph credential is not serializable", exception);
    }
  }

  private static ObjectNode requiredObject(ObjectNode parent, String field) {
    JsonNode value = parent.required(field);
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(field + " must be an object");
    }
    return object;
  }

  private static void sortedArray(ArrayNode target, List<String> values) {
    Objects.requireNonNull(values, "capabilities").stream().sorted().forEach(target::add);
  }

  private static String identifier(String value, String field) {
    if (value == null || !IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " is not a bounded identifier");
    }
    return value;
  }

  private static GraphEnvelopeSigningKeyResolver singleKeyResolver(
      GraphEnvelopeSigningKey signingKey) {
    GraphEnvelopeSigningKey requiredKey = Objects.requireNonNull(signingKey, "signingKey");
    return requestedKeyId -> {
      String availableKeyId = identifier(requiredKey.keyId(), "keyId");
      if (!TargetE2EGraphEnvelopeCodec.constantTimeEquals(requestedKeyId, availableKeyId)) {
        throw new IllegalArgumentException("signing keyId does not match command envelopeKeyId");
      }
      return requiredKey;
    };
  }
}
