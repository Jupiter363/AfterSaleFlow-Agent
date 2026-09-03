package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.application.intake.exchange.IntakeExchangeUris;
import com.example.dispute.workflow.shadow.intake.IntakeSyntheticRuntimeSource.ActivityAuthority;

/** Resolves a pre-trusted manifest reference for one exact signed-synthetic activity tuple. */
@FunctionalInterface
public interface IntakeRuntimeMaterialManifestReferenceSource {

    ManifestObjectReference resolve(ActivityAuthority authority);

    record ManifestObjectReference(
            String schemaVersion,
            String artifactId,
            String uri,
            String objectVersion,
            String contentHash,
            long sizeBytes) {

        public static final String SCHEMA_VERSION =
                "intake-synthetic-runtime-material-manifest-ref.v1";

        public ManifestObjectReference {
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException("runtime material manifest reference is invalid");
            }
            IntakeRuntimeMaterialManifest.identifier(artifactId, "artifactId");
            IntakeExchangeUris.requireCanonical(uri);
            IntakeRuntimeMaterialManifest.identifier(objectVersion, "objectVersion");
            IntakeRuntimeMaterialManifest.sha256(contentHash, "contentHash");
            if (sizeBytes <= 0 || sizeBytes > IntakeRuntimeMaterialManifest.MAX_ENCODED_BYTES) {
                throw new IllegalArgumentException(
                        "runtime material manifest reference exceeds its byte bound");
            }
        }
    }
}
