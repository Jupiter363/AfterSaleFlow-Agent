package com.example.dispute.workflow.application.intake.exchange;

import java.util.Arrays;

/** Exact-version private object-store read gateway for Intake exchange payloads. */
@FunctionalInterface
public interface IntakeExchangePayloadObjectStoreGateway {

    StoredPayload readExact(ReadRequest request);

    record ReadRequest(
            String artifactId,
            String schemaVersion,
            String uri,
            String objectVersion,
            String sha256,
            long sizeBytes) {

        public ReadRequest {
            IntakeExchangeContract.identifier(artifactId, "artifactId");
            IntakeExchangeContract.payloadMaximum(schemaVersion);
            IntakeExchangeUris.requireCanonical(uri);
            IntakeExchangeContract.identifier(objectVersion, "objectVersion");
            IntakeExchangeContract.requireSha256(sha256, "sha256");
            if (sizeBytes <= 0
                    || sizeBytes > IntakeExchangeContract.payloadMaximum(schemaVersion)) {
                throw new IllegalArgumentException("payload size exceeds its schema bound");
            }
        }
    }

    record StoredPayload(
            String artifactId,
            String schemaVersion,
            String uri,
            String objectVersion,
            String sha256,
            long sizeBytes,
            byte[] canonicalPayload) {

        public StoredPayload {
            canonicalPayload = canonicalPayload == null
                    ? null
                    : Arrays.copyOf(canonicalPayload, canonicalPayload.length);
        }

        @Override
        public byte[] canonicalPayload() {
            return canonicalPayload == null
                    ? null
                    : Arrays.copyOf(canonicalPayload, canonicalPayload.length);
        }
    }
}
