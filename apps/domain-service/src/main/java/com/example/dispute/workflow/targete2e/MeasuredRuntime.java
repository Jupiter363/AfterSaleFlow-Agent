package com.example.dispute.workflow.targete2e;

import java.util.Objects;

/** Opaque runtime snapshot obtainable only from the trusted measurement provider. */
public final class MeasuredRuntime {

  private final TargetE2eActivationExpectedRuntime runtime;
  private final TargetE2eRuntimeMeasurementProvider.MeasurementEvidence evidence;

  MeasuredRuntime(
      TargetE2eActivationExpectedRuntime runtime,
      TargetE2eRuntimeMeasurementProvider.MeasurementEvidence evidence) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.evidence = Objects.requireNonNull(evidence, "evidence");
  }

  TargetE2eActivationExpectedRuntime runtime() {
    return runtime;
  }

  TargetE2eRuntimeMeasurementProvider.MeasurementEvidence evidence() {
    return evidence;
  }
}
