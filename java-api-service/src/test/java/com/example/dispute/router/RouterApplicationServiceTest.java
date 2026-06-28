package com.example.dispute.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.audit.AuditRecorder;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import com.example.dispute.infrastructure.persistence.entity.FlowConclusionEntity;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.infrastructure.persistence.entity.PolicyRuleEntity;
import com.example.dispute.infrastructure.persistence.repository.EvidenceDossierRepository;
import com.example.dispute.infrastructure.persistence.repository.FlowConclusionRepository;
import com.example.dispute.infrastructure.persistence.repository.FulfillmentCaseRepository;
import com.example.dispute.infrastructure.persistence.repository.PolicyRuleRepository;
import com.example.dispute.infrastructure.persistence.repository.RouteDecisionRepository;
import com.example.dispute.router.application.RouterApplicationService;
import com.example.dispute.regularflow.application.RegularFlowService;
import com.example.dispute.ruleflow.application.RuleFlowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RouterApplicationServiceTest {

    @Mock private FulfillmentCaseRepository caseRepository;
    @Mock private EvidenceDossierRepository dossierRepository;
    @Mock private PolicyRuleRepository policyRepository;
    @Mock private RouteDecisionRepository decisionRepository;
    @Mock private FlowConclusionRepository conclusionRepository;
    @Mock private AuditRecorder auditRecorder;

    private RouterApplicationService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service =
                new RouterApplicationService(
                        caseRepository,
                        dossierRepository,
                        policyRepository,
                        decisionRepository,
                        conclusionRepository,
                        auditRecorder,
                        objectMapper,
                        Clock.fixed(
                                Instant.parse("2026-06-28T10:00:00Z"),
                                ZoneOffset.UTC),
                        new RegularFlowService(),
                        new RuleFlowService(objectMapper));
        when(decisionRepository.findByCaseId(any())).thenReturn(Optional.empty());
        when(decisionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview() {
        when(conclusionRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        FulfillmentCaseEntity disputeCase =
                dossierBuiltCase("LOGISTICS_QUERY", null, RiskLevel.LOW);
        mockCaseAndDossier(disputeCase, 0, 0);
        when(policyRepository.findActive(any(), any())).thenReturn(List.of());

        var decision =
                service.route(
                        disputeCase.getId(),
                        new AuthenticatedActor("USER_route", ActorRole.USER),
                        "ROUTE_regular");

        assertThat(decision.routeType()).isEqualTo(RouteType.REGULAR_FULFILLMENT);
        assertThat(decision.conclusion()).isNotNull();
        assertThat(decision.conclusion().conclusionType()).isEqualTo("REGULAR_FLOW");
        assertThat(decision.conclusion().requiresRemedyPlanning()).isTrue();
        assertThat(decision.conclusion().requiresHumanReview()).isTrue();
        assertThat(disputeCase.getCaseStatus()).isEqualTo(CaseStatus.ROUTED);
        verify(conclusionRepository).save(any(FlowConclusionEntity.class));
    }

    @Test
    void ruleFlowReferencesTheExactPolicyAndVersion() {
        when(conclusionRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        FulfillmentCaseEntity disputeCase =
                dossierBuiltCase("UNSHIPPED_CANCEL", null, RiskLevel.MEDIUM);
        mockCaseAndDossier(disputeCase, 1, 0);
        PolicyRuleEntity policy =
                PolicyRuleEntity.active(
                        "POLICY_test",
                        "UNSHIPPED_CANCEL",
                        3,
                        "Unshipped cancellation",
                        "UNSHIPPED_CANCEL",
                        OffsetDateTime.parse("2020-01-01T00:00:00Z"),
                        100,
                        "{\"requires_evidence\":true}",
                        "{\"conclusion_code\":\"REFUND_OR_CANCEL_RECOMMENDED\","
                                + "\"recommended_actions\":[\"CANCEL_ORDER\",\"REFUND\"]}",
                        "{\"document_code\":\"FULFILLMENT_POLICY\"}",
                        "system");
        when(policyRepository.findActive(any(), any())).thenReturn(List.of(policy));

        var decision =
                service.route(
                        disputeCase.getId(),
                        new AuthenticatedActor("USER_route", ActorRole.USER),
                        "ROUTE_rule");

        assertThat(decision.routeType()).isEqualTo(RouteType.RULE_BASED_RESOLUTION);
        assertThat(decision.policyRuleId()).isEqualTo("POLICY_test");
        assertThat(decision.conclusion().policyVersion()).isEqualTo(3);
        assertThat(decision.conclusion().recommendedActions())
                .containsExactly("CANCEL_ORDER", "REFUND");
    }

    @Test
    void highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion() {
        FulfillmentCaseEntity disputeCase =
                dossierBuiltCase("UNSHIPPED_CANCEL", null, RiskLevel.HIGH);
        mockCaseAndDossier(disputeCase, 1, 0);
        when(policyRepository.findActive(any(), any())).thenReturn(List.of());

        var decision =
                service.route(
                        disputeCase.getId(),
                        new AuthenticatedActor("USER_route", ActorRole.USER),
                        "ROUTE_hearing");

        assertThat(decision.routeType()).isEqualTo(RouteType.DISPUTE_HEARING);
        assertThat(decision.conclusion()).isNull();
        verify(conclusionRepository, never()).save(any());
    }

    private void mockCaseAndDossier(
            FulfillmentCaseEntity disputeCase, int evidenceCount, int pendingCount) {
        when(caseRepository.findByIdForUpdate(disputeCase.getId()))
                .thenReturn(Optional.of(disputeCase));
        EvidenceDossierEntity dossier =
                EvidenceDossierEntity.firstBuild(
                        "DOSSIER_" + disputeCase.getId(),
                        disputeCase.getId(),
                        "USER_route",
                        "{\"evidence_count\":"
                                + evidenceCount
                                + ",\"pending_parse_count\":"
                                + pendingCount
                                + "}",
                        "[]",
                        "[]");
        when(dossierRepository.findByCaseId(disputeCase.getId()))
                .thenReturn(Optional.of(dossier));
    }

    private static FulfillmentCaseEntity dossierBuiltCase(
            String caseType, String disputeType, RiskLevel riskLevel) {
        FulfillmentCaseEntity disputeCase =
                FulfillmentCaseEntity.create(
                        "CASE_" + caseType,
                        "ORDER_route",
                        null,
                        "USER_route",
                        "MERCHANT_route",
                        "IDEMPOTENCY_" + caseType,
                        caseType,
                        "Routing test",
                        "Routing test description",
                        riskLevel,
                        "USER_route");
        disputeCase.completeIntake(
                disputeType,
                CaseStatus.INTAKE_COMPLETED,
                riskLevel,
                "{}",
                "USER_route");
        disputeCase.markDossierBuilt("USER_route");
        return disputeCase;
    }
}
