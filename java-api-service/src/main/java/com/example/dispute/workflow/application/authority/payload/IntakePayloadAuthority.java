package com.example.dispute.workflow.application.authority.payload;

import java.time.OffsetDateTime;
import java.util.Objects;

/** One immutable payload pointer bound to exactly one private Intake command route. */
public record IntakePayloadAuthority(
        String payloadAuthorityId,
        String commandId,
        IntakeAuthorityRoute route,
        IntakePayloadSourceKind sourceKind,
        String existingEventBindingId,
        String artifactId,
        String schemaVersion,
        String objectUri,
        String objectVersion,
        String contentSha256,
        long sizeBytes,
        IntakePayloadPutReceipt putReceipt,
        OffsetDateTime createdAt) {

    public IntakePayloadAuthority {
        identifier(payloadAuthorityId, "payloadAuthorityId", 128);
        identifier(commandId, "commandId", 128);
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(sourceKind, "sourceKind must not be null");
        identifier(artifactId, "artifactId", 128);
        if (!sourceKind.schemaVersion().equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion does not match source kind");
        }
        if (objectUri == null || objectUri.length() > 1024 || !objectUri.matches("(s3|minio|urn):.*")) {
            throw new IllegalArgumentException("objectUri must use an immutable allowlisted scheme");
        }
        identifier(objectVersion, "objectVersion", 128);
        if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentSha256 must be a lowercase SHA-256");
        }
        if (sizeBytes < 1 || sizeBytes > sourceKind.maximumSizeBytes()) {
            throw new IllegalArgumentException("payload size exceeds the source-kind bound");
        }
        if (sourceKind == IntakePayloadSourceKind.EXISTING_PRIVATE_EVENT) {
            identifier(existingEventBindingId, "existingEventBindingId", 128);
            if (putReceipt != null) {
                throw new IllegalArgumentException("existing private events cannot carry a put receipt");
            }
        } else {
            if (existingEventBindingId != null || putReceipt == null) {
                throw new IllegalArgumentException("server payloads require only a matching put receipt");
            }
            if (putReceipt.sourceKind() != sourceKind) {
                throw new IllegalArgumentException("put receipt source kind does not match authority payload");
            }
            putReceipt.requireMatches(
                    commandId,
                    route,
                    artifactId,
                    schemaVersion,
                    objectUri,
                    objectVersion,
                    contentSha256,
                    sizeBytes);
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static void identifier(String value, String field, int maximumLength) {
        if (value == null
                || value.length() > maximumLength
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (maximumLength - 1) + "}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
    }
}
