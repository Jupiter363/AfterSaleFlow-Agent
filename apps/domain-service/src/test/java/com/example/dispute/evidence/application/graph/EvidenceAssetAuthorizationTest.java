package com.example.dispute.evidence.application.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.evidence.application.graph.EvidenceAssetAuthorization.AuthorizationRequest;
import com.example.dispute.evidence.application.graph.EvidenceAssetAuthorization.LoadedAsset;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EvidenceAssetAuthorizationTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES = Path.of(
            "..", "..", "contracts", "agent-platform", "evidence", "v2", "fixtures", "valid");
    private static final Path COMMAND_FIXTURE = Path.of(
            "..",
            "..",
            "contracts",
            "agent-platform",
            "v1",
            "fixtures",
            "valid",
            "room-graph-command-evidence-valid.json");
    private static final Instant NOW = Instant.parse("2026-07-22T12:05:00Z");
    private static final long JAVA_ROOM_FENCE = 7L;
    private static final long GRAPH_LEASE_FENCE = 7001L;
    private static final byte[] ASSET_BYTES = "signed synthetic Evidence bytes".getBytes();

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
    }

    @Test
    void verifiesDirectManifestSignatureAndBindsAnActualImmutableLoadReceipt() throws Exception {
        Fixture fixture = fixture();
        Set<String> consumed = ConcurrentHashMap.newKeySet();
        EvidenceAssetAuthorization authorization = authorization(
                consumed,
                request -> new LoadedAsset(
                        request.objectRef(),
                        request.immutableObjectVersion(),
                        request.contentType(),
                        ASSET_BYTES,
                        List.of("PDF_METADATA", "TEXT")));

        var authorized = authorization.authorizeAndLoad(request(fixture));

        assertThat(authorized.bytes()).isEqualTo(ASSET_BYTES);
        assertThat(authorized.receipt().loadStatus()).isEqualTo("LOADED");
        assertThat(authorized.receipt().objectSha256())
                .isEqualTo(EvidenceBatchManifest.sha256(ASSET_BYTES));
        assertThat(authorized.receipt().javaRoomFencingToken()).isEqualTo(JAVA_ROOM_FENCE);
        assertThat(authorized.receipt().graphLeaseFencingToken()).isEqualTo(GRAPH_LEASE_FENCE);
        assertThat(authorized.receipt().loadedModalities())
                .containsExactly("PDF_METADATA", "TEXT");
        assertThat(consumed).containsExactly("CAPABILITY_P5_SYNTHETIC_001:NONCE_P5_SYNTHETIC_001");
        assertThat(fixture.manifest()
                        .profileVersions()
                        .required("assessment_output_schema_version")
                        .asText())
                .isEqualTo(EvidenceBatchManifest.ASSESSMENT_OUTPUT_SCHEMA_VERSION);
        assertThat(fixture.manifest()
                        .profileVersions()
                        .required("terminal_output_schema_version")
                        .asText())
                .isEqualTo(EvidenceBatchManifest.TERMINAL_OUTPUT_SCHEMA_VERSION);
    }

    @Test
    void rejectsWrongFullSnapshotBytesBeforeParsingOrResolvingAKey() throws Exception {
        SignedManifest signed = signedManifest();
        AtomicInteger keyLookups = new AtomicInteger();
        var wrongHash = new EvidenceBatchManifest.SnapshotReference(
                signed.snapshot().artifactId(),
                signed.snapshot().schemaVersion(),
                signed.snapshot().uri(),
                "0".repeat(64),
                signed.snapshot().sizeBytes());

        assertThatThrownBy(() -> EvidenceBatchManifest.verifySignedPayload(
                        wrongHash,
                        signed.payload(),
                        signed.command(),
                        JAVA_ROOM_FENCE,
                        GRAPH_LEASE_FENCE,
                        GRAPH_LEASE_FENCE,
                        NOW,
                        keyId -> {
                            keyLookups.incrementAndGet();
                            return keyPair.getPublic();
                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MANIFEST_SNAPSHOT_SHA256_MISMATCH");
        assertThat(keyLookups).hasValue(0);

        var wrongSize = new EvidenceBatchManifest.SnapshotReference(
                signed.snapshot().artifactId(),
                signed.snapshot().schemaVersion(),
                signed.snapshot().uri(),
                signed.snapshot().sha256(),
                signed.snapshot().sizeBytes() + 1);
        assertThatThrownBy(() -> verify(signed, wrongSize))
                .hasMessage("MANIFEST_SNAPSHOT_SIZE_MISMATCH");

        var wrongUri = new EvidenceBatchManifest.SnapshotReference(
                signed.snapshot().artifactId(),
                signed.snapshot().schemaVersion(),
                "s3://evidence-synthetic-manifests/not-content-addressed.json",
                signed.snapshot().sha256(),
                signed.snapshot().sizeBytes());
        assertThatThrownBy(() -> verify(signed, wrongUri))
                .hasMessage("MANIFEST_SNAPSHOT_URI_NOT_CONTENT_ADDRESSED");
    }

    @Test
    void rejectsDetachedAuthorizationAndCollapsedOrStaleAuthority() throws Exception {
        ObjectNode claims = manifestClaims();
        claims.putObject("authorization_proof_ref").put("proof_id", "FORBIDDEN");
        assertThatThrownBy(() -> EvidenceBatchManifest.issueCanonicalPayload(
                        claims, "KEY_P5_SYNTHETIC_ES256_1", keyPair.getPrivate()))
                .hasMessage("MANIFEST_DETACHED_AUTHORIZATION_FORBIDDEN");

        Fixture fixture = fixture();
        AuthorizationRequest wrongOwner = new AuthorizationRequest(
                fixture.manifest(),
                fixture.capabilityPayload(),
                "TENANT_P5_SYNTHETIC_1",
                "CASE_P5_SYNTHETIC_1",
                "PARTICIPANT_P5_OTHER",
                "PRIVATE",
                JAVA_ROOM_FENCE,
                GRAPH_LEASE_FENCE,
                GRAPH_LEASE_FENCE);
        assertThatThrownBy(() -> authorization(
                                ConcurrentHashMap.newKeySet(),
                                request -> validLoadedAsset(request))
                        .authorizeAndLoad(wrongOwner))
                .hasMessage("CAPABILITY_OWNER_NOT_AUTHORIZED");

        AuthorizationRequest collapsedFence = new AuthorizationRequest(
                fixture.manifest(),
                fixture.capabilityPayload(),
                "TENANT_P5_SYNTHETIC_1",
                "CASE_P5_SYNTHETIC_1",
                "PARTICIPANT_P5_USER",
                "PRIVATE",
                JAVA_ROOM_FENCE,
                JAVA_ROOM_FENCE,
                JAVA_ROOM_FENCE);
        assertThatThrownBy(() -> authorization(
                                ConcurrentHashMap.newKeySet(),
                                request -> validLoadedAsset(request))
                        .authorizeAndLoad(collapsedFence))
                .hasMessage("ROOM_AND_GRAPH_FENCES_MUST_BE_DISTINCT");
    }

    @Test
    void rejectsNonceReplayAndAnyMismatchInActuallyLoadedObject() throws Exception {
        Fixture fixture = fixture();
        Set<String> consumed = ConcurrentHashMap.newKeySet();
        EvidenceAssetAuthorization authorization = authorization(
                consumed, request -> validLoadedAsset(request));
        authorization.authorizeAndLoad(request(fixture));

        assertThatThrownBy(() -> authorization.authorizeAndLoad(request(fixture)))
                .hasMessage("CAPABILITY_NONCE_REPLAYED");

        Fixture anotherFixture = fixture();
        EvidenceAssetAuthorization wrongVersion = authorization(
                ConcurrentHashMap.newKeySet(),
                request -> new LoadedAsset(
                        request.objectRef(),
                        "OTHER_VERSION",
                        request.contentType(),
                        ASSET_BYTES,
                        List.of("TEXT")));
        assertThatThrownBy(() -> wrongVersion.authorizeAndLoad(request(anotherFixture)))
                .hasMessage("ASSET_ACTUAL_IMMUTABLE_OBJECT_VERSION_MISMATCH");

        Fixture thirdFixture = fixture();
        byte[] changed = ASSET_BYTES.clone();
        changed[0] ^= 1;
        EvidenceAssetAuthorization wrongHash = authorization(
                ConcurrentHashMap.newKeySet(),
                request -> new LoadedAsset(
                        request.objectRef(),
                        request.immutableObjectVersion(),
                        request.contentType(),
                        changed,
                        List.of("TEXT")));
        assertThatThrownBy(() -> wrongHash.authorizeAndLoad(request(thirdFixture)))
                .hasMessage("ASSET_ACTUAL_SHA256_MISMATCH");
    }

    private static EvidenceAssetAuthorization authorization(
            Set<String> consumed, EvidenceAssetAuthorization.AssetLoader loader) {
        return new EvidenceAssetAuthorization(
                keyId -> keyPair.getPublic(),
                (capabilityId, nonce, expiresAt) -> consumed.add(capabilityId + ":" + nonce),
                loader,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static LoadedAsset validLoadedAsset(EvidenceAssetAuthorization.AssetLoadRequest request) {
        return new LoadedAsset(
                request.objectRef(),
                request.immutableObjectVersion(),
                request.contentType(),
                ASSET_BYTES,
                List.of("TEXT", "PDF_METADATA"));
    }

    private static AuthorizationRequest request(Fixture fixture) {
        return new AuthorizationRequest(
                fixture.manifest(),
                fixture.capabilityPayload(),
                "TENANT_P5_SYNTHETIC_1",
                "CASE_P5_SYNTHETIC_1",
                "PARTICIPANT_P5_USER",
                "PRIVATE",
                JAVA_ROOM_FENCE,
                GRAPH_LEASE_FENCE,
                GRAPH_LEASE_FENCE);
    }

    private static Fixture fixture() throws Exception {
        SignedManifest signed = signedManifest();
        EvidenceBatchManifest manifest = verify(signed, signed.snapshot());
        ObjectNode capability = (ObjectNode) MAPPER.readTree(
                FIXTURES.resolve("evidence-asset-capability-valid.json").toFile());
        ObjectNode item = manifest.requireItem("EVIDENCE_SYNTH_001");
        capability.put("manifest_hash", manifest.manifestHash());
        for (String field : List.of(
                "item_hash", "object_sha256", "byte_size", "permitted_modalities")) {
            capability.set(field, item.required(field));
        }
        capability.put("profile_versions_hash", manifest.profileVersionsHash());
        byte[] capabilityPayload = EvidenceAssetCapability.issueCanonicalPayload(
                capability, "KEY_P5_SYNTHETIC_ES256_1", keyPair.getPrivate());
        return new Fixture(manifest, capabilityPayload, signed.snapshot());
    }

    private static SignedManifest signedManifest() throws Exception {
        ObjectNode claims = manifestClaims();
        byte[] payload = EvidenceBatchManifest.issueCanonicalPayload(
                claims, "KEY_P5_SYNTHETIC_ES256_1", keyPair.getPrivate());
        String payloadHash = EvidenceBatchManifest.sha256(payload);
        String uri = "s3://evidence-synthetic-manifests/CASE_P5_SYNTHETIC_1/epoch-1/"
                + payloadHash
                + ".json";
        var snapshot = new EvidenceBatchManifest.SnapshotReference(
                "MANIFEST_P5_SYNTHETIC_ONE",
                EvidenceBatchManifest.SCHEMA_VERSION,
                uri,
                payloadHash,
                payload.length);
        ObjectNode command = (ObjectNode) MAPPER.readTree(COMMAND_FIXTURE.toFile())
                .required("instance")
                .deepCopy();
        ObjectNode commandSnapshot = (ObjectNode) command.required("domain_snapshot_ref");
        commandSnapshot.put("uri", uri);
        commandSnapshot.put("sha256", payloadHash);
        commandSnapshot.put("size_bytes", payload.length);
        command.put("request_hash", "0".repeat(64));
        ObjectNode commandPreimage = command.deepCopy();
        commandPreimage.remove("request_hash");
        command.put("request_hash", ContractJson.sha256Hex(commandPreimage));
        return new SignedManifest(payload, snapshot, command);
    }

    private static ObjectNode manifestClaims() throws Exception {
        ObjectNode manifest = (ObjectNode) MAPPER.readTree(
                FIXTURES.resolve("evidence-batch-manifest-synthetic-1-valid.json").toFile());
        ObjectNode item = (ObjectNode) manifest.required("items").required(0);
        item.put("object_sha256", EvidenceBatchManifest.sha256(ASSET_BYTES));
        item.put("byte_size", ASSET_BYTES.length);
        item.put("item_hash", "0".repeat(64));
        item.put("item_hash", EvidenceBatchManifest.canonicalHashOmitting(item, "item_hash"));
        return manifest;
    }

    private static EvidenceBatchManifest verify(
            SignedManifest signed, EvidenceBatchManifest.SnapshotReference snapshot) {
        return EvidenceBatchManifest.verifySignedPayload(
                snapshot,
                signed.payload(),
                signed.command(),
                JAVA_ROOM_FENCE,
                GRAPH_LEASE_FENCE,
                GRAPH_LEASE_FENCE,
                NOW,
                keyId -> keyPair.getPublic());
    }

    private record SignedManifest(
            byte[] payload,
            EvidenceBatchManifest.SnapshotReference snapshot,
            ObjectNode command) {
        private SignedManifest {
            payload = payload.clone();
            command = command.deepCopy();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public ObjectNode command() {
            return command.deepCopy();
        }
    }

    private record Fixture(
            EvidenceBatchManifest manifest,
            byte[] capabilityPayload,
            EvidenceBatchManifest.SnapshotReference snapshot) {
        private Fixture {
            capabilityPayload = capabilityPayload.clone();
        }

        @Override
        public byte[] capabilityPayload() {
            return capabilityPayload.clone();
        }
    }
}
