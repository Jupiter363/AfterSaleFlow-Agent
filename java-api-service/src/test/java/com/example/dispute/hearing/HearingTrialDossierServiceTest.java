package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.application.HearingTrialDossierService;
import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFlowSubmissionStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowActionEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowInstanceEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingTrialDossierEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowActionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowInstanceRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingTrialDossierRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.policy.application.PolicyApplicationService;
import com.example.dispute.policy.application.PolicyRuleView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HearingTrialDossierServiceTest {

    private static final String CASE_ID = "CASE_1";
    private static final String FLOW_ID = "HEARING_FLOW_1";
    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");

    @Mock private HearingTrialDossierRepository dossierRepository;
    @Mock private HearingFlowInstanceRepository flowRepository;
    @Mock private HearingFlowActionRepository actionRepository;
    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private PolicyApplicationService policyApplicationService;
    @Mock private FulfillmentCaseEntity dispute;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HearingTrialDossierService service;
    private HearingFlowInstanceEntity flow;

    @BeforeEach
    void setUp() {
        service =
                new HearingTrialDossierService(
                        dossierRepository,
                        flowRepository,
                        actionRepository,
                        caseRepository,
                        policyApplicationService,
                        objectMapper,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(dispute));
        when(dispute.getUserId()).thenReturn("user-1");
        when(dispute.getMerchantId()).thenReturn("merchant-1");
        flow = flowAtDossierFreezing();
    }

    @Test
    void freezesExactMatricesQuestionsAnswersRequestsAndBatchesOnce() throws Exception {
        Fixture fixture = fixture();
        stubPersistedActions(fixture);
        when(flowRepository.findByCaseIdForUpdate(CASE_ID)).thenReturn(Optional.of(flow));
        when(dossierRepository.findByCaseId(CASE_ID)).thenReturn(Optional.empty());
        when(dossierRepository.save(any(HearingTrialDossierEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        HearingTrialDossierEntity frozen = service.freeze(fixture.command());

        JsonNode payload = objectMapper.readTree(frozen.getPayloadJson());
        assertThat(payload.path("schema_version").asText()).isEqualTo("trial_dossier.v1");
        assertThat(payload.path("case_fact_matrix").path("matrix_status").asText())
                .isEqualTo("WORKING");
        assertThat(payload.path("fact_evidence_matrix").path("matrix_status").asText())
                .isEqualTo("FROZEN");
        assertThat(payload.path("answer_bundles")).hasSize(2);
        assertThat(payload.path("evidence_batches")).hasSize(2);
        assertThat(payload.path("question_set_id").asText()).isEqualTo("QUESTION_SET_1");
        assertThat(payload.path("question_set").path("case_matrix_version").asInt())
                .isEqualTo(1);
        assertThat(payload.path("case_matrix_version").asInt()).isEqualTo(2);
        assertThat(payload.path("policy_rules")).hasSize(1);
        assertThat(payload.path("policy_rules").path(0).path("rule_code").asText())
                .isEqualTo("DELIVERY_PROOF");
        assertThat(payload.path("request_set_id").asText()).isEqualTo("REQUEST_SET_1");
        assertThat(frozen.getContentHash()).matches("[0-9a-f]{64}");
        verify(dossierRepository).save(any(HearingTrialDossierEntity.class));
    }

    @Test
    void orchestrationFreezeSnapshotsTheActivePolicyVersion() throws Exception {
        Fixture fixture = fixture();
        stubPersistedActions(fixture);
        when(flowRepository.findByCaseId(CASE_ID)).thenReturn(Optional.of(flow));
        when(flowRepository.findByCaseIdForUpdate(CASE_ID)).thenReturn(Optional.of(flow));
        when(dossierRepository.findByCaseId(CASE_ID)).thenReturn(Optional.empty());
        when(dossierRepository.save(any(HearingTrialDossierEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(policyApplicationService.findActive(null))
                .thenReturn(
                        List.of(
                                new PolicyRuleView(
                                        "POLICY_DELIVERY_PROOF_V1",
                                        "DELIVERY_PROOF",
                                        1,
                                        "签收争议举证规则",
                                        "DELIVERY_DISPUTE",
                                        "ACTIVE",
                                        OffsetDateTime.parse("2020-01-01T00:00:00Z"),
                                        null,
                                        100,
                                        Map.of("requires_delivery_proof", true),
                                        Map.of("requires_human_review", true),
                                        Map.of("section", "DELIVERY_PROOF"))));
        ObjectNode intakeSynthesis = objectMapper.createObjectNode();
        intakeSynthesis.set("case_fact_matrix", fixture.caseMatrix().deepCopy());
        ObjectNode evidenceSynthesis = objectMapper.createObjectNode();
        evidenceSynthesis.set("fact_evidence_matrix", fixture.evidenceMatrix().deepCopy());

        HearingTrialDossierEntity frozen =
                service.freeze(CASE_ID, intakeSynthesis, evidenceSynthesis, "system");

        JsonNode policy = objectMapper.readTree(frozen.getPayloadJson()).path("policy_rules").path(0);
        assertThat(policy.path("policy_id").asText()).isEqualTo("POLICY_DELIVERY_PROOF_V1");
        assertThat(policy.path("rule_code").asText()).isEqualTo("DELIVERY_PROOF");
        assertThat(policy.path("rule_version").asInt()).isEqualTo(1);
        assertThat(policy.path("source_document").path("section").asText())
                .isEqualTo("DELIVERY_PROOF");
    }

    @Test
    void rejectsSubmittedAnswerBundleThatOmitsAnApplicableQuestion() {
        Fixture fixture = fixture();
        ((ArrayNode) fixture.answerBundles().get(0).path("answers")).removeAll();
        when(flowRepository.findByCaseIdForUpdate(CASE_ID)).thenReturn(Optional.of(flow));

        assertThatThrownBy(() -> service.freeze(fixture.command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("applicable question");
    }

    @Test
    void rejectsQuestionSetBoundToFinalMatrixInsteadOfItsParent() {
        Fixture fixture = fixture();
        fixture.questionSet().put("case_matrix_version", 2);
        fixture.questionSet()
                .put("case_matrix_hash", fixture.caseMatrix().path("content_hash").asText());
        when(flowRepository.findByCaseIdForUpdate(CASE_ID)).thenReturn(Optional.of(flow));

        assertThatThrownBy(() -> service.freeze(fixture.command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("question set case_matrix_version binding mismatch");
    }

    @Test
    void freezesNewStatementAlongsideReadableLegacyAnswerBundle() throws Exception {
        Fixture fixture = fixture();
        ObjectNode statement = objectMapper.createObjectNode();
        statement.put("schema_version", "hearing_party_statement.v1");
        statement.put("issue_set_id", "QUESTION_SET_1");
        statement.put("question_set_id", "QUESTION_SET_1");
        statement.put("participant_id", "user-1");
        statement.put("participant_role", "USER");
        statement.put("submission_status", "SUBMITTED");
        statement.put("statement_text", "I did not receive the goods described in the case.");
        statement.putArray("source_message_ids").add("MESSAGE_USER_1");
        fixture.answerBundles().set(0, statement);

        stubPersistedActions(fixture);
        when(flowRepository.findByCaseIdForUpdate(CASE_ID)).thenReturn(Optional.of(flow));
        when(dossierRepository.findByCaseId(CASE_ID)).thenReturn(Optional.empty());
        when(dossierRepository.save(any(HearingTrialDossierEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        HearingTrialDossierEntity frozen = service.freeze(fixture.command());

        JsonNode payload = objectMapper.readTree(frozen.getPayloadJson());
        assertThat(payload.path("answer_bundles").get(0).path("schema_version").asText())
                .isEqualTo("hearing_party_statement.v1");
        assertThat(payload.path("answer_bundles").get(1).path("schema_version").asText())
                .isEqualTo("hearing_answer_bundle.v1");
    }

    private void stubPersistedActions(Fixture fixture) {
        HearingFlowActionEntity question =
                HearingFlowActionEntity.agentOutput(
                        "QUESTION_SET_1",
                        FLOW_ID,
                        "STAGE_QUESTION",
                        CASE_ID,
                        HearingFlowActionType.QUESTION_SET,
                        json(fixture.questionSet()),
                        hash(fixture.questionSet()),
                        "AGENT_RUN_Q",
                        NOW,
                        "intake-officer");
        HearingFlowActionEntity userAnswer =
                partyAction(
                        "ANSWER_USER",
                        HearingFlowActionType.ANSWER_BUNDLE,
                        ActorRole.USER,
                        fixture.answerBundles().get(0));
        HearingFlowActionEntity merchantAnswer =
                partyAction(
                        "ANSWER_MERCHANT",
                        HearingFlowActionType.ANSWER_BUNDLE,
                        ActorRole.MERCHANT,
                        fixture.answerBundles().get(1));
        HearingFlowActionEntity request =
                HearingFlowActionEntity.agentOutput(
                        "REQUEST_SET_1",
                        FLOW_ID,
                        "STAGE_REQUEST",
                        CASE_ID,
                        HearingFlowActionType.EVIDENCE_REQUEST_SET,
                        json(fixture.requestSet()),
                        hash(fixture.requestSet()),
                        "AGENT_RUN_R",
                        NOW,
                        "evidence-clerk");
        HearingFlowActionEntity userBatch =
                partyAction(
                        "BATCH_USER",
                        HearingFlowActionType.EVIDENCE_BATCH,
                        ActorRole.USER,
                        fixture.evidenceBatches().get(0));
        HearingFlowActionEntity merchantBatch =
                partyAction(
                        "BATCH_MERCHANT",
                        HearingFlowActionType.EVIDENCE_BATCH,
                        ActorRole.MERCHANT,
                        fixture.evidenceBatches().get(1));

        when(actionRepository.findByFlowInstanceIdAndActionType(
                        FLOW_ID, HearingFlowActionType.QUESTION_SET))
                .thenReturn(Optional.of(question));
        when(actionRepository.findByFlowInstanceIdAndActionType(
                        FLOW_ID, HearingFlowActionType.EVIDENCE_REQUEST_SET))
                .thenReturn(Optional.of(request));
        when(actionRepository.findByFlowInstanceIdAndActionTypeAndParticipantId(
                        FLOW_ID, HearingFlowActionType.ANSWER_BUNDLE, "user-1"))
                .thenReturn(Optional.of(userAnswer));
        when(actionRepository.findByFlowInstanceIdAndActionTypeAndParticipantId(
                        FLOW_ID, HearingFlowActionType.ANSWER_BUNDLE, "merchant-1"))
                .thenReturn(Optional.of(merchantAnswer));
        when(actionRepository.findByFlowInstanceIdAndActionTypeAndParticipantId(
                        FLOW_ID, HearingFlowActionType.EVIDENCE_BATCH, "user-1"))
                .thenReturn(Optional.of(userBatch));
        when(actionRepository.findByFlowInstanceIdAndActionTypeAndParticipantId(
                        FLOW_ID, HearingFlowActionType.EVIDENCE_BATCH, "merchant-1"))
                .thenReturn(Optional.of(merchantBatch));
    }

    private HearingFlowActionEntity partyAction(
            String id, HearingFlowActionType type, ActorRole role, JsonNode payload) {
        return HearingFlowActionEntity.partyActionWithSchema(
                id,
                FLOW_ID,
                "STAGE_" + type.name(),
                CASE_ID,
                type,
                payload.path("schema_version").asText(),
                role == ActorRole.USER ? "user-1" : "merchant-1",
                role,
                HearingFlowSubmissionStatus.SUBMITTED,
                json(payload),
                hash(payload),
                NOW,
                role.name().toLowerCase());
    }

    private Fixture fixture() {
        ObjectNode caseMatrix = objectMapper.createObjectNode();
        caseMatrix.put("schema_version", "case_fact_matrix.v2");
        caseMatrix.put("case_id", CASE_ID);
        caseMatrix.put("matrix_id", "CASE_MATRIX_1");
        caseMatrix.put("matrix_version", 2);
        caseMatrix.put("matrix_status", "WORKING");
        ObjectNode parentRef = caseMatrix.putObject("parent_ref");
        parentRef.put("matrix_id", "CASE_MATRIX_0");
        parentRef.put("matrix_version", 1);
        parentRef.put("content_hash", "1".repeat(64));
        caseMatrix.putArray("fact_rows").addObject().put("fact_id", "FACT_1");
        putEmbeddedHash(caseMatrix);

        ObjectNode evidenceMatrix = objectMapper.createObjectNode();
        evidenceMatrix.put("schema_version", "fact_evidence_matrix.v2");
        evidenceMatrix.put("case_id", CASE_ID);
        evidenceMatrix.put("matrix_id", "EVIDENCE_MATRIX_1");
        evidenceMatrix.put("matrix_version", 3);
        evidenceMatrix.put("matrix_status", "WORKING");
        evidenceMatrix.put("case_fact_matrix_id", "CASE_MATRIX_1");
        evidenceMatrix.put("case_fact_matrix_version", 2);
        evidenceMatrix.put("case_fact_matrix_hash", caseMatrix.path("content_hash").asText());
        evidenceMatrix.putArray("links");
        evidenceMatrix.putArray("fact_coverage");
        putEmbeddedHash(evidenceMatrix);

        ObjectNode questionSet = objectMapper.createObjectNode();
        questionSet.put("schema_version", "hearing_question_set.v1");
        questionSet.put("question_set_id", "QUESTION_SET_1");
        questionSet.put("case_id", CASE_ID);
        questionSet.put("case_matrix_version", 1);
        questionSet.put("case_matrix_hash", "1".repeat(64));
        ObjectNode question = questionSet.putArray("questions").addObject();
        question.put("question_id", "QUESTION_1");
        question.putArray("fact_ids").add("FACT_1");
        question.put("issue_id", "ISSUE_1");
        question.putArray("target_roles").add("USER").add("MERCHANT");
        question.put("question_text", "请分别说明事实经过。");

        ArrayNode answers = objectMapper.createArrayNode();
        answers.add(answerBundle("USER"));
        answers.add(answerBundle("MERCHANT"));

        ObjectNode requestSet = objectMapper.createObjectNode();
        requestSet.put("schema_version", "hearing_evidence_request_set.v1");
        requestSet.put("request_set_id", "REQUEST_SET_1");
        requestSet.put("case_id", CASE_ID);
        requestSet.put("case_matrix_version", 2);
        requestSet.put("case_matrix_hash", caseMatrix.path("content_hash").asText());
        ObjectNode request = requestSet.putArray("requests").addObject();
        request.put("request_id", "REQUEST_1");
        request.putArray("fact_ids").add("FACT_1");
        request.putArray("target_roles").add("USER").add("MERCHANT");
        request.put("requested_material", "交易记录");
        request.put("verification_goal", "核验事实发生时间");
        request.put("required", true);

        ArrayNode batches = objectMapper.createArrayNode();
        batches.add(evidenceBatch("USER", "EVIDENCE_USER"));
        batches.add(evidenceBatch("MERCHANT", "EVIDENCE_MERCHANT"));

        ArrayNode policyRules = objectMapper.createArrayNode();
        ObjectNode policyRule = policyRules.addObject();
        policyRule.put("policy_id", "POLICY_DELIVERY_PROOF_V1");
        policyRule.put("rule_code", "DELIVERY_PROOF");
        policyRule.put("rule_version", 1);
        policyRule.put("rule_name", "签收争议举证规则");
        policyRule.put("rule_scope", "DELIVERY_DISPUTE");
        policyRule.put("rule_status", "ACTIVE");
        policyRule.put("effective_from", "2020-01-01T00:00:00Z");
        policyRule.putNull("effective_to");
        policyRule.put("priority", 100);
        policyRule.putObject("conditions").put("requires_delivery_proof", true);
        policyRule.putObject("outcome").put("requires_human_review", true);
        policyRule.putObject("source_document").put("section", "DELIVERY_PROOF");

        return new Fixture(
                caseMatrix, evidenceMatrix, questionSet, answers, requestSet, batches, policyRules);
    }

    private ObjectNode answerBundle(String role) {
        ObjectNode bundle = objectMapper.createObjectNode();
        bundle.put("schema_version", "hearing_answer_bundle.v1");
        bundle.put("question_set_id", "QUESTION_SET_1");
        bundle.put("participant_role", role);
        bundle.put("submission_status", "SUBMITTED");
        bundle.put("submitted_at", NOW.toString());
        ObjectNode answer = bundle.putArray("answers").addObject();
        answer.put("question_id", "QUESTION_1");
        answer.put("answer_text", role + " answer");
        answer.putArray("attachment_refs");
        bundle.putArray("source_message_ids").add("MESSAGE_" + role);
        return bundle;
    }

    private ObjectNode evidenceBatch(String role, String evidenceId) {
        ObjectNode batch = objectMapper.createObjectNode();
        batch.put("schema_version", "hearing_evidence_batch.v1");
        batch.put("request_set_id", "REQUEST_SET_1");
        batch.put("participant_role", role);
        batch.put("submission_status", "SUBMITTED");
        batch.put("submitted_at", NOW.toString());
        batch.putArray("evidence_ids").add(evidenceId);
        batch.putArray("request_ids").add("REQUEST_1");
        batch.put("batch_note", role + " batch");
        return batch;
    }

    private HearingFlowInstanceEntity flowAtDossierFreezing() {
        HearingFlowInstanceEntity value =
                HearingFlowInstanceEntity.start(FLOW_ID, CASE_ID, "HEARING_1", NOW, "system");
        HearingFlowStage[] stages = {
            HearingFlowStage.CASE_INTRODUCTION,
            HearingFlowStage.EVIDENCE_INTRODUCTION,
            HearingFlowStage.INTAKE_QUESTIONS_GENERATING,
            HearingFlowStage.PARTY_ANSWERS_OPEN,
            HearingFlowStage.INTAKE_SYNTHESIZING,
            HearingFlowStage.EVIDENCE_REQUESTS_GENERATING,
            HearingFlowStage.PARTY_EVIDENCE_OPEN,
            HearingFlowStage.EVIDENCE_SYNTHESIZING,
            HearingFlowStage.DOSSIER_FREEZING
        };
        int sequence = 1;
        for (HearingFlowStage stage : stages) {
            sequence++;
            value.advance(
                    stage,
                    sequence,
                    stage.hasSharedPartyDeadline() ? NOW.plusSeconds(3600) : null,
                    NOW.plusSeconds(sequence),
                    "system");
        }
        return value;
    }

    private void putEmbeddedHash(ObjectNode value) {
        ObjectNode copy = value.deepCopy();
        copy.remove("content_hash");
        value.put("content_hash", hash(copy));
    }

    private String hash(JsonNode value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(json(canonical(value)).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            value.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> sorted.set(name, canonical(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value.deepCopy();
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(
            ObjectNode caseMatrix,
            ObjectNode evidenceMatrix,
            ObjectNode questionSet,
            ArrayNode answerBundles,
            ObjectNode requestSet,
            ArrayNode evidenceBatches,
            ArrayNode policyRules) {
        private HearingTrialDossierService.FreezeTrialDossierCommand command() {
            return new HearingTrialDossierService.FreezeTrialDossierCommand(
                    CASE_ID,
                    FLOW_ID,
                    caseMatrix,
                    evidenceMatrix,
                    questionSet,
                    answerBundles,
                    requestSet,
                    evidenceBatches,
                    policyRules,
                    "hearing-controller");
        }
    }
}
