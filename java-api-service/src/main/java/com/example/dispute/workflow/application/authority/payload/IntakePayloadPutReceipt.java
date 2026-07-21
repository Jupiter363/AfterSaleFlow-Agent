package com.example.dispute.workflow.application.authority.payload;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Objects;

/** Immutable result of a server-owned put before a human-input or branch payload enters Domain DB. */
public record IntakePayloadPutReceipt(
        String schemaVersion,
        String receiptId,
        String putIdempotencyKey,
        String commandId,
        String tenantSurrogate,
        String caseId,
        String registrationId,
        String actorId,
        String accessSessionId,
        IntakePayloadSourceKind sourceKind,
        String artifactId,
        String payloadSchemaVersion,
        String objectUri,
        String objectVersion,
        String contentSha256,
        long sizeBytes,
        long storedAtEpochMicros,
        String receiptHash) {

    public static final String SCHEMA_VERSION = "intake-command-payload-put-receipt.v1";

    public IntakePayloadPutReceipt {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
        }
        identifier(receiptId, "receiptId", 128);
        if (putIdempotencyKey == null || !putIdempotencyKey.matches("iput[.]v1[.][0-9a-f]{64}")) {
            throw new IllegalArgumentException("putIdempotencyKey must be an intake put key");
        }
        identifier(commandId, "commandId", 128);
        identifier(tenantSurrogate, "tenantSurrogate", 128);
        identifier(caseId, "caseId", 64);
        identifier(registrationId, "registrationId", 128);
        identifier(actorId, "actorId", 128);
        identifier(accessSessionId, "accessSessionId", 64);
        Objects.requireNonNull(sourceKind, "sourceKind must not be null");
        if (!sourceKind.requiresPutReceipt()) {
            throw new IllegalArgumentException("existing private events cannot carry a put receipt");
        }
        identifier(artifactId, "artifactId", 128);
        requirePayload(sourceKind, payloadSchemaVersion, objectUri, objectVersion, contentSha256, sizeBytes);
        if (storedAtEpochMicros < 0 || storedAtEpochMicros > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("storedAtEpochMicros must be JCS-safe");
        }
        requireHash(receiptHash, "receiptHash");
        if (!receiptHash.equals(canonicalHash(
                schemaVersion,
                receiptId,
                putIdempotencyKey,
                commandId,
                tenantSurrogate,
                caseId,
                registrationId,
                actorId,
                accessSessionId,
                sourceKind,
                artifactId,
                payloadSchemaVersion,
                objectUri,
                objectVersion,
                contentSha256,
                sizeBytes,
                storedAtEpochMicros))) {
            throw new IllegalArgumentException("receiptHash does not match canonical put receipt");
        }
    }

    public void requireMatches(
            String candidateCommandId,
            IntakeAuthorityRoute candidateRoute,
            String candidateArtifactId,
            String candidateSchemaVersion,
            String candidateObjectUri,
            String candidateObjectVersion,
            String candidateContentSha256,
            long candidateSizeBytes) {
        if (!commandId.equals(candidateCommandId)
                || !tenantSurrogate.equals(candidateRoute.tenantSurrogate())
                || !caseId.equals(candidateRoute.caseId())
                || !registrationId.equals(candidateRoute.registrationId())
                || !actorId.equals(candidateRoute.actorId())
                || !accessSessionId.equals(candidateRoute.accessSessionId())
                || !artifactId.equals(candidateArtifactId)
                || !payloadSchemaVersion.equals(candidateSchemaVersion)
                || !objectUri.equals(candidateObjectUri)
                || !objectVersion.equals(candidateObjectVersion)
                || !contentSha256.equals(candidateContentSha256)
                || sizeBytes != candidateSizeBytes) {
            throw new IllegalArgumentException("put receipt does not match the authority payload tuple");
        }
    }

    public String canonicalHash() {
        return canonicalHash(
                schemaVersion,
                receiptId,
                putIdempotencyKey,
                commandId,
                tenantSurrogate,
                caseId,
                registrationId,
                actorId,
                accessSessionId,
                sourceKind,
                artifactId,
                payloadSchemaVersion,
                objectUri,
                objectVersion,
                contentSha256,
                sizeBytes,
                storedAtEpochMicros);
    }

    private static String canonicalHash(
            String schemaVersion,
            String receiptId,
            String putIdempotencyKey,
            String commandId,
            String tenantSurrogate,
            String caseId,
            String registrationId,
            String actorId,
            String accessSessionId,
            IntakePayloadSourceKind sourceKind,
            String artifactId,
            String payloadSchemaVersion,
            String objectUri,
            String objectVersion,
            String contentSha256,
            long sizeBytes,
            long storedAtEpochMicros) {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("schema_version", schemaVersion);
        root.put("receipt_id", receiptId);
        root.put("put_idempotency_key", putIdempotencyKey);
        root.put("command_id", commandId);
        root.put("tenant_surrogate", tenantSurrogate);
        root.put("case_id", caseId);
        root.put("registration_id", registrationId);
        root.put("actor_id", actorId);
        root.put("access_session_id", accessSessionId);
        root.put("source_kind", sourceKind.name());
        root.put("artifact_id", artifactId);
        root.put("payload_schema_version", payloadSchemaVersion);
        root.put("object_uri", objectUri);
        root.put("object_version", objectVersion);
        root.put("content_sha256", contentSha256);
        root.put("size_bytes", sizeBytes);
        root.put("stored_at_epoch_micros", storedAtEpochMicros);
        return ContractJson.sha256Hex(root);
    }

    private static void requirePayload(
            IntakePayloadSourceKind sourceKind,
            String schemaVersion,
            String objectUri,
            String objectVersion,
            String contentSha256,
            long sizeBytes) {
        if (!sourceKind.schemaVersion().equals(schemaVersion)) {
            throw new IllegalArgumentException("payload schema does not match source kind");
        }
        if (objectUri == null || objectUri.length() > 1024 || !objectUri.matches("(s3|minio|urn):.*")) {
            throw new IllegalArgumentException("objectUri must use an immutable allowlisted scheme");
        }
        identifier(objectVersion, "objectVersion", 128);
        requireHash(contentSha256, "contentSha256");
        if (sizeBytes < 1 || sizeBytes > sourceKind.maximumSizeBytes()) {
            throw new IllegalArgumentException("payload size exceeds the source-kind bound");
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
    }

    private static void identifier(String value, String field, int maximumLength) {
        if (value == null
                || value.length() > maximumLength
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (maximumLength - 1) + "}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
    }
}
