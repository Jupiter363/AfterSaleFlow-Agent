package com.example.dispute.workflow.runtime.rooms.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.evidence.application.EvidenceDossierFreezer;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import com.example.dispute.evidence.domain.EvidenceVerificationStatus;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceDossierItemEntity;
import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceDossierItemRepository;
import com.example.dispute.evidence.infrastructure.persistence.repository.EvidenceVerificationRepository;
import com.example.dispute.hearing.domain.HearingWriterMode;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.room.domain.RoomType;
import com.example.dispute.room.infrastructure.persistence.entity.CaseIntakeDossierEntity;
import com.example.dispute.room.infrastructure.persistence.repository.CaseIntakeDossierRepository;
import com.example.dispute.workflow.contract.v1.ContractJson;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.SnapshotRef;
import com.example.dispute.workflow.runtime.exchange.rooms.ProductionRoomObjectIndex;
import com.example.dispute.workflow.runtime.ingress.rooms.MinioProductionRoomCommandPayloadPublisher;
import com.example.dispute.workflow.runtime.ingress.rooms.MinioProductionRoomCommandPayloadPublisher.PublishedObject;
import com.example.dispute.workflow.runtime.ingress.rooms.ProductionHearingInvocationPublisher;
import com.example.dispute.workflow.temporal.room.hearing.HearingRoomStart;
import com.example.dispute.workflow.temporal.room.hearing.HearingWorkflowStage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class JdbcTargetHearingAgentStageInputFactoryContractTest {

  private static final String CASE_ID = "CASE_EVIDENCE_MATRIX";
  private static final String SHARED_BARRIER_RECEIPT_HASH = "3".repeat(64);
  private static final String HEARING_REFERENCE_BATCH_ID = "opaque-hearing-reference";
  private static final String ORIGINAL_EVIDENCE_SUBMISSION_BATCH_ID =
      "opaque-original-evidence-submission";

  @Test
  void frozenEvidenceDossierBindsExactSharedBarrierToCanonicalHearingInvocation()
      throws Exception {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    ObjectNode preHearingMatrix = caseMatrix(mapper);
    ObjectNode successorMatrix = successorCaseMatrix(mapper, preHearingMatrix);
    EvidenceDossierRepository dossierRepository = mock(EvidenceDossierRepository.class);
    EvidenceDossierItemRepository dossierItemRepository = mock(EvidenceDossierItemRepository.class);
    EvidenceItemRepository evidenceRepository = mock(EvidenceItemRepository.class);
    EvidenceVerificationRepository verificationRepository = mock(EvidenceVerificationRepository.class);
    CaseIntakeDossierRepository intakeDossierRepository = mock(CaseIntakeDossierRepository.class);
    EvidenceItemEntity evidence = evidence();
    EvidenceVerificationEntity verification = verification();
    CaseIntakeDossierEntity intakeDossier = mock(CaseIntakeDossierEntity.class);

    when(dossierRepository.findByCaseIdAndDossierVersion(CASE_ID, 1))
        .thenReturn(Optional.empty());
    when(dossierRepository.findTopByCaseIdOrderByDossierVersionDesc(CASE_ID))
        .thenReturn(Optional.empty());
    when(dossierRepository.save(any(EvidenceDossierEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(dossierItemRepository.saveAll(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(evidenceRepository.findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(
            CASE_ID))
        .thenReturn(List.of(evidence));
    when(verificationRepository.findTopByEvidenceIdOrderByVerificationVersionDesc(
            "EVIDENCE_MATRIX_1"))
        .thenReturn(Optional.of(verification));
    when(intakeDossier.getDossierJson())
        .thenReturn(
            mapper.createObjectNode().set("case_fact_matrix", preHearingMatrix).toString());
    when(intakeDossierRepository.findByCaseIdAndRoomType(CASE_ID, RoomType.INTAKE))
        .thenReturn(Optional.of(intakeDossier));

    EvidenceDossierEntity frozen;
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(EvidenceDossierRepository.class, () -> dossierRepository);
      context.registerBean(EvidenceDossierItemRepository.class, () -> dossierItemRepository);
      context.registerBean(EvidenceItemRepository.class, () -> evidenceRepository);
      context.registerBean(EvidenceVerificationRepository.class, () -> verificationRepository);
      context.registerBean(CaseIntakeDossierRepository.class, () -> intakeDossierRepository);
      context.registerBean(ObjectMapper.class, () -> mapper);
      context.registerBean(
          Clock.class,
          () -> Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC));
      context.registerBean(EvidenceDossierFreezer.class);
      context.refresh();
      frozen = context.getBean(EvidenceDossierFreezer.class).freeze(CASE_ID, 1, "system");
    }

    HearingRoomStart start = hearingStart();
    ObjectNode terminalReceipt = terminalReceipt(mapper, start, frozen);
    DataSource dataSource =
        dataSource(
            mapper,
            preHearingMatrix,
            successorMatrix,
            frozen,
            List.of(terminalReceipt.toString()),
            ORIGINAL_EVIDENCE_SUBMISSION_BATCH_ID);
    JdbcTargetHearingAgentStageInputFactory factory =
        new JdbcTargetHearingAgentStageInputFactory(dataSource, mapper);
    JdbcTargetHearingAgentStageInputFactory.StageInput intakeQuestionsInput =
        factory.load(start, HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING);
    ObjectNode intakeQuestions = intakeQuestionsInput.request();
    ObjectNode intakeSynthesis =
        factory.load(start, HearingWorkflowStage.INTAKE_SYNTHESIZING).request();
    ObjectNode request =
        factory.load(start, HearingWorkflowStage.EVIDENCE_REQUESTS_GENERATING).request();
    ObjectNode synthesis =
        factory.load(start, HearingWorkflowStage.EVIDENCE_SYNTHESIZING).request();
    JsonNode requestCaseMatrix = request.path("case_fact_matrix");
    JsonNode matrix = request.path("evidence_dossier").path("fact_evidence_matrix");

    assertThat(intakeQuestions.path("case_fact_matrix")).isEqualTo(preHearingMatrix);
    assertThat(intakeSynthesis.path("case_fact_matrix")).isEqualTo(preHearingMatrix);
    assertThat(intakeQuestions.path("context_schema_version").asText())
        .isEqualTo("hearing_intake_context.v4");
    assertThat(intakeQuestions.path("question_slots")).hasSize(5);
    assertThat(intakeSynthesis.path("question_set").path("schema_version").asText())
        .isEqualTo("hearing_question_set.v4");
    assertThat(intakeSynthesis.path("party_answer_bundles")).hasSize(2);
    assertThat(intakeSynthesis.path("party_answer_bundles").path(0)
        .path("participant_role").asText()).isEqualTo("USER");
    assertThat(intakeSynthesis.path("party_answer_bundles").path(1)
        .path("participant_role").asText()).isEqualTo("MERCHANT");
    assertThat(intakeSynthesis.has("party_submissions")).isFalse();
    assertThat(requestCaseMatrix).isEqualTo(successorMatrix);
    assertThat(synthesis.path("case_fact_matrix")).isEqualTo(successorMatrix);
    assertThat(matrix.isObject()).as("frozen fact_evidence_matrix must be a v3 object").isTrue();
    assertThat(matrix.path("schema_version").asText()).isEqualTo("fact_evidence_matrix.v3");
    assertThat(matrix.path("case_id").asText()).isEqualTo(CASE_ID);
    assertThat(matrix.path("matrix_version").asInt()).isEqualTo(1);
    assertThat(matrix.path("matrix_status").asText()).isEqualTo("FROZEN");
    assertThat(matrix.path("parent_ref").isNull()).isTrue();
    assertThat(matrix.path("case_fact_matrix_id").asText())
        .isEqualTo(preHearingMatrix.path("matrix_id").asText());
    assertThat(matrix.path("case_fact_matrix_version").asInt())
        .isEqualTo(preHearingMatrix.path("matrix_version").asInt());
    assertThat(matrix.path("case_fact_matrix_hash").asText())
        .isEqualTo(preHearingMatrix.path("content_hash").asText());
    assertThat(matrix.path("content_hash").asText())
        .isEqualTo(
            JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
                mapper, (ObjectNode) matrix, "content_hash"));
    assertThat(matrix.path("links")).hasSize(1);
    assertThat(matrix.path("links").path(0).path("fact_id").asText())
        .isEqualTo("FACT_DELIVERY");
    assertThat(matrix.path("links").path(0).path("evidence_id").asText())
        .isEqualTo("EVIDENCE_MATRIX_1");
    assertThat(matrix.path("fact_coverage")).hasSize(1);
    assertThat(matrix.path("fact_coverage").path(0).path("coverage_status").asText())
        .isEqualTo("COVERED_BY_FROZEN_DOSSIER");
    assertThat(synthesis.path("prior_fact_evidence_matrix")).isEqualTo(matrix);
    assertThat(synthesis.path("prior_fact_evidence_matrix").path("matrix_version").asInt())
        .isEqualTo(1);
    JsonNode userBatch = synthesis.path("party_batches").path(0);
    JsonNode merchantBatch = synthesis.path("party_batches").path(1);
    assertThat(userBatch.path("participant_role").asText()).isEqualTo("USER");
    assertThat(userBatch.path("terminal_status").asText()).isEqualTo("COMPLETED");
    assertThat(userBatch.path("batch_id").asText()).isEqualTo(HEARING_REFERENCE_BATCH_ID);
    assertThat(userBatch.path("batch_id").asText())
        .isNotEqualTo(ORIGINAL_EVIDENCE_SUBMISSION_BATCH_ID);
    assertThat(userBatch.path("evidence")).hasSize(1);
    assertThat(userBatch.path("evidence").path(0).path("evidence_id").asText())
        .isEqualTo("EVIDENCE_HEARING_SUPPLEMENT_1");
    assertThat(userBatch.path("evidence").path(0).path("parsed_text").asText())
        .isEqualTo("补充物流轨迹显示包裹已在驿站签收。");
    assertThat(userBatch.path("evidence").path(0).path("claimed_fact").asText())
        .isEqualTo("物流系统记录包裹已签收");
    assertThat(merchantBatch.path("participant_role").asText()).isEqualTo("MERCHANT");
    assertThat(merchantBatch.path("terminal_status").asText()).isEqualTo("COMPLETED");
    assertThat(userBatch.path("source_refs").path(0).asText())
        .isEqualTo("EVIDENCE_HEARING_SUPPLEMENT_1");
    assertThat(merchantBatch.path("source_refs").path(0).asText())
        .isEqualTo("EVIDENCE_HEARING_SUPPLEMENT_1");
    assertThat(merchantBatch.path("evidence")).isEmpty();

    MinioProductionRoomCommandPayloadPublisher payloadPublisher =
        mock(MinioProductionRoomCommandPayloadPublisher.class);
    ArgumentCaptor<JsonNode> publishedDocuments = ArgumentCaptor.forClass(JsonNode.class);
    when(payloadPublisher.publishCanonical(
            anyString(), eq("HEARING"), publishedDocuments.capture()))
        .thenAnswer(
            invocation -> {
              String artifactId = invocation.getArgument(0);
              JsonNode document = invocation.getArgument(2);
              String hash = ContractJson.sha256Hex(document);
              return new PublishedObject(
                  new SnapshotRef(
                      artifactId,
                      document.path("schema_version").asText(),
                      "urn:test:hearing:" + hash,
                      hash,
                      ContractJson.canonicalize(document).length),
                  "test-bucket",
                  "hearing/" + hash + ".json");
            });
    ProductionHearingInvocationPublisher invocationPublisher =
        new ProductionHearingInvocationPublisher(
            payloadPublisher, mock(ProductionRoomObjectIndex.class), mapper);
    ObjectNode event = mapper.createObjectNode();
    event.put("schema_version", "production-runtime-hearing-stage-event.v1");
    event.put("case_id", start.caseId());
    event.put("stage_sequence", HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING.sequence());
    event.put("operation", intakeQuestionsInput.operation());
    var published =
        invocationPublisher.publish(
            "hearing-stage:4:shared-barrier",
            intakeQuestionsInput.operation(),
            intakeQuestionsInput.sharedBarrierReceiptHash(),
            intakeQuestionsInput.request(),
            event);
    JdbcTargetHearingAgentStageInputFactory.StageInput replayInput =
        factory.load(start, HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING);
    var replay =
        invocationPublisher.publish(
            "hearing-stage:4:shared-barrier",
            replayInput.operation(),
            replayInput.sharedBarrierReceiptHash(),
            replayInput.request(),
            event);

    assertThat(intakeQuestionsInput.sharedBarrierReceiptHash())
        .isEqualTo(SHARED_BARRIER_RECEIPT_HASH);
    assertThat(replayInput).isEqualTo(intakeQuestionsInput);
    assertThat(published).isEqualTo(replay);
    assertThat(publishedDocuments.getAllValues()).hasSize(4);
    JsonNode invocation = publishedDocuments.getAllValues().get(0);
    JsonNode replayInvocation = publishedDocuments.getAllValues().get(2);
    assertThat(invocation.path("schema_version").asText())
        .isEqualTo("production-runtime-hearing-invocation.v4");
    assertThat(invocation.path("shared_barrier_receipt_hash").asText())
        .isEqualTo(terminalReceipt.path("receipt_hash").asText())
        .isEqualTo(SHARED_BARRIER_RECEIPT_HASH);
    assertThat(invocation.path("request")).isEqualTo(intakeQuestionsInput.request());
    assertThat(invocation.has("fixture_proposal")).isFalse();
    assertThat(invocation.has("fixture_work_results")).isFalse();
    assertThat(invocation.path("request").path("case_fact_matrix").toString())
        .contains("物流系统记录包裹已签收", "双方对实际交付有争议");
    assertThat(publishedDocuments.getAllValues().get(1)).isEqualTo(event);
    assertThat(replayInvocation).isEqualTo(invocation);
    assertThat(publishedDocuments.getAllValues().get(3)).isEqualTo(event);

    assertThatThrownBy(
            () ->
                new JdbcTargetHearingAgentStageInputFactory(
                        dataSource(
                            mapper,
                            preHearingMatrix,
                            successorMatrix,
                            frozen,
                            List.of(terminalReceipt.toString()),
                            ORIGINAL_EVIDENCE_SUBMISSION_BATCH_ID,
                            0),
                        mapper)
                    .load(start, HearingWorkflowStage.EVIDENCE_SYNTHESIZING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bound supplemental Hearing evidence")
        .hasMessageContaining("is absent or ambiguous");
    assertThatThrownBy(
            () ->
                new JdbcTargetHearingAgentStageInputFactory(
                        dataSource(
                            mapper,
                            preHearingMatrix,
                            successorMatrix,
                            frozen,
                            List.of(),
                            ORIGINAL_EVIDENCE_SUBMISSION_BATCH_ID),
                        mapper)
                    .load(start, HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal receipt is absent or ambiguous");
    assertThatThrownBy(
            () ->
                new JdbcTargetHearingAgentStageInputFactory(
                        dataSource(
                            mapper,
                            preHearingMatrix,
                            successorMatrix,
                            frozen,
                            List.of(terminalReceipt.toString(), terminalReceipt.toString()),
                            ORIGINAL_EVIDENCE_SUBMISSION_BATCH_ID),
                        mapper)
                    .load(start, HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal receipt is absent or ambiguous");
    ObjectNode mismatchedReceipt = terminalReceipt.deepCopy();
    mismatchedReceipt.put("hearing_room_id", "ROOM_HEARING_FOREIGN");
    assertThatThrownBy(
            () ->
                new JdbcTargetHearingAgentStageInputFactory(
                        dataSource(
                            mapper,
                            preHearingMatrix,
                            successorMatrix,
                            frozen,
                            List.of(mismatchedReceipt.toString()),
                            ORIGINAL_EVIDENCE_SUBMISSION_BATCH_ID),
                        mapper)
                    .load(start, HearingWorkflowStage.INTAKE_QUESTIONS_GENERATING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal receipt binding is invalid");
    assertThatThrownBy(
            () ->
                invocationPublisher.publish(
                    "hearing-stage:4:shared-barrier",
                    intakeQuestionsInput.operation(),
                    "A".repeat(64),
                    intakeQuestionsInput.request(),
                    event))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shared barrier receipt hash");
    assertThat(publishedDocuments.getAllValues()).hasSize(4);
    validateStrictPythonRequests(intakeQuestions, intakeSynthesis, request, synthesis);
  }

  private static EvidenceItemEntity evidence() {
    EvidenceItemEntity value = mock(EvidenceItemEntity.class);
    when(value.getId()).thenReturn("EVIDENCE_MATRIX_1");
    when(value.getCaseId()).thenReturn(CASE_ID);
    when(value.getSubmissionStatus()).thenReturn(EvidenceSubmissionStatus.SUBMITTED);
    when(value.getEvidenceType()).thenReturn("DELIVERY_RECORD");
    when(value.getSourceType()).thenReturn("USER_UPLOAD");
    when(value.getSubmittedByRole()).thenReturn("USER");
    when(value.getOriginalFilename()).thenReturn("delivery-proof.pdf");
    when(value.getFileHash()).thenReturn("file-hash");
    when(value.getSubmissionBatchId()).thenReturn("EVIDENCE_BATCH_MATRIX_1");
    when(value.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-08-15T00:00:00Z"));
    when(value.getMetadataJson())
        .thenReturn("{\"claimed_fact\":\"物流签收记录证明系统已登记签收\"}");
    return value;
  }

  private static EvidenceVerificationEntity verification() {
    EvidenceVerificationEntity value = mock(EvidenceVerificationEntity.class);
    when(value.getVerificationStatus()).thenReturn(EvidenceVerificationStatus.VERIFIED);
    when(value.getAgentFindingsJson())
        .thenReturn(
            """
            {"authenticity_score":0.95,"authenticity_score_explanation":"来源可追溯。",
             "relevance_score":0.9,"relevance_score_explanation":"直接关联物流事实。",
             "completeness_score":0.85,"completeness_score_explanation":"主要节点完整。",
             "assessment_confidence":0.9,"assessment_confidence_explanation":"材料可读。",
             "risk_level":"LOW","risk_explanation":"未见明显异常。",
             "source_basis":["物流签收记录"],"formation_time_assessment":"时间可读。",
             "findings":[{"finding_type":"LOGISTICS_RECORD","description":"可见签收节点"}],
             "limitations":[],"unsupported_claims":[],"assessment_public_text":"物流记录已核验。",
             "reason_details":[],"fact_links":[{"fact_id":"FACT_DELIVERY",
             "relation":"CONTENT_SUPPORTS","reason":"物流签收记录与该正式事实直接关联。",
             "source_unit_id":"SOURCE_UNIT_DELIVERY","observation_slot":"OBS_DELIVERY"}]}
            """);
    when(value.isRequiresHumanReview()).thenReturn(false);
    return value;
  }

  private static ObjectNode caseMatrix(ObjectMapper mapper) throws Exception {
    ObjectNode matrix =
        (ObjectNode)
            mapper.readTree(
                """
                {
                  "schema_version":"case_fact_matrix.v2",
                  "case_id":"CASE_EVIDENCE_MATRIX",
                  "matrix_id":"CASE_MATRIX_EVIDENCE_MATRIX",
                  "matrix_version":3,
                  "matrix_kind":"BILATERAL_FROZEN",
                  "parent_ref":null,
                  "content_hash":"0000000000000000000000000000000000000000000000000000000000000000",
                  "party_map":{"initiator_role":"USER","respondent_role":"MERCHANT"},
                  "source_refs":["SOURCE_INTAKE_FINAL"],
                  "case_overview":{"neutral_summary":"用户称未收到商品，商家称物流已签收。",
                    "core_conflict":"包裹是否实际交付。","summary_source_fact_ids":["FACT_DELIVERY"]},
                  "claims":{"initiator_claim":{"initiator_role":"USER","requested_resolution":"REFUND",
                    "requested_amount":100.0,"requested_items":"商品","reason_summary":"未收到商品。",
                    "position_summary":"用户要求退款。","source_refs":["SOURCE_USER"]},
                    "respondent_reported_by_initiator":null,
                    "respondent_direct":{"respondent_role":"MERCHANT","attitude":"DISAGREE",
                      "position_summary":"商家认为已经签收。","alternative_proposal":null,
                      "source_type":"RESPONDENT_DIRECT_INTAKE","source_refs":["SOURCE_MERCHANT"]},
                    "claim_conflict":"双方对实际交付有争议。"},
                  "fact_rows":[{"fact_id":"FACT_DELIVERY","category":"LOGISTICS",
                    "fact_target":"物流系统记录包裹已签收","materiality":"CORE",
                    "origin":{"introduced_stage":"INITIATOR_INTAKE","source_refs":["SOURCE_USER"]},
                    "positions":{"USER":{"stance":"DENY","position_summary":"用户否认本人收到。",
                      "asserted_value":"未收到","source_type":"DIRECT_PARTY_STATEMENT","source_refs":["SOURCE_USER"]},
                      "MERCHANT":{"stance":"CONFIRM","position_summary":"商家确认物流已签收。",
                      "asserted_value":"已签收","source_type":"DIRECT_PARTY_STATEMENT","source_refs":["SOURCE_MERCHANT"]}},
                    "party_alignment":{"status":"CONTESTED","agreed_statement":null,
                      "conflict_summary":"是否实际交付存在争议。"},"requires_resolution":true,
                    "truth_status":"NOT_EVALUATED","evidence_coverage_status":"COVERED_BY_FROZEN_DOSSIER"}],
                  "fact_relationships":[],
                  "generation_ref":{"actor_role":"SYSTEM","source_stage":"RESPONDENT_INTAKE",
                    "latest_source_ref":"SOURCE_INTAKE_FINAL",
                    "source_context_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                  "fact_indexes":{"not_computed_fact_ids":[],"agreed_fact_ids":[],
                    "partially_agreed_fact_ids":[],"contested_fact_ids":["FACT_DELIVERY"],
                    "one_sided_fact_ids":[],"unresolved_fact_ids":[],"core_fact_ids":["FACT_DELIVERY"],
                    "requires_resolution_fact_ids":["FACT_DELIVERY"]}
                }
                """);
    ObjectNode unsigned = matrix.deepCopy();
    unsigned.remove("content_hash");
    matrix.put("content_hash", ContractJson.sha256Hex(unsigned));
    return matrix;
  }

  private static ObjectNode successorCaseMatrix(ObjectMapper mapper, ObjectNode parent) {
    ObjectNode successor = parent.deepCopy();
    successor.put("matrix_id", "CASE_MATRIX_EVIDENCE_MATRIX_SUCCESSOR");
    successor.put("matrix_version", parent.path("matrix_version").asInt() + 1);
    successor.put("matrix_kind", "HEARING_CLARIFIED_FROZEN");
    ObjectNode parentRef = successor.putObject("parent_ref");
    parentRef.put("matrix_id", parent.path("matrix_id").asText());
    parentRef.put("matrix_version", parent.path("matrix_version").asInt());
    parentRef.put("content_hash", parent.path("content_hash").asText());
    successor.putArray("source_refs").add("SOURCE_INTAKE_SYNTHESIS");
    ObjectNode generation = (ObjectNode) successor.path("generation_ref");
    generation.put("actor_role", "SYSTEM");
    generation.put("source_stage", "HEARING_CLARIFICATION");
    generation.put("latest_source_ref", "SOURCE_INTAKE_SYNTHESIS");
    generation.put(
        "source_context_hash",
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    successor.put("content_hash", "0".repeat(64));
    successor.put(
        "content_hash",
        JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
            mapper, successor, "content_hash"));
    return successor;
  }

  private static HearingRoomStart hearingStart() {
    Instant opened = Instant.parse("2026-08-15T01:00:00Z");
    return new HearingRoomStart(
        "hearing-room-start.v1",
        "tenant-evidence",
        CASE_ID,
        "ROOM_EVIDENCE_MATRIX",
        "FLOW_EVIDENCE_MATRIX",
        "EPOCH_EVIDENCE_MATRIX",
        HearingWriterMode.TEMPORAL,
        1,
        1,
        "user-local",
        "merchant-local",
        opened,
        opened.plusSeconds(3600),
        300,
        1,
        1,
        "hearing-build-v1");
  }

  private static DataSource dataSource(
      ObjectMapper mapper,
      ObjectNode preHearingMatrix,
      ObjectNode successorMatrix,
      EvidenceDossierEntity frozen,
      List<String> terminalReceiptRows,
      String originalEvidenceSubmissionBatchId)
      throws Exception {
    return dataSource(
        mapper,
        preHearingMatrix,
        successorMatrix,
        frozen,
        terminalReceiptRows,
        originalEvidenceSubmissionBatchId,
        1);
  }

  private static DataSource dataSource(
      ObjectMapper mapper,
      ObjectNode preHearingMatrix,
      ObjectNode successorMatrix,
      EvidenceDossierEntity frozen,
      List<String> terminalReceiptRows,
      String originalEvidenceSubmissionBatchId,
      int supplementalEvidenceRowCount)
      throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString()))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("join hearing_domain_receipt receipt")) {
                return preludeStatement(
                    connection,
                    mapper,
                    preHearingMatrix,
                    frozen);
              }
              return statement(
                  connection,
                  sqlPayloads(
                      mapper,
                      sql,
                      preHearingMatrix,
                      successorMatrix,
                      frozen,
                      terminalReceiptRows,
                      originalEvidenceSubmissionBatchId,
                      supplementalEvidenceRowCount));
            });
    return dataSource;
  }

  private static List<String> sqlPayloads(
      ObjectMapper mapper,
      String sql,
      ObjectNode preHearingMatrix,
      ObjectNode successorMatrix,
      EvidenceDossierEntity frozen,
      List<String> terminalReceiptRows,
      String originalEvidenceSubmissionBatchId,
      int supplementalEvidenceRowCount)
      throws Exception {
    if (sql.contains("select dossier_json from case_intake_dossier")) {
      return List.of(
          mapper.createObjectNode().set("case_fact_matrix", preHearingMatrix).toString());
    }
    if (sql.contains("from evidence_dossier")) {
      ObjectNode value = mapper.createObjectNode();
      JsonNode matrixSummary = mapper.readTree(frozen.getMatrixSummaryJson());
      JsonNode frozenMatrix = matrixSummary.path("fact_evidence_matrix");
      value.put("dossier_id", frozen.getId());
      value.put("dossier_version", frozen.getDossierVersion());
      value.put("dossier_status", frozen.getDossierStatus());
      value.set(
          "fact_evidence_matrix",
          frozenMatrix);
      value.set("evidence_summary", mapper.readTree(frozen.getSummaryJson()));
      return List.of(value.toString());
    }
    if (sql.contains("from production_runtime_evidence_terminal_receipt")) {
      assertThat(sql)
          .contains(
              "receipt.tenant_surrogate = ?",
              "receipt.case_id = ?",
              "receipt.hearing_room_id = ?",
              "receipt.dossier_id = ?",
              "receipt.dossier_version = ?");
      return terminalReceiptRows;
    }
    if (sql.contains("select output_json from hearing_flow_stage")) {
      ObjectNode value = mapper.createObjectNode();
      value.set("case_fact_matrix", successorMatrix);
      return List.of(value.toString());
    }
    if (sql.contains("from hearing_flow_instance")) {
      ObjectNode value = mapper.createObjectNode();
      value.put("flow_instance_id", "FLOW_EVIDENCE_MATRIX");
      value.put("stage_code", HearingWorkflowStage.EVIDENCE_REQUESTS_GENERATING.name());
      value.put("stage_sequence", HearingWorkflowStage.EVIDENCE_REQUESTS_GENERATING.sequence());
      value.putObject("stage_input");
      value.putObject("stage_output");
      value.putObject("trial_dossier");
      var actions = value.putArray("actions");
      ObjectNode questionSet = v4QuestionSet(
          mapper, preHearingMatrix,
          ContractJson.sha256Hex(preludePayload(mapper, preHearingMatrix, frozen)));
      ObjectNode questionAction = actions.addObject();
      questionAction.put("id", questionSet.path("question_set_id").asText());
      questionAction.put("action_type", "QUESTION_SET");
      questionAction.put("schema_version", "hearing_question_set.v4");
      questionAction.putNull("participant_id");
      questionAction.putNull("participant_role");
      questionAction.putNull("submission_status");
      questionAction.put("content_hash", questionSet.path("question_set_hash").asText());
      questionAction.set("payload", questionSet);
      addV4AnswerAction(
          mapper, actions.addObject(), "USER", "user-local", questionSet);
      addV4AnswerAction(
          mapper, actions.addObject(), "MERCHANT", "merchant-local", questionSet);
      actions
          .addObject()
          .put("action_type", "EVIDENCE_REQUEST_SET")
          .putObject("payload")
          .putObject("proposal")
          .putArray("requests");
      ObjectNode userEvidence = actions.addObject();
      addPartyAction(userEvidence, "EVIDENCE_BATCH", "USER", "user-local");
      ObjectNode userEvidencePayload = (ObjectNode) userEvidence.path("payload");
      userEvidencePayload.put("submission_status", "SUBMITTED");
      userEvidencePayload.put("batch_id", HEARING_REFERENCE_BATCH_ID);
      ((com.fasterxml.jackson.databind.node.ArrayNode) userEvidencePayload.path("evidence_ids"))
          .add("EVIDENCE_HEARING_SUPPLEMENT_1");
      ObjectNode merchantEvidence = actions.addObject();
      addPartyAction(merchantEvidence, "EVIDENCE_BATCH", "MERCHANT", "merchant-local");
      ObjectNode merchantEvidencePayload = (ObjectNode) merchantEvidence.path("payload");
      merchantEvidencePayload.put("submission_status", "SUBMITTED");
      merchantEvidencePayload.put("batch_id", "opaque-merchant-hearing-reference");
      ((com.fasterxml.jackson.databind.node.ArrayNode)
              merchantEvidencePayload.path("evidence_ids"))
          .add("EVIDENCE_HEARING_SUPPLEMENT_1");
      return List.of(value.toString());
    }
    if (sql.contains("from evidence_item")) {
      assertThat(originalEvidenceSubmissionBatchId)
          .isNotEqualTo(HEARING_REFERENCE_BATCH_ID);
      assertThat(sql)
          .contains(
              "evidence.id = ?",
              "evidence.case_id = ?")
          .doesNotContain(
              "evidence.submitted_by_id",
              "evidence.submitted_by_role",
              "evidence.submission_batch_id",
              "evidence.submission_status",
              "evidence.visibility",
              "evidence.submitted_at",
              "evidence.deleted_at",
              "and jsonb_typeof(evidence.metadata_json)");
      ObjectNode value = mapper.createObjectNode();
      value.put("evidence_id", "EVIDENCE_HEARING_SUPPLEMENT_1");
      value.put("evidence_type", "DELIVERY_RECORD");
      value.put("source_type", "USER_UPLOAD");
      value.put("original_filename", "supplemental-delivery.txt");
      value.put("content_type", "text/plain");
      value.put("file_hash", "supplemental-file-hash");
      value.put("parsed_text", "补充物流轨迹显示包裹已在驿站签收。");
      value.put("claimed_fact", "物流系统记录包裹已签收");
      value.putObject("metadata").put("claimed_fact", "物流系统记录包裹已签收");
      return java.util.Collections.nCopies(supplementalEvidenceRowCount, value.toString());
    }
    throw new IllegalArgumentException("unexpected SQL in focused contract fixture: " + sql);
  }

  private static void addPartyAction(
      ObjectNode action, String actionType, String role, String participantId) {
    action.put("id", "ACTION_" + actionType + "_" + role);
    action.put("action_type", actionType);
    action.put("schema_version", "hearing_evidence_batch.v1");
    action.put("participant_id", participantId);
    action.put("participant_role", role);
    action.put("submission_status", "AUTO_TIMEOUT");
    action.put("content_hash", "e".repeat(64));
    ObjectNode payload = action.putObject("payload");
    payload.put("participant_id", participantId);
    payload.put("participant_role", role);
    payload.put("submission_status", "AUTO_TIMEOUT");
    payload.put("batch_id", "BATCH_" + role);
    payload.putArray("source_message_ids");
    payload.putArray("request_ids");
    payload.put("batch_note", "");
    payload.putArray("evidence_ids");
  }

  private static void addV4AnswerAction(
      ObjectMapper mapper,
      ObjectNode action,
      String role,
      String participantId,
      ObjectNode questionSet) {
    String bundleId = "ANSWER_BUNDLE_" + role;
    ObjectNode payload = mapper.createObjectNode();
    payload.put("schema_version", "hearing_answer_bundle.v4");
    payload.put("answer_bundle_id", bundleId);
    payload.put("answer_bundle_hash", "0".repeat(64));
    payload.put("question_set_id", questionSet.path("question_set_id").asText());
    payload.put("question_set_hash", questionSet.path("question_set_hash").asText());
    payload.put("formal_issue_catalog_hash",
        questionSet.path("formal_issue_catalog_hash").asText());
    payload.put("participant_id", participantId);
    payload.put("participant_role", role);
    payload.put("submission_status", "SUBMITTED");
    JsonNode question = questionSet.path("questions").get(0);
    payload.putArray("answer_units").addObject()
        .put("answer_unit_id", "ANSWER_UNIT_" + role)
        .put("question_id", question.path("question_id").asText())
        .put("issue_id", question.path("issue_id").asText())
        .put("answer_text", role + " current answer");
    payload.putArray("source_message_ids").add("MESSAGE_" + role);
    payload.put("answer_bundle_hash",
        JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
            mapper, payload, "answer_bundle_hash"));
    action.put("id", bundleId);
    action.put("action_type", "ANSWER_BUNDLE");
    action.put("schema_version", "hearing_answer_bundle.v4");
    action.put("participant_id", participantId);
    action.put("participant_role", role);
    action.put("submission_status", "SUBMITTED");
    action.put("content_hash", payload.path("answer_bundle_hash").asText());
    action.set("payload", payload);
  }

  private static ObjectNode v4QuestionSet(
      ObjectMapper mapper, ObjectNode matrix, String preludeHash) {
    ObjectNode baseline = mapper.createObjectNode();
    baseline.put("issue_statement", "包裹是否实际交付");
    baseline.putArray("source_fact_ids").add("FACT_DELIVERY");
    ObjectNode positions = baseline.putObject("effective_party_positions");
    positions.putObject("USER")
        .put("position_source", "M1")
        .put("position_summary", "用户否认收到包裹。");
    positions.putObject("MERCHANT")
        .put("position_source", "M1")
        .put("position_summary", "商家认为包裹已签收。");
    baseline.putObject("alignment")
        .put("status", "CONTESTED")
        .putNull("agreed_statement")
        .put("conflict_summary", "双方对实际交付存在争议。");
    ObjectNode question = mapper.createObjectNode();
    question.put("question_slot_id", "QUESTION_SLOT_01");
    question.put("question_id", "QUESTION_V4_01");
    question.put("issue_id", "ISSUE_V4_01");
    question.put("issue_version", 1);
    question.put("issue_state_hash", "a".repeat(64));
    question.putArray("target_roles").add("USER").add("MERCHANT");
    question.putArray("fact_ids").add("FACT_DELIVERY");
    question.put("question_text", "请双方说明包裹是否实际交付。");
    question.set("issue_baseline", baseline.deepCopy());
    question.putObject("party_prompts")
        .put("USER", "请说明收货情况。")
        .put("MERCHANT", "请说明交付情况。");
    ObjectNode catalog = mapper.createObjectNode();
    catalog.put("schema_version", "hearing_formal_issue_catalog.v4");
    ObjectNode issue = catalog.putArray("issues").addObject();
    issue.put("question_slot_id", "QUESTION_SLOT_01");
    issue.put("question_id", "QUESTION_V4_01");
    issue.put("issue_id", "ISSUE_V4_01");
    issue.put("issue_version", 1);
    issue.put("issue_state_hash", question.path("issue_state_hash").asText());
    issue.set("issue_baseline", baseline);
    ObjectNode result = mapper.createObjectNode();
    result.put("schema_version", "hearing_question_set.v4");
    result.put("question_set_id", "QUESTION_SET_V4");
    result.put("question_set_hash", "0".repeat(64));
    result.put("formal_issue_catalog_hash",
        JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
            mapper, catalog, "__absent_hash_field__"));
    result.put("case_id", CASE_ID);
    result.put("source_matrix_id", matrix.path("matrix_id").asText());
    result.put("source_matrix_version", matrix.path("matrix_version").asInt());
    result.put("source_matrix_hash", matrix.path("content_hash").asText());
    result.put("prelude_authority_hash", preludeHash);
    result.putArray("questions").add(question);
    result.put("question_set_hash",
        JdbcTargetHearingAgentStageInputFactory.pythonContentHash(
            mapper, result, "question_set_hash"));
    return result;
  }

  private static ObjectNode terminalReceipt(
      ObjectMapper mapper, HearingRoomStart start, EvidenceDossierEntity frozen) {
    ObjectNode receipt = mapper.createObjectNode();
    receipt.put("receipt_hash", SHARED_BARRIER_RECEIPT_HASH);
    receipt.put("tenant_surrogate", start.tenantSurrogate());
    receipt.put("case_id", start.caseId());
    receipt.put("hearing_room_id", start.roomId());
    receipt.put("dossier_id", frozen.getId());
    receipt.put("dossier_version", frozen.getDossierVersion());
    return receipt;
  }

  private static PreparedStatement statement(Connection connection, List<String> payloads)
      throws Exception {
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    AtomicInteger cursor = new AtomicInteger(-1);
    when(statement.getConnection()).thenReturn(connection);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenAnswer(ignored -> cursor.incrementAndGet() < payloads.size());
    when(rows.getString(1)).thenAnswer(ignored -> payloads.get(cursor.get()));
    return statement;
  }

  private static PreparedStatement preludeStatement(
      Connection connection,
      ObjectMapper mapper,
      ObjectNode preHearingMatrix,
      EvidenceDossierEntity frozen)
      throws Exception {
    ObjectNode prelude = preludePayload(mapper, preHearingMatrix, frozen);
    List<String> columns =
        List.of(
            prelude.toString(),
            ContractJson.sha256Hex(prelude),
            "HDR_PRELUDE_AUTHORITY",
            "4".repeat(64));
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    AtomicInteger cursor = new AtomicInteger(-1);
    when(statement.getConnection()).thenReturn(connection);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenAnswer(ignored -> cursor.incrementAndGet() == 0);
    when(rows.getString(org.mockito.ArgumentMatchers.anyInt()))
        .thenAnswer(invocation -> columns.get(((Integer) invocation.getArgument(0)) - 1));
    return statement;
  }

  private static ObjectNode preludePayload(
      ObjectMapper mapper,
      ObjectNode preHearingMatrix,
      EvidenceDossierEntity frozen) {
    try {
      HearingRoomStart start = hearingStart();
      ObjectNode evidenceDossier = mapper.createObjectNode();
      JsonNode matrixSummary = mapper.readTree(frozen.getMatrixSummaryJson());
      evidenceDossier.put("dossier_id", frozen.getId());
      evidenceDossier.put("dossier_version", frozen.getDossierVersion());
      evidenceDossier.put("dossier_status", frozen.getDossierStatus());
      evidenceDossier.set(
          "fact_evidence_matrix",
          matrixSummary.path("fact_evidence_matrix").deepCopy());
      evidenceDossier.set("evidence_summary", mapper.readTree(frozen.getSummaryJson()));
      ObjectNode prelude = mapper.createObjectNode();
      prelude.put("schema_version", JdbcTargetHearingPreludeAuthority.SCHEMA_VERSION);
      prelude.put("tenant_surrogate", start.tenantSurrogate());
      prelude.put("case_id", start.caseId());
      prelude.put("flow_instance_id", start.flowInstanceId());
      prelude.put("epoch_id", start.epochId());
      prelude.put("room_epoch", start.roomEpoch());
      prelude.put("fencing_token", start.fencingToken());
      prelude.set("case_fact_matrix", preHearingMatrix.deepCopy());
      prelude.set("evidence_dossier", evidenceDossier);
      return prelude;
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void validateStrictPythonRequests(
      ObjectNode intakeQuestions,
      ObjectNode intakeSynthesis,
      ObjectNode requests,
      ObjectNode synthesis) throws Exception {
    Path pythonService = Path.of("..", "agent-runtime").toAbsolutePath().normalize();
    ProcessBuilder builder =
        new ProcessBuilder(
            "D:\\miniconda\\python.exe",
            "-c",
            "from app.schemas.hearing_flow import HearingIntakeQuestionsRequestV4, HearingIntakeSynthesisRequestV4, HearingEvidenceRequestsRequest, HearingEvidenceSynthesisRequest; "
                + "import os; HearingIntakeQuestionsRequestV4.model_validate_json(os.environ['HEARING_INTAKE_QUESTIONS_JSON']); "
                + "HearingIntakeSynthesisRequestV4.model_validate_json(os.environ['HEARING_INTAKE_SYNTHESIS_JSON']); "
                + "HearingEvidenceRequestsRequest.model_validate_json(os.environ['HEARING_REQUEST_JSON']); "
                + "HearingEvidenceSynthesisRequest.model_validate_json(os.environ['HEARING_SYNTHESIS_JSON'])");
    builder.directory(pythonService.toFile());
    builder.redirectErrorStream(true);
    builder.environment().put("HEARING_INTAKE_QUESTIONS_JSON", intakeQuestions.toString());
    builder.environment().put("HEARING_INTAKE_SYNTHESIS_JSON", intakeSynthesis.toString());
    builder.environment().put("HEARING_REQUEST_JSON", requests.toString());
    builder.environment().put("HEARING_SYNTHESIS_JSON", synthesis.toString());
    Process process = builder.start();
    boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(finished).as("strict Python request validation timed out").isTrue();
    assertThat(process.exitValue()).as(output).isZero();
  }
}
