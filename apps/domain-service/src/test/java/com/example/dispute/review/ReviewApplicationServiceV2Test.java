package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowArtifactEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowArtifactRepository;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewPacketEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalPolicyDecisionRepository;
import com.example.dispute.infrastructure.persistence.repository.ApprovalRecordRepository;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewPacketRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.notification.application.CaseLifecycleNotificationService;
import com.example.dispute.review.application.PostReviewOrchestrationService;
import com.example.dispute.review.application.ReviewApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class ReviewApplicationServiceV2Test {

    private static final String CASE_ID = "CASE_review_v2";
    private static final String DRAFT_ID = "DRAFT_V2";
    private static final String PLAN_ID = "PLAN_V2";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private RemedyPlanRepository planRepository;
    @Mock private AdjudicationDraftRepository draftRepository;
    @Mock private HearingFlowArtifactRepository artifactRepository;
    @Mock private HearingStateRepository hearingRepository;
    @Mock private EvidenceDossierRepository dossierRepository;
    @Mock private ReviewPacketRepository packetRepository;
    @Mock private ReviewTaskRepository taskRepository;
    @Mock private ApprovalRecordRepository approvalRepository;
    @Mock private ApprovalPolicyDecisionRepository policyDecisionRepository;
    @Mock private CaseLifecycleNotificationService lifecycleNotifications;
    @Mock private AuditRecorder auditRecorder;
    @Mock private PostReviewOrchestrationService postReviewOrchestration;
    @Mock private TransactionTemplate transactions;

    private ObjectMapper objectMapper;
    private ReviewApplicationService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service =
                new ReviewApplicationService(
                        caseRepository,
                        planRepository,
                        draftRepository,
                        artifactRepository,
                        hearingRepository,
                        dossierRepository,
                        packetRepository,
                        taskRepository,
                        approvalRepository,
                        policyDecisionRepository,
                        lifecycleNotifications,
                        auditRecorder,
                        postReviewOrchestration,
                        objectMapper,
                        transactions,
                        new BigDecimal("500.00"),
                        new BigDecimal("300.00"),
                        168,
                        1);
    }

    @Test
    void freezesTheExactV2ArtifactAndAllThreeExistingAgentRunReferences() throws Exception {
        FulfillmentCaseEntity dispute = hearingCase();
        AdjudicationDraftEntity projection = projection();
        RemedyPlanEntity plan = plan();
        String exactPayload =
                "{\"schema_version\":\"adjudication_draft.v2\","
                        + "\"draft_id\":\"DRAFT_V2\","
                        + "\"trial_dossier_id\":\"DOSSIER_1\","
                        + "\"trial_dossier_hash\":\"" + HASH_A + "\","
                        + "\"proposal_id\":\"PROPOSAL_1\","
                        + "\"proposal_content_hash\":\"" + HASH_B + "\","
                        + "\"report_id\":\"REPORT_1\","
                        + "\"report_content_hash\":\"" + HASH_C + "\","
                        + "\"draft\":{\"draft_text\":\"原样裁决正文\"},"
                        + "\"public_text\":\"原样裁决正文\","
                        + "\"content_hash\":\"" + HASH_A + "\"}";
        HearingFlowArtifactEntity proposal =
                HearingFlowArtifactEntity.judgeProposal(
                        "PROPOSAL_1",
                        CASE_ID,
                        "FLOW_1",
                        "DOSSIER_1",
                        HASH_A,
                        HASH_B,
                        "{}",
                        "RUN_V1",
                        NOW,
                        "system");
        HearingFlowArtifactEntity report =
                HearingFlowArtifactEntity.juryReviewReport(
                        "REPORT_1",
                        CASE_ID,
                        "FLOW_1",
                        "DOSSIER_1",
                        HASH_A,
                        proposal.getId(),
                        proposal.getContentHash(),
                        HASH_C,
                        "{}",
                        "RUN_JURY",
                        NOW,
                        "system");
        HearingFlowArtifactEntity v2 =
                HearingFlowArtifactEntity.adjudicationDraft(
                        DRAFT_ID,
                        CASE_ID,
                        "FLOW_1",
                        "DOSSIER_1",
                        HASH_A,
                        proposal.getId(),
                        proposal.getContentHash(),
                        report.getId(),
                        report.getContentHash(),
                        HASH_A,
                        exactPayload,
                        "RUN_V2",
                        NOW,
                        "system");

        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(dispute));
        when(taskRepository.findFirstByCaseIdAndPlanIdOrderByCreatedAtDesc(CASE_ID, PLAN_ID))
                .thenReturn(Optional.empty());
        when(planRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(draftRepository.findFirstByCaseIdOrderByDraftVersionDesc(CASE_ID))
                .thenReturn(Optional.of(projection));
        when(artifactRepository.findByCaseIdAndArtifactType(anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            HearingArtifactType type = invocation.getArgument(1);
                            return Optional.of(
                                    switch (type) {
                                        case JUDGE_PROPOSAL -> proposal;
                                        case JURY_REVIEW_REPORT -> report;
                                        case ADJUDICATION_DRAFT -> v2;
                                    });
                        });
        when(hearingRepository.findByCaseId(CASE_ID)).thenReturn(Optional.empty());
        when(dossierRepository.findByCaseId(CASE_ID)).thenReturn(Optional.empty());
        when(packetRepository.findFirstByCaseIdAndPlanIdOrderByPacketVersionDesc(
                        CASE_ID, PLAN_ID))
                .thenReturn(Optional.empty());
        when(packetRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(caseRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String taskId = service.createForWorkflow(CASE_ID, PLAN_ID);

        assertThat(taskId).startsWith("REVIEW_");
        ArgumentCaptor<ReviewPacketEntity> packet =
                ArgumentCaptor.forClass(ReviewPacketEntity.class);
        org.mockito.Mockito.verify(packetRepository).save(packet.capture());
        assertThat(packet.getValue().getDraftJson()).isEqualTo(exactPayload);
        assertThat(objectMapper.readTree(packet.getValue().getDraftJson()))
                .isEqualTo(objectMapper.readTree(exactPayload));
        assertThat(packet.getValue().getAgentRunRefsJson())
                .isEqualTo("[\"RUN_V1\",\"RUN_JURY\",\"RUN_V2\"]");
    }

    private FulfillmentCaseEntity hearingCase() {
        FulfillmentCaseEntity dispute =
                FulfillmentCaseEntity.create(
                        CASE_ID,
                        "ORDER_V2",
                        null,
                        "user-v2",
                        "merchant-v2",
                        "CREATE_V2",
                        "QUALITY_DISPUTE",
                        "V2 review",
                        "review exact artifact",
                        RiskLevel.HIGH,
                        "user-v2");
        dispute.completeIntake(
                "QUALITY_DISPUTE", CaseStatus.INTAKE_COMPLETED, RiskLevel.HIGH, "{}", "system");
        dispute.markDossierBuilt("system");
        dispute.applyRoute(RouteType.FULL_HEARING, "system");
        dispute.markRemedyPlanned("system");
        return dispute;
    }

    private AdjudicationDraftEntity projection() {
        return AdjudicationDraftEntity.create(
                DRAFT_ID,
                CASE_ID,
                "HEARING_STATE_1",
                2,
                "[]",
                "[]",
                "[]",
                "[]",
                "REFUND",
                new BigDecimal("0.72"),
                "原样裁决正文",
                "PRESIDING_JUDGE",
                "RUN_V2",
                "PENDING_HUMAN_REVIEW",
                "system");
    }

    private RemedyPlanEntity plan() {
        return RemedyPlanEntity.pendingApproval(
                PLAN_ID,
                CASE_ID,
                DRAFT_ID,
                1,
                RouteType.FULL_HEARING,
                RiskLevel.HIGH,
                "[{\"action_type\":\"REFUND\"}]",
                "[]",
                "[]",
                "system");
    }
}
