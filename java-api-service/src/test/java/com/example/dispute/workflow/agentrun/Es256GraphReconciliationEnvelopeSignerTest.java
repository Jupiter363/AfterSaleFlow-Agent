package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.infrastructure.security.Es256GraphReconciliationEnvelopeSigner;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Es256GraphReconciliationEnvelopeSignerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURE = Path.of(
            "..",
            "contracts",
            "agent-platform",
            "v1",
            "fixtures",
            "valid",
            "room-graph-command-valid.json");
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private static KeyPair keyPair;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
    }

    @Test
    void issuesExactResultOnlyClaimsAndAValidEs256Signature() throws Exception {
        RoomGraphCommand command = command();
        var signer = new Es256GraphReconciliationEnvelopeSigner(
                signingKey("java-reconciliation-es256-2"),
                MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(60),
                () -> "reconciliation-jti-001");

        var envelope = signer.sign(command);
        String[] segments = envelope.compactJws().split("\\.", -1);
        ObjectNode header = decodeObject(segments[0]);
        ObjectNode claims = decodeObject(segments[1]);

        assertThat(header.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("alg", "kid", "typ");
        assertThat(header.path("alg").asText()).isEqualTo("ES256");
        assertThat(header.path("kid").asText()).isEqualTo("java-reconciliation-es256-2");
        assertThat(header.path("typ").asText()).isEqualTo("graph-reconcile+jwt");
        assertThat(claims.properties().stream().map(java.util.Map.Entry::getKey).collect(
                        java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of(
                        "iss", "aud", "sub", "iat", "nbf", "exp", "jti",
                        "capability", "original_envelope_key_id", "command_id",
                        "command_nonce", "request_hash", "tenant_surrogate", "case_id",
                        "room_epoch", "thread_id", "graph_key", "graph_version",
                        "checkpoint_schema_version", "actor_scope_hash", "capabilities_hash",
                        "profile_bindings_hash"));
        assertThat(claims.path("iss").asText()).isEqualTo("java-api-service");
        assertThat(claims.path("aud").asText()).isEqualTo("python-agent-service");
        assertThat(claims.path("sub").asText()).isEqualTo("graph-reconcile");
        assertThat(claims.path("capability").asText()).isEqualTo("RECONCILE_ONLY");
        assertThat(claims.path("original_envelope_key_id").asText())
                .isEqualTo(command.invocationContext().envelopeKeyId());
        assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(claims.path("nbf").asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(claims.path("exp").asLong()).isEqualTo(NOW.plusSeconds(60).getEpochSecond());
        assertThat(claims.path("jti").asText()).isEqualTo("reconciliation-jti-001");
        assertThat(claims.path("request_hash").asText()).isEqualTo(command.requestHash());
        assertThat(claims.path("actor_scope_hash").asText())
                .isEqualTo("c17e854151c9e43ddce06670c2748705abd5e5db2ba21b00555513833ad25c64");
        assertThat(claims.path("capabilities_hash").asText())
                .isEqualTo("80cd39b1d37bf6e8a2807e48af87a3a72c70b34f98812dae17a85386f7e8c31a");
        assertThat(claims.path("profile_bindings_hash").asText())
                .isEqualTo("cacc32606bb568cc5f714676889c7ad30f9448c6274294b7d477d839c2b2e38e");
        assertThat(envelope.keyId()).isEqualTo("java-reconciliation-es256-2");
        assertThat(envelope.issuedAt()).isEqualTo(NOW);
        assertThat(envelope.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(envelope.compactJws().getBytes(StandardCharsets.US_ASCII)).hasSizeLessThan(8_192);

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(segments[2]))).isTrue();
    }

    @Test
    void rejectsACommandWhoseSelfHashDoesNotBindItsBody() throws Exception {
        ObjectNode fixture = (ObjectNode) MAPPER.readTree(FIXTURE.toFile()).required("instance");
        fixture.put("request_hash", "0".repeat(64));
        RoomGraphCommand command = MAPPER.treeToValue(fixture, RoomGraphCommand.class);
        var signer = new Es256GraphReconciliationEnvelopeSigner(
                signingKey("java-reconciliation-es256-2"),
                MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(60),
                () -> "reconciliation-jti-001");

        assertThatThrownBy(() -> signer.sign(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestHash does not bind");
    }

    @Test
    void rejectsInvalidLifetimeJtiKeyAndSignatureShape() {
        assertThatThrownBy(() -> new Es256GraphReconciliationEnvelopeSigner(
                        signingKey("java-reconciliation-es256-2"),
                        MAPPER,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        Duration.ofSeconds(61),
                        () -> "reconciliation-jti-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..60");
        assertThatThrownBy(() -> signer(signingKey("invalid key"), "jti").sign(commandUnchecked()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyId");
        assertThatThrownBy(() -> signer(signingKey("key-1"), "invalid jti").sign(commandUnchecked()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jti");

        GraphEnvelopeSigningKey shortSignature = new GraphEnvelopeSigningKey() {
            @Override
            public String keyId() {
                return "key-1";
            }

            @Override
            public byte[] signSha256(byte[] signingInput) {
                return new byte[63];
            }
        };
        assertThatThrownBy(() -> signer(shortSignature, "jti-1").sign(commandUnchecked()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64-byte");
    }

    private static Es256GraphReconciliationEnvelopeSigner signer(
            GraphEnvelopeSigningKey key,
            String jti) {
        return new Es256GraphReconciliationEnvelopeSigner(
                key,
                MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(60),
                () -> jti);
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

    private static RoomGraphCommand command() throws Exception {
        return MAPPER.treeToValue(
                MAPPER.readTree(FIXTURE.toFile()).required("instance"),
                RoomGraphCommand.class);
    }

    private static RoomGraphCommand commandUnchecked() {
        try {
            return command();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
