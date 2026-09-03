package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.infrastructure.security.Es256GraphCommandEnvelopeSigner;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKeyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Es256GraphCommandEnvelopeSignerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURE = Path.of(
            "..",
            "..",
            "contracts",
            "agent-platform",
            "v1",
            "fixtures",
            "valid",
            "room-graph-command-valid.json");
    private static final String COMMAND_KEY_ID = "java-invocation-es256-1";
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00.789Z");
    private static final GraphRegistryBindingPolicy.ExpectedBinding REGISTRY_BINDING =
            new GraphRegistryBindingPolicy.ExpectedBinding(
                    "c".repeat(64), "tools.none.v1");
    private static KeyPair keyPair;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
    }

    @Test
    void issuesExactExecutionClaimsAndAValidEs256Signature() throws Exception {
        RoomGraphCommand command = command();
        var signer = new Es256GraphCommandEnvelopeSigner(
                signingKey(COMMAND_KEY_ID),
                MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(45),
                () -> "execution-jti-001");

        var envelope = signer.sign(command, REGISTRY_BINDING);
        String[] segments = envelope.compactJws().split("\\.", -1);
        ObjectNode header = decodeObject(segments[0]);
        ObjectNode claims = decodeObject(segments[1]);

        assertThat(header.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("alg", "kid", "typ");
        assertThat(header.path("alg").asText()).isEqualTo("ES256");
        assertThat(header.path("kid").asText()).isEqualTo(COMMAND_KEY_ID);
        assertThat(header.path("typ").asText()).isEqualTo("graph-command+jwt");
        assertThat(claims.properties().stream().map(java.util.Map.Entry::getKey).collect(
                        java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of(
                        "iss", "aud", "sub", "iat", "nbf", "exp", "jti",
                        "command_id", "command_nonce", "request_hash", "tenant_surrogate",
                        "case_id", "room_epoch", "thread_id", "graph_key", "graph_version",
                        "checkpoint_schema_version", "actor_scope_hash", "capabilities_hash",
                        "profile_bindings_hash"));
        assertThat(claims.path("iss").asText()).isEqualTo("java-api-service");
        assertThat(claims.path("aud").asText()).isEqualTo("python-agent-service");
        assertThat(claims.path("sub").asText()).isEqualTo("graph-command");
        assertThat(claims.has("capability")).isFalse();
        assertThat(claims.has("original_envelope_key_id")).isFalse();
        assertThat(claims.path("iat").asLong())
                .isEqualTo(Instant.parse("2026-07-17T08:00:00Z").getEpochSecond());
        assertThat(claims.path("nbf").asLong())
                .isEqualTo(Instant.parse("2026-07-17T08:00:00Z").getEpochSecond());
        assertThat(claims.path("exp").asLong())
                .isEqualTo(Instant.parse("2026-07-17T08:00:45Z").getEpochSecond());
        assertThat(claims.path("jti").asText()).isEqualTo("execution-jti-001");
        assertThat(claims.path("command_id").asText()).isEqualTo(command.commandId());
        assertThat(claims.path("command_nonce").asText())
                .isEqualTo(command.invocationContext().envelopeNonce());
        assertThat(claims.path("request_hash").asText()).isEqualTo(command.requestHash());
        assertThat(claims.path("tenant_surrogate").asText())
                .isEqualTo(command.tenantSurrogate());
        assertThat(claims.path("case_id").asText()).isEqualTo(command.caseId());
        assertThat(claims.path("room_epoch").asLong()).isEqualTo(command.roomEpoch());
        assertThat(claims.path("thread_id").asText()).isEqualTo(command.threadId());
        assertThat(claims.path("graph_key").asText()).isEqualTo(command.graphKey());
        assertThat(claims.path("graph_version").asText()).isEqualTo(command.graphVersion());
        assertThat(claims.path("checkpoint_schema_version").asText())
                .isEqualTo(command.checkpointSchemaVersion());
        assertThat(claims.path("actor_scope_hash").asText())
                .isEqualTo("c17e854151c9e43ddce06670c2748705abd5e5db2ba21b00555513833ad25c64");
        assertThat(claims.path("capabilities_hash").asText())
                .isEqualTo("80cd39b1d37bf6e8a2807e48af87a3a72c70b34f98812dae17a85386f7e8c31a");
        assertThat(claims.path("profile_bindings_hash").asText())
                .isEqualTo("ccccb6cff9387ecad3d5dfe8c4ce086941e7711f17014ddd147889edd49e0340");
        assertThat(envelope.keyId()).isEqualTo(COMMAND_KEY_ID);
        assertThat(envelope.jti()).isEqualTo("execution-jti-001");
        assertThat(envelope.issuedAt()).isEqualTo(Instant.parse("2026-07-17T08:00:00Z"));
        assertThat(envelope.expiresAt()).isEqualTo(Instant.parse("2026-07-17T08:00:45Z"));
        assertThat(envelope.compactJws().getBytes(StandardCharsets.US_ASCII))
                .hasSizeLessThan(8_192);

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(segments[2]))).isTrue();
    }

    @Test
    void rejectsACommandWhoseSelfHashDoesNotBindItsBody() throws Exception {
        ObjectNode fixture = fixture();
        fixture.put("request_hash", "0".repeat(64));
        RoomGraphCommand command = MAPPER.treeToValue(fixture, RoomGraphCommand.class);

        assertThatThrownBy(() -> signer(signingKey(COMMAND_KEY_ID), () -> "execution-jti-001")
                        .sign(command, REGISTRY_BINDING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestHash does not bind");
    }

    @Test
    void rejectsSigningKeyThatDoesNotMatchTheCommandKeyIdBeforeCallingIt() {
        AtomicInteger calls = new AtomicInteger();
        GraphEnvelopeSigningKey wrongKey = new GraphEnvelopeSigningKey() {
            @Override
            public String keyId() {
                return "java-invocation-es256-other";
            }

            @Override
            public byte[] signSha256(byte[] signingInput) {
                calls.incrementAndGet();
                return new byte[64];
            }
        };

        assertThatThrownBy(() -> signer(wrongKey, () -> "execution-jti-001")
                        .sign(commandUnchecked(), REGISTRY_BINDING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match command envelopeKeyId");
        assertThat(calls).hasValue(0);
    }

    @Test
    void resolvesTheExactCommandBoundKeySoRetainedKeysCanSignRecoveryDelivery() {
        AtomicInteger resolutions = new AtomicInteger();
        GraphEnvelopeSigningKeyResolver retainedKeys = requestedKeyId -> {
            resolutions.incrementAndGet();
            assertThat(requestedKeyId).isEqualTo(COMMAND_KEY_ID);
            return signingKey(COMMAND_KEY_ID);
        };
        var signer = new Es256GraphCommandEnvelopeSigner(
                retainedKeys,
                MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(60),
                () -> "recovery-delivery-jti-1");

        var envelope = signer.sign(commandUnchecked(), REGISTRY_BINDING);

        assertThat(envelope.keyId()).isEqualTo(COMMAND_KEY_ID);
        assertThat(resolutions).hasValue(1);
    }

    @Test
    void rejectsAResolverThatReturnsAnotherKey() {
        GraphEnvelopeSigningKeyResolver wrongKey = requestedKeyId ->
                signingKey("java-invocation-es256-other");
        var signer = new Es256GraphCommandEnvelopeSigner(
                wrongKey,
                MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(60),
                () -> "execution-jti-001");

        assertThatThrownBy(() -> signer.sign(commandUnchecked(), REGISTRY_BINDING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match command envelopeKeyId");
    }

    @Test
    void rejectsInvalidLifetimeJtiKeyAndSignatureShape() {
        assertThatThrownBy(() -> new Es256GraphCommandEnvelopeSigner(
                        signingKey(COMMAND_KEY_ID),
                        MAPPER,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        Duration.ZERO,
                        () -> "execution-jti-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..60");
        assertThatThrownBy(() -> new Es256GraphCommandEnvelopeSigner(
                        signingKey(COMMAND_KEY_ID),
                        MAPPER,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        Duration.ofMillis(1_500),
                        () -> "execution-jti-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole seconds");
        assertThatThrownBy(() -> new Es256GraphCommandEnvelopeSigner(
                        signingKey(COMMAND_KEY_ID),
                        MAPPER,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        Duration.ofSeconds(61),
                        () -> "execution-jti-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..60");
        assertThatThrownBy(() -> signer(signingKey("invalid key"), () -> "jti-1")
                        .sign(commandUnchecked(), REGISTRY_BINDING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyId");
        assertThatThrownBy(() -> signer(signingKey(COMMAND_KEY_ID), () -> "invalid jti")
                        .sign(commandUnchecked(), REGISTRY_BINDING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jti");
        assertThatThrownBy(() -> signer(signingKey(COMMAND_KEY_ID), () -> "x".repeat(129))
                        .sign(commandUnchecked(), REGISTRY_BINDING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jti");
        assertThatThrownBy(() -> signer(
                            signingKey(COMMAND_KEY_ID),
                            () -> commandUnchecked().invocationContext().envelopeNonce())
                        .sign(commandUnchecked(), REGISTRY_BINDING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not reuse");

        GraphEnvelopeSigningKey shortSignature = new GraphEnvelopeSigningKey() {
            @Override
            public String keyId() {
                return COMMAND_KEY_ID;
            }

            @Override
            public byte[] signSha256(byte[] signingInput) {
                return new byte[63];
            }
        };
        assertThatThrownBy(() -> signer(shortSignature, () -> "jti-1")
                        .sign(commandUnchecked(), REGISTRY_BINDING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64-byte");
    }

    @Test
    void obtainsAFreshTransportJtiForEveryEnvelope() {
        AtomicInteger sequence = new AtomicInteger();
        var signer = signer(
                signingKey(COMMAND_KEY_ID),
                () -> "execution-jti-" + sequence.incrementAndGet());

        var first = signer.sign(commandUnchecked(), REGISTRY_BINDING);
        var second = signer.sign(commandUnchecked(), REGISTRY_BINDING);

        assertThat(first.jti()).isEqualTo("execution-jti-1");
        assertThat(second.jti()).isEqualTo("execution-jti-2");
        assertThat(first.compactJws()).isNotEqualTo(second.compactJws());
    }

    private static Es256GraphCommandEnvelopeSigner signer(
            GraphEnvelopeSigningKey key,
            java.util.function.Supplier<String> jtiSupplier) {
        return new Es256GraphCommandEnvelopeSigner(
                key,
                MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(60),
                jtiSupplier);
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
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
    }

    private static ObjectNode decodeObject(String segment) throws Exception {
        JsonNode node = MAPPER.readTree(Base64.getUrlDecoder().decode(segment));
        assertThat(node).isInstanceOf(ObjectNode.class);
        return (ObjectNode) node;
    }

    private static ObjectNode fixture() throws Exception {
        return (ObjectNode) MAPPER.readTree(FIXTURE.toFile()).required("instance");
    }

    private static RoomGraphCommand command() throws Exception {
        return MAPPER.treeToValue(fixture(), RoomGraphCommand.class);
    }

    private static RoomGraphCommand commandUnchecked() {
        try {
            return command();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
