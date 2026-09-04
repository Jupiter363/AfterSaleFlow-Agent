package com.example.dispute.workflow.runtime;

import java.util.Objects;

/** Opaque runtime snapshot obtainable only from the trusted measurement provider. */
public final class MeasuredRuntime {

  private final ProductionActivationExpectedRuntime runtime;
  private final ProductionRuntimeMeasurementProvider.MeasurementEvidence evidence;

  MeasuredRuntime(
      ProductionActivationExpectedRuntime runtime,
      ProductionRuntimeMeasurementProvider.MeasurementEvidence evidence) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.evidence = Objects.requireNonNull(evidence, "evidence");
  }

  ProductionActivationExpectedRuntime runtime() {
    return runtime;
  }

  ProductionRuntimeMeasurementProvider.MeasurementEvidence evidence() {
    return evidence;
  }
}
