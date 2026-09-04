package com.example.dispute.hearing.application;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Validates that hearing clarification extends, but does not rewrite, a frozen fact matrix. */
final class HearingClarifiedMatrixValidator {

    private HearingClarifiedMatrixValidator() {}

    static void validate(ObjectNode clarified, ObjectNode sourceMatrix) {
        requireSchema(sourceMatrix, "case_fact_matrix.v2");
        verifyEmbeddedHash(sourceMatrix, "content_hash");
        if (!requiredText(sourceMatrix, "case_id").equals(requiredText(clarified, "case_id"))
                || !"HEARING_CLARIFIED_FROZEN"
                        .equals(requiredText(clarified, "matrix_kind"))) {
            throw new IllegalStateException("hearing clarified matrix identity is invalid");
        }
        int sourceVersion = sourceMatrix.path("matrix_version").asInt(-1);
        if (sourceVersion < 1 || clarified.path("matrix_version").asInt(-1) != sourceVersion + 1) {
            throw new IllegalStateException("hearing clarified matrix version must increment once");
        }
        ObjectNode parent = object(clarified.path("parent_ref"));
        if (!requiredText(sourceMatrix, "matrix_id").equals(requiredText(parent, "matrix_id"))
                || sourceVersion != parent.path("matrix_version").asInt(-1)
                || !requiredText(sourceMatrix, "content_hash")
                        .equals(requiredText(parent, "content_hash"))) {
            throw new IllegalStateException("hearing clarified matrix parent binding is invalid");
        }
        if (!canonicalJson(sourceMatrix.path("party_map"))
                        .equals(canonicalJson(clarified.path("party_map")))
                || !canonicalJson(sourceMatrix.path("claims"))
                        .equals(canonicalJson(clarified.path("claims")))
                || !canonicalJson(sourceMatrix.path("fact_relationships"))
                        .equals(canonicalJson(clarified.path("fact_relationships")))) {
            throw new IllegalStateException(
                    "hearing clarification cannot replace party identity, claims, or relationships");
        }
        Set<String> clarifiedSourceRefs = textSet(clarified.path("source_refs"), "source_refs");
        if (!clarifiedSourceRefs.containsAll(
                textSet(sourceMatrix.path("source_refs"), "source_refs"))) {
            throw new IllegalStateException("hearing clarified matrix dropped prior source_refs");
        }
        ObjectNode generation = object(clarified.path("generation_ref"));
        if (!"HEARING_CLARIFICATION".equals(requiredText(generation, "source_stage"))
                || !"SYSTEM".equals(requiredText(generation, "actor_role"))
                || !requiredText(generation, "source_context_hash").matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(
                    "hearing clarification generation_ref is invalid");
        }
        requiredText(generation, "latest_source_ref");

        ArrayNode priorRows = array(sourceMatrix.path("fact_rows"));
        ArrayNode rows = array(clarified.path("fact_rows"));
        if (priorRows.isEmpty() || rows.size() < priorRows.size() || rows.size() > 200) {
            throw new IllegalStateException("hearing clarified matrix fact row count is invalid");
        }
        Set<String> factIds = new LinkedHashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            ObjectNode row = object(rows.get(index));
            String factId = requiredText(row, "fact_id");
            if (!factIds.add(factId)) {
                throw new IllegalStateException(
                        "hearing clarified matrix contains duplicate fact_id");
            }
            if (!"NOT_EVALUATED".equals(requiredText(row, "truth_status"))) {
                throw new IllegalStateException("hearing clarification cannot evaluate fact truth");
            }
            assertDerivedResolution(row);
            if (index < priorRows.size()) {
                ObjectNode prior = object(priorRows.get(index));
                if (!requiredText(prior, "fact_id").equals(factId)
                        || !requiredText(prior, "category").equals(requiredText(row, "category"))
                        || !requiredText(prior, "fact_target")
                                .equals(requiredText(row, "fact_target"))
                        || !requiredText(prior, "materiality")
                                .equals(requiredText(row, "materiality"))) {
                    throw new IllegalStateException(
                            "hearing clarification changed or renumbered a prior fact");
                }
                continue;
            }
            if (!factId.startsWith("FACT_HEARING_")
                    || !"HEARING_CLARIFICATION"
                            .equals(requiredText(object(row.path("origin")), "introduced_stage"))
                    || !"NOT_COVERED_BY_FROZEN_DOSSIER"
                            .equals(requiredText(row, "evidence_coverage_status"))) {
                throw new IllegalStateException(
                        "new hearing fact lacks stable identity or frozen-dossier coverage state");
            }
        }
        assertFactIndexes(clarified, rows);
    }

    private static void assertDerivedResolution(ObjectNode row) {
        String status = requiredText(object(row.path("party_alignment")), "status");
        JsonNode resolution = row.path("requires_resolution");
        if ("NOT_COMPUTED".equals(status)) {
            if (!resolution.isNull()) {
                throw new IllegalStateException(
                        "NOT_COMPUTED fact alignment requires null requires_resolution");
            }
            return;
        }
        if (!Set.of("AGREED", "PARTIALLY_AGREED", "CONTESTED", "ONE_SIDED", "UNRESOLVED")
                .contains(status)) {
            throw new IllegalStateException("hearing fact alignment status is invalid");
        }
        if (!resolution.isBoolean() || resolution.asBoolean() != !"AGREED".equals(status)) {
            throw new IllegalStateException(
                    "requires_resolution must be derived from party_alignment");
        }
    }

    private static void assertFactIndexes(ObjectNode matrix, ArrayNode rows) {
        ObjectNode expected = JsonNodeFactory.instance.objectNode();
        for (String key :
                List.of(
                        "not_computed_fact_ids",
                        "agreed_fact_ids",
                        "partially_agreed_fact_ids",
                        "contested_fact_ids",
                        "one_sided_fact_ids",
                        "unresolved_fact_ids",
                        "core_fact_ids",
                        "requires_resolution_fact_ids")) {
            expected.putArray(key);
        }
        for (JsonNode value : rows) {
            ObjectNode row = object(value);
            String factId = requiredText(row, "fact_id");
            String status = requiredText(object(row.path("party_alignment")), "status");
            String indexKey =
                    switch (status) {
                        case "NOT_COMPUTED" -> "not_computed_fact_ids";
                        case "AGREED" -> "agreed_fact_ids";
                        case "PARTIALLY_AGREED" -> "partially_agreed_fact_ids";
                        case "CONTESTED" -> "contested_fact_ids";
                        case "ONE_SIDED" -> "one_sided_fact_ids";
                        case "UNRESOLVED" -> "unresolved_fact_ids";
                        default -> throw new IllegalStateException(
                                "hearing fact alignment status is invalid");
                    };
            expected.withArray(indexKey).add(factId);
            if ("CORE".equals(row.path("materiality").asText())) {
                expected.withArray("core_fact_ids").add(factId);
            }
            if (row.path("requires_resolution").asBoolean(false)) {
                expected.withArray("requires_resolution_fact_ids").add(factId);
            }
        }
        if (!canonicalJson(expected).equals(canonicalJson(matrix.path("fact_indexes")))) {
            throw new IllegalStateException("hearing clarified matrix fact_indexes are invalid");
        }
    }

    private static Set<String> textSet(JsonNode value, String field) {
        ArrayNode values = array(value);
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode item : values) {
            String text = item.asText();
            if (text.isBlank() || !result.add(text)) {
                throw new IllegalStateException(field + " must contain unique non-blank text");
            }
        }
        return Set.copyOf(result);
    }

    private static void verifyEmbeddedHash(ObjectNode payload, String hashField) {
        String expected = requiredText(payload, hashField);
        ObjectNode copy = payload.deepCopy();
        copy.remove(hashField);
        String actual = ContractJson.sha256Hex(copy);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(hashField + " is invalid");
        }
    }

    private static String canonicalJson(JsonNode value) {
        return ContractJson.canonicalString(value);
    }

    private static String requiredText(JsonNode value, String field) {
        String result = value.path(field).asText();
        if (result.isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return result;
    }

    private static void requireSchema(ObjectNode value, String schema) {
        if (!schema.equals(value.path("schema_version").asText())) {
            throw new IllegalStateException("expected " + schema);
        }
    }

    private static ObjectNode object(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalStateException("hearing payload must be a JSON object");
        }
        return (ObjectNode) value;
    }

    private static ArrayNode array(JsonNode value) {
        return value != null && value.isArray()
                ? (ArrayNode) value
                : JsonNodeFactory.instance.arrayNode();
    }
}
