package com.example.dispute.workflow.shadow.intake;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Text-free, bounded result of comparing legacy and synthetic Intake observations. */
public record IntakeShadowComparison(
        String schemaVersion,
        String comparisonKeyHash,
        Map<Dimension, DimensionComparison> dimensions,
        Set<HardZeroFinding> hardZeroFindings) {

    public static final String V1 = "intake-shadow-comparison.v1";
    private static final Set<Dimension> REQUIRED_DIMENSIONS =
            Set.copyOf(EnumSet.allOf(Dimension.class));

    public IntakeShadowComparison {
        if (!V1.equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be " + V1);
        }
        requireSha256(comparisonKeyHash, "comparisonKeyHash");
        Objects.requireNonNull(dimensions, "dimensions must not be null");
        if (!dimensions.keySet().equals(REQUIRED_DIMENSIONS)) {
            throw new IllegalArgumentException(
                    "comparison must contain every bounded parity dimension exactly once");
        }
        EnumMap<Dimension, DimensionComparison> dimensionCopy =
                new EnumMap<>(Dimension.class);
        dimensions.forEach((dimension, comparison) -> dimensionCopy.put(
                Objects.requireNonNull(dimension, "dimension must not be null"),
                Objects.requireNonNull(comparison, "dimension comparison must not be null")));
        dimensions = Map.copyOf(dimensionCopy);
        hardZeroFindings = Set.copyOf(
                Objects.requireNonNull(hardZeroFindings, "hardZeroFindings must not be null"));
    }

    public Verdict verdict() {
        if (!hardZeroFindings.isEmpty()) {
            return Verdict.HARD_FAILURE;
        }
        return dimensions.values().stream().allMatch(DimensionComparison::matches)
                ? Verdict.MATCH
                : Verdict.DIFFERENT;
    }

    public Set<Dimension> differingDimensions() {
        EnumSet<Dimension> differences = EnumSet.noneOf(Dimension.class);
        dimensions.forEach((dimension, comparison) -> {
            if (!comparison.matches()) {
                differences.add(dimension);
            }
        });
        return Set.copyOf(differences);
    }

    public enum Dimension {
        SCHEMA,
        STABLE_FACTS,
        SOURCE_HASH_MEMBERSHIP,
        READINESS,
        NORMALIZED_PATCH,
        RECOMMENDATION,
        GUARDRAIL,
        TERMINAL,
        PRIVACY
    }

    public enum HardZeroFinding {
        PRIVACY_LEAKAGE,
        STALE_FENCE_SUCCESS,
        UNAUTHORIZED_FIELD,
        FORMAL_SINK_REACHABILITY
    }

    public enum Verdict {
        MATCH,
        DIFFERENT,
        HARD_FAILURE
    }

    public enum Classification {
        VALID,
        INVALID,
        PRESENT,
        ABSENT,
        READY,
        NOT_READY,
        ACCEPT,
        REJECT,
        REVIEW,
        ALLOW,
        BLOCK,
        TERMINAL,
        NON_TERMINAL,
        CLEAN,
        VIOLATION,
        VALUE
    }

    public record ObservedValue(Classification classification, String valueHash) {

        public ObservedValue {
            Objects.requireNonNull(classification, "classification must not be null");
            requireSha256(valueHash, "valueHash");
        }
    }

    public record DimensionComparison(ObservedValue legacy, ObservedValue shadow) {

        public DimensionComparison {
            Objects.requireNonNull(legacy, "legacy must not be null");
            Objects.requireNonNull(shadow, "shadow must not be null");
        }

        public boolean matches() {
            return legacy.equals(shadow);
        }
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }
}
