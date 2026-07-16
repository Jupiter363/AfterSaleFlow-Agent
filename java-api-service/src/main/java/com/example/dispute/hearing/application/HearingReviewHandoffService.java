package com.example.dispute.hearing.application;

import com.example.dispute.domain.model.HearingStatus;
import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.domain.HearingFlowStage;
import com.example.dispute.hearing.domain.HearingFlowStageStatus;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowArtifactEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowInstanceEntity;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowStageEntity;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowArtifactRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowInstanceRepository;
import com.example.dispute.hearing.infrastructure.persistence.repository.HearingFlowStageRepository;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.infrastructure.persistence.repository.ReviewTaskRepository;
import com.example.dispute.remedy.application.RemedyApplicationService;
import com.example.dispute.review.application.ReviewApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic post-commit bridge from a frozen V2 draft to the human review facts. */
@Service
public class HearingReviewHandoffService {

    private static final String SYSTEM_ACTOR = "hearing-flow-v2";

    private final HearingFlowArtifactRepository artifactRepository;
    private final HearingFlowInstanceRepository flowInstanceRepository;
    private final HearingFlowStageRepository flowStageRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final HearingStateRepository hearingStateRepository;
    private final RemedyPlanRepository remedyPlanRepository;
    private final ReviewTaskRepository reviewTaskRepository;
    private final RemedyApplicationService remedyService;
    private final ReviewApplicationService reviewService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HearingReviewHandoffService(
            HearingFlowArtifactRepository artifactRepository,
            HearingFlowInstanceRepository flowInstanceRepository,
            HearingFlowStageRepository flowStageRepository,
            AdjudicationDraftRepository draftRepository,
            HearingStateRepository hearingStateRepository,
            RemedyPlanRepository remedyPlanRepository,
            ReviewTaskRepository reviewTaskRepository,
            RemedyApplicationService remedyService,
            ReviewApplicationService reviewService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.artifactRepository = artifactRepository;
        this.flowInstanceRepository = flowInstanceRepository;
        this.flowStageRepository = flowStageRepository;
        this.draftRepository = draftRepository;
        this.hearingStateRepository = hearingStateRepository;
        this.remedyPlanRepository = remedyPlanRepository;
        this.reviewTaskRepository = reviewTaskRepository;
        this.remedyService = remedyService;
        this.reviewService = reviewService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public String handoff(String caseId, String expectedDraftId, String expectedDraftHash) {
        HearingFlowArtifactEntity artifact =
                artifactRepository
                        .findByCaseIdAndArtifactType(
                                caseId, HearingArtifactType.ADJUDICATION_DRAFT)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "frozen V2 adjudication artifact is required"));
        if (!artifact.getId().equals(expectedDraftId)
                || !artifact.getContentHash().equals(expectedDraftHash)) {
            throw new IllegalStateException("review handoff is not bound to the frozen V2 draft");
        }
        validateDecisionChain(caseId, artifact);
        AdjudicationDraftEntity draft =
                draftRepository
                        .findById(expectedDraftId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "V2 adjudication projection is required"));
        if (!caseId.equals(draft.getCaseId())
                || !artifact.getAgentRunId().equals(draft.getCreatedByAgentRunId())) {
            throw new IllegalStateException("V2 adjudication projection does not match its artifact");
        }
        HearingStateEntity hearing =
                hearingStateRepository
                        .findByCaseId(caseId)
                        .orElseThrow(() -> new IllegalStateException("hearing state is required"));
        if (hearing.getHearingStatus() != HearingStatus.COMPLETED) {
            throw new IllegalStateException("V2 hearing must complete before review handoff");
        }

        String planId =
                remedyPlanRepository
                        .findFirstByCaseIdOrderByPlanVersionDesc(caseId)
                        .filter(item -> expectedDraftId.equals(item.getAdjudicationDraftId()))
                        .map(item -> item.getId())
                        .orElseGet(
                                () ->
                                        remedyService.generateForWorkflow(
                                                caseId, hearing.getWorkflowId()));
        RemedyPlanEntity plan =
                remedyPlanRepository
                        .findById(planId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "V2 review handoff remedy plan is required"));
        if (!caseId.equals(plan.getCaseId())
                || !expectedDraftId.equals(plan.getAdjudicationDraftId())) {
            throw new IllegalStateException(
                    "review handoff remedy plan is not bound to the frozen V2 draft");
        }
        var existingTask =
                reviewTaskRepository.findFirstByCaseIdAndPlanIdOrderByCreatedAtDesc(
                        caseId, planId);
        if (existingTask.isPresent()) {
            String taskId = existingTask.orElseThrow().getId();
            closeHearingFlow(caseId, expectedDraftId, expectedDraftHash, taskId);
            return taskId;
        }
        String taskId = reviewService.createForWorkflow(caseId, planId);
        closeHearingFlow(caseId, expectedDraftId, expectedDraftHash, taskId);
        return taskId;
    }

    private void validateDecisionChain(
            String caseId, HearingFlowArtifactEntity adjudicationDraft) {
        HearingFlowArtifactEntity proposal =
                artifactRepository
                        .findByCaseIdAndArtifactType(caseId, HearingArtifactType.JUDGE_PROPOSAL)
                        .orElseThrow(
                                () -> new IllegalStateException("V1 proposal artifact is required"));
        HearingFlowArtifactEntity report =
                artifactRepository
                        .findByCaseIdAndArtifactType(
                                caseId, HearingArtifactType.JURY_REVIEW_REPORT)
                        .orElseThrow(
                                () -> new IllegalStateException("jury review artifact is required"));
        boolean sameDossier =
                caseId.equals(proposal.getCaseId())
                        && caseId.equals(report.getCaseId())
                        && caseId.equals(adjudicationDraft.getCaseId())
                        && proposal.getFlowInstanceId().equals(report.getFlowInstanceId())
                        && proposal.getFlowInstanceId()
                                .equals(adjudicationDraft.getFlowInstanceId())
                        && proposal.getTrialDossierId().equals(report.getTrialDossierId())
                        && proposal.getTrialDossierId()
                                .equals(adjudicationDraft.getTrialDossierId())
                        && proposal.getTrialDossierHash().equals(report.getTrialDossierHash())
                        && proposal.getTrialDossierHash()
                                .equals(adjudicationDraft.getTrialDossierHash());
        boolean parentChain =
                proposal.getId().equals(report.getProposalId())
                        && proposal.getContentHash().equals(report.getProposalContentHash())
                        && proposal.getId().equals(adjudicationDraft.getProposalId())
                        && proposal.getContentHash()
                                .equals(adjudicationDraft.getProposalContentHash())
                        && report.getId().equals(adjudicationDraft.getReportId())
                        && report.getContentHash()
                                .equals(adjudicationDraft.getReportContentHash());
        if (!sameDossier || !parentChain) {
            throw new IllegalStateException(
                    "V2 review handoff decision artifact chain is invalid");
        }
    }

    private void closeHearingFlow(
            String caseId, String draftId, String draftHash, String reviewTaskId) {
        HearingFlowInstanceEntity flow =
                flowInstanceRepository
                        .findByCaseIdForUpdate(caseId)
                        .orElseThrow(
                                () -> new IllegalStateException("hearing flow instance is required"));
        if (flow.getCurrentStage() == HearingFlowStage.CLOSED) {
            return;
        }
        if (flow.getCurrentStage() != HearingFlowStage.HUMAN_REVIEW_OPEN) {
            throw new IllegalStateException(
                    "review handoff can close only the HUMAN_REVIEW_OPEN stage");
        }
        HearingFlowStageEntity handoffStage =
                flowStageRepository
                        .findByFlowInstanceIdAndStageSequence(
                                flow.getId(), flow.getStageSequence())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "human review handoff stage is required"));
        if (handoffStage.getStageCode() != HearingFlowStage.HUMAN_REVIEW_OPEN) {
            throw new IllegalStateException("active handoff stage binding is invalid");
        }
        Instant now = clock.instant();
        ObjectNode output = objectMapper.createObjectNode();
        output.put("judge_v2_id", draftId);
        output.put("judge_v2_hash", draftHash);
        output.put("review_task_id", reviewTaskId);
        output.put("review_gate_ready", true);
        output.put("handoff_status", "COMPLETED");
        handoffStage.complete(output.toString(), now, SYSTEM_ACTOR);

        int closedSequence = flow.getStageSequence() + 1;
        flow.advance(HearingFlowStage.CLOSED, closedSequence, null, now, SYSTEM_ACTOR);
        flowInstanceRepository.save(flow);
        HearingFlowStageEntity closed =
                flowStageRepository.save(
                        HearingFlowStageEntity.open(
                                "HEARING_STAGE_"
                                        + UUID.randomUUID().toString().replace("-", ""),
                                flow.getId(),
                                caseId,
                                HearingFlowStage.CLOSED,
                                closedSequence,
                                "SYSTEM",
                                HearingFlowStageStatus.RUNNING,
                                null,
                                output.toString(),
                                now,
                                SYSTEM_ACTOR));
        closed.complete(output.toString(), now, SYSTEM_ACTOR);
    }
}
