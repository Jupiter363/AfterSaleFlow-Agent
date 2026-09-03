package com.example.dispute.workflow.shadow.hearing;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.shadow.hearing.HearingSignedSyntheticAdmissionService.AdmissionReceipt;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compares the complete bounded Hearing trace without retaining prompts or party content. */
public final class HearingShadowParityService {

    public static final String SCHEMA_VERSION = "hearing-shadow-parity.v1";
    private static final Set<Dimension> REQUIRED_DIMENSIONS =
            Set.copyOf(EnumSet.allOf(Dimension.class));

    public ParityComparison compare(
            AdmissionReceipt admission, ParitySnapshot legacy, ParitySnapshot shadow) {
        Objects.requireNonNull(admission, "admission must not be null");
        Objects.requireNonNull(legacy, "legacy must not be null");
        Objects.requireNonNull(shadow, "shadow must not be null");
        if (!admission.expectedTraceHash().equals(shadow.traceHash())) {
            throw new IllegalArgumentException(
                    "shadow trace does not match its signed synthetic admission");
        }

        EnumMap<Dimension, DimensionComparison> dimensions = new EnumMap<>(Dimension.class);
        for (Dimension dimension : Dimension.values()) {
            dimensions.put(
                    dimension,
                    new DimensionComparison(
                            legacy.values().get(dimension), shadow.values().get(dimension)));
        }
        EnumSet<StopCondition> stops = EnumSet.noneOf(StopCondition.class);
        stops.addAll(legacy.stopConditions());
        stops.addAll(shadow.stopConditions());
        String comparisonKeyHash = comparisonKey(admission, legacy, shadow);
        return new ParityComparison(
                SCHEMA_VERSION,
                comparisonKeyHash,
                admission.scopeHash(),
                legacy.traceHash(),
                shadow.traceHash(),
                dimensions,
                stops,
                comparisonHash(comparisonKeyHash, dimensions, stops));
    }

    private static String comparisonKey(
            AdmissionReceipt admission, ParitySnapshot legacy, ParitySnapshot shadow) {
        ObjectNode key = JsonNodeFactory.instance.objectNode();
        key.put("schema_version", "hearing-shadow-comparison-key.v1");
        key.put("fixture_id", admission.fixtureId());
        key.put("scope_kind", admission.scopeKind().name());
        key.put("scope_hash", admission.scopeHash());
        key.put("admission_envelope_hash", admission.envelopeHash());
        key.put("admission_claims_hash", admission.claimsHash());
        key.put("legacy_trace_hash", legacy.traceHash());
        key.put("shadow_trace_hash", shadow.traceHash());
        key.put("cohort_policy_version", admission.cohortPolicyVersion());
        return ContractJson.sha256Hex(key);
    }

    private static String comparisonHash(
            String comparisonKeyHash,
            Map<Dimension, DimensionComparison> dimensions,
            Set<StopCondition> stops) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("schema_version", SCHEMA_VERSION);
        value.put("comparison_key_hash", comparisonKeyHash);
        ObjectNode dimensionNode = value.putObject("dimensions");
        dimensions.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                .forEach(entry -> {
                    ObjectNode item = dimensionNode.putObject(entry.getKey().name());
                    item.put("legacy_classification", entry.getValue().legacy().classification().name());
                    item.put("legacy_hash", entry.getValue().legacy().valueHash());
                    item.put("shadow_classification", entry.getValue().shadow().classification().name());
                    item.put("shadow_hash", entry.getValue().shadow().valueHash());
                });
        ArrayNode stopNode = value.putArray("stop_conditions");
        stops.stream().sorted(Comparator.comparing(Enum::name)).forEach(stop -> stopNode.add(stop.name()));
        return ContractJson.sha256Hex(value);
    }

    public record ParitySnapshot(
            String traceHash,
            Map<Dimension, ObservedValue> values,
            Set<StopCondition> stopConditions) {

        public ParitySnapshot {
            requireSha256(traceHash, "traceHash");
            Objects.requireNonNull(values, "values must not be null");
            if (!values.keySet().equals(REQUIRED_DIMENSIONS)) {
                throw new IllegalArgumentException(
                        "snapshot must contain every Hearing parity dimension exactly once");
            }
            EnumMap<Dimension, ObservedValue> copy = new EnumMap<>(Dimension.class);
            values.forEach((dimension, observed) -> copy.put(
                    Objects.requireNonNull(dimension, "dimension must not be null"),
                    Objects.requireNonNull(observed, "observed value must not be null")));
            values = Map.copyOf(copy);
            stopConditions = Set.copyOf(
                    Objects.requireNonNull(stopConditions, "stopConditions must not be null"));
        }
    }

    public record ParityComparison(
            String schemaVersion,
            String comparisonKeyHash,
            String scopeHash,
            String legacyTraceHash,
            String shadowTraceHash,
            Map<Dimension, DimensionComparison> dimensions,
            Set<StopCondition> stopConditions,
            String comparisonHash) {

        public ParityComparison {
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
            }
            requireSha256(comparisonKeyHash, "comparisonKeyHash");
            requireSha256(scopeHash, "scopeHash");
            requireSha256(legacyTraceHash, "legacyTraceHash");
            requireSha256(shadowTraceHash, "shadowTraceHash");
            requireSha256(comparisonHash, "comparisonHash");
            Objects.requireNonNull(dimensions, "dimensions must not be null");
            if (!dimensions.keySet().equals(REQUIRED_DIMENSIONS)) {
                throw new IllegalArgumentException(
                        "comparison must contain every Hearing parity dimension exactly once");
            }
            dimensions = Map.copyOf(dimensions);
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
        STAGE_SEQUENCE,
        PARTY_ACTION_TERMINALS,
        PARTY_SHARED_DEADLINES,
        OPERATION_RECEIPT_ORDER,
        GRAPH_PROPOSAL_LINEAGE,
        DOSSIER_PARENT_CHAIN,
        JUDGE_V1_PARENT,
        JURY_PARENT,
        JUDGE_V2_PARENT,
        HANDOFF_RECEIPT,
        CLOSED_PROJECTION,
        PROCESS_REVISION,
        FENCING_TOKEN,
        PRIVACY_BOUNDARY,
        FORMAL_WRITER_COUNT
    }

    public enum Classification {
        VALID,
        INVALID,
        PRESENT,
        ABSENT,
        ORDERED,
        OUT_OF_ORDER,
        ONE,
        NOT_ONE,
        CLEAN,
        VIOLATION,
        VALUE
    }

    public enum StopCondition {
        PRIVACY_VIOLATION,
        ILLEGAL_OR_DUPLICATE_TRANSITION,
        STALE_FENCE_SUCCESS,
        PARENT_HASH_BREAK,
        DEADLINE_DRIFT,
        DUPLICATE_FORMAL_ARTIFACT,
        FORMAL_SINK_REACHABLE,
        REAL_CASE_DATA_OBSERVED
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
