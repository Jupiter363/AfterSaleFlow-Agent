package com.example.dispute.workflow.targete2e.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class TargetE2eEmbeddedMarker {

    static final String RESOURCE = "META-INF/after-sale-flow/target-e2e-artifact.marker";

    private TargetE2eEmbeddedMarker() {}

    static String read(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        try (InputStream input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("TARGET_E2E_ARTIFACT_MARKER_MISSING");
            }
            byte[] bytes = input.readNBytes(257);
            if (bytes.length > 256 || input.read() != -1) {
                throw new IllegalStateException("TARGET_E2E_ARTIFACT_MARKER_TOO_LARGE");
            }
            return new String(bytes, StandardCharsets.US_ASCII).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("TARGET_E2E_ARTIFACT_MARKER_UNREADABLE", exception);
        }
    }
}
