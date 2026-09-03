package com.example.dispute.workflow.application.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Revalidates the strict respondent delta union member independently of model parsing. */
final class IntakeRespondentMatrixDeltaPolicy {

    private static final Set<String> ROOT_REQUIRED =
            Set.of("schema_version", "fact_rows", "summary_source_fact_keys");
    private static final Set<String> ROOT_ALLOWED =
            union(ROOT_REQUIRED, Set.of("respondent_claim"));
    private static final Set<String> ROW_REQUIRED = Set.of(
            "fact_key",
            "category",
            "fact_target",
            "materiality",
            "stance",
            "position_summary",
            "source_scope");
    private static final Set<String> ROW_ALLOWED = union(
            ROW_REQUIRED, Set.of("asserted_value", "agreed_statement", "conflict_summary"));
    private static final Set<String> CLAIM_REQUIRED = Set.of("attitude", "position_summary");
    private static final Set<String> CLAIM_ALLOWED =
            union(CLAIM_REQUIRED, Set.of("alternative_proposal"));
    private static final Set<String> CATEGORIES = Set.of(
            "ORDER",
            "PRODUCT_PAGE",
            "PAYMENT",
            "FULFILLMENT",
            "LOGISTICS",
            "PRODUCT_STATE",
            "COMMUNICATION",
            "AFTER_SALES",
            "TIME",
            "OTHER");
    private static final Set<String> MATERIALITIES = Set.of("CORE", "SUPPORTING", "CONTEXT");
    private static final Set<String> STANCES =
            Set.of("CONFIRM", "DENY", "PARTIAL", "UNKNOWN", "NOT_ADDRESSED");
    private static final Set<String> SOURCE_SCOPES = Set.of(
            "CURRENT_SOURCE", "PREVIOUS_MATRIX", "PREVIOUS_AND_CURRENT_SOURCE");
    private static final Set<String> CLAIM_ATTITUDES = Set.of(
            "AGREE",
            "PARTIALLY_AGREE",
            "DISAGREE",
            "ALTERNATIVE_PROPOSED",
            "NEED_MORE_INFO",
            "NOT_ADDRESSED");

    ValidatedDelta validate(JsonNode candidate) {
        if (candidate == null || !candidate.isObject()) {
            throw rejected("INTAKE_RESPONDENT_MATRIX_DELTA_INVALID", "matrix delta is not an object");
        }
        ObjectNode delta = (ObjectNode) candidate;
        requireFields(delta, ROOT_REQUIRED, ROOT_ALLOWED, "matrix delta");
        if (!"case_fact_matrix.delta.v2".equals(requiredText(delta, "schema_version", 64))) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DELTA_INVALID",
                    "respondent matrix patch must be case_fact_matrix.delta.v2");
        }

        JsonNode rowValues = delta.path("fact_rows");
        if (!rowValues.isArray() || rowValues.isEmpty() || rowValues.size() > 200) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DELTA_INVALID",
                    "respondent matrix delta fact rows have an invalid length");
        }
        List<DeltaRow> rows = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode value : rowValues) {
            DeltaRow row = validateRow(value);
            if (!keys.add(row.factKey())) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_DELTA_DUPLICATE_KEY",
                        "respondent matrix delta fact keys must be unique");
            }
            rows.add(row);
        }

        List<String> summaryKeys = requiredFactKeyArray(
                delta.path("summary_source_fact_keys"), "matrix delta summary fact keys");
        if (!keys.containsAll(summaryKeys)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DELTA_SUMMARY_INVALID",
                    "respondent matrix summary references a row outside the proposal");
        }
        RespondentClaim respondentClaim = validateClaim(delta.get("respondent_claim"));
        return new ValidatedDelta(List.copyOf(rows), summaryKeys, respondentClaim);
    }

    private static DeltaRow validateRow(JsonNode candidate) {
        if (!candidate.isObject()) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DELTA_INVALID",
                    "respondent matrix delta fact row is not an object");
        }
        ObjectNode row = (ObjectNode) candidate;
        requireFields(row, ROW_REQUIRED, ROW_ALLOWED, "matrix delta fact row");
        String factKey = requiredText(row, "fact_key", 128);
        if (!factKey.matches("(?:FACT|NEW)_[A-Za-z0-9_:-]{1,123}")) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DELTA_INVALID",
                    "respondent matrix fact key is invalid");
        }
        String category = requiredEnum(row, "category", CATEGORIES);
        String factTarget = requiredText(row, "fact_target", 20_000);
        String materiality = requiredEnum(row, "materiality", MATERIALITIES);
        String stance = requiredEnum(row, "stance", STANCES);
        String positionSummary = requiredText(row, "position_summary", 20_000);
        String assertedValue = optionalText(row, "asserted_value", 2_000);
        String sourceScope = requiredEnum(row, "source_scope", SOURCE_SCOPES);
        String agreedStatement = optionalText(row, "agreed_statement", 20_000);
        String conflictSummary = optionalText(row, "conflict_summary", 20_000);

        if (factKey.startsWith("NEW_")
                && ("NOT_ADDRESSED".equals(stance) || "PREVIOUS_MATRIX".equals(sourceScope))) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DELTA_RELATION_INVALID",
                    "a new respondent fact cannot be unaddressed or previous-only");
        }
        if ("NOT_ADDRESSED".equals(stance)
                && (!factKey.startsWith("FACT_")
                        || !"PREVIOUS_MATRIX".equals(sourceScope)
                        || assertedValue != null)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DELTA_RELATION_INVALID",
                    "NOT_ADDRESSED must carry one prior fact without an asserted value");
        }
        return new DeltaRow(
                factKey,
                category,
                factTarget,
                materiality,
                stance,
                positionSummary,
                assertedValue,
                sourceScope,
                agreedStatement,
                conflictSummary);
    }

    private static RespondentClaim validateClaim(JsonNode candidate) {
        if (candidate == null || candidate.isNull()) {
            return null;
        }
        if (!candidate.isObject()) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DELTA_INVALID",
                    "respondent claim delta is not an object or null");
        }
        ObjectNode claim = (ObjectNode) candidate;
        requireFields(claim, CLAIM_REQUIRED, CLAIM_ALLOWED, "respondent claim delta");
        return new RespondentClaim(
                requiredEnum(claim, "attitude", CLAIM_ATTITUDES),
                requiredText(claim, "position_summary", 20_000),
                optionalText(claim, "alternative_proposal", 20_000));
    }

    private static List<String> requiredFactKeyArray(JsonNode values, String label) {
        if (!values.isArray() || values.isEmpty() || values.size() > 200) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DELTA_INVALID", label + " have an invalid length");
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual()
                    || !value.textValue().matches("(?:FACT|NEW)_[A-Za-z0-9_:-]{1,123}")
                    || !unique.add(value.textValue())) {
                throw rejected(
                        "INTAKE_RESPONDENT_MATRIX_DELTA_SUMMARY_INVALID",
                        label + " contain an invalid or duplicate key");
            }
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private static String requiredEnum(JsonNode owner, String field, Set<String> values) {
        String value = requiredText(owner, field, 128);
        if (!values.contains(value)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DELTA_INVALID", field + " is invalid");
        }
        return value;
    }

    private static String requiredText(JsonNode owner, String field, int maximum) {
        JsonNode value = owner.path(field);
        if (!value.isTextual()
                || value.textValue().isBlank()
                || value.textValue().length() > maximum) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DELTA_INVALID", field + " is invalid");
        }
        return value.textValue();
    }

    private static String optionalText(ObjectNode owner, String field, int maximum) {
        JsonNode value = owner.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()
                || value.textValue().isBlank()
                || value.textValue().length() > maximum) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DELTA_INVALID", field + " is invalid");
        }
        return value.textValue();
    }

    private static void requireFields(
            ObjectNode value, Set<String> required, Set<String> allowed, String label) {
        Set<String> actual = new HashSet<>();
        Iterator<String> names = value.fieldNames();
        names.forEachRemaining(actual::add);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
            throw rejected(
                    "INTAKE_RESPONDENT_MATRIX_DELTA_INVALID",
                    label + " has unexpected or missing fields");
        }
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }

    private static IntakeFinalizationRejectedException rejected(String code, String message) {
        return new IntakeFinalizationRejectedException(code, message);
    }

    record ValidatedDelta(
            List<DeltaRow> rows,
            List<String> summaryKeys,
            RespondentClaim respondentClaim) {

        ValidatedDelta {
            rows = List.copyOf(rows);
            summaryKeys = List.copyOf(summaryKeys);
        }
    }

    record DeltaRow(
            String factKey,
            String category,
            String factTarget,
            String materiality,
            String stance,
            String positionSummary,
            String assertedValue,
            String sourceScope,
            String agreedStatement,
            String conflictSummary) {}

    record RespondentClaim(String attitude, String positionSummary, String alternativeProposal) {}
}
