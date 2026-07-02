package com.example.dispute.remedy.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.HearingStatus;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import com.example.dispute.infrastructure.persistence.entity.FlowConclusionEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import com.example.dispute.infrastructure.persistence.repository.AdjudicationDraftRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.HearingStateRepository;
import com.example.dispute.infrastructure.persistence.repository.RemedyPlanRepository;
import com.example.dispute.remedy.domain.PlannedRemedyAction;
import com.example.dispute.remedy.domain.RemedyPlanDraft;
import com.example.dispute.remedy.domain.RemedyPlanner;
import com.example.dispute.remedy.domain.RemedyPlanningSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RemedyApplicationService {

    private static final AuthenticatedActor SYSTEM =
            new AuthenticatedActor("temporal-worker", ActorRole.SYSTEM);

    private final FulfillmentCaseRepository caseRepository;
    private final FlowConclusionRepository conclusionRepository;
    private final AdjudicationDraftRepository draftRepository;
    private final HearingStateRepository hearingRepository;
    private final RemedyPlanRepository planRepository;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final RemedyPlanner planner = new RemedyPlanner();

    public RemedyApplicationService(
            FulfillmentCaseRepository caseRepository,
            FlowConclusionRepository conclusionRepository,
            AdjudicationDraftRepository draftRepository,
            HearingStateRepository hearingRepository,
            RemedyPlanRepository planRepository,
            AuditRecorder auditRecorder,
            ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.conclusionRepository = conclusionRepository;
        this.draftRepository = draftRepository;
        this.hearingRepository = hearingRepository;
        this.planRepository = planRepository;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String generateForWorkflow(String caseId, String workflowId) {
        FulfillmentCaseEntity disputeCase =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(() -> caseNotFound(caseId));
        if (disputeCase.getRouteType() == RouteType.FULL_HEARING
                && !workflowId.equals(disputeCase.getCurrentWorkflowId())) {
            throw new BusinessException(
                    ErrorCode.CASE_STATUS_INVALID,
                    "workflow does not own this hearing case",
                    Map.of("case_id", caseId));
        }
        return generate(disputeCase, SYSTEM).getId();
    }

    @Transactional(readOnly = true)
    public RemedyPlanView get(String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity disputeCase =
                caseRepository.findById(caseId).orElseThrow(() -> caseNotFound(caseId));
        assertCanRead(disputeCase, actor);
        RemedyPlanEntity plan =
                planRepository
                        .findFirstByCaseIdOrderByPlanVersionDesc(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "remedy plan not found",
                                                Map.of("case_id", caseId)));
        return toView(plan);
    }

    private RemedyPlanEntity generate(
            FulfillmentCaseEntity disputeCase, AuthenticatedActor actor) {
        var existing =
                planRepository.findFirstByCaseIdOrderByPlanVersionDesc(
                        disputeCase.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        int version = 1;
        SourceData source = sourceFor(disputeCase);
        RemedyPlanDraft planned =
                planner.plan(
                        new RemedyPlanningSource(
                                disputeCase.getId(),
                                disputeCase.getRouteType(),
                                disputeCase.getRiskLevel(),
                                source.conclusionCode(),
                                source.actions(),
                                source.draftId(),
                                source.draftRecommendation(),
                                version));
        RemedyPlanEntity plan =
                planRepository.save(
                        RemedyPlanEntity.pendingApproval(
                                "REMEDY_" + compactUuid(),
                                disputeCase.getId(),
                                planned.sourceDraftId(),
                                version,
                                disputeCase.getRouteType(),
                                planned.riskLevel(),
                                writeJson(planned.actions()),
                                writeJson(planned.preconditions()),
                                writeJson(planned.notificationPlan()),
                                actor.actorId()));
        disputeCase.markRemedyPlanned(actor.actorId());
        caseRepository.save(disputeCase);
        auditRecorder.record(
                actor,
                "REMEDY_PLAN_CREATED",
                "REMEDY_PLAN",
                plan.getId(),
                disputeCase.getId(),
                Map.of("case_status", source.previousCaseStatus()),
                Map.of(
                        "case_status", "REMEDY_PLANNED",
                        "source_route", disputeCase.getRouteType().name(),
                        "risk_level", planned.riskLevel().name(),
                        "requires_human_review", true,
                        "action_count", planned.actions().size()));
        return plan;
    }

    private SourceData sourceFor(FulfillmentCaseEntity disputeCase) {
        if (disputeCase.getRouteType() == RouteType.FULL_HEARING) {
            var hearing =
                    hearingRepository
                            .findByCaseId(disputeCase.getId())
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.CASE_STATUS_INVALID,
                                                    "hearing state is required",
                                                    Map.of(
                                                            "case_id",
                                                            disputeCase.getId())));
            if (hearing.getHearingStatus() != HearingStatus.COMPLETED) {
                throw new BusinessException(
                        ErrorCode.CASE_STATUS_INVALID,
                        "hearing must complete before remedy planning",
                        Map.of(
                                "hearing_status",
                                hearing.getHearingStatus().name()));
            }
            AdjudicationDraftEntity draft =
                    draftRepository
                            .findFirstByCaseIdOrderByDraftVersionDesc(
                                    disputeCase.getId())
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.CASE_STATUS_INVALID,
                                                    "adjudication draft is required",
                                                    Map.of(
                                                            "case_id",
                                                            disputeCase.getId())));
            return new SourceData(
                    "ADJUDICATION_DRAFT",
                    List.of(),
                    draft.getId(),
                    draft.getRecommendedDecision(),
                    disputeCase.getCaseStatus().name());
        }
        FlowConclusionEntity conclusion =
                conclusionRepository
                        .findByCaseId(disputeCase.getId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.CASE_STATUS_INVALID,
                                                "flow conclusion is required",
                                                Map.of(
                                                        "case_id",
                                                        disputeCase.getId())));
        if (!conclusion.isRequiresRemedyPlanning()) {
            throw new BusinessException(
                    ErrorCode.CASE_STATUS_INVALID,
                    "flow conclusion is not ready for remedy planning",
                    Map.of("case_id", disputeCase.getId()));
        }
        return new SourceData(
                conclusion.getConclusionCode(),
                readActions(conclusion.getRecommendedActionsJson()),
                null,
                null,
                disputeCase.getCaseStatus().name());
    }

    private RemedyPlanView toView(RemedyPlanEntity plan) {
        try {
            return new RemedyPlanView(
                    plan.getId(),
                    plan.getCaseId(),
                    plan.getAdjudicationDraftId(),
                    plan.getPlanVersion(),
                    plan.getSourceRoute(),
                    plan.getPlanStatus(),
                    plan.getRiskLevel(),
                    plan.getTotalAmount(),
                    plan.getCurrency(),
                    objectMapper.readValue(
                            plan.getActionsJson(),
                            new TypeReference<List<PlannedRemedyAction>>() {}),
                    objectMapper.readValue(
                            plan.getPreconditionsJson(),
                            new TypeReference<List<String>>() {}),
                    objectMapper.readValue(
                            plan.getNotificationPlanJson(),
                            new TypeReference<List<String>>() {}),
                    plan.isRequiresHumanReview(),
                    plan.getCreatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted remedy plan JSON", exception);
        }
    }

    private List<String> readActions(String json) {
        try {
            return objectMapper.readValue(
                    json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "invalid persisted recommended actions", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize remedy plan", exception);
        }
    }

    private static void assertCanRead(
            FulfillmentCaseEntity disputeCase, AuthenticatedActor actor) {
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(disputeCase.getUserId());
                    case MERCHANT -> actor.actorId().equals(disputeCase.getMerchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot read this remedy plan");
        }
    }

    private static NotFoundException caseNotFound(String caseId) {
        return new NotFoundException(
                ErrorCode.CASE_NOT_FOUND,
                "case not found",
                Map.of("case_id", caseId));
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record SourceData(
            String conclusionCode,
            List<String> actions,
            String draftId,
            String draftRecommendation,
            String previousCaseStatus) {}
}
