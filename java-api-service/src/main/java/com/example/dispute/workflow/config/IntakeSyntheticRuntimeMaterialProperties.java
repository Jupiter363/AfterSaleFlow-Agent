package com.example.dispute.workflow.config;

import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Private object-store namespace and trusted index for synthetic Intake runtime material. */
@ConfigurationProperties(prefix = "app.orchestration.intake-synthetic-runtime-material")
public record IntakeSyntheticRuntimeMaterialProperties(
        boolean enabled,
        @DefaultValue("intake-synthetic-private") String bucket,
        @DefaultValue("signed-synthetic/intake/runtime-material") String prefix,
        Path manifestReferenceIndexPath) {

    public IntakeSyntheticRuntimeMaterialProperties {
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException(
                    "Intake synthetic runtime material bucket is invalid");
        }
        if (prefix == null
                || prefix.length() > 256
                || !prefix.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")) {
            throw new IllegalArgumentException(
                    "Intake synthetic runtime material prefix is invalid");
        }
        if (enabled) {
            manifestReferenceIndexPath = Objects.requireNonNull(
                    manifestReferenceIndexPath,
                    "enabled Intake synthetic runtime material requires a manifest index");
            if (!manifestReferenceIndexPath.isAbsolute()) {
                throw new IllegalArgumentException(
                        "Intake synthetic runtime material manifest index must be absolute");
            }
        }
    }

    public Path requireManifestReferenceIndexPath() {
        if (!enabled || manifestReferenceIndexPath == null) {
            throw new IllegalStateException("Intake synthetic runtime material is disabled");
        }
        return manifestReferenceIndexPath;
    }
}
