package com.example.dispute.workflow.shadow.evidence;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds bounded, text-free comparisons for a Java-signed synthetic Evidence fixture. */
public final class EvidenceShadowParityService {

    public static final String SCHEMA_VERSION = "evidence-shadow-parity.v1";
    private static final Set<Dimension> REQUIRED_DIMENSIONS =
            Set.copyOf(EnumSet.allOf(Dimension.class));

    public ParityComparison compare(
            String comparisonKeyHash,
            ParitySnapshot legacy,
            ParitySnapshot shadow) {
        requireSha256(comparisonKeyHash, "comparisonKeyHash");
        Objects.requireNonNull(legacy, "legacy must not be null");
        Objects.requireNonNull(shadow, "shadow must not be null");

        EnumMap<Dimension, DimensionComparison> dimensions = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) {
            dimensions.put(
                    dimension,
                    new DimensionComparison(
                            legacy.values().get(dimension), shadow.values().get(dimension)));
        }
        EnumSet<StopCondition> stopConditions = EnumSet.noneOf(StopCondition.class);
        stopConditions.addAll(legacy.stopConditions());
        stopConditions.addAll(shadow.stopConditions());
        return new ParityComparison(
                SCHEMA_VERSION, comparisonKeyHash, dimensions, stopConditions);
    }

    public record ParitySnapshot(
            Map<Dimension, ObservedValue> values,
            Set<StopCondition> stopConditions) {

        public ParitySnapshot {
            Objects.requireNonNull(values, "values must not be null");
            if (!values.keySet().equals(REQUIRED_DIMENSIONS)) {
                throw new IllegalArgumentException(
                        "snapshot must contain every bounded parity dimension exactly once");
            }
            EnumMap<Dimension, ObservedValue> copy = new EnumMap<>(Dimension.class);
            values.forEach((dimension, value) -> copy.put(
                    Objects.requireNonNull(dimension, "dimension must not be null"),
                    Objects.requireNonNull(value, "observed value must not be null")));
            values = Map.copyOf(copy);
            stopConditions = Set.copyOf(
                    Objects.requireNonNull(stopConditions, "stopConditions must not be null"));
        }
    }

    public record ParityComparison(
            String schemaVersion,
            String comparisonKeyHash,
            Map<Dimension, DimensionComparison> dimensions,
            Set<StopCondition> stopConditions) {

        public ParityComparison {
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
            }
            requireSha256(comparisonKeyHash, "comparisonKeyHash");
            Objects.requireNonNull(dimensions, "dimensions must not be null");
            if (!dimensions.keySet().equals(REQUIRED_DIMENSIONS)) {
                throw new IllegalArgumentException(
                        "comparison must contain every bounded parity dimension exactly once");
            }
            EnumMap<Dimension, DimensionComparison> copy = new EnumMap<>(Dimension.class);
            dimensions.forEach((dimension, comparison) -> copy.put(
                    Objects.requireNonNull(dimension, "dimension must not be null"),
                    Objects.requireNonNull(comparison, "dimension comparison must not be null")));
            dimensions = Map.copyOf(copy);
            stopConditions = Set.copyOf(
                    Objects.requireNonNull(stopConditions, "stopConditions must not be null"));
        }

        public Verdict verdict() {
            if (!stopConditions.isEmpty()) {
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
    }

    public enum Dimension {
        MANIFEST_MEMBERSHIP_HASH,
        ASSESSMENT_KEY_COVERAGE_AND_TERMINAL_CLASS,
        AUTHENTICITY_RELEVANCE_CATEGORY,
        FACT_SOURCE_REFS,
        LOADED_MODALITY_RECEIPTS,
        REVIEW_CLASSIFICATION,
        MATRIX_CANONICAL_HASH,
        ADMISSION_CLASSIFICATION,
        TIMER_COMPLETION_ORDERING,
        MERGE_COUNT_INVARIANT,
        PRIVACY_AUTHORITY_INVARIANT
    }

    public enum Classification {
        VALID,
        INVALID,
        PRESENT,
        ABSENT,
        COMPLETE,
        INCOMPLETE,
        ORDERED,
        OUT_OF_ORDER,
        ONE,
        NOT_ONE,
        CLEAN,
        VIOLATION,
        VALUE
    }

    /** Any listed condition stops synthetic parity; no result is eligible for formal handling. */
    public enum StopCondition {
        UNAUTHORIZED_ASSET_BYTES,
        PRIVACY_VIOLATION,
        CONFLICTING_REDUCER_KEY,
        STALE_FENCE_SUCCESS,
        ASSESSMENT_COVERAGE_GAP,
        MERGE_FREEZE_COUNT_NOT_ONE,
        HEARING_OPEN_WITHOUT_JAVA_ADMISSION_RECEIPT
    }

    public enum Verdict {
        MATCH,
        DIFFERENT,
        HARD_FAILURE
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
