package com.example.dispute.workflow.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Secret-volume location for the current and retained Phase 3 Graph signing keys. */
@ConfigurationProperties(prefix = "app.agent-run-v2.graph-client.signing")
public record GraphSigningKeyProperties(Path keyDirectory, String activeKeyId) {

    public GraphSigningKeyProperties {
        boolean directoryConfigured = keyDirectory != null;
        boolean keyIdConfigured = activeKeyId != null && !activeKeyId.isBlank();
        if (directoryConfigured != keyIdConfigured) {
            throw new IllegalArgumentException(
                    "Graph signing key directory and active key ID must be configured together");
        }
        if (directoryConfigured && !keyDirectory.isAbsolute()) {
            throw new IllegalArgumentException(
                    "SHADOW Graph signing key directory must be absolute");
        }
        if (keyIdConfigured
                && !activeKeyId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(
                    "SHADOW Graph active signing key ID is invalid");
        }
    }

    public void requireConfigured() {
        if (keyDirectory == null) {
            throw new IllegalStateException(
                    "SHADOW Graph signing key directory is required");
        }
        if (activeKeyId == null || activeKeyId.isBlank()) {
            throw new IllegalStateException(
                    "SHADOW Graph active signing key ID is required");
        }
    }
}
