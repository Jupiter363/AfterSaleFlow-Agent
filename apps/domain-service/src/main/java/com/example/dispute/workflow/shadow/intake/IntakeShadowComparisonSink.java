package com.example.dispute.workflow.shadow.intake;

/** Isolated telemetry boundary; implementations must not route to a formal domain sink. */
@FunctionalInterface
public interface IntakeShadowComparisonSink {

    void record(IntakeShadowComparison comparison);
}
