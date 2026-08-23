package com.example.dispute.workflow.projection.hearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.hearing.application.HearingFlowView;
import com.example.dispute.hearing.application.query.HearingProjectionQueryService;
import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.domain.HearingFlowActionType;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFlowStageStatus;
import com.example.dispute.hearing.domain.HearingFlowStatus;
import com.example.dispute.hearing.domain.HearingFlowSubmissionStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowActionEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowArtifactEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowInstanceEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowStageEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingTrialDossierEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowActionRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowArtifactRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowInstanceRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowStageRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingTrialDossierRepository;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.entity.ReviewTaskEntity;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class HearingProjectionQueryServiceTest {

    private static final String CASE_ID = "CASE-1";
    private static final String FLOW_ID = "HEARING-FLOW-1";
    private static final String STAGE_ID = "HEARING-STAGE-1";

    private final FulfillmentCaseRepository caseRepository = mock(FulfillmentCaseRepository.class);
    private final HearingFlowInstanceRepository instanceRepository =
            mock(HearingFlowInstanceRepository.class);
    private final HearingFlowStageRepository stageRepository = mock(HearingFlowStageRepository.class);
    private final HearingFlowActionRepository actionRepository =
            mock(HearingFlowActionRepository.class);
    private final HearingFlowArtifactRepository artifactRepository =
            mock(HearingFlowArtifactRepository.class);
    private final HearingTrialDossierRepository trialDossierRepository =
            mock(HearingTrialDossierRepository.class);
    private final RemedyPlanRepository remedyPlanRepository = mock(RemedyPlanRepository.class);
    private final ReviewTaskRepository reviewTaskRepository = mock(ReviewTaskRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HearingProjectionQueryService service =
            new HearingProjectionQueryService(
                    caseRepository,
                    instanceRepository,
                    stageRepository,
                    actionRepository,
                    artifactRepository,
                    trialDossierRepository,
                    remedyPlanRepository,
                    reviewTaskRepository,
                    new HearingProjectionAdapter(),
                    objectMapper);

    @Test
    void getAndCompleteReadExistingProjectionWithoutLocksOrWrites() {
        stubBaseline(HearingFlowStage.CASE_INTRODUCTION, 2, List.of());

        HearingFlowView get =
                service.get(CASE_ID, new AuthenticatedActor("user-1", ActorRole.USER));
        HearingFlowView complete =
                service.completeGate(
                        CASE_ID, new AuthenticatedActor("user-1", ActorRole.USER));

        assertThat(get).isEqualTo(complete);
        assertThat(get.status().stageCode()).isEqualTo("CASE_INTRODUCTION");
        assertThat(get.status().stageSequence()).isEqualTo(2);
        assertReadOnlyBoundary();
        assertNoWritesOrWriteLocks();
    }

    @Test
    void privatePartyPayloadNeverEntersTheAuthorizedProjection() throws Exception {
        HearingFlowActionEntity questionSet = mock(HearingFlowActionEntity.class);
        when(questionSet.getActionType()).thenReturn(HearingFlowActionType.QUESTION_SET);
        when(questionSet.getPayloadJson())
                .thenReturn(
                        "{\"schema_version\":\"hearing_question_set.v1\","
                                + "\"question_set_id\":\"QUESTION-1\",\"questions\":[]}");

        HearingFlowActionEntity privateAnswer = mock(HearingFlowActionEntity.class);
        when(privateAnswer.getActionType()).thenReturn(HearingFlowActionType.ANSWER_BUNDLE);
        when(privateAnswer.getStageId()).thenReturn(STAGE_ID);
        when(privateAnswer.getParticipantId()).thenReturn("user-1");
        when(privateAnswer.getParticipantRole()).thenReturn(ActorRole.USER);
        when(privateAnswer.getSubmissionStatus())
                .thenReturn(HearingFlowSubmissionStatus.SUBMITTED);
        when(privateAnswer.getPayloadJson())
                .thenReturn(
                        "{\"statement_text\":\"private-answer\","
                                + "\"settlement\":\"private-offer\"}");
        stubBaseline(
                HearingFlowStage.PARTY_ANSWERS_OPEN,
                5,
                List.of(questionSet, privateAnswer));

        HearingFlowView view =
                service.get(CASE_ID, new AuthenticatedActor("user-1", ActorRole.USER));

        assertThat(view.questionSet().path("question_set_id").asText()).isEqualTo("QUESTION-1");
        assertThat(view.status().partyStatuses())
                .containsEntry("USER", "SUBMITTED")
                .containsEntry("MERCHANT", "PENDING");
        assertThat(objectMapper.writeValueAsString(view))
                .doesNotContain("private-answer", "private-offer", "statement_text", "settlement");
        verify(privateAnswer, never()).getPayloadJson();
        assertNoWritesOrWriteLocks();
    }

    @Test
    void mapsOnlyImmutableDossierAndDecisionReferencesAtTheReviewGate() {
        stubBaseline(HearingFlowStage.HUMAN_REVIEW_OPEN, 14, List.of());
        HearingTrialDossierEntity dossier = mock(HearingTrialDossierEntity.class);
        when(dossier.getId()).thenReturn("DOSSIER-1");
        when(dossier.getSchemaVersion()).thenReturn("trial_dossier.v1");
        when(dossier.getContentHash()).thenReturn("d".repeat(64));
        when(trialDossierRepository.findByCaseId(CASE_ID)).thenReturn(Optional.of(dossier));

        HearingFlowArtifactEntity proposal = artifact("PROPOSAL-1", "judge_proposal.v1", "a");
        HearingFlowArtifactEntity review = artifact("REVIEW-1", "jury_review_report.v1", "b");
        HearingFlowArtifactEntity draft = artifact("DRAFT-1", "adjudication_draft.v2", "c");
        when(artifactRepository.findByCaseIdAndArtifactType(
                        CASE_ID, HearingArtifactType.JUDGE_PROPOSAL))
                .thenReturn(Optional.of(proposal));
        when(artifactRepository.findByCaseIdAndArtifactType(
                        CASE_ID, HearingArtifactType.JURY_REVIEW_REPORT))
                .thenReturn(Optional.of(review));
        when(artifactRepository.findByCaseIdAndArtifactType(
                        CASE_ID, HearingArtifactType.ADJUDICATION_DRAFT))
                .thenReturn(Optional.of(draft));
        RemedyPlanEntity plan = mock(RemedyPlanEntity.class);
        when(plan.getId()).thenReturn("PLAN-1");
        when(plan.getAdjudicationDraftId()).thenReturn("DRAFT-1");
        when(remedyPlanRepository.findFirstByCaseIdOrderByPlanVersionDesc(CASE_ID))
                .thenReturn(Optional.of(plan));
        when(reviewTaskRepository.findFirstByCaseIdAndPlanIdOrderByCreatedAtDesc(
                        CASE_ID, "PLAN-1"))
                .thenReturn(Optional.of(mock(ReviewTaskEntity.class)));

        HearingFlowView view =
                service.get(CASE_ID, new AuthenticatedActor("reviewer-1", ActorRole.PLATFORM_REVIEWER));

        assertThat(view.status().reviewGateReady()).isTrue();
        assertThat(view.status().latestDraftId()).isEqualTo("DRAFT-1");
        assertThat(view.trialDossier())
                .isEqualTo(
                        new HearingFlowView.Reference(
                                "DOSSIER-1", "trial_dossier.v1", "d".repeat(64)));
        assertThat(view.decisionChain())
                .containsOnlyKeys(
                        "JUDGE_PROPOSAL", "JURY_REVIEW_REPORT", "ADJUDICATION_DRAFT");
        assertNoWritesOrWriteLocks();
    }

    @Test
    void projectsTheCompletedM2AndIssueStateAuthoritiesWithoutLegacyAliases() throws Exception {
        stubBaseline(HearingFlowStage.EVIDENCE_REQUESTS_GENERATING, 7, List.of());
        HearingFlowStageEntity synthesis = mock(HearingFlowStageEntity.class);
        when(synthesis.getStageStatus()).thenReturn(HearingFlowStageStatus.COMPLETED);
        var output = objectMapper.createObjectNode();
        output.put("schema_version", "hearing_intake_synthesis.v5");
        output.putObject("case_fact_matrix")
                .put("schema_version", "case_fact_matrix.v2")
                .put("matrix_id", "MATRIX_M2")
                .put("content_hash", "a".repeat(64));
        output.putObject("issue_state_set")
                .put("schema_version", "hearing_issue_state_set.v4")
                .put("issue_state_set_id", "ISSUE_STATE_M2")
                .put("content_hash", "b".repeat(64));
        when(synthesis.getOutputJson()).thenReturn(objectMapper.writeValueAsString(output));
        when(stageRepository.findByFlowInstanceIdAndStageCode(
                        FLOW_ID, HearingFlowStage.INTAKE_SYNTHESIZING))
                .thenReturn(Optional.of(synthesis));

        HearingFlowView view =
                service.get(CASE_ID, new AuthenticatedActor("user-1", ActorRole.USER));

        assertThat(view.projectionSchemaVersion()).isEqualTo("hearing-flow-projection.v4");
        assertThat(view.caseFactMatrix())
                .isEqualTo(new HearingFlowView.Reference(
                        "MATRIX_M2", "case_fact_matrix.v2", "a".repeat(64)));
        assertThat(view.issueStateSet())
                .isEqualTo(new HearingFlowView.Reference(
                        "ISSUE_STATE_M2", "hearing_issue_state_set.v4", "b".repeat(64)));
        assertThat(objectMapper.writeValueAsString(view)).doesNotContain("issue_set");
        assertNoWritesOrWriteLocks();
    }

    @Test
    void rejectsAnUnauthorizedActorBeforeReadingAnyHearingProjection() {
        FulfillmentCaseEntity dispute = caseFixture();
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(dispute));

        assertThatThrownBy(
                        () ->
                                service.get(
                                        CASE_ID,
                                        new AuthenticatedActor(
                                                "customer-service-1", ActorRole.CUSTOMER_SERVICE)))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(
                instanceRepository,
                stageRepository,
                actionRepository,
                artifactRepository,
                trialDossierRepository,
                remedyPlanRepository,
                reviewTaskRepository);
    }

    @Test
    void doesNotCreateAFlowWhenNoProjectionHasBeenInitialized() {
        FulfillmentCaseEntity dispute = caseFixture();
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(dispute));
        when(instanceRepository.findByCaseId(CASE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.get(
                                        CASE_ID,
                                        new AuthenticatedActor("merchant-1", ActorRole.MERCHANT)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.errorCode())
                                    .isEqualTo(ErrorCode.CASE_STATUS_INVALID);
                            assertThat(exception.getMessage())
                                    .isEqualTo("hearing flow is not initialized");
                            assertThat(exception.details()).containsEntry("case_id", CASE_ID);
                        });

        assertNoWritesOrWriteLocks();
    }

    private void stubBaseline(
            HearingFlowStage stageCode,
            int stageSequence,
            List<HearingFlowActionEntity> actions) {
        FulfillmentCaseEntity dispute = caseFixture();
        HearingFlowInstanceEntity instance = mock(HearingFlowInstanceEntity.class);
        when(instance.getId()).thenReturn(FLOW_ID);
        when(instance.getCaseId()).thenReturn(CASE_ID);
        when(instance.getSchemaVersion()).thenReturn("hearing_flow.v2");
        when(instance.getCurrentStage()).thenReturn(stageCode);
        when(instance.getStageSequence()).thenReturn(stageSequence);
        when(instance.getFlowStatus()).thenReturn(HearingFlowStatus.ACTIVE);
        HearingFlowStageEntity stage = mock(HearingFlowStageEntity.class);
        when(stage.getId()).thenReturn(STAGE_ID);
        when(stage.getStageCode()).thenReturn(stageCode);
        when(stage.getStageStatus()).thenReturn(HearingFlowStageStatus.RUNNING);

        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(dispute));
        when(instanceRepository.findByCaseId(CASE_ID)).thenReturn(Optional.of(instance));
        when(stageRepository.findByFlowInstanceIdAndStageSequence(FLOW_ID, stageSequence))
                .thenReturn(Optional.of(stage));
        when(actionRepository.findAllByFlowInstanceIdOrderByCreatedAtAsc(FLOW_ID))
                .thenReturn(actions);
        when(trialDossierRepository.findByCaseId(CASE_ID)).thenReturn(Optional.empty());
        for (HearingArtifactType type : HearingArtifactType.values()) {
            when(artifactRepository.findByCaseIdAndArtifactType(CASE_ID, type))
                    .thenReturn(Optional.empty());
        }
    }

    private static FulfillmentCaseEntity caseFixture() {
        FulfillmentCaseEntity dispute = mock(FulfillmentCaseEntity.class);
        when(dispute.getUserId()).thenReturn("user-1");
        when(dispute.getMerchantId()).thenReturn("merchant-1");
        return dispute;
    }

    private static HearingFlowArtifactEntity artifact(
            String id, String schemaVersion, String hashCharacter) {
        HearingFlowArtifactEntity artifact = mock(HearingFlowArtifactEntity.class);
        when(artifact.getId()).thenReturn(id);
        when(artifact.getSchemaVersion()).thenReturn(schemaVersion);
        when(artifact.getContentHash()).thenReturn(hashCharacter.repeat(64));
        return artifact;
    }

    private static void assertReadOnlyBoundary() {
        Transactional transaction =
                HearingProjectionQueryService.class.getAnnotation(Transactional.class);
        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
    }

    private void assertNoWritesOrWriteLocks() {
        verify(caseRepository, never()).findByIdForUpdate(anyString());
        verify(instanceRepository, never()).findByCaseIdForUpdate(anyString());
        verify(caseRepository, never()).save(any());
        verify(instanceRepository, never()).save(any());
        verify(stageRepository, never()).save(any());
        verify(actionRepository, never()).save(any());
        verify(artifactRepository, never()).save(any());
        verify(trialDossierRepository, never()).save(any());
        verify(remedyPlanRepository, never()).save(any());
        verify(reviewTaskRepository, never()).save(any());
    }
}
