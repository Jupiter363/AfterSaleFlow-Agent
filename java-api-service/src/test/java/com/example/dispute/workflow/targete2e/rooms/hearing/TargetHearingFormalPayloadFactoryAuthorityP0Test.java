package com.example.dispute.workflow.targete2e.rooms.hearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.hearing.domain.HearingAuthorityExpectation;
import com.example.dispute.hearing.application.HearingPublicTranscriptPolicy;
import com.example.dispute.hearing.domain.HearingAuthorityCommit;
import com.example.dispute.hearing.domain.HearingAuthorityLedger;
import com.example.dispute.hearing.domain.HearingAuthorityRejectedException;
import com.example.dispute.hearing.domain.HearingDomainReceipt;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFormalFinalizer;
import com.example.dispute.hearing.domain.HearingFormalRequestHash;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.hearing.infrastructure.persistence.JdbcHearingFormalFinalizer;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class TargetHearingFormalPayloadFactoryAuthorityP0Test {
  private final ObjectMapper mapper = new ObjectMapper();
  private final TargetHearingFormalPayloadFactory factory = new TargetHearingFormalPayloadFactory(mapper);

  @Test
  void generatedActionsKeepTheirEstablishedTopLevelFormalShape() throws Exception {
    ObjectNode questionMatrix = caseMatrix("matrix-1", 1, null);
    ObjectNode requestMatrix = caseMatrix("matrix-2", 2, questionMatrix);
    ObjectNode questionResult = intakeQuestions(questionMatrix);
    var questions = factory.project("intake_questions", questionResult, "ignored-formal-id",
        binding(HearingFlowStage.INTAKE_QUESTIONS_GENERATING, questionMatrix));
    var questionPayload = mapper.readTree(questions.json());
    assertEquals("hearing_question_set.v4", questionPayload.path("schema_version").asText());
    assertEquals("question-set-v4", questionPayload.path("question_set_id").asText());
    assertEquals(questionPayload.path("question_set_hash").asText(), questions.contentHash());
    assertEquals("hearing_intake_questions.v5",
        mapper.readTree(questions.stageOutputJson()).path("schema_version").asText());
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
  void v5SynthesisBindsM1QuestionAnswersIssueStateFramesAndM2() throws Exception {
    ObjectNode questionMatrix = caseMatrix("matrix-1", 1, null);
    ObjectNode successorMatrix = caseMatrix("matrix-2", 2, questionMatrix);
    ObjectNode questionResult = intakeQuestions(questionMatrix);
    ObjectNode synthesis = intakeSynthesis(questionResult, successorMatrix);

    var synthesisBinding = binding(HearingFlowStage.INTAKE_SYNTHESIZING, questionMatrix);
    var first = factory.project("intake_synthesis", synthesis, "ignored-state-id",
        synthesisBinding);
    var replay = factory.project("intake_synthesis", synthesis.deepCopy(), "ignored-state-id",
        binding(HearingFlowStage.INTAKE_SYNTHESIZING, questionMatrix));
    assertEquals(first, replay);
    ObjectNode formal = (ObjectNode) mapper.readTree(first.json());
    assertEquals("hearing_intake_synthesis.v5", formal.path("schema_version").asText());
    assertEquals(questionMatrix.path("content_hash").asText(),
        formal.path("case_fact_matrix").path("parent_ref").path("content_hash").asText());
    assertEquals(successorMatrix.path("content_hash").asText(),
        formal.path("issue_state_set").path("matrix_hash").asText());
    assertEquals(formal.path("issue_transition_set").path("transition_hash").asText(),
        formal.path("issue_state_set").path("transition_hash").asText());
    assertEquals(2, formal.path("public_frames").size());
    assertTrue(first.json().contains("\"requested_amount\":20"));

    var command = intakeMatrixCommand(first, synthesisBinding);
    var commandReplay = intakeMatrixCommand(first, synthesisBinding);
    assertEquals(command, commandReplay);
    assertEquals(first.contentHash(), command.contentHash());

    var transcriptPolicy = new HearingPublicTranscriptPolicy();
    var drafts = transcriptPolicy.agentFinalized(
        HearingFlowStage.INTAKE_SYNTHESIZING, formal, command.agentRunId());
    assertEquals(drafts, transcriptPolicy.agentFinalized(
        HearingFlowStage.INTAKE_SYNTHESIZING, formal.deepCopy(), command.agentRunId()));
    assertEquals(3, drafts.size());
    for (int index = 0; index < formal.path("public_frames").size(); index++) {
      assertEquals(formal.path("public_frames").get(index).path("public_text").asText(),
          drafts.get(index).text());
      assertEquals(command.agentRunId(), drafts.get(index).agentRunId());
      assertEquals("intake-synthesis-frame-" + (index + 1), drafts.get(index).suffix());
    }
    assertNull(drafts.getLast().agentRunId());
    assertEquals("evidence-requests-next", drafts.getLast().suffix());

    ObjectNode badParent = synthesis.deepCopy();
    badParent.with("case_fact_matrix").with("parent_ref").put("content_hash", "f".repeat(64));
    pythonHash((ObjectNode) badParent.path("case_fact_matrix"));
    assertThrows(IllegalArgumentException.class, () -> factory.project(
        "intake_synthesis", badParent, "ignored-state-id",
        binding(HearingFlowStage.INTAKE_SYNTHESIZING, questionMatrix)));
  }

  @Test
  void m2FinalizerPersistsExactIssueStateOnceAndRejectsAnyParentMismatch() {
    ObjectNode m1 = caseMatrix("matrix-1", 1, null);
    ObjectNode m2 = caseMatrix("matrix-2", 2, m1);
    ObjectNode questions = intakeQuestions(m1);
    var synthesisBinding = binding(HearingFlowStage.INTAKE_SYNTHESIZING, m1);
    var formal = factory.project(
        "intake_synthesis", intakeSynthesis(questions, m2), "ignored-state-id",
        synthesisBinding);
    var command = intakeMatrixCommand(formal, synthesisBinding);

    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    AtomicBoolean parentsExact = new AtomicBoolean(true);
    AtomicReference<String> parentSql = new AtomicReference<>();
    when(jdbc.queryForObject(
        anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
        .thenAnswer(invocation -> {
          String sql = invocation.getArgument(0);
          if (sql.contains("from hearing_flow_stage") || sql.contains("from agent_run run")) {
            return 1;
          }
          if (sql.contains("from hearing_flow_action question_set")) {
            parentSql.set(sql);
            return parentsExact.get() ? 1 : 0;
          }
          throw new AssertionError("unexpected M2 cardinality query: " + sql);
        });
    when(jdbc.queryForObject(anyString(), any(Map.class), eq(Long.class))).thenReturn(1L);
    AtomicInteger issueStateInserts = new AtomicInteger();
    when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
        .thenAnswer(invocation -> {
          if (((String) invocation.getArgument(0)).contains(
              "insert into hearing_issue_state_set")) {
            issueStateInserts.incrementAndGet();
          }
          return 1;
        });

    HearingAuthorityLedger ledger = replayingLedger();
    JdbcHearingFormalFinalizer finalizer = new JdbcHearingFormalFinalizer(jdbc, ledger);
    HearingDomainReceipt first = finalizer.finalizeMatrixSynthesis(command);
    HearingDomainReceipt replay = finalizer.finalizeMatrixSynthesis(command);
    assertEquals(first, replay);
    assertEquals(1, issueStateInserts.get());
    assertTrue(parentSql.get().contains("hearing_question_set.v4"));
    assertTrue(parentSql.get().contains("hearing_answer_bundle.v4"));
    assertTrue(parentSql.get().contains("submission_status = 'SUBMITTED'"));
    assertTrue(parentSql.get().contains("with ordinality"));
    assertTrue(parentSql.get().contains("when 1 then 'USER' when 2 then 'MERCHANT'"));

    parentsExact.set(false);
    JdbcHearingFormalFinalizer rejecting = new JdbcHearingFormalFinalizer(jdbc, replayingLedger());
    HearingAuthorityRejectedException mismatch = assertThrows(
        HearingAuthorityRejectedException.class,
        () -> rejecting.finalizeMatrixSynthesis(command));
    assertEquals("HEARING_INTAKE_V4_PARENTS_NOT_EXACT", mismatch.code());
    assertEquals(1, issueStateInserts.get());
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

  private ObjectNode intakeQuestions(ObjectNode matrix) {
    ObjectNode proposal = base("hearing_intake_questions.v5");
    proposal.put("speaker_role", "INTAKE_OFFICER");
    ObjectNode baseline = mapper.createObjectNode();
    baseline.put("issue_statement", "Whether the product met the agreed performance.");
    baseline.putArray("source_fact_ids").add("FACT_1");
    ObjectNode positions = baseline.putObject("effective_party_positions");
    positions.putObject("USER")
        .put("position_source", "M1")
        .put("position_summary", "The product did not meet the agreement.");
    positions.putObject("MERCHANT")
        .put("position_source", "M1")
        .put("position_summary", "The product met the agreement.");
    baseline.putObject("alignment")
        .put("status", "CONTESTED")
        .putNull("agreed_statement")
        .put("conflict_summary", "The parties disagree about performance.");

    ObjectNode question = mapper.createObjectNode();
    question.put("question_slot_id", "QUESTION_SLOT_01");
    question.put("question_id", "question-v4-1");
    question.put("issue_id", "issue-v4-1");
    question.put("issue_version", 1);
    ObjectNode baselineState = mapper.createObjectNode();
    baselineState.put("schema_version", "hearing_issue_baseline_state.v4");
    baselineState.put("issue_id", "issue-v4-1");
    baselineState.put("issue_version", 1);
    baselineState.put("question_id", "question-v4-1");
    baselineState.put("question_slot_id", "QUESTION_SLOT_01");
    baselineState.set("issue_baseline", baseline.deepCopy());
    question.put("issue_state_hash", pythonDigest(baselineState));
    question.putArray("target_roles").add("USER").add("MERCHANT");
    question.putArray("fact_ids").add("FACT_1");
    question.put("question_text", "Please explain whether performance met the agreement.");
    question.set("issue_baseline", baseline.deepCopy());
    question.putObject("party_prompts")
        .put("USER", "Describe observed performance.")
        .put("MERCHANT", "Describe the agreed standard.");

    ObjectNode catalog = mapper.createObjectNode();
    catalog.put("schema_version", "hearing_formal_issue_catalog.v4");
    ObjectNode catalogIssue = catalog.putArray("issues").addObject();
    catalogIssue.put("question_slot_id", "QUESTION_SLOT_01");
    catalogIssue.put("question_id", "question-v4-1");
    catalogIssue.put("issue_id", "issue-v4-1");
    catalogIssue.put("issue_version", 1);
    catalogIssue.put("issue_state_hash", question.path("issue_state_hash").asText());
    catalogIssue.set("issue_baseline", baseline.deepCopy());

    ObjectNode questionSet = proposal.putObject("question_set");
    questionSet.put("schema_version", "hearing_question_set.v4");
    questionSet.put("question_set_id", "question-set-v4");
    questionSet.put("question_set_hash", "0".repeat(64));
    questionSet.put("formal_issue_catalog_hash", pythonDigest(catalog));
    questionSet.put("case_id", "case-1");
    questionSet.put("source_matrix_id", matrix.path("matrix_id").asText());
    questionSet.put("source_matrix_version", matrix.path("matrix_version").asInt());
    questionSet.put("source_matrix_hash", matrix.path("content_hash").asText());
    questionSet.put("prelude_authority_hash", "b".repeat(64));
    questionSet.putArray("questions").add(question);
    pythonHash(questionSet, "question_set_hash");

    String lead = "The frozen case matrix is loaded.";
    proposal.putArray("public_frames")
        .add(publicFrame(1, "HEARING_INTAKE_QUESTION_LEAD", "question-set-v4", lead))
        .add(publicFrame(2, "SHARED_ISSUE_QUESTION", "question-v4-1",
            question.path("question_text").asText()));
    proposal.put("lead_public_text", lead);
    return proposal;
  }

  private ObjectNode intakeSynthesis(ObjectNode questionResult, ObjectNode matrix) {
    ObjectNode proposal = base("hearing_intake_synthesis.v5");
    proposal.put("stage_sequence", HearingFlowStage.INTAKE_SYNTHESIZING.ordinal() + 1);
    ObjectNode questionSet = (ObjectNode) questionResult.path("question_set");
    String questionId = questionSet.path("questions").get(0).path("question_id").asText();
    String issueId = questionSet.path("questions").get(0).path("issue_id").asText();
    String lead = "Both current answer bundles are sealed.";
    String frameText = "Both parties now partly agree and the amount remains disputed.";
    proposal.putArray("public_frames")
        .add(publicFrame(1, "HEARING_INTAKE_SYNTHESIS_LEAD", "transition-v4", lead))
        .add(publicFrame(2, "REBIND_ISSUE_SYNTHESIS", issueId, frameText));

    ObjectNode issue = mapper.createObjectNode();
    issue.put("issue_id", issueId);
    issue.put("issue_version", 2);
    issue.put("issue_state_hash", "0".repeat(64));
    issue.put("issue_kind", "REBIND");
    issue.put("issue_statement", "Whether the product met the agreed performance.");
    ObjectNode current = issue.putObject("effective_party_positions");
    current.putObject("USER")
        .put("position_source", "CURRENT_ANSWER")
        .put("position_summary", "The user requests a partial refund.");
    current.putObject("MERCHANT")
        .put("position_source", "CURRENT_ANSWER")
        .put("position_summary", "The merchant accepts discussing a partial refund.");
    issue.putObject("current_alignment")
        .put("status", "PARTIALLY_AGREED")
        .put("agreed_statement", "The parties accept partial-refund discussions.")
        .put("conflict_summary", "The amount remains disputed.");
    issue.put("requires_resolution", true);
    issue.put("source_question_id", questionId);
    issue.putArray("source_answer_bundle_ids").add("answer-user").add("answer-merchant");
    issue.putArray("source_answer_unit_ids").add("unit-user").add("unit-merchant");
    pythonHash(issue, "issue_state_hash");

    ObjectNode transition = proposal.putObject("issue_transition_set");
    transition.put("schema_version", "hearing_issue_transition_set.v4");
    transition.put("transition_set_id", "transition-v4");
    transition.put("transition_hash", "0".repeat(64));
    transition.put("case_id", "case-1");
    transition.put("question_set_id", questionSet.path("question_set_id").asText());
    transition.put("question_set_hash", questionSet.path("question_set_hash").asText());
    transition.putArray("answer_bundle_ids").add("answer-user").add("answer-merchant");
    transition.putArray("answer_bundle_hashes").add("c".repeat(64)).add("d".repeat(64));
    transition.putArray("issues").add(issue.deepCopy());
    pythonHash(transition, "transition_hash");
    proposal.set("case_fact_matrix", matrix.deepCopy());

    ObjectNode state = proposal.putObject("issue_state_set");
    state.put("schema_version", "hearing_issue_state_set.v4");
    state.put("issue_state_set_id", "issue-state-set-v4");
    state.put("content_hash", "0".repeat(64));
    state.put("case_id", "case-1");
    state.put("transition_set_id", transition.path("transition_set_id").asText());
    state.put("transition_hash", transition.path("transition_hash").asText());
    state.put("question_set_id", transition.path("question_set_id").asText());
    state.put("question_set_hash", transition.path("question_set_hash").asText());
    state.set("answer_bundle_ids", transition.path("answer_bundle_ids").deepCopy());
    state.set("answer_bundle_hashes", transition.path("answer_bundle_hashes").deepCopy());
    state.put("matrix_id", matrix.path("matrix_id").asText());
    state.put("matrix_version", matrix.path("matrix_version").asInt());
    state.put("matrix_hash", matrix.path("content_hash").asText());
    state.set("issues", transition.path("issues").deepCopy());
    pythonHash(state);
    proposal.put("lead_public_text", lead);
    return proposal;
  }

  private ObjectNode publicFrame(
      int sequence, String type, String authorityRef, String text) {
    ObjectNode frame = mapper.createObjectNode();
    frame.put("frame_sequence", sequence);
    frame.put("frame_type", type);
    frame.put("authority_ref", authorityRef);
    frame.put("public_text", text);
    frame.put("public_text_hash", textHash(text));
    return frame;
  }

  private ObjectNode evidenceRequests() {
    ObjectNode proposal = base("hearing_evidence_requests.v1");
    proposal.putArray("requests").addObject().put("request_id", "r-1").put("text", "Provide the receipt.");
    proposal.put("public_message", "Please provide the requested evidence.");
    return proposal;
  }

  private ObjectNode judgeV1(JdbcTargetHearingFormalAuthorityLoader.Ref dossier) {
    ObjectNode proposal = base("hearing_judge_v1.v2");
    proposal.put("stage_sequence", HearingFlowStage.JUDGE_V1_GENERATING.ordinal() + 1);
    proposal.put("trial_dossier_id", dossier.id());
    proposal.put("trial_dossier_hash", dossier.hash());
    proposal.put("proposal_id", "python-proposal-1");
    proposal.set("draft", adjudicationDraft());
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
    ObjectNode draft = base("hearing_judge_v2.v2");
    draft.put("stage_sequence", HearingFlowStage.JUDGE_V2_GENERATING.ordinal() + 1);
    draft.put("trial_dossier_id", dossier.id());
    draft.put("trial_dossier_hash", dossier.hash());
    draft.put("judge_v2_id", "python-judge-v2-1");
    draft.put("parent_proposal_id", judgeV1.path("proposal_id").asText());
    draft.put("parent_proposal_hash", judgeV1.path("proposal_hash").asText());
    draft.put("jury_review_id", jury.path("review_id").asText());
    draft.put("jury_review_hash", jury.path("review_hash").asText());
    draft.set("draft", adjudicationDraft());
    draft.putArray("review_responses").addObject()
        .put("review_item_ref", "JURY_FINDING_FACT_COMPLETENESS")
        .put("review_source", "JURY_FINDING")
        .put("disposition", "ACCEPTED")
        .put("response", "The frozen authority supports the response.")
        .putArray("affected_fields").add("decision_reasoning");
    draft.put("public_message", "Verified Judge V2 draft.");
    draft.put("draft_status", "PENDING_HUMAN_REVIEW");
    draft.put("requires_human_review", true);
    draft.put("is_final_decision", false);
    pythonHash(draft, "judge_v2_hash");
    return draft;
  }

  private ObjectNode adjudicationDraft() {
    ObjectNode draft = mapper.createObjectNode();
    draft.put("decision_action", "REFUND_ONLY");
    draft.putArray("remedy_orders").addObject()
        .put("remedy_type", "REFUND_ONLY")
        .put("order_text", "Refund the supported amount.")
        .putArray("fact_ids").add("FACT_1");
    draft.putArray("fact_findings").addObject()
        .put("fact_id", "FACT_1")
        .put("finding", "The frozen evidence only partly supports delivery.")
        .putArray("evidence_ids").add("evidence-1");
    ((ObjectNode) draft.path("fact_findings").get(0)).putNull("evidence_gap");
    ((ObjectNode) draft.path("fact_findings").get(0)).put("confidence", 0.7);
    ObjectNode application = draft.putArray("rule_applications").addObject();
    application.put("rule_code", "DELIVERY_PROOF");
    application.put("rule_version", 1);
    application.put("rule_name", "Delivery proof");
    application.putArray("fact_ids").add("FACT_1");
    application.put("applicable", true);
    application.putArray("conditions_met").add("Delivery is disputed.");
    application.putArray("conditions_unmet");
    application.put("rationale", "The rule applies to the frozen delivery dispute.");
    application.put("resulting_effect", "Human review remains required.");
    draft.put("decision_reasoning", "Fact, evidence and rule jointly support partial relief.");
    draft.putArray("reviewer_attention").add("Confirm the exact refund amount.");
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
    matrix.put("schema_version", "fact_evidence_matrix.v3");
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
        .put("relation", "INCONCLUSIVE")
        .put("reason", "测试材料绑定到正式事实。")
        .put("source_unit_id", "SOURCE_UNIT_1")
        .put("observation_slot", "OBS_1");
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

  private String pythonDigest(ObjectNode value) {
    return JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
        mapper, value, "__absent_hash_field__");
  }

  private static String textHash(String value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String outerHash(ObjectNode wrapper) {
    wrapper.remove("content_hash");
    String hash = ContractJson.sha256Hex(wrapper);
    wrapper.put("content_hash", hash);
    return hash;
  }

  private HearingFormalFinalizer.MatrixSynthesisCommand intakeMatrixCommand(
      TargetHearingFormalPayloadFactory.FormalPayload payload,
      JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding) {
    var transition = binding.transitionFor(payload.stageOutputJson());
    String runId = "RUN_M2_V4";
    String resultHash = "e".repeat(64);
    String requestHash = HearingFormalRequestHash.compute(
        "MATRIX_SYNTHESIS", binding.authority(), transition,
        HearingFormalFinalizer.MatrixKind.INTAKE, payload.contentHash(), runId, resultHash,
        binding.actorId());
    var commit = new HearingAuthorityCommit(
        HearingAuthorityCommit.SCHEMA_VERSION,
        binding.authority(),
        HearingAuthorityCommit.OperationType.FINALIZE,
        "hearing.finalize:" + binding.authority().tenantSurrogate() + ':'
            + binding.authority().caseId() + ':' + binding.authority().roomEpoch() + ':'
            + binding.authority().stageSequence() + ':'
            + HearingFormalFinalizer.MatrixKind.INTAKE.schemaVersion() + ':' + requestHash,
        requestHash,
        null,
        Instant.parse("2026-01-01T00:00:00Z"));
    return new HearingFormalFinalizer.MatrixSynthesisCommand(
        commit, transition, HearingFormalFinalizer.MatrixKind.INTAKE,
        payload.json(), payload.contentHash(), runId, resultHash, binding.actorId());
  }

  private HearingAuthorityLedger replayingLedger() {
    HearingAuthorityLedger ledger = mock(HearingAuthorityLedger.class);
    AtomicReference<HearingDomainReceipt> stored = new AtomicReference<>();
    when(ledger.commitOrReplay(
        any(HearingAuthorityCommit.class), any(HearingAuthorityLedger.FormalCommitAction.class)))
        .thenAnswer(invocation -> {
          HearingDomainReceipt replay = stored.get();
          if (replay != null) {
            return replay;
          }
          HearingAuthorityLedger.FormalCommitAction mutation = invocation.getArgument(1);
          mutation.commit();
          HearingDomainReceipt receipt = mock(HearingDomainReceipt.class);
          stored.set(receipt);
          return receipt;
        });
    return ledger;
  }

  private static JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding binding(
      HearingFlowStage stage, ObjectNode matrix) {
    int sequence = stage.ordinal() + 1;
    HearingFlowStage resultStage = HearingFlowStage.values()[stage.ordinal() + 1];
    var authority = new HearingAuthorityExpectation("tenant-1", "case-1", "flow-1", "epoch-1", 1,
        HearingWriterMode.TEMPORAL, stage, sequence, 1, 1, 1);
    var matrixAuthority = new JdbcTargetHearingFormalAuthorityLoader.MatrixAuthority(
        matrix.path("matrix_id").asText(), matrix.path("matrix_version").asInt(),
        matrix.path("content_hash").asText());
    return new JdbcTargetHearingFormalAuthorityLoader.FormalAuthorityBinding(authority,
        "stage-" + sequence, resultStage, sequence + 1,
        resultStage.hasSharedPartyDeadline() ? Instant.parse("2026-01-01T00:00:00Z") : null,
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
