package com.example.dispute.workflow.observability.hearing;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Micrometer adapter with a fixed enum product and no identifier-bearing labels. */
@Component
public final class HearingReliabilityMetrics implements HearingReliabilityObservationSink {

    public static final String METER_NAME = "dispute.hearing.reliability.events";
    private final MeterRegistry meterRegistry;

    public HearingReliabilityMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    @Override
    public void record(Event event, Outcome outcome) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        meterRegistry
                .counter(
                        METER_NAME,
                        "room",
                        "hearing",
                        "event",
                        label(event),
                        "outcome",
                        label(outcome))
                .increment();
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
