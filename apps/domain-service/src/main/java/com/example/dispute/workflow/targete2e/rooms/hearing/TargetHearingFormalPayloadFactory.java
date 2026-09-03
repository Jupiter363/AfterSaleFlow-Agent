package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministically projects an advisory, canonical Graph proposal into a Java formal payload. */
final class TargetHearingFormalPayloadFactory {
  private final ObjectMapper mapper;

  TargetHearingFormalPayloadFactory(ObjectMapper mapper) { this.mapper = mapper.copy(); }

  FormalPayload project(String operation, JsonNode proposal, String formalId,
      JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding) {
    return switch (operation) {
      case "intake_questions" -> intakeQuestions(proposal, binding);
      case "evidence_requests" -> action(proposal, formalId, "hearing_evidence_requests.v1",
          "hearing_evidence_request_set.v1", "request_set_id", HearingFlowStage.EVIDENCE_REQUESTS_GENERATING,
          binding, Set.of("schema_version", "case_id", "workflow_id", "stage_sequence", "requests", "public_message"));
      case "intake_synthesis" -> intakeSynthesis(proposal, binding);
      case "evidence_synthesis" -> matrix(proposal, "hearing_evidence_synthesis.v1", "fact_evidence_matrix",
          Set.of("schema_version", "case_id", "workflow_id", "stage_sequence", "fact_evidence_matrix", "evidence_summary", "evidence_gaps", "public_message"));
      case "judge_v1" -> decision(proposal, formalId, "hearing_judge_v1.v2", "judge_proposal.v2", "proposal_id",
          binding, Set.of("schema_version", "case_id", "workflow_id", "stage_sequence", "trial_dossier_id", "trial_dossier_hash", "proposal_id", "proposal_hash", "draft", "review_focus", "public_message", "is_final_decision"));
      case "jury_review" -> decision(proposal, formalId, "hearing_jury_review.v1", "jury_review_report.v1", "report_id",
          binding, Set.of("schema_version", "case_id", "workflow_id", "stage_sequence", "trial_dossier_id", "trial_dossier_hash", "review_id", "review_hash", "reviewed_proposal_id", "reviewed_proposal_hash", "findings", "mandatory_revisions", "public_message", "approval_performed", "execution_triggered", "is_final_decision"));
      case "judge_v2" -> decision(proposal, formalId, "hearing_judge_v2.v2", "adjudication_draft.v3", "draft_id",
          binding, Set.of("schema_version", "case_id", "workflow_id", "stage_sequence", "trial_dossier_id", "trial_dossier_hash", "judge_v2_id", "judge_v2_hash", "parent_proposal_id", "parent_proposal_hash", "jury_review_id", "jury_review_hash", "draft", "review_responses", "public_message", "draft_status", "requires_human_review", "is_final_decision"));
      default -> throw new IllegalArgumentException("unsupported target Hearing operation");
    };
  }

  private FormalPayload intakeQuestions(
      JsonNode source,
      JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding) {
    requireSource(source, "hearing_intake_questions.v5", Set.of(
        "schema_version", "case_id", "workflow_id", "stage_sequence", "speaker_role",
        "question_set", "public_frames", "lead_public_text"));
    if (binding.authority().stage() != HearingFlowStage.INTAKE_QUESTIONS_GENERATING
        || binding.matrixAuthority() == null) {
      throw new IllegalStateException("target Hearing V5 question matrix authority is absent");
    }
    ObjectNode questionSet = object(source.path("question_set"), "question set");
    requireQuestionSet(questionSet, source, binding.matrixAuthority());
    requireQuestionFrames(source, questionSet);
    String contentHash = questionSet.path("question_set_hash").asText();
    return new FormalPayload(canonical(questionSet), contentHash, canonical(source));
  }

  private FormalPayload intakeSynthesis(
      JsonNode source,
      JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding) {
    requireSource(source, "hearing_intake_synthesis.v5", Set.of(
        "schema_version", "case_id", "workflow_id", "stage_sequence", "public_frames",
        "issue_transition_set", "case_fact_matrix", "issue_state_set",
        "lead_public_text"));
    if (binding.authority().stage() != HearingFlowStage.INTAKE_SYNTHESIZING
        || binding.matrixAuthority() == null) {
      throw new IllegalStateException("target Hearing V5 synthesis matrix authority is absent");
    }
    ObjectNode transition = object(source.path("issue_transition_set"), "issue transition set");
    ObjectNode matrix = object(source.path("case_fact_matrix"), "case fact matrix");
    ObjectNode stateSet = object(source.path("issue_state_set"), "issue state set");
    requireSelfHash(transition, "hearing_issue_transition_set.v4", "transition_hash");
    requireSelfHash(matrix, "case_fact_matrix.v2", "content_hash");
    requireSelfHash(stateSet, "hearing_issue_state_set.v4", "content_hash");
    var parent = object(matrix.path("parent_ref"), "matrix parent");
    var m1 = binding.matrixAuthority();
    require(source.path("case_id").asText().equals(matrix.path("case_id").asText())
            && matrix.path("matrix_version").asInt(0) == m1.version() + 1
            && m1.id().equals(parent.path("matrix_id").asText())
            && parent.path("matrix_version").asInt(0) == m1.version()
            && m1.hash().equals(parent.path("content_hash").asText()),
        "V4 M2 parent binding");
    JsonNode bundleIds = transition.path("answer_bundle_ids");
    JsonNode bundleHashes = transition.path("answer_bundle_hashes");
    require(source.path("case_id").asText().equals(transition.path("case_id").asText())
            && transition.path("question_set_id").isTextual()
            && !transition.path("question_set_id").asText().isBlank()
            && transition.path("question_set_hash").asText().matches("[a-f0-9]{64}")
            && bundleIds.isArray() && bundleIds.size() == 2
            && bundleHashes.isArray() && bundleHashes.size() == 2
            && !bundleIds.get(0).asText().equals(bundleIds.get(1).asText())
            && !bundleHashes.get(0).asText().equals(bundleHashes.get(1).asText())
            && bundleHashes.get(0).asText().matches("[a-f0-9]{64}")
            && bundleHashes.get(1).asText().matches("[a-f0-9]{64}"),
        "V4 transition authority");
    require(stateSet.path("case_id").asText().equals(source.path("case_id").asText())
            && stateSet.path("transition_set_id").asText()
                .equals(transition.path("transition_set_id").asText())
            && stateSet.path("transition_hash").asText()
                .equals(transition.path("transition_hash").asText())
            && stateSet.path("question_set_id").asText()
                .equals(transition.path("question_set_id").asText())
            && stateSet.path("question_set_hash").asText()
                .equals(transition.path("question_set_hash").asText())
            && stateSet.path("answer_bundle_ids").equals(bundleIds)
            && stateSet.path("answer_bundle_hashes").equals(bundleHashes)
            && stateSet.path("matrix_id").asText().equals(matrix.path("matrix_id").asText())
            && stateSet.path("matrix_version").asInt(0) == matrix.path("matrix_version").asInt()
            && stateSet.path("matrix_hash").asText().equals(matrix.path("content_hash").asText())
            && stateSet.path("issues").equals(transition.path("issues")),
        "V4 final issue-state binding");
    requireSynthesisFrames(source, transition);
    return payload((ObjectNode) source.deepCopy(), false);
  }

  private FormalPayload action(JsonNode source, String id, String sourceSchema, String formalSchema,
      String idField, HearingFlowStage expectedStage,
      JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding, Set<String> fields) {
    requireSource(source, sourceSchema, fields);
    if (binding.authority().stage() != expectedStage || binding.matrixAuthority() == null) {
      throw new IllegalStateException("target Hearing action matrix authority is absent");
    }
    ObjectNode result = ((ObjectNode) source).deepCopy();
    result.put("schema_version", formalSchema); result.put(idField, id);
    result.put("case_matrix_version", binding.matrixAuthority().version());
    result.put("case_matrix_hash", binding.matrixAuthority().hash());
    return payload(result, false);
  }

  private void requireQuestionSet(
      ObjectNode questionSet,
      JsonNode source,
      JdbcTargetHearingFormalAuthorityLoader.MatrixAuthority matrix) {
    requireSelfHash(questionSet, "hearing_question_set.v4", "question_set_hash");
    JsonNode questions = questionSet.path("questions");
    require(source.path("case_id").asText().equals(questionSet.path("case_id").asText())
            && matrix.id().equals(questionSet.path("source_matrix_id").asText())
            && matrix.version() == questionSet.path("source_matrix_version").asInt(0)
            && matrix.hash().equals(questionSet.path("source_matrix_hash").asText())
            && questionSet.path("prelude_authority_hash").asText().matches("[a-f0-9]{64}")
            && questionSet.path("formal_issue_catalog_hash").asText().matches("[a-f0-9]{64}")
            && questions.isArray() && questions.size() >= 1 && questions.size() <= 5,
        "V4 question set binding");
    ObjectNode catalog = mapper.createObjectNode();
    catalog.put("schema_version", "hearing_formal_issue_catalog.v4");
    var catalogIssues = catalog.putArray("issues");
    Set<String> questionIds = new HashSet<>();
    Set<String> issueIds = new HashSet<>();
    for (int index = 0; index < questions.size(); index++) {
      ObjectNode question = object(questions.get(index), "formal question");
      String questionId = question.path("question_id").asText();
      String issueId = question.path("issue_id").asText();
      JsonNode roles = question.path("target_roles");
      require(("QUESTION_SLOT_%02d".formatted(index + 1))
                  .equals(question.path("question_slot_id").asText())
              && !questionId.isBlank() && questionIds.add(questionId)
              && !issueId.isBlank() && issueIds.add(issueId)
              && question.path("issue_version").asInt(0) == 1
              && question.path("issue_state_hash").asText().matches("[a-f0-9]{64}")
              && roles.isArray() && roles.size() == 2
              && "USER".equals(roles.get(0).asText())
              && "MERCHANT".equals(roles.get(1).asText())
              && question.path("fact_ids").isArray() && !question.path("fact_ids").isEmpty()
              && question.path("question_text").isTextual()
              && !question.path("question_text").asText().isBlank()
              && question.path("issue_baseline").isObject()
              && question.path("party_prompts").isObject(),
          "V4 formal question binding");
      ObjectNode baselineState = mapper.createObjectNode();
      baselineState.put("schema_version", "hearing_issue_baseline_state.v4");
      baselineState.put("issue_id", issueId);
      baselineState.put("issue_version", 1);
      baselineState.put("question_id", questionId);
      baselineState.put("question_slot_id", question.path("question_slot_id").asText());
      baselineState.set("issue_baseline", question.path("issue_baseline").deepCopy());
      require(question.path("issue_state_hash").asText().equals(
              JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
                  mapper, baselineState, "__absent_hash_field__")),
          "V4 baseline issue state hash");
      ObjectNode catalogIssue = catalogIssues.addObject();
      catalogIssue.put("question_slot_id", question.path("question_slot_id").asText());
      catalogIssue.put("question_id", questionId);
      catalogIssue.put("issue_id", issueId);
      catalogIssue.put("issue_version", 1);
      catalogIssue.put("issue_state_hash", question.path("issue_state_hash").asText());
      catalogIssue.set("issue_baseline", question.path("issue_baseline").deepCopy());
    }
    require(questionSet.path("formal_issue_catalog_hash").asText()
            .equals(JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
                mapper, catalog, "__absent_hash_field__")),
        "V4 formal issue catalog hash");
  }

  private void requireQuestionFrames(JsonNode source, ObjectNode questionSet) {
    JsonNode frames = source.path("public_frames");
    JsonNode questions = questionSet.path("questions");
    require(frames.isArray() && frames.size() == questions.size() + 1,
        "V5 question frame cardinality");
    requireFrame(frames.get(0), 1, "HEARING_INTAKE_QUESTION_LEAD",
        questionSet.path("question_set_id").asText(), source.path("lead_public_text").asText());
    for (int index = 0; index < questions.size(); index++) {
      JsonNode question = questions.get(index);
      requireFrame(frames.get(index + 1), index + 2, "SHARED_ISSUE_QUESTION",
          question.path("question_id").asText(), question.path("question_text").asText());
    }
  }

  private void requireSynthesisFrames(JsonNode source, ObjectNode transition) {
    JsonNode frames = source.path("public_frames");
    JsonNode issues = transition.path("issues");
    require(issues.isArray() && !issues.isEmpty() && issues.size() <= 10
            && frames.isArray() && frames.size() == issues.size() + 1,
        "V5 synthesis frame cardinality");
    requireFrame(frames.get(0), 1, "HEARING_INTAKE_SYNTHESIS_LEAD",
        transition.path("transition_set_id").asText(), source.path("lead_public_text").asText());
    Set<String> frameRefs = new HashSet<>();
    for (int index = 0; index < issues.size(); index++) {
      JsonNode issue = issues.get(index);
      if (!(issue instanceof ObjectNode issueObject)) {
        throw new IllegalArgumentException("target Hearing V5 issue state is invalid");
      }
      requireSelfHash(issueObject, null, "issue_state_hash");
      boolean rebinding = "REBIND".equals(issue.path("issue_kind").asText());
      String expectedType = rebinding ? "REBIND_ISSUE_SYNTHESIS" : "NEW_ISSUE_SYNTHESIS";
      JsonNode frame = frames.get(index + 1);
      require(frame.path("authority_ref").isTextual()
              && frameRefs.add(frame.path("authority_ref").asText()),
          "V5 synthesis frame identity");
      requireFrame(frames.get(index + 1), index + 2, expectedType,
          issue.path("issue_id").asText(), frames.get(index + 1).path("public_text").asText());
    }
  }

  private void requireFrame(
      JsonNode frame,
      int sequence,
      String type,
      String authorityRef,
      String publicText) {
    require(frame.isObject()
            && frame.path("frame_sequence").asInt(0) == sequence
            && type.equals(frame.path("frame_type").asText())
            && authorityRef.equals(frame.path("authority_ref").asText())
            && frame.path("public_text").isTextual()
            && !publicText.isBlank()
            && publicText.equals(frame.path("public_text").asText())
            && sha256(publicText).equals(frame.path("public_text_hash").asText()),
        "V5 public frame binding");
  }

  private void requireSelfHash(ObjectNode value, String schema, String field) {
    require((schema == null || schema.equals(value.path("schema_version").asText()))
            && value.path(field).asText().matches("[a-f0-9]{64}")
            && value.path(field).asText().equals(
                JdbcTargetHearingAgentStageInputFactory.pythonContentHash(mapper, value, field)),
        schema + " self hash");
  }

  private FormalPayload matrix(JsonNode source, String schema, String matrixField, Set<String> fields) {
    requireSource(source, schema, fields);
    if (!source.path(matrixField).isObject()) throw new IllegalArgumentException("matrix proposal is incomplete");
    return payload((ObjectNode) source.deepCopy(), false);
  }

  private FormalPayload decision(JsonNode source, String id, String sourceSchema, String formalSchema,
      String idField, JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding, Set<String> fields) {
    requireSource(source, sourceSchema, fields);
    String operation = operation(sourceSchema);
    requireDecisionSource(source, operation);
    var parents = binding.parents();
    if (parents.dossier() == null) throw new IllegalStateException("target Hearing dossier parent is absent");
    requireDecisionParents(source, operation, parents);
    ObjectNode result = mapper.createObjectNode();
    result.put("schema_version", formalSchema); result.put(idField, id);
    result.put("trial_dossier_id", parents.dossier().id()); result.put("trial_dossier_hash", parents.dossier().hash());
    if ("jury_review".equals(operation) || "judge_v2".equals(operation)) {
      if (parents.proposal() == null) throw new IllegalStateException("target Hearing proposal parent is absent");
      result.put("proposal_id", parents.proposal().id()); result.put("proposal_content_hash", parents.proposal().hash());
    }
    if ("judge_v2".equals(operation)) {
      if (parents.report() == null) throw new IllegalStateException("target Hearing report parent is absent");
      result.put("report_id", parents.report().id()); result.put("report_content_hash", parents.report().hash());
      result.set("draft", source.path("draft").deepCopy());
      result.set("review_responses", source.path("review_responses").deepCopy());
      result.put("public_text", source.path("public_message").asText());
    } else result.set("proposal", source.deepCopy());
    FormalPayload unsigned = payload(result, true);
    result.put("content_hash", unsigned.contentHash());
    return new FormalPayload(canonical(result), unsigned.contentHash(), canonical(result));
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
      if (parents.proposalSource() == null) {
        throw new IllegalStateException("target Hearing proposal source parent is absent");
      }
      String idField = "jury_review".equals(operation) ? "reviewed_proposal_id" : "parent_proposal_id";
      String hashField = "jury_review".equals(operation) ? "reviewed_proposal_hash" : "parent_proposal_hash";
      requireExactText(source, idField, parents.proposalSource().id());
      requireExactText(source, hashField, parents.proposalSource().hash());
    }
    if ("judge_v2".equals(operation)) {
      if (parents.reportSource() == null) {
        throw new IllegalStateException("target Hearing report source parent is absent");
      }
      requireExactText(source, "jury_review_id", parents.reportSource().id());
      requireExactText(source, "jury_review_hash", parents.reportSource().hash());
    }
  }

  private void requireDecisionSource(JsonNode source, String operation) {
    String idField = switch (operation) {
      case "judge_v1" -> "proposal_id";
      case "jury_review" -> "review_id";
      case "judge_v2" -> "judge_v2_id";
      default -> throw new IllegalArgumentException("unsupported target Hearing decision operation");
    };
    String hashField = switch (operation) {
      case "judge_v1" -> "proposal_hash";
      case "jury_review" -> "review_hash";
      case "judge_v2" -> "judge_v2_hash";
      default -> throw new IllegalArgumentException("unsupported target Hearing decision operation");
    };
    if (!(source instanceof ObjectNode object)
        || !object.path(idField).isTextual() || object.path(idField).asText().isBlank()
        || !object.path(hashField).asText().matches("[0-9a-f]{64}")
        || !object.path(hashField).asText().equals(
            JdbcTargetHearingAgentStageInputFactory.pythonContentHash(mapper, object, hashField))) {
      throw new IllegalArgumentException("target Hearing nested Python decision source is invalid");
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
    String json = canonical(value);
    return new FormalPayload(json, ContractJson.sha256Hex(hashValue), json);
  }
  private String canonical(JsonNode value) { return ContractJson.canonicalString(value); }
  private static ObjectNode object(JsonNode value, String label) {
    if (value instanceof ObjectNode object) return object;
    throw new IllegalArgumentException("target Hearing " + label + " is not an object");
  }
  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
  private static void require(boolean condition, String label) {
    if (!condition) throw new IllegalArgumentException("target Hearing " + label + " is invalid");
  }
  private static String operation(String schema) { return switch (schema) {
    case "hearing_jury_review.v1" -> "jury_review"; case "hearing_judge_v2.v2" -> "judge_v2"; default -> "judge_v1"; }; }
  record FormalPayload(String json, String contentHash, String stageOutputJson) {}
}
