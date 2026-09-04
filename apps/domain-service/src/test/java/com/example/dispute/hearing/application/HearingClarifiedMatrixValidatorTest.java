package com.example.dispute.hearing.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class HearingClarifiedMatrixValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void acceptsAnAppendOnlyClarificationWithDerivedIndexes() {
        ObjectNode source = sourceMatrix();
        ObjectNode clarified = clarifiedMatrix(source);

        assertThatCode(() -> HearingClarifiedMatrixValidator.validate(clarified, source))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRewritingAFrozenFact() {
        ObjectNode source = sourceMatrix();
        ObjectNode clarified = clarifiedMatrix(source);
        ((ObjectNode) clarified.withArray("fact_rows").get(0)).put("materiality", "SUPPORTING");

        assertThatThrownBy(() -> HearingClarifiedMatrixValidator.validate(clarified, source))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("hearing clarification changed or renumbered a prior fact");
    }

    private static ObjectNode sourceMatrix() {
        ObjectNode source = MAPPER.createObjectNode();
        source.put("schema_version", "case_fact_matrix.v2");
        source.put("case_id", "CASE_1");
        source.put("matrix_id", "MATRIX_1");
        source.put("matrix_version", 1);
        source.putObject("party_map").put("USER", "USER_1").put("MERCHANT", "MERCHANT_1");
        source.putArray("claims");
        source.putArray("fact_relationships");
        source.putArray("source_refs").add("MESSAGE_1");
        ObjectNode row = source.putArray("fact_rows").addObject();
        row.put("fact_id", "FACT_1");
        row.put("category", "PRODUCT_CONDITION");
        row.put("fact_target", "商品到货状态");
        row.put("materiality", "CORE");
        row.put("truth_status", "NOT_EVALUATED");
        row.putObject("party_alignment").put("status", "NOT_COMPUTED");
        row.putNull("requires_resolution");
        source.put("content_hash", ContractJson.sha256Hex(source));
        return source;
    }

    private static ObjectNode clarifiedMatrix(ObjectNode source) {
        ObjectNode clarified = MAPPER.createObjectNode();
        clarified.put("schema_version", "case_fact_matrix.v2");
        clarified.put("case_id", "CASE_1");
        clarified.put("matrix_kind", "HEARING_CLARIFIED_FROZEN");
        clarified.put("matrix_version", 2);
        clarified.putObject("parent_ref")
                .put("matrix_id", "MATRIX_1")
                .put("matrix_version", 1)
                .put("content_hash", source.path("content_hash").asText());
        clarified.set("party_map", source.path("party_map").deepCopy());
        clarified.set("claims", source.path("claims").deepCopy());
        clarified.set("fact_relationships", source.path("fact_relationships").deepCopy());
        clarified.putArray("source_refs").add("MESSAGE_1").add("HEARING_MESSAGE_1");
        clarified.putObject("generation_ref")
                .put("source_stage", "HEARING_CLARIFICATION")
                .put("actor_role", "SYSTEM")
                .put("source_context_hash", "a".repeat(64))
                .put("latest_source_ref", "HEARING_MESSAGE_1");

        ArrayNode rows = clarified.putArray("fact_rows");
        rows.add(source.withArray("fact_rows").get(0).deepCopy());
        ObjectNode added = rows.addObject();
        added.put("fact_id", "FACT_HEARING_1");
        added.put("category", "COMMUNICATION");
        added.put("fact_target", "双方沟通内容");
        added.put("materiality", "SUPPORTING");
        added.put("truth_status", "NOT_EVALUATED");
        added.putObject("party_alignment").put("status", "CONTESTED");
        added.put("requires_resolution", true);
        added.putObject("origin").put("introduced_stage", "HEARING_CLARIFICATION");
        added.put("evidence_coverage_status", "NOT_COVERED_BY_FROZEN_DOSSIER");

        ObjectNode indexes = clarified.putObject("fact_indexes");
        indexes.putArray("not_computed_fact_ids").add("FACT_1");
        indexes.putArray("agreed_fact_ids");
        indexes.putArray("partially_agreed_fact_ids");
        indexes.putArray("contested_fact_ids").add("FACT_HEARING_1");
        indexes.putArray("one_sided_fact_ids");
        indexes.putArray("unresolved_fact_ids");
        indexes.putArray("core_fact_ids").add("FACT_1");
        indexes.putArray("requires_resolution_fact_ids").add("FACT_HEARING_1");
        return clarified;
    }
}
