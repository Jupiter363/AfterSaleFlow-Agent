package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;

/** Builds the exact Python {@code TrialDossierV2} wire shape from locked formal parents. */
final class TargetHearingTrialDossier {
  private TargetHearingTrialDossier() {}

  static Value build(ObjectMapper mapper, String dossierId, String caseId, Instant frozenAt,
      JsonNode caseSource, JsonNode evidenceSource, JsonNode questionSet, JsonNode requestSet,
      JsonNode answers, JsonNode evidenceBatches, JsonNode policyRules) {
    Objects.requireNonNull(mapper, "mapper");
    ObjectNode caseMatrix = matrix(caseSource, "case_fact_matrix");
    ObjectNode evidenceMatrix = matrix(evidenceSource, "fact_evidence_matrix");
    require(caseId.equals(text(caseMatrix, "case_id")), "case matrix case binding");
    require(caseId.equals(text(evidenceMatrix, "case_id")), "evidence matrix case binding");
    int caseVersion = positiveInt(caseMatrix, "matrix_version");
    int evidenceVersion = positiveInt(evidenceMatrix, "matrix_version");
    String caseHash = exactHash(mapper, caseMatrix);
    String evidenceHash = exactHash(mapper, evidenceMatrix);
    require("FROZEN".equals(text(evidenceMatrix, "matrix_status")), "frozen evidence matrix");
    require(text(evidenceMatrix, "case_fact_matrix_id").equals(text(caseMatrix, "matrix_id"))
        && evidenceMatrix.path("case_fact_matrix_version").asInt(-1) == caseVersion
        && caseHash.equals(text(evidenceMatrix, "case_fact_matrix_hash")), "matrix parent binding");
    String questionId = text(questionSet, "question_set_id");
    String requestId = text(requestSet, "request_set_id");
    require(("hearing_question_set.v4".equals(text(questionSet, "schema_version"))
            || "hearing_question_set.v1".equals(text(questionSet, "schema_version")))
        && caseId.equals(text(questionSet, "case_id")), "question set lineage");
    require("hearing_evidence_request_set.v1".equals(text(requestSet, "schema_version"))
        && requestSet.path("case_matrix_version").asInt(-1) == caseVersion
        && caseHash.equals(text(requestSet, "case_matrix_hash")), "request set binding");
    require(answers.isArray() && answers.size() == 2 && evidenceBatches.isArray()
        && evidenceBatches.size() == 2 && policyRules.isArray() && !policyRules.isEmpty(), "dossier parents");
    ObjectNode value = mapper.createObjectNode();
    value.put("schema_version", "trial_dossier.v2"); value.put("trial_dossier_id", dossierId);
    value.put("case_id", caseId); value.put("frozen_at", frozenAt.toString());
    value.put("case_matrix_version", caseVersion); value.put("case_matrix_hash", caseHash);
    value.set("case_fact_matrix", caseMatrix.deepCopy());
    value.put("evidence_matrix_version", evidenceVersion); value.put("evidence_matrix_hash", evidenceHash);
    value.set("fact_evidence_matrix", evidenceMatrix.deepCopy());
    value.set("adjudication_rules", policyRules.deepCopy());
    String hash = JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
        mapper, value, "content_hash");
    value.put("content_hash", hash);
    return new Value(ContractJson.canonicalString(value), hash, caseVersion, caseHash, evidenceVersion,
        evidenceHash, questionId, requestId);
  }

  private static ObjectNode matrix(JsonNode source, String name) {
    JsonNode value = source.path(name).isObject() ? source.path(name) : source;
    if (!value.isObject()) throw new IllegalStateException("target Hearing " + name + " is absent");
    return (ObjectNode) value;
  }
  private static String exactHash(ObjectMapper mapper, ObjectNode value) {
    String hash = text(value, "content_hash"); ObjectNode unsigned = value.deepCopy(); unsigned.remove("content_hash");
    require(hash.equals(JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
        mapper, unsigned, "content_hash")), "matrix content hash");
    return hash;
  }
  private static int positiveInt(JsonNode value, String field) { int result = value.path(field).asInt(-1); if (result < 1) throw new IllegalStateException("target Hearing " + field + " is invalid"); return result; }
  private static String text(JsonNode value, String field) { String result = value.path(field).asText(null); if (result == null || result.isBlank()) throw new IllegalStateException("target Hearing " + field + " is absent"); return result; }
  private static void require(boolean value, String label) { if (!value) throw new IllegalStateException("target Hearing " + label + " is invalid"); }
  record Value(String json, String hash, int caseVersion, String caseHash, int evidenceVersion,
      String evidenceHash, String questionSetId, String requestSetId) {}
}
