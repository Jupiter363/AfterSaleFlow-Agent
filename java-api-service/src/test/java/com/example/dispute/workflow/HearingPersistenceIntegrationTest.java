package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.executor.application.ToolExecutorService;
import com.example.dispute.evaluation.application.CaseClosureService;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.entity.PartySubmissionEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceItemRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.PartySubmissionRepository;
import com.example.dispute.infrastructure.persistence.repository.PolicyRuleRepository;
import com.example.dispute.workflow.application.CaseFulfillmentDisputeActivitiesImpl;
import com.example.dispute.workflow.application.HearingAgentResult;
import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.review.application.ReviewApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Testcontainers
class HearingPersistenceIntegrationTest {

    @Container
    private static final GenericContainer<?> POSTGRESQL =
            new GenericContainer<>(
                            DockerImageName.parse(
                                    "public.ecr.aws/docker/library/postgres:16-alpine"))
                    .withEnv("POSTGRES_DB", "dispute_hearing")
                    .withEnv("POSTGRES_USER", "dispute_test")
                    .withEnv("POSTGRES_PASSWORD", "local_test_password")
                    .withExposedPorts(5432);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () ->
                        "jdbc:postgresql://"
                                + POSTGRESQL.getHost()
                                + ":"
                                + POSTGRESQL.getMappedPort(5432)
                                + "/dispute_hearing");
        registry.add("spring.datasource.username", () -> "dispute_test");
        registry.add("spring.datasource.password", () -> "local_test_password");
    }

    @Autowired private FulfillmentCaseRepository caseRepository;
    @Autowired private HearingStateRepository stateRepository;
    @Autowired private HearingRecordRepository recordRepository;
    @Autowired private AdjudicationDraftRepository draftRepository;
    @Autowired private PartySubmissionRepository submissionRepository;
    @Autowired private EvidenceItemRepository evidenceRepository;
    @Autowired private PolicyRuleRepository policyRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void persistsStateAppendOnlyRecordsDraftAndPartySubmission() {
        FulfillmentCaseEntity disputeCase =
                FulfillmentCaseEntity.create(
                        "CASE_hearingdb",
                        "ORDER_hearingdb",
                        null,
                        "user-hearing",
                        "merchant-hearing",
                        "idem-hearing",
                        "NON_RECEIPT",
                        "delivery dispute",
                        "package marked delivered but not received",
                        RiskLevel.HIGH,
                        "user-hearing");
        disputeCase.completeIntake(
                "FULFILLMENT_CONFLICT",
                CaseStatus.INTAKE_COMPLETED,
                RiskLevel.HIGH,
                "{}",
                "user-hearing");
        disputeCase.markDossierBuilt("user-hearing");
        disputeCase.applyRoute(RouteType.FULL_HEARING, "user-hearing");
        disputeCase.startHearing("CASEWORKFLOW_CASE_hearingdb", "temporal-worker");
        caseRepository.saveAndFlush(disputeCase);

        HearingStateEntity state =
                stateRepository.saveAndFlush(
                        HearingStateEntity.start(
                                "HEARING_db",
                                disputeCase.getId(),
                                "CASEWORKFLOW_CASE_hearingdb",
                                "temporal-worker"));
        recordRepository.saveAndFlush(
                HearingRecordEntity.record(
                        "HREC_db",
                        disputeCase.getId(),
                        state.getId(),
                        state.getWorkflowId(),
                        "issue_framing_node",
                        0,
                        "AGENT_NODE",
                        "{}",
                        "{\"issues\":[]}",
                        "{}",
                        "hearing-v1",
                        "test-model",
                        12L,
                        42,
                        "temporal-worker"));
        draftRepository.saveAndFlush(
                AdjudicationDraftEntity.create(
                        "DRAFT_db",
                        disputeCase.getId(),
                        state.getId(),
                        1,
                        "[]",
                        "[]",
                        "[]",
                        "[\"verify delivery proof\"]",
                        "DENY_OR_REFUND_AFTER_REVIEW",
                        new BigDecimal("0.7500"),
                        "This is a non-final recommendation for human review.",
                        "python-agent-service/test-model",
                        "PENDING_HUMAN_REVIEW",
                        "temporal-worker"));
        submissionRepository.saveAndFlush(
                PartySubmissionEntity.submit(
                        "SUB_db",
                        disputeCase.getId(),
                        null,
                        "USER",
                        "user-hearing",
                        "SUPPLEMENTAL_EVIDENCE",
                        "door camera did not show delivery",
                        "{}",
                        "[]"));

        assertThat(stateRepository.findByCaseId(disputeCase.getId()))
                .hasValueSatisfying(
                        saved ->
                                assertThat(saved.getWorkflowId())
                                        .isEqualTo("CASEWORKFLOW_CASE_hearingdb"));
        assertThat(
                        recordRepository
                                .findAllByCaseIdOrderByCreatedAtAsc(disputeCase.getId()))
                .extracting(HearingRecordEntity::getNodeName)
                .containsExactly("issue_framing_node");
        assertThat(
                        draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(
                                disputeCase.getId()))
                .hasValueSatisfying(
                        draft ->
                                assertThat(draft.getDraftStatus())
                                        .isEqualTo("PENDING_HUMAN_REVIEW"));
        assertThat(
                        submissionRepository.findAllByCaseIdOrderByCreatedAtAsc(
                                disputeCase.getId()))
                .singleElement()
                .satisfies(
                        submission ->
                                assertThat(submission.getSubmittedByRole())
                                        .isEqualTo("USER"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void activityCallsAgentOutsideTransactionAndPersistsEveryNodeAndDraft()
            throws Exception {
        FulfillmentCaseEntity disputeCase =
                routedCase("CASE_activitydb", "idem-activity");
        caseRepository.saveAndFlush(disputeCase);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AtomicBoolean calledOutsideTransaction = new AtomicBoolean();
        var raw =
                mapper.readTree(
                        """
                        {
                          "case_id":"CASE_activitydb",
                          "workflow_id":"CASEWORKFLOW_CASE_activitydb",
                          "workflow_status":"COMPLETED",
                          "executed_nodes":["issue_framing_node","adjudication_draft_node"],
                          "issue_framing":{"neutral_summary":"dispute","issues":[]},
                          "evidence_gap":{"requires_supplemental_evidence":false,"gaps":[]},
                          "evidence_cross_check":{"findings":[]},
                          "rule_application":{"applications":[]},
                          "adjudication_draft":{"draft":{
                            "draft_status":"PENDING_HUMAN_REVIEW",
                            "recommended_outcome":"REVIEW_REFUND",
                            "reasoning_summary":"Evidence requires platform confirmation.",
                            "issue_findings":[],
                            "confidence":0.72,
                            "review_focus":["verify delivery evidence"]
                          }},
                          "manual_review_reasons":[],
                          "prompt_version":"hearing-v1",
                          "model":"test-model"
                        }
                        """);
        var activities =
                new CaseFulfillmentDisputeActivitiesImpl(
                        caseRepository,
                        evidenceRepository,
                        policyRepository,
                        stateRepository,
                        recordRepository,
                        draftRepository,
                        submissionRepository,
                        (request, traceId, requestId) -> {
                            calledOutsideTransaction.set(
                                    !org.springframework.transaction.support
                                            .TransactionSynchronizationManager
                                            .isActualTransactionActive());
                            assertThat(request.path("claims")).hasSize(1);
                            return new HearingAgentResult(
                                    raw,
                                    false,
                                    false,
                                    List.of(
                                            "issue_framing_node",
                                            "adjudication_draft_node"),
                                    "hearing-v1",
                                    "test-model");
                        },
                        org.mockito.Mockito.mock(RemedyApplicationService.class),
                        org.mockito.Mockito.mock(ReviewApplicationService.class),
                        org.mockito.Mockito.mock(ToolExecutorService.class),
                        org.mockito.Mockito.mock(CaseClosureService.class),
                        org.mockito.Mockito.mock(AuditRecorder.class),
                        mapper,
                        new TransactionTemplate(transactionManager));
        String workflowId = "CASEWORKFLOW_CASE_activitydb";
        activities.initializeHearing(
                new CaseWorkflowInput(
                        disputeCase.getId(),
                        workflowId,
                        RouteType.FULL_HEARING,
                        Duration.ofHours(72),
                        2));

        var result =
                activities.analyzeHearing(
                        new HearingAnalysisActivityCommand(
                                disputeCase.getId(), workflowId, 0, false, false));
        activities.completeHearing(
                disputeCase.getId(), workflowId, false, false);

        assertThat(calledOutsideTransaction).isTrue();
        assertThat(result.draftId()).startsWith("DRAFT_");
        assertThat(
                        recordRepository.findAllByCaseIdOrderByCreatedAtAsc(
                                disputeCase.getId()))
                .hasSize(2);
        assertThat(stateRepository.findByCaseId(disputeCase.getId()))
                .hasValueSatisfying(
                        state -> {
                            assertThat(state.getHearingStatus().name())
                                    .isEqualTo("COMPLETED");
                            assertThat(state.getCurrentNode())
                                    .isEqualTo("C6_ADJUDICATION_DRAFT");
                        });
        assertThat(caseRepository.findById(disputeCase.getId()))
                .hasValueSatisfying(
                        saved ->
                                assertThat(saved.getCaseStatus())
                                        .isEqualTo(CaseStatus.HEARING));
    }

    private static FulfillmentCaseEntity routedCase(
            String caseId, String idempotencyKey) {
        FulfillmentCaseEntity disputeCase =
                FulfillmentCaseEntity.create(
                        caseId,
                        "ORDER_" + caseId,
                        null,
                        "user-hearing",
                        "merchant-hearing",
                        idempotencyKey,
                        "NON_RECEIPT",
                        "delivery dispute",
                        "package marked delivered but not received",
                        RiskLevel.HIGH,
                        "user-hearing");
        disputeCase.completeIntake(
                "FULFILLMENT_CONFLICT",
                CaseStatus.INTAKE_COMPLETED,
                RiskLevel.HIGH,
                "{}",
                "user-hearing");
        disputeCase.markDossierBuilt("user-hearing");
        disputeCase.applyRoute(RouteType.FULL_HEARING, "user-hearing");
        return disputeCase;
    }
}
