package com.example.dispute.workflow.targete2e.rooms.hearing;

import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only assembly of the seven strict Python Hearing contracts from frozen Java parents. */
public final class JdbcTargetHearingAgentStageInputFactory {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final JdbcTargetHearingPreludeAuthority preludeAuthority;

  public JdbcTargetHearingAgentStageInputFactory(DataSource dataSource, ObjectMapper mapper) {
    DataSource exactDataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.jdbc = new JdbcTemplate(exactDataSource);
    this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
    this.preludeAuthority =
        new JdbcTargetHearingPreludeAuthority(exactDataSource, this.mapper);
  }

  public StageInput load(HearingRoomStart start, HearingWorkflowStage stage) {
    Objects.requireNonNull(start, "start");
    if (!stage.requiresAgentRun()) throw new IllegalArgumentException("Hearing stage has no agent operation");
    JdbcTargetHearingPreludeAuthority.Authority prelude =
        preludeAuthority.loadCommitted(start);
    ObjectNode preHearingMatrix = prelude.caseFactMatrix().deepCopy();
    ObjectNode evidence = prelude.evidenceDossier().deepCopy();
    requireFrozenEvidenceMatrix(evidence, preHearingMatrix, start);
    String sharedBarrierReceiptHash = sharedBarrierReceiptHash(start, evidence);
    ObjectNode hearing = object(one("""
        select jsonb_build_object('flow_instance_id', f.id, 'stage_id', s.id, 'stage_code', s.stage_code,
          'stage_sequence', s.stage_sequence, 'stage_input', s.input_json, 'stage_output', s.output_json,
          'trial_dossier', coalesce((select payload_json from hearing_trial_dossier d where d.case_id = f.case_id and d.flow_instance_id = f.id order by d.created_at desc, d.id desc limit 1), '{}'::jsonb),
          'actions', coalesce((select jsonb_agg(jsonb_build_object('id', a.id, 'action_type', a.action_type,
            'schema_version', a.schema_version, 'participant_id', a.participant_id,
            'participant_role', a.participant_role, 'submission_status', a.submission_status,
            'content_hash', a.content_hash, 'payload', a.payload_json) order by a.created_at, a.id)
             from hearing_flow_action a where a.flow_instance_id = f.id), '[]'::jsonb))
         from hearing_flow_instance f join hearing_flow_stage s on s.flow_instance_id = f.id
         where f.case_id = ? and f.id = ? and s.stage_code = ? and s.stage_sequence = ? for update of f, s
        """, "Hearing stage parents", start.caseId(), start.flowInstanceId(), stage.name(), stage.sequence()));
    String operation = stage.agentOperation();
    ObjectNode matrix = currentCaseFactMatrix(start, stage, preHearingMatrix);
    ObjectNode request = base(start, stage);
    if (operation.equals("intake_questions") || operation.equals("intake_synthesis")) {
      request.put("context_schema_version", "hearing_intake_context.v4");
      request.put("prelude_authority_hash", prelude.contentHash());
      ArrayNode sources = request.putArray("source_refs");
      sources.add(prelude.contentHash());
      sources.add(sharedBarrierReceiptHash);
    }
    switch (operation) {
      case "intake_questions" -> {
        request.set("case_fact_matrix", matrix.deepCopy());
        ArrayNode slots = request.putArray("question_slots");
        for (int index = 1; index <= 5; index++) {
          ObjectNode slot = slots.addObject();
          slot.put("question_slot_id", "QUESTION_SLOT_%02d".formatted(index));
          slot.putArray("target_roles").add("USER").add("MERCHANT");
        }
      }
      case "intake_synthesis" -> {
        request.set("case_fact_matrix", matrix.deepCopy());
        ObjectNode questionSet = requireQuestionSet(
            object(action(hearing, "QUESTION_SET")), matrix, prelude.contentHash(), start.caseId());
        request.set("question_set", questionSet.deepCopy());
        request.set("party_answer_bundles", partyAnswerBundles(hearing, questionSet));
        ArrayNode issueSlots = request.putArray("new_issue_slots");
        for (int index = 1; index <= 5; index++) {
          issueSlots.add("NEW_ISSUE_SLOT_%02d".formatted(index));
        }
        ArrayNode factSlots = request.putArray("new_fact_slots");
        for (int index = 1; index <= 20; index++) {
          factSlots.add("NEW_FACT_SLOT_%02d".formatted(index));
        }
      }
      case "evidence_requests" -> {
        request.set("case_fact_matrix", matrix.deepCopy()); request.set("evidence_dossier", evidence.deepCopy());
      }
      case "evidence_synthesis" -> {
        request.set("requests", proposal(action(hearing, "EVIDENCE_REQUEST_SET")).path("requests").deepCopy());
        request.set("party_batches", partyBatches(start, hearing)); request.set("case_fact_matrix", matrix.deepCopy());
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
    return new StageInput(operation, sharedBarrierReceiptHash, request);
  }

  private ObjectNode base(HearingRoomStart start, HearingWorkflowStage stage) {
    ObjectNode request = mapper.createObjectNode(); request.put("flow_schema_version", "hearing_flow.v2");
    request.put("case_id", start.caseId()); request.put("workflow_id", start.flowInstanceId());
    request.put("stage_code", stage.agentOperation().toUpperCase()); request.put("stage_sequence", stage.sequence()); return request;
  }

  private ObjectNode currentCaseFactMatrix(
      HearingRoomStart start, HearingWorkflowStage stage, ObjectNode preHearingMatrix) {
    if (stage != HearingWorkflowStage.EVIDENCE_REQUESTS_GENERATING
        && stage != HearingWorkflowStage.EVIDENCE_SYNTHESIZING) {
      return preHearingMatrix;
    }
    ObjectNode successor = object(
        proposal(completed(start, "INTAKE_SYNTHESIZING")).path("case_fact_matrix"));
    JsonNode parent = successor.path("parent_ref");
    require("case_fact_matrix.v2".equals(successor.path("schema_version").asText())
        && start.caseId().equals(successor.path("case_id").asText())
        && successor.path("matrix_id").isTextual()
        && !successor.path("matrix_id").asText().isBlank()
        && successor.path("matrix_version").asInt(0)
            == preHearingMatrix.path("matrix_version").asInt() + 1,
        "completed intake synthesis matrix");
    require(parent.isObject()
        && preHearingMatrix.path("matrix_id").asText().equals(parent.path("matrix_id").asText())
        && preHearingMatrix.path("matrix_version").asInt()
            == parent.path("matrix_version").asInt()
        && preHearingMatrix.path("content_hash").asText()
            .equals(parent.path("content_hash").asText()),
        "completed intake synthesis matrix parent");
    require(successor.path("content_hash").asText().matches("[a-f0-9]{64}")
        && successor.path("content_hash").asText()
            .equals(pythonContentHash(mapper, successor, "content_hash")),
        "completed intake synthesis matrix hash");
    return successor;
  }

  private ObjectNode requireFrozenEvidenceMatrix(
      ObjectNode evidence, ObjectNode caseMatrix, HearingRoomStart start) {
    ObjectNode matrix = requiredObject(evidence, "fact_evidence_matrix");
    require("FROZEN".equals(evidence.path("dossier_status").asText()), "frozen evidence dossier status");
    require(evidence.path("dossier_id").isTextual()
        && !evidence.path("dossier_id").asText().isBlank()
        && evidence.path("dossier_version").asInt(0) >= 1, "frozen evidence dossier identity");
    require("fact_evidence_matrix.v3".equals(matrix.path("schema_version").asText())
        && start.caseId().equals(matrix.path("case_id").asText())
        && "FROZEN".equals(matrix.path("matrix_status").asText())
        && matrix.path("matrix_id").isTextual()
        && !matrix.path("matrix_id").asText().isBlank()
        && matrix.path("matrix_version").asInt(0) >= 1, "frozen evidence matrix authority");
    require(caseMatrix.path("matrix_id").asText().equals(matrix.path("case_fact_matrix_id").asText())
        && caseMatrix.path("matrix_version").asInt()
            == matrix.path("case_fact_matrix_version").asInt()
        && caseMatrix.path("content_hash").asText()
            .equals(matrix.path("case_fact_matrix_hash").asText()), "frozen evidence matrix case binding");
    require(matrix.path("source_refs").isArray()
        && matrix.path("links").isArray()
        && matrix.path("fact_coverage").isArray(), "frozen evidence matrix structure");
    int version = matrix.path("matrix_version").asInt();
    JsonNode parent = matrix.path("parent_ref");
    require(version == 1
            ? parent.isNull()
            : parent.isObject()
                && parent.path("matrix_id").isTextual()
                && parent.path("matrix_version").asInt(0) + 1 == version
                && parent.path("content_hash").asText().matches("[a-f0-9]{64}"),
        "frozen evidence matrix parent");
    require(matrix.path("content_hash").asText().matches("[a-f0-9]{64}")
        && matrix.path("content_hash").asText()
            .equals(pythonContentHash(mapper, matrix, "content_hash")),
        "frozen evidence matrix hash");
    return matrix;
  }

  private String sharedBarrierReceiptHash(HearingRoomStart start, ObjectNode evidence) {
    ObjectNode receipt = object(one("""
        select jsonb_build_object(
          'receipt_hash', receipt.receipt_hash,
          'tenant_surrogate', receipt.tenant_surrogate,
          'case_id', receipt.case_id,
          'hearing_room_id', receipt.hearing_room_id,
          'dossier_id', receipt.dossier_id,
          'dossier_version', receipt.dossier_version)
          from target_e2e_evidence_terminal_receipt receipt
         where receipt.tenant_surrogate = ?
           and receipt.case_id = ?
           and receipt.hearing_room_id = ?
           and receipt.dossier_id = ?
           and receipt.dossier_version = ?
         for update
        """, "target Hearing terminal receipt", start.tenantSurrogate(), start.caseId(),
        start.roomId(), evidence.path("dossier_id").asText(),
        evidence.path("dossier_version").asInt()));
    String receiptHash = receipt.path("receipt_hash").asText();
    require(receiptHash.matches("[a-f0-9]{64}")
        && start.tenantSurrogate().equals(receipt.path("tenant_surrogate").asText())
        && start.caseId().equals(receipt.path("case_id").asText())
        && start.roomId().equals(receipt.path("hearing_room_id").asText())
        && evidence.path("dossier_id").asText().equals(receipt.path("dossier_id").asText())
        && evidence.path("dossier_version").asInt()
            == receipt.path("dossier_version").asInt(), "terminal receipt binding");
    return receiptHash;
  }

  static String pythonContentHash(ObjectMapper mapper, ObjectNode value, String field) {
    ObjectNode unsigned = value.deepCopy();
    unsigned.remove(field);
    return ContractJson.sha256Hex(unsigned);
  }

  private ObjectNode requireQuestionSet(
      ObjectNode value, ObjectNode matrix, String preludeHash, String caseId) {
    require("hearing_question_set.v4".equals(value.path("schema_version").asText())
            && caseId.equals(value.path("case_id").asText())
            && matrix.path("matrix_id").asText().equals(value.path("source_matrix_id").asText())
            && matrix.path("matrix_version").asInt() == value.path("source_matrix_version").asInt(0)
            && matrix.path("content_hash").asText().equals(value.path("source_matrix_hash").asText())
            && preludeHash.equals(value.path("prelude_authority_hash").asText())
            && value.path("question_set_id").isTextual()
            && !value.path("question_set_id").asText().isBlank()
            && value.path("question_set_hash").asText().matches("[a-f0-9]{64}")
            && value.path("question_set_hash").asText()
                .equals(pythonContentHash(mapper, value, "question_set_hash")),
        "V4 question set authority");
    JsonNode rawQuestions = value.path("questions");
    require(rawQuestions.isArray() && rawQuestions.size() >= 1 && rawQuestions.size() <= 5,
        "V4 question set size");
    Set<String> knownFacts = new LinkedHashSet<>();
    matrix.path("fact_rows").forEach(row -> knownFacts.add(row.path("fact_id").asText()));
    Set<String> questionIds = new LinkedHashSet<>();
    Set<String> issueIds = new LinkedHashSet<>();
    ObjectNode catalog = mapper.createObjectNode();
    catalog.put("schema_version", "hearing_formal_issue_catalog.v4");
    ArrayNode catalogIssues = catalog.putArray("issues");
    for (int index = 0; index < rawQuestions.size(); index++) {
      ObjectNode question = object(rawQuestions.get(index));
      String slot = "QUESTION_SLOT_%02d".formatted(index + 1);
      String questionId = question.path("question_id").asText();
      String issueId = question.path("issue_id").asText();
      JsonNode targetRoles = question.path("target_roles");
      JsonNode factIds = question.path("fact_ids");
      require(slot.equals(question.path("question_slot_id").asText())
              && !questionId.isBlank() && questionIds.add(questionId)
              && !issueId.isBlank() && issueIds.add(issueId)
              && question.path("issue_version").asInt(0) == 1
              && question.path("issue_state_hash").asText().matches("[a-f0-9]{64}")
              && targetRoles.isArray() && targetRoles.size() == 2
              && "USER".equals(targetRoles.get(0).asText())
              && "MERCHANT".equals(targetRoles.get(1).asText())
              && factIds.isArray() && !factIds.isEmpty()
              && question.path("question_text").isTextual()
              && !question.path("question_text").asText().isBlank()
              && question.path("issue_baseline").isObject()
              && question.path("party_prompts").isObject(),
          "V4 formal question");
      Set<String> localFacts = new LinkedHashSet<>();
      factIds.forEach(fact -> require(
          fact.isTextual() && knownFacts.contains(fact.asText()) && localFacts.add(fact.asText()),
          "V4 formal question fact authority"));
      ObjectNode catalogIssue = catalogIssues.addObject();
      catalogIssue.put("question_slot_id", slot);
      catalogIssue.put("question_id", questionId);
      catalogIssue.put("issue_id", issueId);
      catalogIssue.put("issue_version", 1);
      catalogIssue.put("issue_state_hash", question.path("issue_state_hash").asText());
      catalogIssue.set("issue_baseline", question.path("issue_baseline").deepCopy());
    }
    require(value.path("formal_issue_catalog_hash").asText().matches("[a-f0-9]{64}")
            && value.path("formal_issue_catalog_hash").asText().equals(pythonHash(catalog)),
        "V4 formal issue catalog hash");
    return value;
  }

  private ArrayNode partyAnswerBundles(ObjectNode hearing, ObjectNode questionSet) {
    List<JsonNode> selected = new ArrayList<>(actions(hearing, "ANSWER_BUNDLE"));
    selected.sort(Comparator.comparingInt(value -> switch (
        value.path("participant_role").asText()) {
      case "USER" -> 0;
      case "MERCHANT" -> 1;
      default -> 2;
    }));
    require(selected.size() == 2, "two V4 answer bundle parents");
    ArrayNode questions = (ArrayNode) questionSet.path("questions");
    ArrayNode result = mapper.createArrayNode();
    Set<String> participantIds = new LinkedHashSet<>();
    Set<String> bundleIds = new LinkedHashSet<>();
    for (int roleIndex = 0; roleIndex < selected.size(); roleIndex++) {
      ObjectNode action = object(selected.get(roleIndex));
      ObjectNode payload = object(action.path("payload"));
      String expectedRole = roleIndex == 0 ? "USER" : "MERCHANT";
      String bundleId = payload.path("answer_bundle_id").asText();
      String bundleHash = payload.path("answer_bundle_hash").asText();
      String participantId = payload.path("participant_id").asText();
      require("hearing_answer_bundle.v4".equals(action.path("schema_version").asText())
              && "hearing_answer_bundle.v4".equals(payload.path("schema_version").asText())
              && action.path("id").asText().equals(bundleId)
              && !bundleId.isBlank() && bundleIds.add(bundleId)
              && action.path("content_hash").asText().equals(bundleHash)
              && bundleHash.matches("[a-f0-9]{64}")
              && bundleHash.equals(pythonContentHash(mapper, payload, "answer_bundle_hash"))
              && expectedRole.equals(action.path("participant_role").asText())
              && expectedRole.equals(payload.path("participant_role").asText())
              && "SUBMITTED".equals(action.path("submission_status").asText())
              && "SUBMITTED".equals(payload.path("submission_status").asText())
              && action.path("participant_id").asText().equals(participantId)
              && !participantId.isBlank() && participantIds.add(participantId)
              && questionSet.path("question_set_id").asText()
                  .equals(payload.path("question_set_id").asText())
              && questionSet.path("question_set_hash").asText()
                  .equals(payload.path("question_set_hash").asText())
              && questionSet.path("formal_issue_catalog_hash").asText()
                  .equals(payload.path("formal_issue_catalog_hash").asText()),
          "V4 answer bundle authority");
      JsonNode answerUnits = payload.path("answer_units");
      require(answerUnits.isArray() && answerUnits.size() == questions.size(),
          "V4 answer bundle coverage");
      Set<String> unitIds = new LinkedHashSet<>();
      int totalCharacters = 0;
      for (int index = 0; index < questions.size(); index++) {
        JsonNode unit = answerUnits.get(index);
        JsonNode question = questions.get(index);
        String answerText = unit.path("answer_text").asText();
        require(unit.path("answer_unit_id").isTextual()
                && !unit.path("answer_unit_id").asText().isBlank()
                && unitIds.add(unit.path("answer_unit_id").asText())
                && question.path("question_id").asText().equals(unit.path("question_id").asText())
                && question.path("issue_id").asText().equals(unit.path("issue_id").asText())
                && unit.path("answer_text").isTextual()
                && !answerText.trim().isEmpty()
                && answerText.length() <= 2_000,
            "V4 answer unit binding");
        totalCharacters += answerText.length();
      }
      require(totalCharacters <= 10_000
              && payload.path("source_message_ids").isArray(),
          "V4 answer bundle budget");
      result.add(payload.deepCopy());
    }
    return result;
  }

  private String pythonHash(ObjectNode value) {
    return pythonContentHash(mapper, value, "__absent_hash_field__");
  }

  private ArrayNode partyBatches(HearingRoomStart start, ObjectNode hearing) {
    ArrayNode result = mapper.createArrayNode();
    Set<String> materializedEvidenceIds = new LinkedHashSet<>();
    for (JsonNode action : actions(hearing, "EVIDENCE_BATCH")) {
      ObjectNode payload = object(action.path("payload"));
      String participantId = payload.path("participant_id").asText();
      String participantRole = payload.path("participant_role").asText();
      String batchId = payload.path("batch_id").asText();
      JsonNode evidenceIds = payload.path("evidence_ids");
      JsonNode requestIds = payload.path("request_ids");
      require(!participantId.isBlank()
          && ("USER".equals(participantRole) || "MERCHANT".equals(participantRole))
          && !batchId.isBlank()
          && evidenceIds.isArray() && evidenceIds.size() <= 50
          && requestIds.isArray() && requestIds.size() <= 10,
          "evidence batch authority");
      boolean submitted = "SUBMITTED".equals(payload.path("submission_status").asText());
      require(submitted || "AUTO_TIMEOUT".equals(payload.path("submission_status").asText()),
          "evidence batch terminal status");
      ObjectNode item = result.addObject();
      item.put("participant_role", participantRole);
      item.put("terminal_status", submitted ? "COMPLETED" : "TIMED_OUT");
      item.put("submission_source", submitted ? "PARTY_ACTION" : "AUTO_TIMEOUT");
      item.put("batch_id", batchId);
      item.set("request_ids", requestIds.deepCopy());
      item.put("batch_note", payload.path("batch_note").asText(""));
      ArrayNode sourceRefs = item.putArray("source_refs");
      ArrayNode evidence = item.putArray("evidence");
      if (!submitted) {
        require(evidenceIds.isEmpty(), "timed-out evidence batch is empty");
        continue;
      }
      Set<String> batchEvidenceIds = new LinkedHashSet<>();
      for (JsonNode evidenceIdNode : evidenceIds) {
        String evidenceId = evidenceIdNode.asText();
        require(evidenceIdNode.isTextual() && !evidenceId.isBlank()
            && batchEvidenceIds.add(evidenceId), "supplemental evidence identity");
        sourceRefs.add(evidenceId);
        if (materializedEvidenceIds.add(evidenceId)) {
          evidence.add(supplementalEvidence(
              start, evidenceId, participantId, participantRole));
        }
      }
    }
    require(result.size() == 2, "two evidence batch parents");
    return result;
  }

  private ObjectNode supplementalEvidence(
      HearingRoomStart start,
      String evidenceId,
      String ignoredParticipantId,
      String ignoredParticipantRole) {
    return object(one("""
        select jsonb_build_object(
          'evidence_id', evidence.id,
          'evidence_type', evidence.evidence_type,
          'source_type', evidence.source_type,
          'original_filename', evidence.original_filename,
          'content_type', evidence.content_type,
          'file_hash', evidence.file_hash,
          'parsed_text', evidence.parsed_text,
          'claimed_fact', nullif(evidence.metadata_json ->> 'claimed_fact', ''),
          'metadata', case
            when jsonb_typeof(evidence.metadata_json) = 'object'
              then evidence.metadata_json
            else '{}'::jsonb
          end)
         from evidence_item evidence
         where evidence.id = ?
           and evidence.case_id = ?
         for update
        """, "bound supplemental Hearing evidence " + evidenceId,
        evidenceId, start.caseId()));
  }
  private JsonNode completed(HearingRoomStart start, String stageCode) { return one("select output_json from hearing_flow_stage where flow_instance_id = ? and case_id = ? and stage_code = ? and stage_status = 'COMPLETED' for update", "completed Hearing parent " + stageCode, start.flowInstanceId(), start.caseId(), stageCode); }
  private JsonNode action(ObjectNode hearing, String type) { List<JsonNode> values = actions(hearing, type); if (values.size() != 1) throw new IllegalStateException("Hearing action parent is absent or ambiguous: " + type); return values.getFirst().path("payload"); }
  private List<JsonNode> actions(ObjectNode hearing, String type) { return java.util.stream.StreamSupport.stream(hearing.path("actions").spliterator(), false).filter(a -> type.equals(a.path("action_type").asText())).toList(); }
  private JsonNode proposal(JsonNode value) { return value.path("proposal").isObject() ? value.path("proposal") : value; }
  private JsonNode one(String sql, String label, Object... arguments) { List<String> rows = jdbc.query(sql, (row, ignored) -> row.getString(1), arguments); if (rows.size() != 1) throw new IllegalStateException(label + " is absent or ambiguous"); try { return mapper.readTree(rows.getFirst()); } catch (Exception failure) { throw new IllegalStateException(label + " is invalid JSON", failure); } }
  private static ObjectNode object(JsonNode value) { if (value == null || !value.isObject()) throw new IllegalStateException("Hearing source is not an object"); return (ObjectNode) value; }
  private static ObjectNode requiredObject(ObjectNode source, String field) { return object(source.path(field)); }
  private static void require(boolean value, String label) { if (!value) throw new IllegalStateException("target Hearing " + label + " is invalid"); }
  public record StageInput(String operation, String sharedBarrierReceiptHash, ObjectNode request) {
    public StageInput {
      Objects.requireNonNull(operation);
      Objects.requireNonNull(sharedBarrierReceiptHash);
      Objects.requireNonNull(request);
      require(sharedBarrierReceiptHash.matches("[a-f0-9]{64}"), "shared barrier receipt hash");
    }
  }
}
