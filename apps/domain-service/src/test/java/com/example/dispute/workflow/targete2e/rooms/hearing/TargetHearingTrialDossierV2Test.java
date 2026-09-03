package com.example.dispute.workflow.targete2e.rooms.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TargetHearingTrialDossierV2Test {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void freezesOnlyM2E2AndAdjudicationRulesWhileRetainingLineageOutsidePayload() throws Exception {
    ObjectNode caseMatrix = caseMatrix();
    ObjectNode evidenceMatrix = evidenceMatrix(caseMatrix);
    ObjectNode questionSet = mapper.createObjectNode()
        .put("schema_version", "hearing_question_set.v4")
        .put("question_set_id", "QUESTION_SET_1")
        .put("case_id", "CASE_1");
    ObjectNode requestSet = mapper.createObjectNode()
        .put("schema_version", "hearing_evidence_request_set.v1")
        .put("request_set_id", "REQUEST_SET_1")
        .put("case_matrix_version", 2)
        .put("case_matrix_hash", caseMatrix.path("content_hash").asText());
    ArrayNode answers = twoParents();
    ArrayNode batches = twoParents();
    ArrayNode rules = mapper.createArrayNode();
    rules.addObject()
        .put("rule_code", "DELIVERY_PROOF")
        .put("rule_version", 1)
        .put("rule_name", "签收争议举证规则");

    TargetHearingTrialDossier.Value built = TargetHearingTrialDossier.build(
        mapper,
        "TRIAL_DOSSIER_1",
        "CASE_1",
        Instant.parse("2026-08-21T00:00:00Z"),
        caseMatrix,
        evidenceMatrix,
        questionSet,
        requestSet,
        answers,
        batches,
        rules);

    JsonNode payload = mapper.readTree(built.json());
    assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(Set.of(
        "schema_version", "trial_dossier_id", "case_id", "frozen_at",
        "case_matrix_version", "case_matrix_hash", "case_fact_matrix",
        "evidence_matrix_version", "evidence_matrix_hash", "fact_evidence_matrix",
        "adjudication_rules", "content_hash"));
    assertThat(payload.path("schema_version").asText()).isEqualTo("trial_dossier.v2");
    assertThat(payload.path("case_fact_matrix")).isEqualTo(caseMatrix);
    assertThat(payload.path("fact_evidence_matrix")).isEqualTo(evidenceMatrix);
    assertThat(payload.path("adjudication_rules")).isEqualTo(rules);
    assertThat(payload.has("question_set")).isFalse();
    assertThat(payload.has("answer_bundles")).isFalse();
    assertThat(payload.has("evidence_request_set")).isFalse();
    assertThat(payload.has("evidence_batches")).isFalse();
    assertThat(payload.has("policy_rules")).isFalse();
    assertThat(built.questionSetId()).isEqualTo("QUESTION_SET_1");
    assertThat(built.requestSetId()).isEqualTo("REQUEST_SET_1");
    assertThat(built.hash()).isEqualTo(payload.path("content_hash").asText());
  }

  @Test
  void rejectsAFreezeWithoutBoundAdjudicationRules() {
    ObjectNode caseMatrix = caseMatrix();
    ObjectNode evidenceMatrix = evidenceMatrix(caseMatrix);
    ObjectNode questionSet = mapper.createObjectNode()
        .put("schema_version", "hearing_question_set.v4")
        .put("question_set_id", "QUESTION_SET_1")
        .put("case_id", "CASE_1");
    ObjectNode requestSet = mapper.createObjectNode()
        .put("schema_version", "hearing_evidence_request_set.v1")
        .put("request_set_id", "REQUEST_SET_1")
        .put("case_matrix_version", 2)
        .put("case_matrix_hash", caseMatrix.path("content_hash").asText());

    assertThatThrownBy(() -> TargetHearingTrialDossier.build(
        mapper, "TRIAL_DOSSIER_1", "CASE_1", Instant.EPOCH,
        caseMatrix, evidenceMatrix, questionSet, requestSet,
        twoParents(), twoParents(), mapper.createArrayNode()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dossier parents");
  }

  private ObjectNode caseMatrix() {
    ObjectNode matrix = mapper.createObjectNode();
    matrix.put("schema_version", "case_fact_matrix.v2");
    matrix.put("case_id", "CASE_1");
    matrix.put("matrix_id", "CASE_MATRIX_2");
    matrix.put("matrix_version", 2);
    matrix.putArray("fact_rows").addObject().put("fact_id", "FACT_1");
    putHash(matrix);
    return matrix;
  }

  private ObjectNode evidenceMatrix(ObjectNode caseMatrix) {
    ObjectNode matrix = mapper.createObjectNode();
    matrix.put("schema_version", "fact_evidence_matrix.v3");
    matrix.put("case_id", "CASE_1");
    matrix.put("matrix_id", "EVIDENCE_MATRIX_2");
    matrix.put("matrix_version", 2);
    matrix.put("matrix_status", "FROZEN");
    matrix.put("case_fact_matrix_id", caseMatrix.path("matrix_id").asText());
    matrix.put("case_fact_matrix_version", caseMatrix.path("matrix_version").asInt());
    matrix.put("case_fact_matrix_hash", caseMatrix.path("content_hash").asText());
    matrix.putArray("links");
    matrix.putArray("fact_coverage");
    putHash(matrix);
    return matrix;
  }

  private ArrayNode twoParents() {
    ArrayNode values = mapper.createArrayNode();
    values.addObject().put("role", "USER");
    values.addObject().put("role", "MERCHANT");
    return values;
  }

  private void putHash(ObjectNode value) {
    value.put("content_hash", JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
        mapper, value, "content_hash"));
  }
}
