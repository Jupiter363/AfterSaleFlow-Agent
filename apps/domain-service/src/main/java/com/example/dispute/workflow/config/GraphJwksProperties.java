package com.example.dispute.workflow.config;

import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Public-only secret-volume projection used by the Java API JWKS endpoint. */
@ConfigurationProperties(prefix = "app.graph-jwks")
public record GraphJwksProperties(boolean enabled, Path keyDirectory) {

    public GraphJwksProperties {
        if (enabled) {
            keyDirectory = Objects.requireNonNull(
                    keyDirectory,
                    "enabled Graph JWKS requires a public key directory");
            if (!keyDirectory.isAbsolute()) {
                throw new IllegalArgumentException(
                        "Graph JWKS public key directory must be absolute");
            }
        }
    }
}
