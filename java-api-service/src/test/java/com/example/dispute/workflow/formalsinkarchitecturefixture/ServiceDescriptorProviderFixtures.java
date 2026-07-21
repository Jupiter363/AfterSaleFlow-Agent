package com.example.dispute.workflow.formalsinkarchitecturefixture;

final class OpaqueProvider {

    private final CrossFileFormalWrapper wrapper;

    OpaqueProvider(CrossFileFormalWrapper wrapper) {
        this.wrapper = wrapper;
    }

    Object value() {
        return wrapper;
    }

    static final class NestedOpaqueProvider {

        private final CrossFileFormalWrapper wrapper;

        NestedOpaqueProvider(CrossFileFormalWrapper wrapper) {
            this.wrapper = wrapper;
        }

        Object value() {
            return wrapper;
        }
    }
}

final class SafeIntakeRoomActivitiesMetricsProvider {

    private final MetricsSink metrics;

    SafeIntakeRoomActivitiesMetricsProvider(MetricsSink metrics) {
        this.metrics = metrics;
    }

    Object value() {
        return metrics;
    }

    interface MetricsSink {}
}

final class ManifestOnlyFormalProvider {

    private final CrossFileFormalWrapper wrapper;

    ManifestOnlyFormalProvider(CrossFileFormalWrapper wrapper) {
        this.wrapper = wrapper;
    }

    Object value() {
        return wrapper;
    }
}
