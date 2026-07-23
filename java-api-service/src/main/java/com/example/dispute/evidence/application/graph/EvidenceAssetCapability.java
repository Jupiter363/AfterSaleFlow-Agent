package com.example.dispute.evidence.application.graph;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** A verified, single-item, signed-synthetic capability issued by Java. */
public final class EvidenceAssetCapability {

    public static final String SCHEMA_VERSION = "evidence-asset-capability.v1";

    private static final Set<String> FIELDS = Set.of(
            "schema_version", "capability_id", "capability_hash", "execution_scope",
            "writer_mode", "real_case_allowed", "formal_sink_eligible", "registration_id",
            "manifest_id", "manifest_hash", "item_hash", "tenant_surrogate", "case_id",
            "room_epoch", "fencing_token", "thread_id", "actor_scope_hash",
            "agent_session_id", "evidence_id", "owner_participant_id", "owner_role",
            "visibility", "object_ref", "immutable_object_version", "object_sha256",
            "content_type", "byte_size", "privacy_basis", "parse_ref", "parse_hash",
            "parse_status", "profile_versions_hash", "permitted_modalities",
            "synthetic_fixture_id", "nonce", "issued_at", "expires_at",
            "signature_algorithm", "signing_key_id", "signature");

    private final ObjectNode document;
    private final byte[] canonicalPayload;

    private EvidenceAssetCapability(ObjectNode document, byte[] canonicalPayload) {
        this.document = document.deepCopy();
        this.canonicalPayload = canonicalPayload.clone();
    }

    public static EvidenceAssetCapability verifySignedPayload(
            byte[] signedPayload,
            EvidenceBatchManifest manifest,
            long currentJavaRoomFence,
            Instant now,
            EvidenceBatchManifest.PublicKeyResolver keyResolver) {
        Objects.requireNonNull(signedPayload, "signedPayload");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(keyResolver, "keyResolver");
        ObjectNode document = EvidenceBatchManifest.parseCanonicalCapability(signedPayload);
        requireExactFields(document);
        requireText(document, "schema_version", SCHEMA_VERSION);
        if (document.has("authorization_proof_ref")) {
            throw EvidenceBatchManifest.rejected("CAPABILITY_DETACHED_AUTHORIZATION_FORBIDDEN");
        }
        String claimedHash = EvidenceBatchManifest.requiredHash(document, "capability_hash");
        String calculatedHash = EvidenceBatchManifest.canonicalHashOmitting(
                document, "capability_hash", "signature");
        if (!MessageDigest.isEqual(claimedHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                calculatedHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw EvidenceBatchManifest.rejected("CAPABILITY_SELF_HASH_MISMATCH");
        }
        EvidenceBatchManifest.verifyDirectEs256(
                claimedHash,
                EvidenceBatchManifest.requiredText(document, "signature_algorithm"),
                EvidenceBatchManifest.requiredText(document, "signing_key_id"),
                EvidenceBatchManifest.requiredText(document, "signature"),
                keyResolver);
        validateSemantics(document, now);
        requireManifestBinding(document, manifest);
        if (EvidenceBatchManifest.requiredLong(document, "fencing_token", 1)
                != currentJavaRoomFence) {
            throw EvidenceBatchManifest.rejected("CAPABILITY_JAVA_ROOM_FENCE_MISMATCH");
        }
        return new EvidenceAssetCapability(document, signedPayload);
    }

    /** Issues canonical signed capability bytes from complete authority claims. */
    public static byte[] issueCanonicalPayload(
            ObjectNode claims, String signingKeyId, PrivateKey privateKey) {
        Objects.requireNonNull(claims, "claims");
        ObjectNode issued = claims.deepCopy();
        if (issued.has("authorization_proof_ref")) {
            throw EvidenceBatchManifest.rejected("CAPABILITY_DETACHED_AUTHORIZATION_FORBIDDEN");
        }
        issued.put("schema_version", SCHEMA_VERSION);
        issued.put("signature_algorithm", "ES256");
        issued.put("signing_key_id", signingKeyId);
        issued.put("signature", "A".repeat(86));
        issued.put("capability_hash", "0".repeat(64));
        issued.put(
                "capability_hash",
                EvidenceBatchManifest.canonicalHashOmitting(
                        issued, "capability_hash", "signature"));
        issued.put(
                "signature",
                EvidenceBatchManifest.signHash(
                        EvidenceBatchManifest.requiredText(issued, "capability_hash"), privateKey));
        return ContractJson.canonicalize(issued);
    }

    public ObjectNode document() {
        return document.deepCopy();
    }

    public byte[] canonicalPayload() {
        return canonicalPayload.clone();
    }

    public String text(String field) {
        return EvidenceBatchManifest.requiredText(document, field);
    }

    public long number(String field) {
        return EvidenceBatchManifest.requiredLong(document, field, 0);
    }

    public Instant instant(String field) {
        return EvidenceBatchManifest.requiredInstant(document, field);
    }

    public List<String> permittedModalities() {
        List<String> modalities = new ArrayList<>();
        EvidenceBatchManifest.requiredArray(document, "permitted_modalities")
                .forEach(value -> modalities.add(value.textValue()));
        return List.copyOf(modalities);
    }

    private static void validateSemantics(ObjectNode capability, Instant now) {
        requireText(capability, "execution_scope", "SIGNED_SYNTHETIC_ONLY");
        requireText(capability, "writer_mode", "SHADOW");
        requireBoolean(capability, "real_case_allowed", false);
        requireBoolean(capability, "formal_sink_eligible", false);
        requireText(capability, "privacy_basis", "SIGNED_SYNTHETIC_FIXTURE");
        requireText(capability, "signature_algorithm", "ES256");
        if (!EvidenceBatchManifest.requiredText(capability, "object_ref")
                .startsWith("urn:synthetic-evidence:")) {
            throw EvidenceBatchManifest.rejected("CAPABILITY_REAL_OBJECT_FORBIDDEN");
        }
        EvidenceBatchManifest.requiredHash(capability, "manifest_hash");
        EvidenceBatchManifest.requiredHash(capability, "item_hash");
        EvidenceBatchManifest.requiredHash(capability, "object_sha256");
        EvidenceBatchManifest.requiredHash(capability, "profile_versions_hash");
        EvidenceBatchManifest.requiredLong(capability, "room_epoch", 0);
        EvidenceBatchManifest.requiredLong(capability, "fencing_token", 1);
        EvidenceBatchManifest.requiredLong(capability, "byte_size", 1);
        Instant issuedAt = EvidenceBatchManifest.requiredInstant(capability, "issued_at");
        Instant expiresAt = EvidenceBatchManifest.requiredInstant(capability, "expires_at");
        if (!expiresAt.isAfter(issuedAt)) {
            throw EvidenceBatchManifest.rejected("CAPABILITY_TIME_WINDOW_INVALID");
        }
        if (now.isBefore(issuedAt) || !now.isBefore(expiresAt)) {
            throw EvidenceBatchManifest.rejected("CAPABILITY_NOT_CURRENTLY_VALID");
        }
        validateModalities(capability);
        validateParseBinding(capability);
    }

    private static void requireManifestBinding(
            ObjectNode capability, EvidenceBatchManifest manifest) {
        ObjectNode manifestDocument = manifest.document();
        ObjectNode item = manifest.requireItem(
                EvidenceBatchManifest.requiredText(capability, "evidence_id"));
        requireEqual(capability, manifestDocument, "registration_id");
        requireEqual(capability, manifestDocument, "manifest_id");
        requireEqual(capability, manifestDocument, "manifest_hash");
        requireEqual(capability, manifestDocument, "tenant_surrogate");
        requireEqual(capability, manifestDocument, "case_id");
        requireEqual(capability, manifestDocument, "room_epoch");
        requireEqual(capability, manifestDocument, "fencing_token");
        requireEqual(capability, manifestDocument, "thread_id");
        requireEqual(capability, manifestDocument, "actor_scope_hash");
        requireEqual(capability, manifestDocument, "agent_session_id");
        requireEqual(capability, manifestDocument, "synthetic_fixture_id");
        for (String field : List.of(
                "evidence_id", "item_hash", "owner_participant_id", "owner_role",
                "visibility", "object_ref", "immutable_object_version", "object_sha256",
                "content_type", "byte_size", "privacy_basis", "parse_ref", "parse_hash",
                "parse_status", "permitted_modalities")) {
            requireEqual(capability, item, field);
        }
        if (!manifest.profileVersionsHash()
                .equals(EvidenceBatchManifest.requiredText(capability, "profile_versions_hash"))) {
            throw EvidenceBatchManifest.rejected(
                    "CAPABILITY_MANIFEST_BINDING_MISMATCH:profile_versions_hash");
        }
    }

    private static void validateModalities(ObjectNode capability) {
        JsonNode node = EvidenceBatchManifest.requiredArray(capability, "permitted_modalities");
        if (node.isEmpty() || node.size() > 4) {
            throw EvidenceBatchManifest.rejected("CAPABILITY_MODALITIES_INVALID");
        }
        Set<String> allowed = Set.of("TEXT", "IMAGE_PIXELS", "PDF_METADATA", "OCR");
        Set<String> seen = new HashSet<>();
        node.forEach(value -> {
            if (!value.isTextual()
                    || !allowed.contains(value.textValue())
                    || !seen.add(value.textValue())) {
                throw EvidenceBatchManifest.rejected("CAPABILITY_MODALITIES_INVALID");
            }
        });
    }

    private static void validateParseBinding(ObjectNode capability) {
        String status = EvidenceBatchManifest.requiredText(capability, "parse_status");
        JsonNode ref = capability.get("parse_ref");
        JsonNode hash = capability.get("parse_hash");
        if ("AVAILABLE".equals(status)) {
            if (ref == null
                    || !ref.isTextual()
                    || !ref.textValue().startsWith("urn:synthetic-evidence-parse:")
                    || hash == null
                    || !hash.isTextual()
                    || hash.textValue().length() != 64) {
                throw EvidenceBatchManifest.rejected("CAPABILITY_PARSE_BINDING_INVALID");
            }
        } else if (!("NOT_REQUESTED".equals(status) || "FAILED".equals(status))
                || ref == null
                || !ref.isNull()
                || hash == null
                || !hash.isNull()) {
            throw EvidenceBatchManifest.rejected("CAPABILITY_PARSE_BINDING_INVALID");
        }
    }

    private static void requireExactFields(ObjectNode object) {
        Set<String> actual = new HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!FIELDS.equals(actual)) {
            Set<String> unknown = new TreeSet<>(actual);
            unknown.removeAll(FIELDS);
            Set<String> missing = new TreeSet<>(FIELDS);
            missing.removeAll(actual);
            throw EvidenceBatchManifest.rejected(
                    "CAPABILITY_FIELDS_INVALID:unknown=" + unknown + ",missing=" + missing);
        }
    }

    private static void requireEqual(JsonNode actual, JsonNode expected, String field) {
        if (!actual.required(field).equals(expected.required(field))) {
            throw EvidenceBatchManifest.rejected("CAPABILITY_MANIFEST_BINDING_MISMATCH:" + field);
        }
    }

    private static void requireText(JsonNode parent, String field, String expected) {
        if (!expected.equals(EvidenceBatchManifest.requiredText(parent, field))) {
            throw EvidenceBatchManifest.rejected(field.toUpperCase() + "_MISMATCH");
        }
    }

    private static void requireBoolean(JsonNode parent, String field, boolean expected) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean() || value.booleanValue() != expected) {
            throw EvidenceBatchManifest.rejected(field.toUpperCase() + "_MISMATCH");
        }
    }
}
