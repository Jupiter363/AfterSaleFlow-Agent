package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Deterministically projects an advisory, canonical Graph proposal into a Java formal payload. */
final class TargetHearingFormalPayloadFactory {
  private final ObjectMapper mapper;

  TargetHearingFormalPayloadFactory(ObjectMapper mapper) { this.mapper = mapper.copy(); }

  FormalPayload project(String operation, JsonNode proposal, String formalId,
      JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding) {
    return switch (operation) {
      case "intake_questions" -> action(proposal, formalId, "hearing_intake_questions.v1",
          "hearing_question_set.v1", "question_set_id", Set.of("schema_version", "case_id", "workflow_id", "stage_sequence", "speaker_role", "questions", "public_message"));
      case "evidence_requests" -> action(proposal, formalId, "hearing_evidence_requests.v1",
          "hearing_evidence_request_set.v1", "request_set_id", Set.of("schema_version", "case_id", "workflow_id", "stage_sequence", "requests", "public_message"));
      case "intake_synthesis" -> matrix(proposal, "hearing_intake_synthesis.v1", "case_fact_matrix",
          Set.of("schema_version", "case_id", "workflow_id", "stage_sequence", "case_fact_matrix", "dispute_points", "issue_mappings", "public_message"));
      case "evidence_synthesis" -> matrix(proposal, "hearing_evidence_synthesis.v1", "fact_evidence_matrix",
          Set.of("schema_version", "case_id", "workflow_id", "stage_sequence", "fact_evidence_matrix", "evidence_summary", "evidence_gaps", "public_message"));
      case "judge_v1" -> decision(proposal, formalId, "hearing_judge_v1.v1", "judge_proposal.v1", "proposal_id",
          binding, Set.of("schema_version", "case_id", "workflow_id", "stage_sequence", "trial_dossier_id", "trial_dossier_hash", "proposal_id", "proposal_hash", "proposal_text", "recommended_decision", "reasoning_summary", "review_focus", "public_message", "is_final_decision"));
      case "jury_review" -> decision(proposal, formalId, "hearing_jury_review.v1", "jury_review_report.v1", "report_id",
          binding, Set.of("schema_version", "case_id", "workflow_id", "stage_sequence", "trial_dossier_id", "trial_dossier_hash", "review_id", "review_hash", "reviewed_proposal_id", "reviewed_proposal_hash", "findings", "mandatory_revisions", "public_message", "approval_performed", "execution_triggered", "is_final_decision"));
      case "judge_v2" -> decision(proposal, formalId, "hearing_judge_v2.v1", "adjudication_draft.v2", "draft_id",
          binding, Set.of("schema_version", "case_id", "workflow_id", "stage_sequence", "trial_dossier_id", "trial_dossier_hash", "judge_v2_id", "judge_v2_hash", "parent_proposal_id", "parent_proposal_hash", "jury_review_id", "jury_review_hash", "draft", "public_message"));
      default -> throw new IllegalArgumentException("unsupported target Hearing operation");
    };
  }

  private FormalPayload action(JsonNode source, String id, String sourceSchema, String formalSchema,
      String idField, Set<String> fields) {
    requireSource(source, sourceSchema, fields);
    ObjectNode result = ((ObjectNode) source).deepCopy();
    result.put("schema_version", formalSchema); result.put(idField, id);
    return payload(result, false);
  }

  private FormalPayload matrix(JsonNode source, String schema, String matrixField, Set<String> fields) {
    requireSource(source, schema, fields);
    if (!source.path(matrixField).isObject()) throw new IllegalArgumentException("matrix proposal is incomplete");
    return payload((ObjectNode) source.deepCopy(), false);
  }

  private FormalPayload decision(JsonNode source, String id, String sourceSchema, String formalSchema,
      String idField, JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding, Set<String> fields) {
    requireSource(source, sourceSchema, fields);
    var parents = binding.parents();
    if (parents.dossier() == null) throw new IllegalStateException("target Hearing dossier parent is absent");
    requireDecisionParents(source, operation(sourceSchema), parents);
    ObjectNode result = mapper.createObjectNode();
    result.put("schema_version", formalSchema); result.put(idField, id);
    result.put("trial_dossier_id", parents.dossier().id()); result.put("trial_dossier_hash", parents.dossier().hash());
    if ("jury_review".equals(operation(sourceSchema)) || "judge_v2".equals(operation(sourceSchema))) {
      if (parents.proposal() == null) throw new IllegalStateException("target Hearing proposal parent is absent");
      result.put("proposal_id", parents.proposal().id()); result.put("proposal_content_hash", parents.proposal().hash());
    }
    if ("judge_v2".equals(operation(sourceSchema))) {
      if (parents.report() == null) throw new IllegalStateException("target Hearing report parent is absent");
      result.put("report_id", parents.report().id()); result.put("report_content_hash", parents.report().hash());
      result.set("draft", source.path("draft").deepCopy()); result.put("public_text", source.path("public_message").asText());
    } else result.set("proposal", source.deepCopy());
    FormalPayload unsigned = payload(result, true);
    result.put("content_hash", unsigned.contentHash());
    return new FormalPayload(canonical(result), unsigned.contentHash());
  }

  private void requireSource(JsonNode value, String schema, Set<String> allowed) {
    if (!(value instanceof ObjectNode object) || !schema.equals(object.path("schema_version").asText())
        || object.size() != allowed.size() || !object.fieldNames().hasNext()) throw new IllegalArgumentException("target Hearing proposal schema is invalid");
    object.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw new IllegalArgumentException("target Hearing proposal contains an extra field"); });
    for (String field : allowed) if (!object.has(field) || object.path(field).isNull()) throw new IllegalArgumentException("target Hearing proposal omits a required field");
    rejectAuthority(value);
  }

  private static void requireDecisionParents(JsonNode source, String operation,
      JdbcTargetHearingFormalAuthorityLoader.Parents parents) {
    requireExactText(source, "trial_dossier_id", parents.dossier().id());
    requireExactText(source, "trial_dossier_hash", parents.dossier().hash());
    if ("jury_review".equals(operation) || "judge_v2".equals(operation)) {
      if (parents.proposal() == null) throw new IllegalStateException("target Hearing proposal parent is absent");
      String idField = "jury_review".equals(operation) ? "reviewed_proposal_id" : "parent_proposal_id";
      String hashField = "jury_review".equals(operation) ? "reviewed_proposal_hash" : "parent_proposal_hash";
      requireExactText(source, idField, parents.proposal().id());
      requireExactText(source, hashField, parents.proposal().hash());
    }
    if ("judge_v2".equals(operation)) {
      if (parents.report() == null) throw new IllegalStateException("target Hearing report parent is absent");
      requireExactText(source, "jury_review_id", parents.report().id());
      requireExactText(source, "jury_review_hash", parents.report().hash());
    }
  }

  private static void requireExactText(JsonNode source, String field, String expected) {
    if (!source.path(field).isTextual() || !expected.equals(source.path(field).asText())) {
      throw new IllegalArgumentException("target Hearing proposal " + field + " does not bind its locked parent");
    }
  }

  private static void rejectAuthority(JsonNode value) {
    if (value.isObject()) {
      value.fields().forEachRemaining(entry -> {
        if (Set.of("formal_authority", "formalAuthority", "writer_mode", "writerMode", "authority_commit", "authorityCommit", "fencing_token", "fencingToken").contains(entry.getKey())) {
          throw new IllegalArgumentException("Graph proposal cannot carry Hearing authority");
        }
        rejectAuthority(entry.getValue());
      });
    } else if (value.isArray()) value.forEach(TargetHearingFormalPayloadFactory::rejectAuthority);
  }

  private FormalPayload payload(ObjectNode value, boolean withoutContentHash) {
    ObjectNode hashValue = value.deepCopy(); if (withoutContentHash) hashValue.remove("content_hash");
    return new FormalPayload(canonical(value), ContractJson.sha256Hex(hashValue));
  }
  private String canonical(JsonNode value) { return ContractJson.canonicalString(value); }
  private static String operation(String schema) { return switch (schema) {
    case "hearing_jury_review.v1" -> "jury_review"; case "hearing_judge_v2.v1" -> "judge_v2"; default -> "judge_v1"; }; }
  record FormalPayload(String json, String contentHash) {}
}
