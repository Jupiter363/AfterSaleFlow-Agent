package com.example.dispute.workflow.config;

import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Dedicated public-key mount for verification-only synthetic Intake admission. */
@ConfigurationProperties(prefix = "app.orchestration.intake-synthetic-admission-trust")
public record IntakeSyntheticAdmissionTrustProperties(boolean enabled, Path publicKeyDirectory) {

    public IntakeSyntheticAdmissionTrustProperties {
        if (enabled) {
            publicKeyDirectory = Objects.requireNonNull(
                    publicKeyDirectory,
                    "enabled Intake admission trust requires a public key directory");
            if (!publicKeyDirectory.isAbsolute()) {
                throw new IllegalArgumentException(
                        "Intake admission public key directory must be absolute");
            }
        }
    }

    public Path requireConfigured() {
        if (!enabled || publicKeyDirectory == null) {
            throw new IllegalStateException("Intake synthetic admission trust is disabled");
        }
        return publicKeyDirectory;
    }
}
