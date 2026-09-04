package com.example.dispute.workflow.runtime.artifact;

/** Marker bean proving that this process was built from the isolated production-runtime artifact. */
public record ProductionArtifactMarker(String value) {

    public static final String EXPECTED_VALUE = "PRODUCTION_RUNTIME_JAVA_ARTIFACT_V1";

    public ProductionArtifactMarker {
        if (!EXPECTED_VALUE.equals(value)) {
            throw new IllegalArgumentException("unexpected production-runtime artifact marker");
        }
    }
}
