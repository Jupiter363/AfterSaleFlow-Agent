package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.hearing.application.HearingReviewHandoffRecoveryScheduler;
import com.example.dispute.hearing.application.HearingReviewHandoffService;
import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFlowStageStatus;
import com.example.dispute.hearing.domain.HearingFlowStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowArtifactEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowInstanceEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowStageEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowArtifactRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowInstanceRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowStageRepository;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.review.application.ReviewApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HearingReviewHandoffServiceTest {

    private static final String CASE_ID = "CASE_handoff";
    private static final String DRAFT_ID = "DRAFT_V2";
    private static final String PLAN_ID = "PLAN_V2";
    private static final String TASK_ID = "REVIEW_V2";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock private HearingFlowArtifactRepository artifactRepository;
    @Mock private HearingFlowInstanceRepository flowInstanceRepository;
    @Mock private HearingFlowStageRepository flowStageRepository;
    @Mock private AdjudicationDraftRepository draftRepository;
    @Mock private HearingStateRepository hearingStateRepository;
    @Mock private RemedyPlanRepository remedyPlanRepository;
    @Mock private ReviewTaskRepository reviewTaskRepository;
    @Mock private RemedyApplicationService remedyService;
    @Mock private ReviewApplicationService reviewService;

    private HearingReviewHandoffService service;
    private HearingFlowArtifactEntity artifact;
    private HearingFlowInstanceEntity flow;
    private HearingFlowStageEntity handoffStage;
    private ReviewTaskEntity task;

    @BeforeEach
    void setUp() {
        service =
                new HearingReviewHandoffService(
                        artifactRepository,
                        flowInstanceRepository,
                        flowStageRepository,
                        draftRepository,
                        hearingStateRepository,
                        remedyPlanRepository,
                        reviewTaskRepository,
                        remedyService,
                        reviewService,
                        new ObjectMapper().findAndRegisterModules(),
                        CLOCK);
        artifact =
                HearingFlowArtifactEntity.adjudicationDraft(
                        DRAFT_ID,
                        CASE_ID,
                        "HEARING_FLOW_1",
                        "DOSSIER_1",
                        HASH_A,
                        "PROPOSAL_1",
                        HASH_B,
                        "REPORT_1",
                        HASH_C,
                        HASH_A,
                        "{}",
                        "AGENT_RUN_V2",
                        NOW,
                        "hearing-flow-v2");
        flow = flowAtHumanReview();
        handoffStage =
                HearingFlowStageEntity.open(
                        "HEARING_STAGE_14",
                        flow.getId(),
                        CASE_ID,
                        HearingFlowStage.HUMAN_REVIEW_OPEN,
                        flow.getStageSequence(),
                        "SYSTEM",
                        HearingFlowStageStatus.RUNNING,
                        null,
                        "{}",
                        NOW,
                        "hearing-flow-v2");
        task =
                ReviewTaskEntity.pending(
                        TASK_ID,
                        CASE_ID,
                        PLAN_ID,
                        "PACKET_V2",
                        "HIGH",
                        "PLATFORM_REVIEWER",
                        OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC),
                        "system");
    }

    @Test
    void createsOneReviewTaskAndClosesTheHearingIdempotently() {
        stubFrozenChain();
        when(reviewTaskRepository.findFirstByCaseIdAndPlanIdOrderByCreatedAtDesc(
                        CASE_ID, PLAN_ID))
                .thenReturn(Optional.empty(), Optional.of(task));
        when(reviewService.createForWorkflow(CASE_ID, PLAN_ID)).thenReturn(TASK_ID);
        when(flowStageRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.handoff(CASE_ID, DRAFT_ID, HASH_A)).isEqualTo(TASK_ID);
        assertThat(service.handoff(CASE_ID, DRAFT_ID, HASH_A)).isEqualTo(TASK_ID);

        verify(reviewService, times(1)).createForWorkflow(CASE_ID, PLAN_ID);
        assertThat(flow.getCurrentStage()).isEqualTo(HearingFlowStage.CLOSED);
        assertThat(flow.getFlowStatus()).isEqualTo(HearingFlowStatus.CLOSED);
        assertThat(handoffStage.getStageStatus()).isEqualTo(HearingFlowStageStatus.COMPLETED);
        assertThat(handoffStage.getOutputJson())
                .contains("\"review_task_id\":\"" + TASK_ID + "\"")
                .contains("\"review_gate_ready\":true")
                .contains("\"handoff_status\":\"COMPLETED\"");
        ArgumentCaptor<HearingFlowStageEntity> closedStage =
                ArgumentCaptor.forClass(HearingFlowStageEntity.class);
        verify(flowStageRepository).save(closedStage.capture());
        assertThat(closedStage.getValue().getStageCode()).isEqualTo(HearingFlowStage.CLOSED);
        assertThat(closedStage.getValue().getStageStatus())
                .isEqualTo(HearingFlowStageStatus.COMPLETED);
    }

    @Test
    void rejectsAHashThatDoesNotIdentifyTheFrozenV2Artifact() {
        when(artifactRepository.findByCaseIdAndArtifactType(
                        CASE_ID, HearingArtifactType.ADJUDICATION_DRAFT))
                .thenReturn(Optional.of(artifact));

        assertThatThrownBy(() -> service.handoff(CASE_ID, DRAFT_ID, HASH_B))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not bound");

        verify(reviewService, never())
                .createForWorkflow(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsAV2ArtifactWhoseStoredParentHashDoesNotMatchTheJuryReport() {
        HearingFlowArtifactEntity proposal =
                HearingFlowArtifactEntity.judgeProposal(
                        "PROPOSAL_1",
                        CASE_ID,
                        "HEARING_FLOW_1",
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
                        "HEARING_FLOW_1",
                        "DOSSIER_1",
                        HASH_A,
                        proposal.getId(),
                        proposal.getContentHash(),
                        HASH_C,
                        "{}",
                        "RUN_JURY",
                        NOW,
                        "system");
        HearingFlowArtifactEntity badDraft =
                HearingFlowArtifactEntity.adjudicationDraft(
                        DRAFT_ID,
                        CASE_ID,
                        "HEARING_FLOW_1",
                        "DOSSIER_1",
                        HASH_A,
                        proposal.getId(),
                        proposal.getContentHash(),
                        report.getId(),
                        HASH_B,
                        HASH_A,
                        "{}",
                        "RUN_V2",
                        NOW,
                        "system");
        when(artifactRepository.findByCaseIdAndArtifactType(anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            HearingArtifactType type = invocation.getArgument(1);
                            return Optional.of(
                                    switch (type) {
                                        case JUDGE_PROPOSAL -> proposal;
                                        case JURY_REVIEW_REPORT -> report;
                                        case ADJUDICATION_DRAFT -> badDraft;
                                    });
                        });

        assertThatThrownBy(() -> service.handoff(CASE_ID, DRAFT_ID, HASH_A))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("artifact chain is invalid");

        verify(reviewService, never())
                .createForWorkflow(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void recoveryRetriesEveryRecentV2ArtifactThroughTheSameIdempotentHandoff() {
        HearingReviewHandoffService handoff = org.mockito.Mockito.mock(HearingReviewHandoffService.class);
        HearingReviewHandoffRecoveryScheduler scheduler =
                new HearingReviewHandoffRecoveryScheduler(artifactRepository, handoff);
        when(artifactRepository.findTop50ByArtifactTypeOrderByCreatedAtDesc(
                        HearingArtifactType.ADJUDICATION_DRAFT))
                .thenReturn(List.of(artifact));

        scheduler.recover();

        verify(handoff).handoff(CASE_ID, DRAFT_ID, HASH_A);
    }

    private void stubFrozenChain() {
        HearingFlowArtifactEntity proposal =
                HearingFlowArtifactEntity.judgeProposal(
                        "PROPOSAL_1",
                        CASE_ID,
                        "HEARING_FLOW_1",
                        "DOSSIER_1",
                        HASH_A,
                        HASH_B,
                        "{}",
                        "AGENT_RUN_V1",
                        NOW,
                        "hearing-flow-v2");
        HearingFlowArtifactEntity report =
                HearingFlowArtifactEntity.juryReviewReport(
                        "REPORT_1",
                        CASE_ID,
                        "HEARING_FLOW_1",
                        "DOSSIER_1",
                        HASH_A,
                        proposal.getId(),
                        proposal.getContentHash(),
                        HASH_C,
                        "{}",
                        "AGENT_RUN_JURY",
                        NOW,
                        "hearing-flow-v2");
        AdjudicationDraftEntity projection =
                AdjudicationDraftEntity.create(
                        DRAFT_ID,
                        CASE_ID,
                        "HEARING_STATE_1",
                        2,
                        "[]",
                        "[]",
                        "[]",
                        "[]",
                        "REFUND",
                        new BigDecimal("0.90"),
                        "draft",
                        "PRESIDING_JUDGE",
                        "AGENT_RUN_V2",
                        "PENDING_HUMAN_REVIEW",
                        "hearing-flow-v2");
        HearingStateEntity hearing =
                HearingStateEntity.start(
                        "HEARING_STATE_1", CASE_ID, "WORKFLOW_1", "hearing-flow-v2");
        hearing.complete(true, "hearing-flow-v2");
        RemedyPlanEntity plan =
                RemedyPlanEntity.pendingApproval(
                        PLAN_ID,
                        CASE_ID,
                        DRAFT_ID,
                        1,
                        RouteType.FULL_HEARING,
                        RiskLevel.HIGH,
                        "[]",
                        "[]",
                        "[]",
                        "system");
        when(artifactRepository.findByCaseIdAndArtifactType(anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            HearingArtifactType type = invocation.getArgument(1);
                            return Optional.of(
                                    switch (type) {
                                        case JUDGE_PROPOSAL -> proposal;
                                        case JURY_REVIEW_REPORT -> report;
                                        case ADJUDICATION_DRAFT -> artifact;
                                    });
                        });
        when(draftRepository.findById(DRAFT_ID)).thenReturn(Optional.of(projection));
        when(hearingStateRepository.findByCaseId(CASE_ID)).thenReturn(Optional.of(hearing));
        when(remedyPlanRepository.findFirstByCaseIdOrderByPlanVersionDesc(CASE_ID))
                .thenReturn(Optional.of(plan));
        when(remedyPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
        when(flowInstanceRepository.findByCaseIdForUpdate(CASE_ID)).thenReturn(Optional.of(flow));
        when(flowStageRepository.findByFlowInstanceIdAndStageSequence(
                        flow.getId(), flow.getStageSequence()))
                .thenReturn(Optional.of(handoffStage));
    }

    private HearingFlowInstanceEntity flowAtHumanReview() {
        HearingFlowInstanceEntity value =
                HearingFlowInstanceEntity.start(
                        "HEARING_FLOW_1", CASE_ID, "HEARING_STATE_1", NOW, "system");
        int sequence = 1;
        for (HearingFlowStage stage : HearingFlowStage.values()) {
            if (stage == HearingFlowStage.COURT_PREPARING) {
                continue;
            }
            sequence++;
            value.advance(
                    stage,
                    sequence,
                    stage.hasSharedPartyDeadline() ? NOW.plusSeconds(3600) : null,
                    NOW,
                    "system");
            if (stage == HearingFlowStage.HUMAN_REVIEW_OPEN) {
                break;
            }
        }
        return value;
    }
}
