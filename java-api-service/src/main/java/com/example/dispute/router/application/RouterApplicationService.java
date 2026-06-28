package com.example.dispute.router.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.common.exception.IdempotencyConflictException;
import com.example.dispute.common.exception.NotFoundException;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FlowConclusionEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.PolicyRuleEntity;
import com.example.dispute.infrastructure.persistence.entity.RouteDecisionEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.PolicyRuleRepository;
import com.example.dispute.infrastructure.persistence.repository.RouteDecisionRepository;
import com.example.dispute.router.domain.DisputeRouter;
import com.example.dispute.router.domain.RoutingContext;
import com.example.dispute.router.domain.RoutingOutcome;
import com.example.dispute.regularflow.application.RegularFlowService;
import com.example.dispute.ruleflow.application.RuleFlowService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RouterApplicationService {

    private final FulfillmentCaseRepository caseRepository;
    private final EvidenceDossierRepository dossierRepository;
    private final PolicyRuleRepository policyRepository;
    private final RouteDecisionRepository decisionRepository;
    private final FlowConclusionRepository conclusionRepository;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RegularFlowService regularFlowService;
    private final RuleFlowService ruleFlowService;
    private final DisputeRouter router = new DisputeRouter();

    public RouterApplicationService(
            FulfillmentCaseRepository caseRepository,
            EvidenceDossierRepository dossierRepository,
            PolicyRuleRepository policyRepository,
            RouteDecisionRepository decisionRepository,
            FlowConclusionRepository conclusionRepository,
            AuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            Clock clock,
            RegularFlowService regularFlowService,
            RuleFlowService ruleFlowService) {
        this.caseRepository = caseRepository;
        this.dossierRepository = dossierRepository;
        this.policyRepository = policyRepository;
        this.decisionRepository = decisionRepository;
        this.conclusionRepository = conclusionRepository;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.regularFlowService = regularFlowService;
        this.ruleFlowService = ruleFlowService;
    }

    @Transactional
    public RouteDecisionView route(
            String caseId, AuthenticatedActor actor, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        FulfillmentCaseEntity disputeCase = authorizedCase(caseId, actor);
        var existing = decisionRepository.findByCaseId(caseId);
        if (existing.isPresent()) {
            if (!existing.get().getIdempotencyKey().equals(idempotencyKey)) {
                throw new IdempotencyConflictException(
                        "case already has a route decision");
            }
            return toView(
                    existing.get(),
                    conclusionRepository.findByCaseId(caseId).orElse(null));
        }

        EvidenceDossierEntity dossier =
                dossierRepository
                        .findByCaseId(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.EVIDENCE_NOT_FOUND,
                                                "evidence dossier not found",
                                                Map.of("case_id", caseId)));
        if (disputeCase.getCaseStatus()
                != com.example.dispute.domain.model.CaseStatus.DOSSIER_BUILT) {
            throw new BusinessException(
                    ErrorCode.CASE_STATUS_INVALID,
                    "case must have a built dossier before routing",
                    Map.of("case_status", disputeCase.getCaseStatus().name()));
        }

        JsonNode dossierSummary = readTree(dossier.getSummaryJson());
        int evidenceCount = dossierSummary.path("evidence_count").asInt(0);
        int pendingParseCount =
                dossierSummary.path("pending_parse_count").asInt(evidenceCount);
        boolean evidenceSufficient = evidenceCount > 0 && pendingParseCount == 0;
        List<PolicyRuleEntity> policies =
                policyRepository.findActive(
                        disputeCase.getCaseType(), OffsetDateTime.now(clock));
        PolicyRuleEntity matchedPolicy = policies.isEmpty() ? null : policies.get(0);
        RoutingOutcome outcome =
                router.decide(
                        new RoutingContext(
                                disputeCase.getCaseType(),
                                disputeCase.getDisputeType(),
                                disputeCase.getRiskLevel(),
                                evidenceSufficient,
                                disputeCase.getDisputeType() != null
                                        && !disputeCase.getDisputeType().isBlank(),
                                matchedPolicy != null));
        PolicyRuleEntity appliedPolicy =
                outcome.routeType() == RouteType.RULE_BASED_RESOLUTION
                        ? matchedPolicy
                        : null;
        RouteDecisionEntity decision =
                RouteDecisionEntity.record(
                        "ROUTE_" + compactUuid(),
                        caseId,
                        idempotencyKey,
                        outcome.routeType(),
                        outcome.reasonCode(),
                        reasonDetail(outcome.reasonCode()),
                        outcome.requiresAdditionalEvidence(),
                        dossier.getDossierVersion(),
                        appliedPolicy == null ? null : appliedPolicy.getId(),
                        writeJson(
                                Map.of(
                                        "case_type", disputeCase.getCaseType(),
                                        "risk_level", disputeCase.getRiskLevel().name(),
                                        "evidence_count", evidenceCount,
                                        "pending_parse_count", pendingParseCount,
                                        "policy_matched", matchedPolicy != null)),
                        actor.actorId());
        RouteDecisionEntity savedDecision = decisionRepository.save(decision);
        disputeCase.applyRoute(outcome.routeType(), actor.actorId());
        caseRepository.save(disputeCase);
        FlowConclusionEntity conclusion =
                createConclusion(disputeCase, savedDecision, appliedPolicy, actor);
        auditRecorder.record(
                actor,
                "ROUTE_DECIDED",
                "ROUTE_DECISION",
                savedDecision.getId(),
                caseId,
                Map.of("case_status", "DOSSIER_BUILT"),
                Map.of(
                        "case_status", "ROUTED",
                        "route_type", outcome.routeType().name(),
                        "reason_code", outcome.reasonCode()));
        return toView(savedDecision, conclusion);
    }

    private FlowConclusionEntity createConclusion(
            FulfillmentCaseEntity disputeCase,
            RouteDecisionEntity decision,
            PolicyRuleEntity policy,
            AuthenticatedActor actor) {
        if (decision.getRouteType() == RouteType.DISPUTE_HEARING) {
            return null;
        }
        ConclusionData data;
        if (decision.getRouteType() == RouteType.REGULAR_FULFILLMENT) {
            var regular = regularFlowService.conclude(disputeCase.getCaseType());
            data =
                    new ConclusionData(
                            regular.conclusionCode(),
                            regular.summary(),
                            regular.recommendedActions());
        } else {
            var rule = ruleFlowService.conclude(policy);
            data =
                    new ConclusionData(
                            rule.conclusionCode(),
                            rule.summary(),
                            rule.recommendedActions());
        }
        FlowConclusionEntity conclusion =
                FlowConclusionEntity.readyForRemedyPlanning(
                        "CONCLUSION_" + compactUuid(),
                        disputeCase.getId(),
                        decision.getId(),
                        decision.getRouteType() == RouteType.REGULAR_FULFILLMENT
                                ? "REGULAR_FLOW"
                                : "RULE_FLOW",
                        data.code(),
                        data.summary(),
                        writeJson(data.actions()),
                        policy == null ? null : policy.getId(),
                        policy == null ? null : policy.getRuleVersion(),
                        disputeCase.getRiskLevel(),
                        actor.actorId());
        return conclusionRepository.save(conclusion);
    }

    private FulfillmentCaseEntity authorizedCase(
            String caseId, AuthenticatedActor actor) {
        FulfillmentCaseEntity entity =
                caseRepository
                        .findByIdForUpdate(caseId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                ErrorCode.CASE_NOT_FOUND,
                                                "case not found",
                                                Map.of("case_id", caseId)));
        boolean allowed =
                switch (actor.role()) {
                    case USER -> actor.actorId().equals(entity.getUserId());
                    case MERCHANT -> actor.actorId().equals(entity.getMerchantId());
                    case CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN, SYSTEM -> true;
                };
        if (!allowed) {
            throw new ForbiddenException("actor cannot route this case");
        }
        return entity;
    }

    private RouteDecisionView toView(
            RouteDecisionEntity decision, FlowConclusionEntity conclusion) {
        return new RouteDecisionView(
                decision.getId(),
                decision.getCaseId(),
                decision.getRouteType(),
                decision.getReasonCode(),
                decision.getReasonDetail(),
                decision.isRequiresAdditionalEvidence(),
                decision.getDossierVersion(),
                decision.getPolicyRuleId(),
                conclusion == null ? null : toView(conclusion),
                decision.getCreatedAt());
    }

    private FlowConclusionView toView(FlowConclusionEntity conclusion) {
        return new FlowConclusionView(
                conclusion.getConclusionType(),
                conclusion.getConclusionStatus(),
                conclusion.getConclusionCode(),
                conclusion.getSummary(),
                readStringList(conclusion.getRecommendedActionsJson()),
                conclusion.getPolicyRuleId(),
                conclusion.getPolicyVersion(),
                conclusion.getRiskLevel(),
                conclusion.isRequiresRemedyPlanning(),
                conclusion.isRequiresHumanReview());
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted routing JSON", exception);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper
                            .getTypeFactory()
                            .constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid persisted action list", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize routing data", exception);
        }
    }

    private static String reasonDetail(String reasonCode) {
        return switch (reasonCode) {
            case "ORDINARY_FULFILLMENT_REQUEST" ->
                    "No material dispute was detected; use the regular fulfillment flow.";
            case "POLICY_MATCHED_AND_EVIDENCE_SUFFICIENT" ->
                    "Evidence is sufficient and an effective versioned policy matched.";
            case "HIGH_RISK_REQUIRES_HEARING" ->
                    "High-risk cases require dispute hearing and cannot use rule flow.";
            case "PARTY_STATEMENTS_CONFLICT" ->
                    "Conflicting party statements require dispute hearing.";
            case "KEY_EVIDENCE_INSUFFICIENT" ->
                    "Key evidence is insufficient; dispute hearing may request evidence.";
            default -> "The case requires dispute hearing for fact and policy review.";
        };
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record ConclusionData(String code, String summary, List<String> actions) {}
}
