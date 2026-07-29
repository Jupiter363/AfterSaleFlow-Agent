package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only assembly of the seven strict Python Hearing contracts from frozen Java parents. */
public final class JdbcTargetHearingAgentStageInputFactory {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcTargetHearingAgentStageInputFactory(DataSource dataSource, ObjectMapper mapper) {
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
  }

  public StageInput load(HearingRoomStart start, HearingWorkflowStage stage) {
    Objects.requireNonNull(start, "start");
    if (!stage.requiresAgentRun()) throw new IllegalArgumentException("Hearing stage has no agent operation");
    ObjectNode intake = object(one("select dossier_json from case_intake_dossier where case_id = ? and room_type = 'INTAKE' for update", "case intake dossier", start.caseId()));
    ObjectNode matrix = object(intake.path("case_fact_matrix"));
    require("case_fact_matrix.v2".equals(matrix.path("schema_version").asText()) && start.caseId().equals(matrix.path("case_id").asText()), "case fact matrix");
    ObjectNode evidence = object(one("""
        select jsonb_build_object('dossier_id', id, 'dossier_version', dossier_version,
          'dossier_status', dossier_status, 'fact_evidence_matrix', matrix_summary_json -> 'fact_evidence_matrix',
          'evidence_summary', summary_json) from evidence_dossier
         where case_id = ? and dossier_status = 'FROZEN' and deleted_at is null
         order by dossier_version desc, id desc limit 2 for update
        """, "frozen evidence dossier", start.caseId()));
    ObjectNode hearing = object(one("""
        select jsonb_build_object('flow_instance_id', f.id, 'stage_id', s.id, 'stage_code', s.stage_code,
          'stage_sequence', s.stage_sequence, 'stage_input', s.input_json, 'stage_output', s.output_json,
          'trial_dossier', coalesce((select payload_json from hearing_trial_dossier d where d.case_id = f.case_id and d.flow_instance_id = f.id order by d.created_at desc, d.id desc limit 1), '{}'::jsonb),
          'actions', coalesce((select jsonb_agg(jsonb_build_object('id', a.id, 'action_type', a.action_type,
            'content_hash', a.content_hash, 'payload', a.payload_json) order by a.created_at, a.id)
             from hearing_flow_action a where a.flow_instance_id = f.id), '[]'::jsonb))
         from hearing_flow_instance f join hearing_flow_stage s on s.flow_instance_id = f.id
         where f.case_id = ? and f.id = ? and s.stage_code = ? and s.stage_sequence = ? for update of f, s
        """, "Hearing stage parents", start.caseId(), start.flowInstanceId(), stage.name(), stage.sequence()));
    String operation = stage.agentOperation();
    ObjectNode request = base(start, stage);
    switch (operation) {
      case "intake_questions" -> request.set("case_fact_matrix", matrix.deepCopy());
      case "intake_synthesis" -> {
        request.set("questions", proposal(action(hearing, "QUESTION_SET")).path("questions").deepCopy());
        request.set("party_submissions", partySubmissions(hearing, "ANSWER_BUNDLE"));
        request.set("case_fact_matrix", matrix.deepCopy());
      }
      case "evidence_requests" -> {
        request.set("case_fact_matrix", matrix.deepCopy()); request.set("evidence_dossier", evidence.deepCopy());
      }
      case "evidence_synthesis" -> {
        request.set("requests", proposal(action(hearing, "EVIDENCE_REQUEST_SET")).path("requests").deepCopy());
        request.set("party_batches", partyBatches(hearing)); request.set("case_fact_matrix", matrix.deepCopy());
        request.set("prior_fact_evidence_matrix", evidence.path("fact_evidence_matrix").deepCopy());
      }
      case "judge_v1" -> request.set("trial_dossier", requiredObject(hearing, "trial_dossier").deepCopy());
      case "jury_review" -> {
        request.set("trial_dossier", requiredObject(hearing, "trial_dossier").deepCopy());
        request.set("judge_v1", proposal(completed(start, "JUDGE_V1_GENERATING")).deepCopy());
      }
      case "judge_v2" -> {
        request.set("trial_dossier", requiredObject(hearing, "trial_dossier").deepCopy());
        request.set("judge_v1", proposal(completed(start, "JUDGE_V1_GENERATING")).deepCopy());
        request.set("jury_review", proposal(completed(start, "JURY_REVIEWING")).deepCopy());
      }
      default -> throw new IllegalArgumentException("unsupported Hearing operation");
    }
    ObjectNode fixture = fixture(operation, start, stage, matrix, evidence, hearing, request);
    return new StageInput(operation, request, fixture, mapper.createObjectNode());
  }

  private ObjectNode base(HearingRoomStart start, HearingWorkflowStage stage) {
    ObjectNode request = mapper.createObjectNode(); request.put("flow_schema_version", "hearing_flow.v2");
    request.put("case_id", start.caseId()); request.put("workflow_id", start.flowInstanceId());
    request.put("stage_code", stage.agentOperation().toUpperCase()); request.put("stage_sequence", stage.sequence()); return request;
  }

  private ObjectNode fixture(String operation, HearingRoomStart start, HearingWorkflowStage stage, ObjectNode matrix,
      ObjectNode evidence, ObjectNode hearing, ObjectNode request) {
    return switch (operation) {
      case "intake_questions" -> questions(start, stage, matrix);
      case "intake_synthesis" -> synthesis(start, stage, matrix);
      case "evidence_requests" -> requests(start, stage);
      case "evidence_synthesis" -> evidenceSynthesis(start, stage, evidence);
      case "judge_v1" -> judgeV1(start, stage, requiredObject(hearing, "trial_dossier"));
      case "jury_review" -> jury(start, stage, requiredObject(hearing, "trial_dossier"), proposal(completed(start, "JUDGE_V1_GENERATING")));
      case "judge_v2" -> judgeV2(start, stage, requiredObject(hearing, "trial_dossier"), proposal(completed(start, "JUDGE_V1_GENERATING")), proposal(completed(start, "JURY_REVIEWING")));
      default -> throw new IllegalArgumentException("unsupported Hearing operation");
    };
  }

  private ObjectNode questions(HearingRoomStart start, HearingWorkflowStage stage, ObjectNode matrix) {
    String factId = matrix.path("fact_rows").path(0).path("fact_id").asText(null); require(factId != null, "question fact parent");
    String id = id("HEARING_ISSUE", start, stage, matrix.path("content_hash").asText()); ObjectNode q = mapper.createObjectNode();
    q.put("question_id", id); q.put("issue_id", id); q.putArray("target_roles").add("USER").add("MERCHANT"); q.putArray("fact_ids").add(factId);
    q.put("question_text", "Synthetic clarification for " + factId); q.put("issue_statement", "Synthetic clarification for " + factId);
    ObjectNode prompts = q.putObject("party_prompts"); prompts.put("USER", "Provide your account."); prompts.put("MERCHANT", "Provide your account.");
    ObjectNode value = result("hearing_intake_questions.v1", start, stage); value.put("speaker_role", "INTAKE_OFFICER"); value.putArray("questions").add(q); value.put("public_message", "Synthetic intake question."); return value;
  }
  private ObjectNode synthesis(HearingRoomStart start, HearingWorkflowStage stage, ObjectNode matrix) {
    ObjectNode value = result("hearing_intake_synthesis.v1", start, stage); value.set("case_fact_matrix", matrix.deepCopy()); value.putArray("dispute_points"); value.putArray("issue_mappings"); value.put("public_message", "Synthetic intake synthesis."); return value;
  }
  private ObjectNode requests(HearingRoomStart start, HearingWorkflowStage stage) {
    ObjectNode value = result("hearing_evidence_requests.v1", start, stage); value.putArray("requests"); value.put("public_message", "Synthetic evidence request set."); return value;
  }
  private ObjectNode evidenceSynthesis(HearingRoomStart start, HearingWorkflowStage stage, ObjectNode evidence) {
    ObjectNode value = result("hearing_evidence_synthesis.v1", start, stage); value.set("fact_evidence_matrix", requiredObject(evidence, "fact_evidence_matrix").deepCopy()); value.set("evidence_summary", evidence.path("evidence_summary").deepCopy()); value.putArray("evidence_gaps"); value.put("public_message", "Synthetic evidence synthesis."); return value;
  }
  private ObjectNode judgeV1(HearingRoomStart start, HearingWorkflowStage stage, ObjectNode dossier) {
    ObjectNode value = result("hearing_judge_v1.v1", start, stage); value.put("trial_dossier_id", dossier.path("trial_dossier_id").asText()); value.put("trial_dossier_hash", dossier.path("content_hash").asText()); value.put("proposal_id", id("JUDGE_PROPOSAL", start, stage, dossier.path("content_hash").asText())); value.put("proposal_hash", "0".repeat(64)); value.put("proposal_text", "Synthetic advisory proposal."); value.put("recommended_decision", "HUMAN_REVIEW"); value.put("reasoning_summary", "Synthetic proposal bound to the frozen dossier."); value.putArray("review_focus").add("Human review required."); value.put("public_message", "Synthetic advisory proposal."); value.put("is_final_decision", false); hash(value, "proposal_hash"); return value;
  }
  private ObjectNode jury(HearingRoomStart start, HearingWorkflowStage stage, ObjectNode dossier, JsonNode judge) {
    ObjectNode value = result("hearing_jury_review.v1", start, stage); value.put("trial_dossier_id", dossier.path("trial_dossier_id").asText()); value.put("trial_dossier_hash", dossier.path("content_hash").asText()); value.put("review_id", id("JURY_REVIEW", start, stage, judge.path("proposal_hash").asText())); value.put("review_hash", "0".repeat(64)); value.put("reviewed_proposal_id", judge.path("proposal_id").asText()); value.put("reviewed_proposal_hash", judge.path("proposal_hash").asText()); ArrayNode findings = value.putArray("findings"); for (String dimension : List.of("FACT_COMPLETENESS", "EVIDENCE_CONSISTENCY", "RULE_APPLICABILITY", "PROCEDURAL_FAIRNESS", "REMEDY_FEASIBILITY", "RISK_AND_OMISSIONS")) { ObjectNode finding = findings.addObject(); finding.put("dimension", dimension); finding.put("severity", "NONE"); finding.put("assessment", "Synthetic review finding."); finding.putArray("basis").add("Frozen parent"); finding.put("requires_revision", false); } value.putArray("mandatory_revisions"); value.put("public_message", "Synthetic jury review."); value.put("approval_performed", false); value.put("execution_triggered", false); value.put("is_final_decision", false); hash(value, "review_hash"); return value;
  }
  private ObjectNode judgeV2(HearingRoomStart start, HearingWorkflowStage stage, ObjectNode dossier, JsonNode judge, JsonNode jury) {
    String fact = dossier.path("case_fact_matrix").path("fact_rows").path(0).path("fact_id").asText(null); require(fact != null, "judge fact parent"); JsonNode rule = dossier.path("policy_rules").path(0); require(rule.isObject(), "judge policy parent");
    ObjectNode value = result("hearing_judge_v2.v1", start, stage); value.put("trial_dossier_id", dossier.path("trial_dossier_id").asText()); value.put("trial_dossier_hash", dossier.path("content_hash").asText()); value.put("judge_v2_id", id("JUDGE_V2", start, stage, judge.path("proposal_hash").asText(), jury.path("review_hash").asText())); value.put("judge_v2_hash", "0".repeat(64)); value.put("parent_proposal_id", judge.path("proposal_id").asText()); value.put("parent_proposal_hash", judge.path("proposal_hash").asText()); value.put("jury_review_id", jury.path("review_id").asText()); value.put("jury_review_hash", jury.path("review_hash").asText()); ObjectNode draft = value.putObject("draft"); draft.put("recommended_decision", "HUMAN_REVIEW"); draft.put("confidence", 0.0); draft.put("draft_text", "Synthetic advisory draft."); ObjectNode finding = draft.putArray("fact_findings").addObject(); finding.put("fact_id", fact); finding.put("finding", "Synthetic finding."); finding.putArray("evidence_ids"); finding.put("evidence_gap", "No synthetic evidence assessment."); finding.put("confidence", 0.0); ObjectNode assessment = draft.putArray("evidence_assessment").addObject(); assessment.put("assessment_type", "EVIDENCE_GAP"); assessment.putNull("evidence_id"); assessment.putArray("fact_ids").add(fact); assessment.put("assessment", "Synthetic evidence gap."); assessment.put("weight", "NONE"); assessment.put("confidence", 0.0); assessment.putArray("limitations"); ObjectNode policy = draft.putArray("policy_application").addObject(); policy.put("rule_code", rule.path("rule_code").asText()); policy.put("rule_version", rule.path("rule_version").asInt()); policy.put("rule_name", rule.path("rule_name").asText()); policy.putArray("fact_ids").add(fact); policy.put("applicable", false); policy.put("rationale", "Synthetic policy review."); policy.putArray("limitations"); draft.putArray("reviewer_attention").add("Human review required."); draft.put("draft_status", "PENDING_HUMAN_REVIEW"); draft.put("requires_human_review", true); draft.put("is_final_decision", false); value.put("public_message", "Synthetic advisory draft."); hash(value, "judge_v2_hash"); return value;
  }

  private ObjectNode result(String schema, HearingRoomStart start, HearingWorkflowStage stage) { ObjectNode value = mapper.createObjectNode(); value.put("schema_version", schema); value.put("case_id", start.caseId()); value.put("workflow_id", start.flowInstanceId()); value.put("stage_sequence", stage.sequence()); return value; }
  private void hash(ObjectNode value, String field) {
    value.put(field, pythonContentHash(mapper, value, field));
  }

  static String pythonContentHash(ObjectMapper mapper, ObjectNode value, String field) {
    ObjectNode unsigned = value.deepCopy();
    unsigned.remove(field);
    try {
      byte[] canonical = mapper.writeValueAsBytes(sortObjectMembers(mapper, unsigned));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (Exception failure) {
      throw new IllegalStateException("target Hearing fixture self-hash failed", failure);
    }
  }

  /** Mirrors the frozen Python Hearing content_hash contract (sorted json.dumps, not RFC 8785). */
  private static JsonNode sortObjectMembers(ObjectMapper mapper, JsonNode value) {
    if (value.isObject()) {
      ObjectNode sorted = mapper.createObjectNode();
      ArrayList<String> fields = new ArrayList<>();
      value.fieldNames().forEachRemaining(fields::add);
      fields.sort(String::compareTo);
      fields.forEach(field -> sorted.set(field, sortObjectMembers(mapper, value.get(field))));
      return sorted;
    }
    if (value.isArray()) {
      ArrayNode sorted = mapper.createArrayNode();
      value.forEach(item -> sorted.add(sortObjectMembers(mapper, item)));
      return sorted;
    }
    return value.deepCopy();
  }
  private ArrayNode partySubmissions(ObjectNode hearing, String actionType) { ArrayNode result = mapper.createArrayNode(); for (JsonNode action : actions(hearing, actionType)) { JsonNode p = action.path("payload"); ObjectNode item = result.addObject(); item.put("participant_id", p.path("participant_id").asText()); item.put("participant_role", p.path("participant_role").asText()); boolean submitted = "SUBMITTED".equals(p.path("submission_status").asText()); item.put("terminal_status", submitted ? "COMPLETED" : "TIMED_OUT"); item.put("submission_source", submitted ? "PARTY_ACTION" : "AUTO_TIMEOUT"); item.set("source_refs", p.path("source_message_ids").deepCopy()); item.set("submission", p.deepCopy()); } require(result.size() == 2, "two answer parents"); return result; }
  private ArrayNode partyBatches(ObjectNode hearing) { ArrayNode result = mapper.createArrayNode(); for (JsonNode action : actions(hearing, "EVIDENCE_BATCH")) { JsonNode p = action.path("payload"); ObjectNode item = result.addObject(); item.put("participant_role", p.path("participant_role").asText()); boolean submitted = "SUBMITTED".equals(p.path("submission_status").asText()); item.put("terminal_status", submitted ? "COMPLETED" : "TIMED_OUT"); item.put("submission_source", submitted ? "PARTY_ACTION" : "AUTO_TIMEOUT"); item.put("batch_id", p.path("batch_id").asText()); item.set("request_ids", p.path("request_ids").deepCopy()); item.put("batch_note", p.path("batch_note").asText("")); item.set("source_refs", p.path("evidence_ids").deepCopy()); item.putArray("evidence"); } require(result.size() == 2, "two evidence batch parents"); return result; }
  private JsonNode completed(HearingRoomStart start, String stageCode) { return one("select output_json from hearing_flow_stage where flow_instance_id = ? and case_id = ? and stage_code = ? and stage_status = 'COMPLETED' for update", "completed Hearing parent " + stageCode, start.flowInstanceId(), start.caseId(), stageCode); }
  private JsonNode action(ObjectNode hearing, String type) { List<JsonNode> values = actions(hearing, type); if (values.size() != 1) throw new IllegalStateException("Hearing action parent is absent or ambiguous: " + type); return values.getFirst().path("payload"); }
  private List<JsonNode> actions(ObjectNode hearing, String type) { return java.util.stream.StreamSupport.stream(hearing.path("actions").spliterator(), false).filter(a -> type.equals(a.path("action_type").asText())).toList(); }
  private JsonNode proposal(JsonNode value) { return value.path("proposal").isObject() ? value.path("proposal") : value; }
  private JsonNode one(String sql, String label, Object... arguments) { List<String> rows = jdbc.query(sql, (row, ignored) -> row.getString(1), arguments); if (rows.size() != 1) throw new IllegalStateException(label + " is absent or ambiguous"); try { return mapper.readTree(rows.getFirst()); } catch (Exception failure) { throw new IllegalStateException(label + " is invalid JSON", failure); } }
  private static ObjectNode object(JsonNode value) { if (value == null || !value.isObject()) throw new IllegalStateException("Hearing source is not an object"); return (ObjectNode) value; }
  private static ObjectNode requiredObject(ObjectNode source, String field) { return object(source.path(field)); }
  private static String id(String prefix, HearingRoomStart start, HearingWorkflowStage stage, String... parents) { return prefix + '_' + UUID.nameUUIDFromBytes((start.caseId() + ':' + start.roomEpoch() + ':' + stage.sequence() + ':' + String.join(":", parents)).getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""); }
  private static void require(boolean value, String label) { if (!value) throw new IllegalStateException("target Hearing " + label + " is invalid"); }
  public record StageInput(String operation, ObjectNode request, ObjectNode fixtureProposal, ObjectNode fixtureWorkResults) { public StageInput { Objects.requireNonNull(operation); Objects.requireNonNull(request); Objects.requireNonNull(fixtureProposal); Objects.requireNonNull(fixtureWorkResults); } }
}
