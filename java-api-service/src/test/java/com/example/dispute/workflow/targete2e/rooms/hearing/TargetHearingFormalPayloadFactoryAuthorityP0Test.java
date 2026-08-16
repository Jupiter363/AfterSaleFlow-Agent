package com.example.dispute.workflow.targete2e.rooms.hearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetHearingFormalPayloadFactoryAuthorityP0Test {
  private final ObjectMapper mapper = new ObjectMapper();
  private final TargetHearingFormalPayloadFactory factory = new TargetHearingFormalPayloadFactory(mapper);

  @Test
  void generatedActionsKeepTheirEstablishedTopLevelFormalShape() throws Exception {
    ObjectNode questionMatrix = caseMatrix("matrix-1", 1, null);
    ObjectNode requestMatrix = caseMatrix("matrix-2", 2, questionMatrix);
    var questions = factory.project("intake_questions", intakeQuestions(), "question-set-1",
        binding(HearingFlowStage.INTAKE_QUESTIONS_GENERATING, questionMatrix));
    var questionPayload = mapper.readTree(questions.json());
    assertEquals("hearing_question_set.v1", questionPayload.path("schema_version").asText());
    assertEquals("question-set-1", questionPayload.path("question_set_id").asText());
    assertTrue(questionPayload.path("questions").isArray());
    assertFalse(questionPayload.has("proposal"));

    var requests = factory.project("evidence_requests", evidenceRequests(), "request-set-1",
        binding(HearingFlowStage.EVIDENCE_REQUESTS_GENERATING, requestMatrix));
    var requestPayload = mapper.readTree(requests.json());
    assertEquals("hearing_evidence_request_set.v1", requestPayload.path("schema_version").asText());
    assertEquals("request-set-1", requestPayload.path("request_set_id").asText());
    assertTrue(requestPayload.path("requests").isArray());
    assertFalse(requestPayload.has("proposal"));
  }

  @Test
  void generatedSetsBindExactMatrixAuthorityIntoTrialDossier() throws Exception {
    ObjectNode questionMatrix = caseMatrix("matrix-1", 1, null);
    ObjectNode successorMatrix = caseMatrix("matrix-2", 2, questionMatrix);
    ObjectNode evidenceMatrix = evidenceMatrix(successorMatrix);
    assertEquals("c8d6e4f9fbc01d0c2602ebf4f71293c2c9c69fa26e5ed9bc1079713211fecd24",
        questionMatrix.path("content_hash").asText());
    assertEquals("afeb5e3c313dcceb31975f4ce40d679b1c253fb2c64fe8af627635012c03cd2b",
        successorMatrix.path("content_hash").asText());
    assertEquals("823ff3b72687ebe007dcf10bf17a280cbdc23708d850d0af89cfb97a1e30c4bc",
        evidenceMatrix.path("content_hash").asText());

    var questionProjection = factory.project(
        "intake_questions", intakeQuestions(), "question-set-1",
        binding(HearingFlowStage.INTAKE_QUESTIONS_GENERATING, questionMatrix));
    var requestProjection = factory.project(
        "evidence_requests", evidenceRequests(), "request-set-1",
        binding(HearingFlowStage.EVIDENCE_REQUESTS_GENERATING, successorMatrix));
    ObjectNode questionSet = (ObjectNode) mapper.readTree(questionProjection.json());
    ObjectNode requestSet = (ObjectNode) mapper.readTree(requestProjection.json());

    assertEquals(successorMatrix.path("matrix_version").asInt(),
        requestSet.path("case_matrix_version").asInt(-1));
    assertEquals(successorMatrix.path("content_hash").asText(),
        requestSet.path("case_matrix_hash").asText());
    assertEquals(questionMatrix.path("matrix_version").asInt(),
        questionSet.path("case_matrix_version").asInt(-1));
    assertEquals(questionMatrix.path("content_hash").asText(),
        questionSet.path("case_matrix_hash").asText());

    var first = TargetHearingTrialDossier.build(mapper, "dossier-1", "case-1",
        Instant.parse("2026-01-01T00:00:00Z"), successorMatrix, evidenceMatrix,
        questionSet, requestSet, answers(), evidenceBatches(), policyRules());
    var replay = TargetHearingTrialDossier.build(mapper, "dossier-1", "case-1",
        Instant.parse("2026-01-01T00:00:00Z"), successorMatrix, evidenceMatrix,
        questionSet, requestSet, answers(), evidenceBatches(), policyRules());
    assertEquals(first, replay);
    assertEquals("a4865300e1eefdd08d4a6794fc41940272725e724948d7671e07f3fb94d8dd9b",
        first.hash());

    ObjectNode dossier = (ObjectNode) mapper.readTree(first.json());
    ObjectNode unsignedDossier = dossier.deepCopy();
    unsignedDossier.remove("content_hash");
    assertEquals(first.hash(), dossier.path("content_hash").asText());
    assertNotEquals(ContractJson.sha256Hex(unsignedDossier), first.hash());

    ObjectNode badQuestionVersion = questionSet.deepCopy();
    badQuestionVersion.put("case_matrix_version", 2);
    assertThrows(IllegalStateException.class, () -> TargetHearingTrialDossier.build(mapper,
        "dossier-1", "case-1", Instant.parse("2026-01-01T00:00:00Z"), successorMatrix,
        evidenceMatrix, badQuestionVersion, requestSet, answers(), evidenceBatches(), policyRules()));

    ObjectNode badRequestHash = requestSet.deepCopy();
    badRequestHash.put("case_matrix_hash", "f".repeat(64));
    assertThrows(IllegalStateException.class, () -> TargetHearingTrialDossier.build(mapper,
        "dossier-1", "case-1", Instant.parse("2026-01-01T00:00:00Z"), successorMatrix,
        evidenceMatrix, questionSet, badRequestHash, answers(), evidenceBatches(), policyRules()));

    ObjectNode jcsMatrix = successorMatrix.deepCopy();
    jcsMatrix.remove("content_hash");
    String jcsHash = ContractJson.sha256Hex(jcsMatrix);
    assertNotEquals(successorMatrix.path("content_hash").asText(), jcsHash);
    jcsMatrix.put("content_hash", jcsHash);
    ObjectNode jcsEvidenceMatrix = evidenceMatrix.deepCopy();
    jcsEvidenceMatrix.put("case_fact_matrix_hash", jcsHash);
    pythonHash(jcsEvidenceMatrix);
    ObjectNode jcsRequestSet = requestSet.deepCopy();
    jcsRequestSet.put("case_matrix_hash", jcsHash);
    assertThrows(IllegalStateException.class, () -> TargetHearingTrialDossier.build(mapper,
        "dossier-1", "case-1", Instant.parse("2026-01-01T00:00:00Z"), jcsMatrix,
        jcsEvidenceMatrix, questionSet, jcsRequestSet, answers(), evidenceBatches(), policyRules()));
  }

  @Test
  void decisionChainMapsVerifiedNestedPythonParentsToOuterFormalArtifacts() throws Exception {
    var dossier = new JdbcTargetHearingFormalAuthorityLoader.Ref(
        "dossier-formal-1", "d".repeat(64));
    ObjectNode judgeV1 = judgeV1(dossier);
    var judgeV1Formal = factory.project("judge_v1", judgeV1, "formal-proposal-1",
        decisionBinding(HearingFlowStage.JUDGE_V1_GENERATING,
            new JdbcTargetHearingFormalAuthorityLoader.Parents(dossier, null, null)));
    ObjectNode judgeV1Wrapper = (ObjectNode) mapper.readTree(judgeV1Formal.json());
    var judgeV1Artifact = JdbcTargetHearingFormalAuthorityLoader.verifiedArtifact(
        mapper, decisionAuthority(HearingFlowStage.JURY_REVIEWING), "JUDGE_PROPOSAL",
        judgeV1Wrapper.path("proposal_id").asText(), judgeV1Formal.contentHash(),
        judgeV1Formal.json(), "JUDGE_V1_GENERATING", 11, "PRESIDING_JUDGE");
    var juryParents = JdbcTargetHearingFormalAuthorityLoader.mapParents(
        dossier, List.of(judgeV1Artifact));

    ObjectNode jury = juryReview(dossier, judgeV1);
    var juryFormal = factory.project("jury_review", jury, "formal-jury-1",
        decisionBinding(HearingFlowStage.JURY_REVIEWING, juryParents));
    assertEquals(juryFormal, factory.project("jury_review", jury, "formal-jury-1",
        decisionBinding(HearingFlowStage.JURY_REVIEWING, juryParents)));

    ObjectNode juryWrapper = (ObjectNode) mapper.readTree(juryFormal.json());
    assertEquals(juryParents.proposal().id(), juryWrapper.path("proposal_id").asText());
    assertEquals(juryParents.proposal().hash(), juryWrapper.path("proposal_content_hash").asText());
    assertEquals(judgeV1.path("proposal_id").asText(), jury.path("reviewed_proposal_id").asText());
    assertEquals(judgeV1.path("proposal_hash").asText(), jury.path("reviewed_proposal_hash").asText());

    var juryArtifact = JdbcTargetHearingFormalAuthorityLoader.verifiedArtifact(
        mapper, decisionAuthority(HearingFlowStage.JUDGE_V2_GENERATING), "JURY_REVIEW_REPORT",
        juryWrapper.path("report_id").asText(), juryFormal.contentHash(), juryFormal.json(),
        "JURY_REVIEWING", 12, "JURY_PANEL");
    var judgeV2Parents = JdbcTargetHearingFormalAuthorityLoader.mapParents(
        dossier, List.of(judgeV1Artifact, juryArtifact));
    ObjectNode judgeV2 = judgeV2(dossier, judgeV1, jury);
    var judgeV2Formal = factory.project("judge_v2", judgeV2, "formal-judge-v2-1",
        decisionBinding(HearingFlowStage.JUDGE_V2_GENERATING, judgeV2Parents));
    var judgeV2Replay = factory.project("judge_v2", judgeV2, "formal-judge-v2-1",
        decisionBinding(HearingFlowStage.JUDGE_V2_GENERATING, judgeV2Parents));
    assertEquals(judgeV2Formal, judgeV2Replay);
    ObjectNode judgeV2Wrapper = (ObjectNode) mapper.readTree(judgeV2Formal.json());
    assertEquals(judgeV2Parents.proposal().id(), judgeV2Wrapper.path("proposal_id").asText());
    assertEquals(judgeV2Parents.proposal().hash(),
        judgeV2Wrapper.path("proposal_content_hash").asText());
    assertEquals(judgeV2Parents.report().id(), judgeV2Wrapper.path("report_id").asText());
    assertEquals(judgeV2Parents.report().hash(), judgeV2Wrapper.path("report_content_hash").asText());

    assertEquals(judgeV1.path("proposal_hash").asText(),
        JdbcTargetHearingAgentStageInputFactory.pythonContentHash(mapper, judgeV1, "proposal_hash"));
    assertEquals(jury.path("review_hash").asText(),
        JdbcTargetHearingAgentStageInputFactory.pythonContentHash(mapper, jury, "review_hash"));
    assertEquals(judgeV2.path("judge_v2_hash").asText(),
        JdbcTargetHearingAgentStageInputFactory.pythonContentHash(mapper, judgeV2, "judge_v2_hash"));
    ObjectNode unsignedJudgeV2 = judgeV2Wrapper.deepCopy();
    unsignedJudgeV2.remove("content_hash");
    assertEquals(judgeV2Formal.contentHash(), ContractJson.sha256Hex(unsignedJudgeV2));
    assertNotEquals(juryParents.proposal().id(), juryParents.proposalSource().id());
    assertNotEquals(juryParents.proposal().hash(), juryParents.proposalSource().hash());

    ObjectNode nestedIdDrift = jury.deepCopy();
    nestedIdDrift.put("reviewed_proposal_id", "python-proposal-drift");
    pythonHash(nestedIdDrift, "review_hash");
    assertThrows(IllegalArgumentException.class, () -> factory.project(
        "jury_review", nestedIdDrift, "formal-jury-drift",
        decisionBinding(HearingFlowStage.JURY_REVIEWING, juryParents)));
    ObjectNode nestedHashDrift = jury.deepCopy();
    nestedHashDrift.put("reviewed_proposal_hash", "f".repeat(64));
    pythonHash(nestedHashDrift, "review_hash");
    assertThrows(IllegalArgumentException.class, () -> factory.project(
        "jury_review", nestedHashDrift, "formal-jury-drift",
        decisionBinding(HearingFlowStage.JURY_REVIEWING, juryParents)));

    assertThrows(IllegalStateException.class, () ->
        JdbcTargetHearingFormalAuthorityLoader.verifiedArtifact(
            mapper, decisionAuthority(HearingFlowStage.JURY_REVIEWING), "JUDGE_PROPOSAL",
            "formal-proposal-drift", judgeV1Formal.contentHash(), judgeV1Formal.json(),
            "JUDGE_V1_GENERATING", 11, "PRESIDING_JUDGE"));
    assertThrows(IllegalStateException.class, () ->
        JdbcTargetHearingFormalAuthorityLoader.verifiedArtifact(
            mapper, decisionAuthority(HearingFlowStage.JURY_REVIEWING), "JUDGE_PROPOSAL",
            judgeV1Wrapper.path("proposal_id").asText(), "f".repeat(64), judgeV1Formal.json(),
            "JUDGE_V1_GENERATING", 11, "PRESIDING_JUDGE"));
    ObjectNode wrapperDrift = judgeV1Wrapper.deepCopy();
    wrapperDrift.withObject("proposal").put("public_message", "drifted wrapper source");
    assertThrows(IllegalStateException.class, () ->
        JdbcTargetHearingFormalAuthorityLoader.verifiedArtifact(
            mapper, decisionAuthority(HearingFlowStage.JURY_REVIEWING), "JUDGE_PROPOSAL",
            judgeV1Wrapper.path("proposal_id").asText(), judgeV1Formal.contentHash(),
            mapper.writeValueAsString(wrapperDrift), "JUDGE_V1_GENERATING", 11, "PRESIDING_JUDGE"));
    assertThrows(IllegalStateException.class, () ->
        JdbcTargetHearingFormalAuthorityLoader.verifiedArtifact(
            mapper, decisionAuthority(HearingFlowStage.JURY_REVIEWING), "JUDGE_PROPOSAL",
            judgeV1Wrapper.path("proposal_id").asText(), judgeV1Formal.contentHash(), judgeV1Formal.json(),
            "JURY_REVIEWING", 11, "PRESIDING_JUDGE"));
    assertThrows(IllegalStateException.class, () ->
        JdbcTargetHearingFormalAuthorityLoader.verifiedArtifact(
            mapper, decisionAuthority(HearingFlowStage.JURY_REVIEWING), "JUDGE_PROPOSAL",
            judgeV1Wrapper.path("proposal_id").asText(), judgeV1Formal.contentHash(), judgeV1Formal.json(),
            "JUDGE_V1_GENERATING", 11, "JURY_PANEL"));
    assertThrows(IllegalStateException.class, () ->
        JdbcTargetHearingFormalAuthorityLoader.verifiedArtifact(
            mapper, decisionAuthority(HearingFlowStage.JURY_REVIEWING), "JURY_REVIEW_REPORT",
            judgeV1Wrapper.path("proposal_id").asText(), judgeV1Formal.contentHash(), judgeV1Formal.json(),
            "JUDGE_V1_GENERATING", 11, "PRESIDING_JUDGE"));

    ObjectNode pairingDrift = juryWrapper.deepCopy();
    pairingDrift.put("proposal_id", "formal-proposal-drift");
    String pairingHash = outerHash(pairingDrift);
    var driftedJuryArtifact = JdbcTargetHearingFormalAuthorityLoader.verifiedArtifact(
        mapper, decisionAuthority(HearingFlowStage.JUDGE_V2_GENERATING), "JURY_REVIEW_REPORT",
        pairingDrift.path("report_id").asText(), pairingHash, mapper.writeValueAsString(pairingDrift),
        "JURY_REVIEWING", 12, "JURY_PANEL");
    assertThrows(IllegalStateException.class, () ->
        JdbcTargetHearingFormalAuthorityLoader.mapParents(
            dossier, List.of(judgeV1Artifact, driftedJuryArtifact)));
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

  private ObjectNode judgeV1(JdbcTargetHearingFormalAuthorityLoader.Ref dossier) {
    ObjectNode proposal = base("hearing_judge_v1.v1");
    proposal.put("stage_sequence", HearingFlowStage.JUDGE_V1_GENERATING.ordinal() + 1);
    proposal.put("trial_dossier_id", dossier.id());
    proposal.put("trial_dossier_hash", dossier.hash());
    proposal.put("proposal_id", "python-proposal-1");
    proposal.put("proposal_text", "Python Judge V1 proposal");
    proposal.put("recommended_decision", "PARTIAL_REFUND");
    proposal.put("reasoning_summary", "Verified nested proposal authority.");
    proposal.putArray("review_focus").add("DELIVERY_PROOF");
    proposal.put("public_message", "Judge V1 proposal ready.");
    proposal.put("is_final_decision", false);
    pythonHash(proposal, "proposal_hash");
    return proposal;
  }

  private ObjectNode juryReview(
      JdbcTargetHearingFormalAuthorityLoader.Ref dossier, ObjectNode judgeV1) {
    ObjectNode review = base("hearing_jury_review.v1");
    review.put("stage_sequence", HearingFlowStage.JURY_REVIEWING.ordinal() + 1);
    review.put("trial_dossier_id", dossier.id());
    review.put("trial_dossier_hash", dossier.hash());
    review.put("review_id", "python-jury-1");
    review.put("reviewed_proposal_id", judgeV1.path("proposal_id").asText());
    review.put("reviewed_proposal_hash", judgeV1.path("proposal_hash").asText());
    review.putArray("findings").add("Proposal is internally consistent.");
    review.putArray("mandatory_revisions");
    review.put("public_message", "Jury review ready.");
    review.put("approval_performed", false);
    review.put("execution_triggered", false);
    review.put("is_final_decision", false);
    pythonHash(review, "review_hash");
    return review;
  }

  private ObjectNode judgeV2(
      JdbcTargetHearingFormalAuthorityLoader.Ref dossier, ObjectNode judgeV1, ObjectNode jury) {
    ObjectNode draft = base("hearing_judge_v2.v1");
    draft.put("stage_sequence", HearingFlowStage.JUDGE_V2_GENERATING.ordinal() + 1);
    draft.put("trial_dossier_id", dossier.id());
    draft.put("trial_dossier_hash", dossier.hash());
    draft.put("judge_v2_id", "python-judge-v2-1");
    draft.put("parent_proposal_id", judgeV1.path("proposal_id").asText());
    draft.put("parent_proposal_hash", judgeV1.path("proposal_hash").asText());
    draft.put("jury_review_id", jury.path("review_id").asText());
    draft.put("jury_review_hash", jury.path("review_hash").asText());
    draft.putObject("draft")
        .put("draft_text", "Verified Judge V2 draft.")
        .put("recommended_decision", "PARTIAL_REFUND");
    draft.put("public_message", "Verified Judge V2 draft.");
    pythonHash(draft, "judge_v2_hash");
    return draft;
  }

  private ObjectNode base(String schemaVersion) {
    ObjectNode proposal = mapper.createObjectNode();
    proposal.put("schema_version", schemaVersion);
    proposal.put("case_id", "case-1");
    proposal.put("workflow_id", "flow-1");
    proposal.put("stage_sequence", 4);
    return proposal;
  }

  private ObjectNode caseMatrix(String id, int version, ObjectNode parent) {
    ObjectNode matrix = mapper.createObjectNode();
    matrix.put("schema_version", "case_fact_matrix.v2");
    matrix.put("case_id", "case-1");
    matrix.put("matrix_id", id);
    matrix.put("matrix_version", version);
    if (parent != null) {
      ObjectNode parentRef = matrix.putObject("parent_ref");
      parentRef.put("matrix_id", parent.path("matrix_id").asText());
      parentRef.put("matrix_version", parent.path("matrix_version").asInt());
      parentRef.put("content_hash", parent.path("content_hash").asText());
    }
    matrix.putObject("claims").putObject("initiator_claim").put("requested_amount", 20.0);
    pythonHash(matrix);
    return matrix;
  }

  private ObjectNode evidenceMatrix(ObjectNode caseMatrix) {
    ObjectNode matrix = mapper.createObjectNode();
    matrix.put("schema_version", "fact_evidence_matrix.v2");
    matrix.put("case_id", "case-1");
    matrix.put("matrix_id", "evidence-matrix-1");
    matrix.put("matrix_version", 1);
    matrix.put("matrix_status", "FROZEN");
    matrix.put("case_fact_matrix_id", caseMatrix.path("matrix_id").asText());
    matrix.put("case_fact_matrix_version", caseMatrix.path("matrix_version").asInt());
    matrix.put("case_fact_matrix_hash", caseMatrix.path("content_hash").asText());
    matrix.putArray("links").addObject()
        .put("fact_id", "FACT_1")
        .put("evidence_id", "evidence-1")
        .put("confidence", 0.0);
    pythonHash(matrix);
    return matrix;
  }

  private ArrayNode answers() {
    ArrayNode answers = mapper.createArrayNode();
    answers.add(answer("USER"));
    answers.add(answer("MERCHANT"));
    return answers;
  }

  private ObjectNode answer(String role) {
    ObjectNode answer = mapper.createObjectNode();
    answer.put("schema_version", "hearing_answer_bundle.v1");
    answer.put("question_set_id", "question-set-1");
    answer.put("participant_role", role);
    answer.put("submission_status", "SUBMITTED");
    answer.put("submitted_at", "2026-01-01T00:00:00Z");
    answer.putArray("answers").addObject()
        .put("question_id", "q-1")
        .put("answer_text", role + " answer")
        .putArray("attachment_refs");
    answer.putArray("source_message_ids").add("message-" + role.toLowerCase());
    return answer;
  }

  private ArrayNode evidenceBatches() {
    ArrayNode batches = mapper.createArrayNode();
    batches.add(evidenceBatch("USER"));
    batches.add(evidenceBatch("MERCHANT"));
    return batches;
  }

  private ObjectNode evidenceBatch(String role) {
    ObjectNode batch = mapper.createObjectNode();
    batch.put("schema_version", "hearing_evidence_batch.v1");
    batch.put("request_set_id", "request-set-1");
    batch.put("participant_role", role);
    batch.put("submission_status", "SUBMITTED");
    batch.put("submitted_at", "2026-01-01T00:00:00Z");
    batch.putArray("evidence_ids").add("evidence-" + role.toLowerCase());
    batch.putArray("request_ids").add("r-1");
    batch.put("batch_note", role + " batch");
    return batch;
  }

  private ArrayNode policyRules() {
    ArrayNode rules = mapper.createArrayNode();
    ObjectNode rule = rules.addObject();
    rule.put("policy_id", "policy-1");
    rule.put("rule_code", "DELIVERY_PROOF");
    rule.put("rule_version", 1);
    rule.put("rule_name", "Delivery proof");
    rule.put("rule_scope", "DELIVERY_DISPUTE");
    rule.put("rule_status", "ACTIVE");
    rule.put("effective_from", "2020-01-01T00:00:00Z");
    rule.putNull("effective_to");
    rule.put("priority", 100);
    rule.putObject("conditions").put("requires_delivery_proof", true);
    rule.putObject("outcome").put("requires_human_review", true);
    rule.putObject("source_document").put("section", "DELIVERY_PROOF");
    return rules;
  }

  private void pythonHash(ObjectNode value) {
    pythonHash(value, "content_hash");
  }

  private void pythonHash(ObjectNode value, String field) {
    value.put(field, JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
        mapper, value, field));
  }

  private static String outerHash(ObjectNode wrapper) {
    wrapper.remove("content_hash");
    String hash = ContractJson.sha256Hex(wrapper);
    wrapper.put("content_hash", hash);
    return hash;
  }

  private static JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding(
      HearingFlowStage stage, ObjectNode matrix) {
    int sequence = stage.ordinal() + 1;
    HearingFlowStage resultStage = HearingFlowStage.values()[stage.ordinal() + 1];
    var authority = new HearingAuthorityExpectation("tenant-1", "case-1", "flow-1", "epoch-1", 1,
        HearingWriterMode.TEMPORAL, stage, sequence, 1, 1, 1);
    var matrixAuthority = new JdbcTargetHearingFormalAuthorityLoader.MatrixAuthority(
        matrix.path("matrix_version").asInt(), matrix.path("content_hash").asText());
    return new JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding(authority,
        "stage-" + sequence, resultStage, sequence + 1, Instant.parse("2026-01-01T00:00:00Z"),
        "stage-" + (sequence + 1), "{}", "hearing-control", matrixAuthority,
        new JdbcTargetHearingFormalAuthorityLoader.Parents(null, null, null));
  }

  private static JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding decisionBinding(
      HearingFlowStage stage, JdbcTargetHearingFormalAuthorityLoader.Parents parents) {
    int sequence = stage.ordinal() + 1;
    HearingFlowStage resultStage = HearingFlowStage.values()[stage.ordinal() + 1];
    var authority = new HearingAuthorityExpectation("tenant-1", "case-1", "flow-1", "epoch-1", 1,
        HearingWriterMode.TEMPORAL, stage, sequence, 1, 1, 1);
    return new JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding(authority,
        "stage-" + sequence, resultStage, sequence + 1, null,
        "stage-" + (sequence + 1), "{}", "hearing-control", null, parents);
  }

  private static HearingAuthorityExpectation decisionAuthority(HearingFlowStage stage) {
    int sequence = stage.ordinal() + 1;
    return new HearingAuthorityExpectation("tenant-1", "case-1", "flow-1", "epoch-1", 1,
        HearingWriterMode.TEMPORAL, stage, sequence, 1, 1, 1);
  }

}
