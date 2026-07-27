package com.example.dispute.workflow.targete2e.artifact;

/** Marker bean proving that this process was built from the isolated target-E2E artifact. */
public record TargetE2eArtifactMarker(String value) {

    public static final String EXPECTED_VALUE = "TARGET_E2E_JAVA_ARTIFACT_V1";

    public TargetE2eArtifactMarker {
        if (!EXPECTED_VALUE.equals(value)) {
            throw new IllegalArgumentException("unexpected target-E2E artifact marker");
        }
    }
}
