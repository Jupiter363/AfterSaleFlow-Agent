package com.example.dispute.workflow.activity.intake;

import java.util.Arrays;
import java.util.Objects;

/**
 * Versioned private object-storage port. Implementations must be content-addressed and idempotent;
 * this port never grants the caller authority to mutate an existing object version.
 */
@FunctionalInterface
public interface IntakeImmutablePayloadPublisher {

    StoredPayload publish(PublishRequest request);

    record PublishRequest(
            String artifactId,
            String schemaVersion,
            String contentSha256,
            byte[] canonicalPayload,
            int maximumBytes) {

        public PublishRequest {
            if (artifactId == null
                    || !artifactId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("artifactId is invalid");
            }
            if (schemaVersion == null
                    || !schemaVersion.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("schemaVersion is invalid");
            }
            if (contentSha256 == null || !contentSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("contentSha256 is invalid");
            }
            Objects.requireNonNull(canonicalPayload, "canonicalPayload");
            if (maximumBytes <= 0) {
                throw new IllegalArgumentException("maximumBytes must be positive");
            }
            canonicalPayload = Arrays.copyOf(canonicalPayload, canonicalPayload.length);
            if (canonicalPayload.length == 0 || canonicalPayload.length > maximumBytes) {
                throw new IllegalArgumentException("canonical payload exceeds its contract bound");
            }
        }

        @Override
        public byte[] canonicalPayload() {
            return Arrays.copyOf(canonicalPayload, canonicalPayload.length);
        }
    }

    record StoredPayload(
            String artifactId,
            String schemaVersion,
            String uri,
            String objectVersion,
            String contentSha256,
            long sizeBytes) {}
}
