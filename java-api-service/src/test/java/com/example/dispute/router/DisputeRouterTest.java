package com.example.dispute.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.router.domain.DisputeRouter;
import com.example.dispute.router.domain.RoutingContext;
import org.junit.jupiter.api.Test;

class DisputeRouterTest {

    private final DisputeRouter router = new DisputeRouter();

    @Test
    void routesOrdinaryLogisticsRequestsToRegularFulfillment() {
        var outcome =
                router.decide(
                        new RoutingContext(
                                "LOGISTICS_QUERY",
                                null,
                                RiskLevel.LOW,
                                true,
                                false,
                                false));

        assertThat(outcome.routeType()).isEqualTo(RouteType.REGULAR_FULFILLMENT);
        assertThat(outcome.reasonCode()).isEqualTo("ORDINARY_FULFILLMENT_REQUEST");
    }

    @Test
    void routesSufficientAndPolicyMatchedCasesToRuleBasedResolution() {
        var outcome =
                router.decide(
                        new RoutingContext(
                                "UNSHIPPED_CANCEL",
                                null,
                                RiskLevel.MEDIUM,
                                true,
                                false,
                                true));

        assertThat(outcome.routeType()).isEqualTo(RouteType.RULE_BASED_RESOLUTION);
        assertThat(outcome.reasonCode()).isEqualTo("POLICY_MATCHED_AND_EVIDENCE_SUFFICIENT");
    }

    @Test
    void routesConflictingOrHighRiskCasesToDisputeHearing() {
        assertThat(
                        router.decide(
                                        new RoutingContext(
                                                "REFUND_REQUEST",
                                                "FULFILLMENT_CONFLICT",
                                                RiskLevel.MEDIUM,
                                                true,
                                                true,
                                                true))
                                .routeType())
                .isEqualTo(RouteType.DISPUTE_HEARING);
        assertThat(
                        router.decide(
                                        new RoutingContext(
                                                "UNSHIPPED_CANCEL",
                                                null,
                                                RiskLevel.HIGH,
                                                false,
                                                false,
                                                true))
                                .requiresAdditionalEvidence())
                .isTrue();
        assertThat(
                        router.decide(
                                        new RoutingContext(
                                                "UNSHIPPED_CANCEL",
                                                null,
                                                RiskLevel.HIGH,
                                                true,
                                                false,
                                                true))
                                .routeType())
                .isEqualTo(RouteType.DISPUTE_HEARING);
    }

    @Test
    void doesNotUseRuleFlowWhenEvidenceIsInsufficient() {
        var outcome =
                router.decide(
                        new RoutingContext(
                                "UNSHIPPED_CANCEL",
                                null,
                                RiskLevel.LOW,
                                false,
                                false,
                                true));

        assertThat(outcome.routeType()).isEqualTo(RouteType.DISPUTE_HEARING);
        assertThat(outcome.requiresAdditionalEvidence()).isTrue();
    }

    @Test
    void routingMovesOnlyADossierBuiltCaseToRoutedWithoutClosingIt() {
        FulfillmentCaseEntity disputeCase =
                FulfillmentCaseEntity.create(
                        "CASE_route",
                        "ORDER_route",
                        null,
                        "USER_route",
                        "MERCHANT_route",
                        "IDEMPOTENCY_route",
                        "LOGISTICS_QUERY",
                        "Track order",
                        "Where is the parcel?",
                        RiskLevel.LOW,
                        "USER_route");
        disputeCase.completeIntake(
                null,
                CaseStatus.INTAKE_COMPLETED,
                RiskLevel.LOW,
                "{}",
                "USER_route");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                disputeCase.applyRoute(
                                        RouteType.REGULAR_FULFILLMENT,
                                        "USER_route"))
                .isInstanceOf(IllegalStateException.class);

        disputeCase.markDossierBuilt("USER_route");
        disputeCase.applyRoute(RouteType.REGULAR_FULFILLMENT, "USER_route");

        assertThat(disputeCase.getCaseStatus()).isEqualTo(CaseStatus.ROUTED);
        assertThat(disputeCase.getRouteType())
                .isEqualTo(RouteType.REGULAR_FULFILLMENT);
    }
}
