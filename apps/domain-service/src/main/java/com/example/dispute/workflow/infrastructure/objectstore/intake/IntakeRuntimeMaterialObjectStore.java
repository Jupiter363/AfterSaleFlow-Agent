package com.example.dispute.workflow.infrastructure.objectstore.intake;

import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeUris;
import com.example.dispute.workflow.shadow.intake.IntakeRuntimeMaterialManifest;
import java.util.Arrays;

/** Exact-version private object-store read port for signed-synthetic runtime material. */
@FunctionalInterface
public interface IntakeRuntimeMaterialObjectStore {

    StoredObject readExact(ReadRequest request);

    record ReadRequest(
            String artifactId,
            String schemaVersion,
            String uri,
            String objectVersion,
            String contentHash,
            long sizeBytes,
            int maximumBytes) {

        public ReadRequest {
            IntakeRuntimeMaterialManifest.identifier(artifactId, "artifactId");
            IntakeRuntimeMaterialManifest.identifier(schemaVersion, "schemaVersion");
            IntakeExchangeUris.requireCanonical(uri);
            IntakeRuntimeMaterialManifest.identifier(objectVersion, "objectVersion");
            IntakeRuntimeMaterialManifest.sha256(contentHash, "contentHash");
            if (maximumBytes <= 0 || sizeBytes <= 0 || sizeBytes > maximumBytes) {
                throw new IllegalArgumentException("runtime material read exceeds its byte bound");
            }
        }
    }

    record StoredObject(
            String artifactId,
            String schemaVersion,
            String uri,
            String objectVersion,
            String contentHash,
            long sizeBytes,
            byte[] content) {

        public StoredObject {
            content = content == null ? null : Arrays.copyOf(content, content.length);
        }

        @Override
        public byte[] content() {
            return content == null ? null : Arrays.copyOf(content, content.length);
        }
    }
}
