package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.Dimension;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.DimensionComparison;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.HardZeroFinding;
import com.example.dispute.workflow.shadow.intake.IntakeShadowComparison.ObservedValue;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Produces bounded synthetic parity telemetry without carrying party or model text. */
public final class IntakeShadowParityService {

    private static final Set<Dimension> REQUIRED_DIMENSIONS =
            Set.copyOf(EnumSet.allOf(Dimension.class));

    private final IntakeShadowComparisonSink sink;

    public IntakeShadowParityService(IntakeShadowComparisonSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
    }

    public IntakeShadowComparison compare(
            String comparisonKeyHash,
            ParitySnapshot legacy,
            ParitySnapshot shadow) {
        Objects.requireNonNull(legacy, "legacy must not be null");
        Objects.requireNonNull(shadow, "shadow must not be null");

        EnumMap<Dimension, DimensionComparison> dimensions = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) {
            dimensions.put(
                    dimension,
                    new DimensionComparison(
                            legacy.values().get(dimension), shadow.values().get(dimension)));
        }
        EnumSet<HardZeroFinding> hardZeroFindings =
                EnumSet.noneOf(HardZeroFinding.class);
        hardZeroFindings.addAll(legacy.hardZeroFindings());
        hardZeroFindings.addAll(shadow.hardZeroFindings());

        IntakeShadowComparison comparison = new IntakeShadowComparison(
                IntakeShadowComparison.V1,
                comparisonKeyHash,
                dimensions,
                hardZeroFindings);
        sink.record(comparison);
        return comparison;
    }

    public record ParitySnapshot(
            Map<Dimension, ObservedValue> values,
            Set<HardZeroFinding> hardZeroFindings) {

        public ParitySnapshot {
            Objects.requireNonNull(values, "values must not be null");
            if (!values.keySet().equals(REQUIRED_DIMENSIONS)) {
                throw new IllegalArgumentException(
                        "snapshot must contain every bounded parity dimension exactly once");
            }
            EnumMap<Dimension, ObservedValue> valueCopy = new EnumMap<>(Dimension.class);
            values.forEach((dimension, value) -> valueCopy.put(
                    Objects.requireNonNull(dimension, "dimension must not be null"),
                    Objects.requireNonNull(value, "observed value must not be null")));
            values = Map.copyOf(valueCopy);
            hardZeroFindings = Set.copyOf(
                    Objects.requireNonNull(hardZeroFindings, "hardZeroFindings must not be null"));
        }
    }
}
