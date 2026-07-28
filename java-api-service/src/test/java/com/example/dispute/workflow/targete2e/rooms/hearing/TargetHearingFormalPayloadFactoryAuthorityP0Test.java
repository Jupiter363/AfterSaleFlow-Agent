package com.example.dispute.workflow.targete2e.rooms.hearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TargetHearingFormalPayloadFactoryAuthorityP0Test {
  private final ObjectMapper mapper = new ObjectMapper();
  private final TargetHearingFormalPayloadFactory factory = new TargetHearingFormalPayloadFactory(mapper);

  @Test
  void generatedActionsKeepTheirEstablishedTopLevelFormalShape() throws Exception {
    var questions = factory.project("intake_questions", intakeQuestions(), "question-set-1", binding());
    var questionPayload = mapper.readTree(questions.json());
    assertEquals("hearing_question_set.v1", questionPayload.path("schema_version").asText());
    assertEquals("question-set-1", questionPayload.path("question_set_id").asText());
    assertTrue(questionPayload.path("questions").isArray());
    assertFalse(questionPayload.has("proposal"));

    var requests = factory.project("evidence_requests", evidenceRequests(), "request-set-1", binding());
    var requestPayload = mapper.readTree(requests.json());
    assertEquals("hearing_evidence_request_set.v1", requestPayload.path("schema_version").asText());
    assertEquals("request-set-1", requestPayload.path("request_set_id").asText());
    assertTrue(requestPayload.path("requests").isArray());
    assertFalse(requestPayload.has("proposal"));
  }

  private ObjectNode intakeQuestions() {
    ObjectNode proposal = base("hearing_intake_questions.v1");
    proposal.put("speaker_role", "INTAKE_OFFICER");
    proposal.putArray("questions").addObject().put("question_id", "q-1").put("text", "State your claim.");
    proposal.put("public_message", "Please answer the questions.");
    return proposal;
  }

  private ObjectNode evidenceRequests() {
    ObjectNode proposal = base("hearing_evidence_requests.v1");
    proposal.putArray("requests").addObject().put("request_id", "r-1").put("text", "Provide the receipt.");
    proposal.put("public_message", "Please provide the requested evidence.");
    return proposal;
  }

  private ObjectNode base(String schemaVersion) {
    ObjectNode proposal = mapper.createObjectNode();
    proposal.put("schema_version", schemaVersion);
    proposal.put("case_id", "case-1");
    proposal.put("workflow_id", "flow-1");
    proposal.put("stage_sequence", 4);
    return proposal;
  }

  private static JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding() {
    var authority = new HearingAuthorityExpectation("tenant-1", "case-1", "flow-1", "epoch-1", 1,
        HearingWriterMode.TEMPORAL, HearingFlowStage.INTAKE_QUESTIONS_GENERATING, 4, 1, 1, 1);
    return new JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding(authority, "stage-4",
        HearingFlowStage.PARTY_ANSWERS_OPEN, 5, Instant.parse("2026-01-01T00:00:00Z"), "stage-5", "{}",
        "hearing-control", new JdbcTargetHearingFormalAuthorityLoader.Parents(null, null, null));
  }
}
