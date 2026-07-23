package com.example.dispute.evidence.application.graph;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Verified Java authority for an immutable, directly signed Evidence batch manifest. */
public final class EvidenceBatchManifest {

    public static final String SCHEMA_VERSION = "evidence-batch-manifest.v1";
    public static final String ASSESSMENT_OUTPUT_SCHEMA_VERSION = "evidence-item-assessment.v1";
    public static final String TERMINAL_OUTPUT_SCHEMA_VERSION = "evidence-batch-proposal.v1";

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SIGNATURE = Pattern.compile("^[A-Za-z0-9_-]{86}$");
    private static final Set<Integer> SYNTHETIC_ITEM_COUNTS = Set.of(1, 8, 100);
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .findAndAddModules()
            .build();

    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "schema_version", "manifest_id", "manifest_hash", "execution_scope",
            "writer_mode", "formal_sink_eligible", "graph_execution_allowed",
            "synthetic_fixture_id", "registration_id", "tenant_surrogate", "case_id",
            "room_id", "room_type", "room_epoch", "fencing_token", "thread_id",
            "actor_id", "actor_role", "participant_id", "actor_scope_hash",
            "agent_session_id", "command_binding", "submission_batch_id",
            "submission_revision", "dossier_target_version", "profile_versions",
            "issued_at", "not_before", "expires_at", "item_count", "ordered_item_keys",
            "items", "signature_algorithm", "signing_key_id", "signature");
    private static final Set<String> COMMAND_FIELDS = Set.of(
            "schema_version", "command_id", "logical_run_id", "attempt_id", "command_type",
            "submitted_at", "deadline_at");
    private static final Set<String> PROFILE_FIELDS = Set.of(
            "graph_version", "checkpoint_schema_version", "state_schema_version",
            "prompt_version", "model_profile_id", "assessment_output_schema_version",
            "terminal_output_schema_version", "policy_version", "guardrail_version",
            "tool_policy_version");
    private static final Set<String> ITEM_FIELDS = Set.of(
            "schema_version", "evidence_id", "item_hash", "owner_participant_id",
            "owner_role", "visibility", "object_ref", "immutable_object_version",
            "object_sha256", "content_type", "byte_size", "original_filename", "parse_ref",
            "parse_hash", "parse_status", "privacy_basis", "permitted_modalities",
            "formal_evidence_revision", "display_order");

    static {
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private final ObjectNode document;
    private final byte[] canonicalPayload;
    private final String payloadSha256;
    private final Map<String, ObjectNode> itemsById;

    private EvidenceBatchManifest(
            ObjectNode document,
            byte[] canonicalPayload,
            String payloadSha256,
            Map<String, ObjectNode> itemsById) {
        this.document = document.deepCopy();
        this.canonicalPayload = canonicalPayload.clone();
        this.payloadSha256 = payloadSha256;
        this.itemsById = Map.copyOf(itemsById);
    }

    /**
     * Verifies the transport bytes before parsing, then the internal self-hash and direct Java
     * signature before command, actor-scope and fence bindings.
     */
    public static EvidenceBatchManifest verifySignedPayload(
            SnapshotReference snapshot,
            byte[] signedPayload,
            JsonNode verifiedRoomGraphCommand,
            long currentJavaRoomFence,
            long expectedGraphLeaseFence,
            long currentGraphLeaseFence,
            Instant now,
            PublicKeyResolver keyResolver) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(signedPayload, "signedPayload");
        Objects.requireNonNull(verifiedRoomGraphCommand, "verifiedRoomGraphCommand");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(keyResolver, "keyResolver");

        String payloadHash = verifyFullPayloadReference(snapshot, signedPayload);
        ObjectNode document = parseCanonicalObject(signedPayload, "manifest");
        requireExactFields(document, MANIFEST_FIELDS, Set.of("synthetic_fixture_id"), "manifest");
        requireText(document, "schema_version", SCHEMA_VERSION);
        requireText(document, "manifest_id", snapshot.artifactId());
        if (document.has("authorization_proof_ref")) {
            throw rejected("MANIFEST_DETACHED_AUTHORIZATION_FORBIDDEN");
        }

        String manifestHash = requiredHash(document, "manifest_hash");
        String calculatedHash = canonicalHashOmitting(document, "manifest_hash", "signature");
        if (!MessageDigest.isEqual(ascii(manifestHash), ascii(calculatedHash))) {
            throw rejected("MANIFEST_SELF_HASH_MISMATCH");
        }
        verifyDirectEs256(
                manifestHash,
                requiredText(document, "signature_algorithm"),
                requiredText(document, "signing_key_id"),
                requiredText(document, "signature"),
                keyResolver);

        Map<String, ObjectNode> items = validateSemantics(document, now);
        requireRoomGraphCommandBinding(document, snapshot, verifiedRoomGraphCommand);
        requireJavaRoomFence(document.required("fencing_token").longValue(), currentJavaRoomFence);
        requireGraphLeaseFence(
                currentJavaRoomFence, expectedGraphLeaseFence, currentGraphLeaseFence);
        return new EvidenceBatchManifest(document, signedPayload, payloadHash, items);
    }

    /** Issues canonical bytes carrying a direct ES256 JOSE-P1363 signature. */
    public static byte[] issueCanonicalPayload(
            ObjectNode claims, String signingKeyId, PrivateKey privateKey) {
        Objects.requireNonNull(claims, "claims");
        requireP256(privateKey);
        ObjectNode issued = claims.deepCopy();
        if (issued.has("authorization_proof_ref")) {
            throw rejected("MANIFEST_DETACHED_AUTHORIZATION_FORBIDDEN");
        }
        issued.put("schema_version", SCHEMA_VERSION);
        issued.put("signature_algorithm", "ES256");
        issued.put("signing_key_id", bounded(signingKeyId, "signingKeyId"));
        issued.put("signature", "A".repeat(86));
        issued.put("manifest_hash", "0".repeat(64));
        issued.put(
                "manifest_hash", canonicalHashOmitting(issued, "manifest_hash", "signature"));
        issued.put("signature", signHash(requiredText(issued, "manifest_hash"), privateKey));
        return ContractJson.canonicalize(issued);
    }

    public String manifestId() {
        return requiredText(document, "manifest_id");
    }

    public String manifestHash() {
        return requiredText(document, "manifest_hash");
    }

    public String payloadSha256() {
        return payloadSha256;
    }

    public int payloadSizeBytes() {
        return canonicalPayload.length;
    }

    public byte[] canonicalPayload() {
        return canonicalPayload.clone();
    }

    public ObjectNode document() {
        return document.deepCopy();
    }

    public ObjectNode profileVersions() {
        return ((ObjectNode) document.required("profile_versions")).deepCopy();
    }

    public String profileVersionsHash() {
        return ContractJson.sha256Hex(document.required("profile_versions"));
    }

    public List<String> orderedItemKeys() {
        List<String> result = new ArrayList<>();
        document.required("ordered_item_keys").forEach(node -> result.add(node.textValue()));
        return List.copyOf(result);
    }

    public ObjectNode requireItem(String evidenceId) {
        ObjectNode item = itemsById.get(evidenceId);
        if (item == null) {
            throw rejected("MANIFEST_ITEM_NOT_FOUND");
        }
        return item.deepCopy();
    }

    public String text(String field) {
        return requiredText(document, field);
    }

    public long number(String field) {
        return requiredLong(document, field, 0);
    }

    public Instant instant(String field) {
        return requiredInstant(document, field);
    }

    private static Map<String, ObjectNode> validateSemantics(ObjectNode document, Instant now) {
        requireText(document, "execution_scope", "SIGNED_SYNTHETIC_ONLY");
        requireText(document, "writer_mode", "SHADOW");
        requireFalse(document, "formal_sink_eligible");
        requireTrue(document, "graph_execution_allowed");
        requireText(document, "room_type", "EVIDENCE");
        requireText(document, "signature_algorithm", "ES256");
        if (!SIGNATURE.matcher(requiredText(document, "signature")).matches()) {
            throw rejected("MANIFEST_SIGNATURE_ENCODING_INVALID");
        }
        bounded(requiredText(document, "synthetic_fixture_id"), "synthetic_fixture_id");
        long roomEpoch = requiredLong(document, "room_epoch", 0);
        long roomFence = requiredLong(document, "fencing_token", 1);
        if (roomEpoch < 0 || roomFence < 1) {
            throw rejected("MANIFEST_EPOCH_OR_FENCE_INVALID");
        }
        Instant issuedAt = requiredInstant(document, "issued_at");
        Instant notBefore = requiredInstant(document, "not_before");
        Instant expiresAt = requiredInstant(document, "expires_at");
        if (notBefore.isBefore(issuedAt) || !expiresAt.isAfter(notBefore)) {
            throw rejected("MANIFEST_TIME_WINDOW_INVALID");
        }
        if (now.isBefore(notBefore) || !now.isBefore(expiresAt)) {
            throw rejected("MANIFEST_NOT_CURRENTLY_VALID");
        }

        ObjectNode command = requiredObject(document, "command_binding");
        requireExactFields(command, COMMAND_FIELDS, Set.of(), "command_binding");
        requireText(command, "schema_version", "evidence-room-command.v1");
        requireText(command, "command_type", "EVIDENCE_ASSESS_BATCH");
        if (!requiredInstant(command, "deadline_at")
                .isAfter(requiredInstant(command, "submitted_at"))) {
            throw rejected("MANIFEST_COMMAND_DEADLINE_INVALID");
        }

        ObjectNode profile = requiredObject(document, "profile_versions");
        requireExactFields(profile, PROFILE_FIELDS, Set.of(), "profile_versions");
        requireText(profile, "state_schema_version", "evidence-graph-state.v2");
        requireText(
                profile,
                "assessment_output_schema_version",
                ASSESSMENT_OUTPUT_SCHEMA_VERSION);
        requireText(profile, "terminal_output_schema_version", TERMINAL_OUTPUT_SCHEMA_VERSION);
        if (ASSESSMENT_OUTPUT_SCHEMA_VERSION.equals(
                requiredText(profile, "terminal_output_schema_version"))) {
            throw rejected("MANIFEST_OUTPUT_PINS_COLLAPSED");
        }

        int itemCount = Math.toIntExact(requiredLong(document, "item_count", 1));
        if (!SYNTHETIC_ITEM_COUNTS.contains(itemCount)) {
            throw rejected("MANIFEST_SYNTHETIC_ITEM_COUNT_NOT_ADMITTED");
        }
        JsonNode keysNode = requiredArray(document, "ordered_item_keys");
        JsonNode itemsNode = requiredArray(document, "items");
        if (keysNode.size() != itemCount || itemsNode.size() != itemCount) {
            throw rejected("MANIFEST_ITEM_COUNT_MISMATCH");
        }

        List<String> keys = new ArrayList<>(itemCount);
        keysNode.forEach(node -> keys.add(requireTextNode(node, "ordered_item_keys")));
        if (new HashSet<>(keys).size() != keys.size()
                || !keys.equals(new ArrayList<>(new TreeSet<>(keys)))) {
            throw rejected("MANIFEST_ITEM_KEYS_NOT_UNIQUE_SORTED");
        }

        Map<String, ObjectNode> items = new LinkedHashMap<>();
        for (JsonNode candidate : itemsNode) {
            if (!(candidate instanceof ObjectNode item)) {
                throw rejected("MANIFEST_ITEM_NOT_OBJECT");
            }
            requireExactFields(item, ITEM_FIELDS, Set.of(), "item");
            requireText(item, "schema_version", "evidence-item-manifest.v1");
            String evidenceId = requiredText(item, "evidence_id");
            String itemHash = requiredHash(item, "item_hash");
            if (!itemHash.equals(canonicalHashOmitting(item, "item_hash"))) {
                throw rejected("MANIFEST_ITEM_HASH_MISMATCH");
            }
            requireText(item, "privacy_basis", "SIGNED_SYNTHETIC_FIXTURE");
            String objectRef = requiredText(item, "object_ref");
            if (!objectRef.startsWith("urn:synthetic-evidence:")) {
                throw rejected("MANIFEST_REAL_OBJECT_FORBIDDEN");
            }
            requiredHash(item, "object_sha256");
            requiredLong(item, "byte_size", 1);
            validateParseBinding(item);
            if (items.put(evidenceId, item.deepCopy()) != null) {
                throw rejected("MANIFEST_DUPLICATE_EVIDENCE_ID");
            }
        }
        if (!keys.equals(new ArrayList<>(items.keySet()))) {
            throw rejected("MANIFEST_ORDERED_KEYS_ITEM_MISMATCH");
        }
        return items;
    }

    private static void requireRoomGraphCommandBinding(
            ObjectNode manifest, SnapshotReference snapshot, JsonNode command) {
        if (!command.isObject()) {
            throw rejected("ROOM_GRAPH_COMMAND_NOT_OBJECT");
        }
        ObjectNode commandPreimage = ((ObjectNode) command).deepCopy();
        String requestHash = requiredHash(commandPreimage, "request_hash");
        commandPreimage.remove("request_hash");
        if (!requestHash.equals(ContractJson.sha256Hex(commandPreimage))) {
            throw rejected("ROOM_GRAPH_COMMAND_REQUEST_HASH_MISMATCH");
        }
        requireText(command, "schema_version", "room-graph-command.v1");
        requireText(command, "graph_key", "evidence.v2");
        for (String forbidden : List.of("fencing_token", "graph_lease_fencing_token")) {
            if (command.has(forbidden)) {
                throw rejected("ROOM_GRAPH_COMMAND_FENCE_FIELD_FORBIDDEN");
            }
        }

        ObjectNode commandBinding = requiredObject(manifest, "command_binding");
        requireEqual(command, commandBinding, "command_id", "command_id");
        requireEqual(command, commandBinding, "logical_run_id", "logical_run_id");
        requireEqual(command, commandBinding, "attempt_id", "attempt_id");
        requireEqual(command, manifest, "tenant_surrogate", "tenant_surrogate");
        requireEqual(command, manifest, "case_id", "case_id");
        requireEqual(command, manifest, "room_type", "room_type");
        requireEqual(command, manifest, "room_epoch", "room_epoch");
        requireEqual(command, manifest, "thread_id", "thread_id");
        requireEqual(command, commandBinding, "deadline_at", "deadline_at");

        ObjectNode profile = requiredObject(manifest, "profile_versions");
        requireEqual(command, profile, "graph_version", "graph_version");
        requireEqual(
                command,
                profile,
                "checkpoint_schema_version",
                "checkpoint_schema_version");
        ObjectNode actorScope = requiredObject(command, "actor_scope");
        requireEqual(actorScope, manifest, "actor_id", "actor_id");
        requireEqual(actorScope, manifest, "actor_role", "actor_role");
        if (!actorScope.required("audience").equals(manifest.required("actor_role"))) {
            throw rejected("ROOM_GRAPH_COMMAND_BINDING_MISMATCH:audience");
        }
        String derivedActorScopeHash = ContractJson.sha256Hex(actorScope);
        if (!derivedActorScopeHash.equals(requiredText(manifest, "actor_scope_hash"))) {
            throw rejected("ROOM_GRAPH_COMMAND_BINDING_MISMATCH:actor_scope_hash");
        }

        ObjectNode invocation = requiredObject(command, "invocation_context");
        requireEqual(invocation, profile, "prompt_profile_id", "prompt_version");
        requireEqual(invocation, profile, "model_profile_id", "model_profile_id");
        requireEqual(
                invocation,
                profile,
                "output_schema_version",
                "terminal_output_schema_version");
        requireEqual(invocation, profile, "policy_version", "policy_version");
        requireEqual(invocation, profile, "guardrail_version", "guardrail_version");
        if (!invocation.required("tool_capabilities").equals(actorScope.required("capabilities"))) {
            throw rejected("ROOM_GRAPH_COMMAND_BINDING_MISMATCH:tool_capabilities");
        }
        if (ASSESSMENT_OUTPUT_SCHEMA_VERSION.equals(
                requiredText(invocation, "output_schema_version"))) {
            throw rejected("ROOM_GRAPH_COMMAND_OUTER_OUTPUT_USES_ASSESSMENT_PIN");
        }

        ObjectNode ref = requiredObject(command, "domain_snapshot_ref");
        requireText(ref, "artifact_id", snapshot.artifactId());
        requireText(ref, "schema_version", SCHEMA_VERSION);
        requireText(ref, "uri", snapshot.uri());
        requireText(ref, "sha256", snapshot.sha256());
        if (requiredLong(ref, "size_bytes", 1) != snapshot.sizeBytes()) {
            throw rejected("ROOM_GRAPH_COMMAND_BINDING_MISMATCH:domain_snapshot_ref.size_bytes");
        }
    }

    private static String verifyFullPayloadReference(SnapshotReference ref, byte[] payload) {
        if (payload.length == 0 || payload.length != ref.sizeBytes()) {
            throw rejected("MANIFEST_SNAPSHOT_SIZE_MISMATCH");
        }
        String actualHash = sha256(payload);
        if (!MessageDigest.isEqual(ascii(actualHash), ascii(ref.sha256()))) {
            throw rejected("MANIFEST_SNAPSHOT_SHA256_MISMATCH");
        }
        String suffix = "/" + actualHash + ".json";
        if (!(ref.uri().startsWith("s3://") || ref.uri().startsWith("minio://"))
                || !ref.uri().endsWith(suffix)) {
            throw rejected("MANIFEST_SNAPSHOT_URI_NOT_CONTENT_ADDRESSED");
        }
        return actualHash;
    }

    private static ObjectNode parseCanonicalObject(byte[] payload, String kind) {
        try {
            JsonNode parsed = MAPPER.readTree(payload);
            if (!(parsed instanceof ObjectNode object)) {
                throw rejected(kind.toUpperCase() + "_NOT_OBJECT");
            }
            if (!MessageDigest.isEqual(payload, ContractJson.canonicalize(object))) {
                throw rejected(kind.toUpperCase() + "_PAYLOAD_NOT_RFC8785_CANONICAL");
            }
            return object;
        } catch (IOException failure) {
            throw new IllegalArgumentException(kind + " payload is invalid JSON", failure);
        }
    }

    static ObjectNode parseCanonicalCapability(byte[] payload) {
        return parseCanonicalObject(payload, "capability");
    }

    static String canonicalHashOmitting(ObjectNode value, String... omittedFields) {
        ObjectNode preimage = value.deepCopy();
        for (String field : omittedFields) {
            preimage.remove(field);
        }
        return ContractJson.sha256Hex(preimage);
    }

    static void verifyDirectEs256(
            String hash,
            String algorithm,
            String keyId,
            String encodedSignature,
            PublicKeyResolver resolver) {
        if (!"ES256".equals(algorithm) || !SIGNATURE.matcher(encodedSignature).matches()) {
            throw rejected("ES256_SIGNATURE_ENCODING_INVALID");
        }
        PublicKey key = Objects.requireNonNull(resolver.resolve(keyId), "resolved public key");
        requireP256(key);
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getUrlDecoder().decode(encodedSignature);
        } catch (IllegalArgumentException failure) {
            throw rejected("ES256_SIGNATURE_ENCODING_INVALID", failure);
        }
        if (signatureBytes.length != 64) {
            throw rejected("ES256_SIGNATURE_ENCODING_INVALID");
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(key);
            verifier.update(ascii(requiredHashText(hash)));
            if (!verifier.verify(signatureBytes)) {
                throw rejected("ES256_SIGNATURE_INVALID");
            }
        } catch (GeneralSecurityException failure) {
            throw rejected("ES256_SIGNATURE_VERIFICATION_FAILED", failure);
        }
    }

    static String signHash(String hash, PrivateKey key) {
        requireP256(key);
        try {
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(key);
            signer.update(ascii(requiredHashText(hash)));
            byte[] signature = signer.sign();
            if (signature.length != 64) {
                throw rejected("ES256_SIGNATURE_ENCODING_INVALID");
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (GeneralSecurityException failure) {
            throw rejected("ES256_SIGNATURE_ISSUE_FAILED", failure);
        }
    }

    static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static void validateParseBinding(ObjectNode item) {
        String status = requiredText(item, "parse_status");
        JsonNode ref = item.get("parse_ref");
        JsonNode hash = item.get("parse_hash");
        if ("AVAILABLE".equals(status)) {
            if (ref == null
                    || !ref.isTextual()
                    || !ref.textValue().startsWith("urn:synthetic-evidence-parse:")
                    || hash == null
                    || !hash.isTextual()
                    || !SHA256.matcher(hash.textValue()).matches()) {
                throw rejected("MANIFEST_PARSE_BINDING_INVALID");
            }
        } else if (!("NOT_REQUESTED".equals(status) || "FAILED".equals(status))
                || ref == null
                || !ref.isNull()
                || hash == null
                || !hash.isNull()) {
            throw rejected("MANIFEST_PARSE_BINDING_INVALID");
        }
    }

    private static void requireJavaRoomFence(long manifestFence, long currentFence) {
        if (manifestFence != currentFence) {
            throw rejected("JAVA_ROOM_FENCE_MISMATCH");
        }
    }

    public static void requireGraphLeaseFence(
            long javaRoomFence, long expectedGraphLeaseFence, long currentGraphLeaseFence) {
        if (expectedGraphLeaseFence < 1 || currentGraphLeaseFence != expectedGraphLeaseFence) {
            throw rejected("GRAPH_LEASE_FENCE_MISMATCH");
        }
        if (javaRoomFence == currentGraphLeaseFence) {
            throw rejected("ROOM_AND_GRAPH_FENCES_MUST_BE_DISTINCT");
        }
    }

    private static void requireExactFields(
            ObjectNode object, Set<String> allowed, Set<String> optional, String kind) {
        Set<String> actual = new HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!allowed.containsAll(actual)) {
            Set<String> unknown = new TreeSet<>(actual);
            unknown.removeAll(allowed);
            throw rejected(kind.toUpperCase() + "_UNKNOWN_FIELDS:" + unknown);
        }
        Set<String> missing = new TreeSet<>(allowed);
        missing.removeAll(optional);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw rejected(kind.toUpperCase() + "_MISSING_FIELDS:" + missing);
        }
    }

    private static void requireEqual(
            JsonNode actualParent,
            JsonNode expectedParent,
            String actualField,
            String expectedField) {
        if (!actualParent.required(actualField).equals(expectedParent.required(expectedField))) {
            throw rejected("ROOM_GRAPH_COMMAND_BINDING_MISMATCH:" + expectedField);
        }
    }

    static ObjectNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (!(value instanceof ObjectNode object)) {
            throw rejected(field.toUpperCase() + "_MUST_BE_OBJECT");
        }
        return object;
    }

    static JsonNode requiredArray(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) {
            throw rejected(field.toUpperCase() + "_MUST_BE_ARRAY");
        }
        return value;
    }

    static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw rejected(field.toUpperCase() + "_MUST_BE_TEXT");
        }
        return value.textValue();
    }

    private static String requireTextNode(JsonNode value, String field) {
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw rejected(field.toUpperCase() + "_MUST_CONTAIN_TEXT");
        }
        return value.textValue();
    }

    static String requiredHash(JsonNode parent, String field) {
        return requiredHashText(requiredText(parent, field));
    }

    private static String requiredHashText(String value) {
        if (!SHA256.matcher(value).matches()) {
            throw rejected("SHA256_LOWERCASE_HEX_REQUIRED");
        }
        return value;
    }

    static long requiredLong(JsonNode parent, String field, long minimum) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw rejected(field.toUpperCase() + "_MUST_BE_INTEGER");
        }
        long result = value.longValue();
        if (result < minimum || result > 9_007_199_254_740_991L) {
            throw rejected(field.toUpperCase() + "_OUT_OF_RANGE");
        }
        return result;
    }

    static Instant requiredInstant(JsonNode parent, String field) {
        try {
            return Instant.parse(requiredText(parent, field));
        } catch (RuntimeException failure) {
            throw rejected(field.toUpperCase() + "_MUST_BE_INSTANT", failure);
        }
    }

    private static void requireText(JsonNode parent, String field, String expected) {
        if (!expected.equals(requiredText(parent, field))) {
            throw rejected(field.toUpperCase() + "_MISMATCH");
        }
    }

    private static void requireFalse(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean() || value.booleanValue()) {
            throw rejected(field.toUpperCase() + "_MUST_BE_FALSE");
        }
    }

    private static void requireTrue(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean() || !value.booleanValue()) {
            throw rejected(field.toUpperCase() + "_MUST_BE_TRUE");
        }
    }

    private static String bounded(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw rejected(field + " is not a bounded identifier");
        }
        return value;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void requireP256(java.security.Key key) {
        if (!(key instanceof ECKey ecKey)
                || ecKey.getParams().getCurve().getField().getFieldSize() != 256) {
            throw rejected("ES256_REQUIRES_P256_KEY");
        }
    }

    static IllegalArgumentException rejected(String code) {
        return new IllegalArgumentException(code);
    }

    static IllegalArgumentException rejected(String code, Throwable failure) {
        return new IllegalArgumentException(code, failure);
    }

    @FunctionalInterface
    public interface PublicKeyResolver {
        PublicKey resolve(String signingKeyId);
    }

    public record SnapshotReference(
            String artifactId, String schemaVersion, String uri, String sha256, long sizeBytes) {
        public SnapshotReference {
            bounded(artifactId, "artifactId");
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw rejected("MANIFEST_SNAPSHOT_SCHEMA_MISMATCH");
            }
            if (uri == null || uri.isBlank() || uri.length() > 1024) {
                throw rejected("MANIFEST_SNAPSHOT_URI_INVALID");
            }
            requiredHashText(sha256);
            if (sizeBytes < 1 || sizeBytes > 2_097_152) {
                throw rejected("MANIFEST_SNAPSHOT_SIZE_INVALID");
            }
        }
    }
}
