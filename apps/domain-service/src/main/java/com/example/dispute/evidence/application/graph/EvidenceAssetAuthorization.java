package com.example.dispute.evidence.application.graph;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Fail-closed authorization and actual-load verification for signed synthetic Evidence assets. */
public final class EvidenceAssetAuthorization {

    private final EvidenceBatchManifest.PublicKeyResolver keyResolver;
    private final NonceRegistry nonceRegistry;
    private final AssetLoader assetLoader;
    private final Clock clock;

    public EvidenceAssetAuthorization(
            EvidenceBatchManifest.PublicKeyResolver keyResolver,
            NonceRegistry nonceRegistry,
            AssetLoader assetLoader,
            Clock clock) {
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.nonceRegistry = Objects.requireNonNull(nonceRegistry, "nonceRegistry");
        this.assetLoader = Objects.requireNonNull(assetLoader, "assetLoader");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Verifies the capability and all duplicated manifest/item claims, atomically consumes its
     * nonce, then binds the bytes actually returned by the immutable synthetic object loader.
     */
    public AuthorizedAsset authorizeAndLoad(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request");
        EvidenceBatchManifest.requireGraphLeaseFence(
                request.currentJavaRoomFence(),
                request.expectedGraphLeaseFence(),
                request.currentGraphLeaseFence());
        Instant now = clock.instant();
        EvidenceAssetCapability capability = EvidenceAssetCapability.verifySignedPayload(
                request.capabilityPayload(),
                request.manifest(),
                request.currentJavaRoomFence(),
                now,
                keyResolver);
        requireExpectedScope(capability, request);
        if (!nonceRegistry.consume(
                capability.text("capability_id"),
                capability.text("nonce"),
                capability.instant("expires_at"))) {
            throw EvidenceBatchManifest.rejected("CAPABILITY_NONCE_REPLAYED");
        }

        AssetLoadRequest loadRequest = new AssetLoadRequest(
                capability.text("capability_id"),
                capability.text("manifest_id"),
                capability.text("manifest_hash"),
                capability.text("evidence_id"),
                capability.text("item_hash"),
                capability.text("object_ref"),
                capability.text("immutable_object_version"),
                capability.text("object_sha256"),
                capability.text("content_type"),
                capability.number("byte_size"),
                capability.permittedModalities());
        LoadedAsset loaded = Objects.requireNonNull(
                assetLoader.load(loadRequest), "asset loader returned null");
        ActualLoadReceipt receipt = verifyActualLoad(
                capability, loaded, request.currentJavaRoomFence(),
                request.currentGraphLeaseFence(), now);
        return new AuthorizedAsset(capability, receipt, loaded.bytes());
    }

    private static void requireExpectedScope(
            EvidenceAssetCapability capability, AuthorizationRequest request) {
        if (!capability.text("owner_participant_id")
                .equals(request.expectedOwnerParticipantId())) {
            throw EvidenceBatchManifest.rejected("CAPABILITY_OWNER_NOT_AUTHORIZED");
        }
        if (!capability.text("visibility").equals(request.expectedVisibility())) {
            throw EvidenceBatchManifest.rejected("CAPABILITY_VISIBILITY_NOT_AUTHORIZED");
        }
        if (!capability.text("tenant_surrogate").equals(request.expectedTenantSurrogate())
                || !capability.text("case_id").equals(request.expectedCaseId())) {
            throw EvidenceBatchManifest.rejected("CAPABILITY_CASE_SCOPE_NOT_AUTHORIZED");
        }
    }

    private static ActualLoadReceipt verifyActualLoad(
            EvidenceAssetCapability capability,
            LoadedAsset loaded,
            long javaRoomFence,
            long graphLeaseFence,
            Instant loadedAt) {
        requireLoadedField(capability, "object_ref", loaded.objectRef());
        requireLoadedField(
                capability, "immutable_object_version", loaded.immutableObjectVersion());
        requireLoadedField(capability, "content_type", loaded.contentType());
        byte[] bytes = loaded.bytes();
        if (bytes.length != capability.number("byte_size")) {
            throw EvidenceBatchManifest.rejected("ASSET_ACTUAL_SIZE_MISMATCH");
        }
        String actualHash = EvidenceBatchManifest.sha256(bytes);
        if (!actualHash.equals(capability.text("object_sha256"))) {
            throw EvidenceBatchManifest.rejected("ASSET_ACTUAL_SHA256_MISMATCH");
        }
        List<String> loadedModalities = validateLoadedModalities(
                loaded.loadedModalities(), capability.permittedModalities());
        return ActualLoadReceipt.create(
                capability,
                javaRoomFence,
                graphLeaseFence,
                actualHash,
                bytes.length,
                loadedModalities,
                loadedAt);
    }

    private static List<String> validateLoadedModalities(
            List<String> loaded, List<String> permitted) {
        if (loaded == null || loaded.isEmpty()) {
            throw EvidenceBatchManifest.rejected("ASSET_ACTUAL_MODALITIES_EMPTY");
        }
        Set<String> unique = new HashSet<>(loaded);
        if (unique.size() != loaded.size() || !new HashSet<>(permitted).containsAll(unique)) {
            throw EvidenceBatchManifest.rejected("ASSET_ACTUAL_MODALITIES_NOT_PERMITTED");
        }
        ArrayList<String> stable = new ArrayList<>(loaded);
        stable.sort(String::compareTo);
        return List.copyOf(stable);
    }

    private static void requireLoadedField(
            EvidenceAssetCapability capability, String field, String actual) {
        if (!capability.text(field).equals(actual)) {
            throw EvidenceBatchManifest.rejected("ASSET_ACTUAL_" + field.toUpperCase() + "_MISMATCH");
        }
    }

    @FunctionalInterface
    public interface NonceRegistry {
        /** Atomically returns true only for the first capability-id/nonce pair. */
        boolean consume(String capabilityId, String nonce, Instant expiresAt);
    }

    @FunctionalInterface
    public interface AssetLoader {
        LoadedAsset load(AssetLoadRequest request);
    }

    public record AuthorizationRequest(
            EvidenceBatchManifest manifest,
            byte[] capabilityPayload,
            String expectedTenantSurrogate,
            String expectedCaseId,
            String expectedOwnerParticipantId,
            String expectedVisibility,
            long currentJavaRoomFence,
            long expectedGraphLeaseFence,
            long currentGraphLeaseFence) {
        public AuthorizationRequest {
            Objects.requireNonNull(manifest, "manifest");
            capabilityPayload = capabilityPayload == null ? null : capabilityPayload.clone();
            if (capabilityPayload == null || capabilityPayload.length == 0) {
                throw new IllegalArgumentException("capabilityPayload must not be empty");
            }
            requireBounded(expectedTenantSurrogate, "expectedTenantSurrogate");
            requireBounded(expectedCaseId, "expectedCaseId");
            requireBounded(expectedOwnerParticipantId, "expectedOwnerParticipantId");
            if (!Set.of("PRIVATE", "PARTIES", "PLATFORM_REVIEWER")
                    .contains(expectedVisibility)) {
                throw new IllegalArgumentException("expectedVisibility is invalid");
            }
            if (currentJavaRoomFence < 1
                    || expectedGraphLeaseFence < 1
                    || currentGraphLeaseFence < 1) {
                throw new IllegalArgumentException("fences must be positive");
            }
        }

        @Override
        public byte[] capabilityPayload() {
            return capabilityPayload.clone();
        }
    }

    public record AssetLoadRequest(
            String capabilityId,
            String manifestId,
            String manifestHash,
            String evidenceId,
            String itemHash,
            String objectRef,
            String immutableObjectVersion,
            String objectSha256,
            String contentType,
            long byteSize,
            List<String> permittedModalities) {
        public AssetLoadRequest {
            requireBounded(capabilityId, "capabilityId");
            requireBounded(manifestId, "manifestId");
            requireBounded(evidenceId, "evidenceId");
            Objects.requireNonNull(manifestHash, "manifestHash");
            Objects.requireNonNull(itemHash, "itemHash");
            Objects.requireNonNull(objectRef, "objectRef");
            requireBounded(immutableObjectVersion, "immutableObjectVersion");
            Objects.requireNonNull(objectSha256, "objectSha256");
            Objects.requireNonNull(contentType, "contentType");
            if (byteSize < 1 || byteSize > 10_485_760) {
                throw new IllegalArgumentException("byteSize is outside the capability bound");
            }
            permittedModalities = List.copyOf(permittedModalities);
        }
    }

    public record LoadedAsset(
            String objectRef,
            String immutableObjectVersion,
            String contentType,
            byte[] bytes,
            List<String> loadedModalities) {
        public LoadedAsset {
            Objects.requireNonNull(objectRef, "objectRef");
            Objects.requireNonNull(immutableObjectVersion, "immutableObjectVersion");
            Objects.requireNonNull(contentType, "contentType");
            bytes = bytes == null ? null : bytes.clone();
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("loaded bytes must not be empty");
            }
            loadedModalities = List.copyOf(loadedModalities);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record ActualLoadReceipt(
            String receiptId,
            String receiptHash,
            String capabilityId,
            String capabilityHash,
            String capabilityNonce,
            String manifestId,
            String manifestHash,
            String evidenceId,
            String itemHash,
            String objectRef,
            String immutableObjectVersion,
            String objectSha256,
            String contentType,
            long byteSize,
            long javaRoomFencingToken,
            long graphLeaseFencingToken,
            String loadStatus,
            List<String> loadedModalities,
            Instant loadedAt) {
        public ActualLoadReceipt {
            requireBounded(receiptId, "receiptId");
            Objects.requireNonNull(receiptHash, "receiptHash");
            Objects.requireNonNull(capabilityId, "capabilityId");
            Objects.requireNonNull(capabilityHash, "capabilityHash");
            Objects.requireNonNull(capabilityNonce, "capabilityNonce");
            Objects.requireNonNull(manifestId, "manifestId");
            Objects.requireNonNull(manifestHash, "manifestHash");
            Objects.requireNonNull(evidenceId, "evidenceId");
            Objects.requireNonNull(itemHash, "itemHash");
            Objects.requireNonNull(objectRef, "objectRef");
            Objects.requireNonNull(immutableObjectVersion, "immutableObjectVersion");
            Objects.requireNonNull(objectSha256, "objectSha256");
            Objects.requireNonNull(contentType, "contentType");
            if (javaRoomFencingToken < 1
                    || graphLeaseFencingToken < 1
                    || javaRoomFencingToken == graphLeaseFencingToken) {
                throw new IllegalArgumentException("room and Graph lease fences must be distinct");
            }
            if (!"LOADED".equals(loadStatus)) {
                throw new IllegalArgumentException("actual load receipt must be LOADED");
            }
            loadedModalities = List.copyOf(loadedModalities);
            Objects.requireNonNull(loadedAt, "loadedAt");
            if (!receiptHash.equals(canonicalHash(
                    receiptId,
                    capabilityId,
                    capabilityHash,
                    capabilityNonce,
                    manifestId,
                    manifestHash,
                    evidenceId,
                    itemHash,
                    objectRef,
                    immutableObjectVersion,
                    objectSha256,
                    contentType,
                    byteSize,
                    javaRoomFencingToken,
                    graphLeaseFencingToken,
                    loadStatus,
                    loadedModalities,
                    loadedAt))) {
                throw new IllegalArgumentException("actual load receipt hash is not canonical");
            }
        }

        private static ActualLoadReceipt create(
                EvidenceAssetCapability capability,
                long javaRoomFence,
                long graphLeaseFence,
                String objectSha256,
                long byteSize,
                List<String> loadedModalities,
                Instant loadedAt) {
            String receiptId = "LOAD_" + capability.text("capability_hash");
            String hash = canonicalHash(
                    receiptId,
                    capability.text("capability_id"),
                    capability.text("capability_hash"),
                    capability.text("nonce"),
                    capability.text("manifest_id"),
                    capability.text("manifest_hash"),
                    capability.text("evidence_id"),
                    capability.text("item_hash"),
                    capability.text("object_ref"),
                    capability.text("immutable_object_version"),
                    objectSha256,
                    capability.text("content_type"),
                    byteSize,
                    javaRoomFence,
                    graphLeaseFence,
                    "LOADED",
                    loadedModalities,
                    loadedAt);
            return new ActualLoadReceipt(
                    receiptId,
                    hash,
                    capability.text("capability_id"),
                    capability.text("capability_hash"),
                    capability.text("nonce"),
                    capability.text("manifest_id"),
                    capability.text("manifest_hash"),
                    capability.text("evidence_id"),
                    capability.text("item_hash"),
                    capability.text("object_ref"),
                    capability.text("immutable_object_version"),
                    objectSha256,
                    capability.text("content_type"),
                    byteSize,
                    javaRoomFence,
                    graphLeaseFence,
                    "LOADED",
                    loadedModalities,
                    loadedAt);
        }

        private static String canonicalHash(
                String receiptId,
                String capabilityId,
                String capabilityHash,
                String capabilityNonce,
                String manifestId,
                String manifestHash,
                String evidenceId,
                String itemHash,
                String objectRef,
                String immutableObjectVersion,
                String objectSha256,
                String contentType,
                long byteSize,
                long javaRoomFence,
                long graphLeaseFence,
                String loadStatus,
                List<String> modalities,
                Instant loadedAt) {
            ObjectNode value = JsonNodeFactory.instance.objectNode();
            value.put("receipt_id", receiptId);
            value.put("capability_id", capabilityId);
            value.put("capability_hash", capabilityHash);
            value.put("capability_nonce", capabilityNonce);
            value.put("manifest_id", manifestId);
            value.put("manifest_hash", manifestHash);
            value.put("evidence_id", evidenceId);
            value.put("item_hash", itemHash);
            value.put("object_ref", objectRef);
            value.put("immutable_object_version", immutableObjectVersion);
            value.put("object_sha256", objectSha256);
            value.put("content_type", contentType);
            value.put("byte_size", byteSize);
            value.put("java_room_fencing_token", javaRoomFence);
            value.put("graph_lease_fencing_token", graphLeaseFence);
            value.put("load_status", loadStatus);
            value.putPOJO("loaded_modalities", modalities);
            value.put("loaded_at", loadedAt.toString());
            return ContractJson.sha256Hex(value);
        }
    }

    public record AuthorizedAsset(
            EvidenceAssetCapability capability, ActualLoadReceipt receipt, byte[] bytes) {
        public AuthorizedAsset {
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(receipt, "receipt");
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private static void requireBounded(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
    }
}
