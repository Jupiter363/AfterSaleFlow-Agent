package com.example.dispute.workflow.infrastructure.security;

import com.example.dispute.workflow.activity.agent.GraphReconciliationEnvelopeSigner;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Builds the fixed {@code graph-reconcile+jwt} envelope and delegates ES256 to KMS/HSM. */
public final class Es256GraphReconciliationEnvelopeSigner
        implements GraphReconciliationEnvelopeSigner {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final GraphEnvelopeSigningKey signingKey;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final long lifetimeSeconds;
    private final Supplier<String> jtiSupplier;

    public Es256GraphReconciliationEnvelopeSigner(
            GraphEnvelopeSigningKey signingKey,
            ObjectMapper objectMapper,
            Clock clock) {
        this(signingKey, objectMapper, clock, Duration.ofSeconds(60),
                () -> "reconcile-" + UUID.randomUUID());
    }

    public Es256GraphReconciliationEnvelopeSigner(
            GraphEnvelopeSigningKey signingKey,
            ObjectMapper objectMapper,
            Clock clock,
            Duration lifetime,
            Supplier<String> jtiSupplier) {
        this.signingKey = Objects.requireNonNull(signingKey, "signingKey");
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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
    }

    @Override
    public SignedEnvelope sign(
            RoomGraphCommand command,
            GraphRegistryBindingPolicy.ExpectedBinding expectedRegistryBinding) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(expectedRegistryBinding, "expectedRegistryBinding");
        String keyId = identifier(signingKey.keyId(), "keyId");
        String jti = identifier(jtiSupplier.get(), "jti");
        if (MessageDigest.isEqual(
                jti.getBytes(StandardCharsets.UTF_8),
                command.invocationContext().envelopeNonce().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("jti must not reuse command envelopeNonce");
        }
        Instant issuedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(lifetimeSeconds);

        ObjectNode commandJson = mapper.valueToTree(command);
        requireCommandSelfHash(commandJson, command.requestHash());
        ObjectNode header = mapper.createObjectNode();
        header.put("alg", "ES256");
        header.put("kid", keyId);
        header.put("typ", "graph-reconcile+jwt");
        ObjectNode claims = claims(
                command,
                commandJson,
                expectedRegistryBinding,
                jti,
                issuedAt,
                expiresAt);

        String encodedHeader = encodeJson(header);
        String encodedClaims = encodeJson(claims);
        byte[] signingInput = (encodedHeader + "." + encodedClaims)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] signature = Objects.requireNonNull(
                signingKey.signSha256(signingInput),
                "Graph signing key returned no signature");
        if (signature.length != 64) {
            throw new IllegalStateException("ES256 signing key must return a 64-byte R || S signature");
        }
        String compactJws = encodedHeader + "." + encodedClaims + "."
                + BASE64_URL.encodeToString(signature);
        return new SignedEnvelope(compactJws, keyId, jti, issuedAt, expiresAt);
    }

    private ObjectNode claims(
            RoomGraphCommand command,
            ObjectNode commandJson,
            GraphRegistryBindingPolicy.ExpectedBinding expectedRegistryBinding,
            String jti,
            Instant issuedAt,
            Instant expiresAt) {
        ObjectNode actorScope = requiredObject(commandJson, "actor_scope");
        ObjectNode invocation = requiredObject(commandJson, "invocation_context");
        ObjectNode capabilities = mapper.createObjectNode();
        sortedArray(capabilities.putArray("actor_capabilities"), command.actorScope().capabilities());
        sortedArray(
                capabilities.putArray("tool_capabilities"),
                command.invocationContext().toolCapabilities());
        ObjectNode profiles = mapper.createObjectNode();
        profiles.put("agent_profile_id", command.invocationContext().agentProfileId());
        profiles.put("prompt_profile_id", command.invocationContext().promptProfileId());
        profiles.put("model_profile_id", command.invocationContext().modelProfileId());
        profiles.put("output_schema_version", command.invocationContext().outputSchemaVersion());
        profiles.put("policy_version", command.invocationContext().policyVersion());
        profiles.put("guardrail_version", command.invocationContext().guardrailVersion());
        profiles.put("registry_binding_hash", expectedRegistryBinding.registryBindingHash());
        profiles.put("tool_policy_version", expectedRegistryBinding.toolPolicyVersion());

        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", "java-api-service");
        claims.put("aud", "python-agent-service");
        claims.put("sub", "graph-reconcile");
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("nbf", issuedAt.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("jti", jti);
        claims.put("capability", "RECONCILE_ONLY");
        claims.put("original_envelope_key_id", invocation.required("envelope_key_id").asText());
        claims.put("command_id", command.commandId());
        claims.put("command_nonce", command.invocationContext().envelopeNonce());
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
        return claims;
    }

    private void requireCommandSelfHash(ObjectNode commandJson, String requestHash) {
        if (!SHA256.matcher(Objects.requireNonNull(requestHash, "requestHash")).matches()) {
            throw new IllegalArgumentException("command requestHash must be lowercase SHA-256");
        }
        ObjectNode unhashed = commandJson.deepCopy();
        JsonNode removed = unhashed.remove("request_hash");
        if (removed == null || !removed.isTextual()) {
            throw new IllegalArgumentException("serialized command has no request_hash");
        }
        String actual = ContractJson.sha256Hex(unhashed);
        if (!MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("command requestHash does not bind its body");
        }
    }

    private String encodeJson(ObjectNode node) {
        try {
            return BASE64_URL.encodeToString(mapper.writeValueAsBytes(node));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("reconciliation envelope is not serializable", exception);
        }
    }

    private static ObjectNode requiredObject(ObjectNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return object;
    }

    private static void sortedArray(ArrayNode target, java.util.List<String> values) {
        Objects.requireNonNull(values, "capabilities").stream().sorted().forEach(target::add);
    }

    private static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a bounded identifier");
        }
        return value;
    }
}
