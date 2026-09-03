package com.example.dispute.workflow.observability.hearing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink.Event;
import com.example.dispute.workflow.observability.hearing.HearingReliabilityObservationSink.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class HearingReliabilityMetricsTest {

    @Test
    void emitsOnlyTheClosedRoomEventAndOutcomeLabelProduct() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HearingReliabilityMetrics metrics = new HearingReliabilityMetrics(registry);

        for (Event event : Event.values()) {
            for (Outcome outcome : Outcome.values()) {
                metrics.record(event, outcome);
            }
        }

        assertThat(registry.find(HearingReliabilityMetrics.METER_NAME).counters())
                .hasSize(Event.values().length * Outcome.values().length)
                .allSatisfy(counter -> {
                    assertThat(counter.getId().getTags()).hasSize(3);
                    assertThat(counter.getId().getTag("room")).isEqualTo("hearing");
                    assertThat(counter.getId().getTag("event")).isNotBlank();
                    assertThat(counter.getId().getTag("outcome")).isNotBlank();
                    assertThat(counter.count()).isEqualTo(1.0);
                });
    }
}
