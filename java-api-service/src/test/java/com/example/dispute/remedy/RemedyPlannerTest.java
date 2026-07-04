package com.example.dispute.remedy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.remedy.domain.RemedyPlanner;
import com.example.dispute.remedy.domain.RemedyPlanningSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemedyPlannerTest {

    private final RemedyPlanner planner = new RemedyPlanner();

    @Test
    void mapsRegularFlowActionsWithoutReAdjudicating() {
        var plan =
                planner.plan(
                        new RemedyPlanningSource(
                                "CASE_regular",
                                RouteType.TRANSFERRED,
                                RiskLevel.LOW,
                                "LOGISTICS_STATUS_READY",
                                List.of(
                                        "QUERY_LOGISTICS",
                                        "PREPARE_STATUS_NOTICE"),
                                null,
                                null,
                                1));

        assertThat(plan.actions())
                .extracting(action -> action.actionType())
                .containsExactly("QUERY_LOGISTICS", "PREPARE_STATUS_NOTICE");
        assertThat(plan.actions())
                .allSatisfy(
                        action -> {
                            assertThat(action.idempotencyKey())
                                    .startsWith("REMEDY:CASE_regular:1:");
                            assertThat(action.preconditions())
                                    .contains("CASE_NOT_CLOSED", "PLATFORM_REVIEW_APPROVED");
                        });
        assertThat(plan.notificationPlan())
                .contains("NOTIFY_USER_AFTER_EXECUTION", "NOTIFY_MERCHANT_AFTER_EXECUTION");
        assertThat(plan.requiresHumanReview()).isTrue();
    }

    @Test
    void mapsRuleFlowRefundAndCancellationAsHighRiskApprovalGatedActions() {
        var plan =
                planner.plan(
                        new RemedyPlanningSource(
                                "CASE_rule",
                                RouteType.SIMPLE_HEARING,
                                RiskLevel.MEDIUM,
                                "REFUND_OR_CANCEL_RECOMMENDED",
                                List.of("CANCEL_ORDER", "REFUND"),
                                null,
                                null,
                                1));

        assertThat(plan.actions())
                .extracting(action -> action.actionType())
                .containsExactly("CANCEL_ORDER", "REFUND");
        assertThat(plan.actions())
                .allSatisfy(
                        action -> {
                            assertThat(action.riskLevel()).isEqualTo(RiskLevel.HIGH);
                            assertThat(action.requiresApproval()).isTrue();
                        });
        assertThat(plan.actions().get(1).preconditions())
                .contains("PAYMENT_ELIGIBLE", "REFUND_AMOUNT_RESOLVED");
    }

    @Test
    void mapsHearingDraftRecommendationButPreservesItAsNonFinalSource() {
        var plan =
                planner.plan(
                        new RemedyPlanningSource(
                                "CASE_hearing",
                                RouteType.FULL_HEARING,
                                RiskLevel.HIGH,
                                "ADJUDICATION_DRAFT",
                                List.of(),
                                "DRAFT_1",
                                "REFUND_AFTER_PLATFORM_REVIEW",
                                1));

        assertThat(plan.actions()).singleElement()
                .satisfies(
                        action -> {
                            assertThat(action.actionType()).isEqualTo("REFUND");
                            assertThat(action.parameters())
                                    .containsEntry(
                                            "source_recommendation",
                                            "REFUND_AFTER_PLATFORM_REVIEW");
                            assertThat(action.requiresApproval()).isTrue();
                        });
        assertThat(plan.sourceDraftId()).isEqualTo("DRAFT_1");
        assertThat(plan.sourceConclusionCode()).isEqualTo("ADJUDICATION_DRAFT");
    }

    @Test
    void mapsChineseConfirmedSettlementTextToConcreteFulfillmentAction() {
        var plan =
                planner.plan(
                        new RemedyPlanningSource(
                                "CASE_settlement",
                                RouteType.FULL_HEARING,
                                RiskLevel.MEDIUM,
                                "SETTLEMENT_CONFIRMED",
                                List.of(),
                                "DRAFT_settlement",
                                "双方一致方案：商家补发正确型号 A-2026 并承担往返运费，用户退回错发商品。",
                                1));

        assertThat(plan.actions()).singleElement()
                .satisfies(
                        action -> {
                            assertThat(action.actionType()).isEqualTo("RESHIP");
                            assertThat(action.parameters())
                                    .containsEntry(
                                            "source_recommendation",
                                            "双方一致方案：商家补发正确型号 A-2026 并承担往返运费，用户退回错发商品。");
                            assertThat(action.preconditions())
                                    .contains("INVENTORY_AVAILABLE");
                        });
        assertThat(plan.sourceConclusionCode()).isEqualTo("SETTLEMENT_CONFIRMED");
    }
}
