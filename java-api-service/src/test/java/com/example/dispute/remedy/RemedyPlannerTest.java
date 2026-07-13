/*
 * 所属模块：确定性补救规划。
 * 文件职责：验证补救Planner，覆盖 「mapsRegularFlowActionsWithoutReAdjudicating」、「mapsRuleFlowRefundAndCancellationAsHighRiskApprovalGatedActions」、「mapsHearingDraftRecommendationButPreservesItAsNonFinalSource」、「mapsChineseConfirmedSettlementTextToConcreteFulfillmentAction」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；把已认定事实和非最终建议转换为退款、补发等结构化候选动作。
 * 关键边界：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
 */
package com.example.dispute.remedy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.remedy.domain.RemedyPlanner;
import com.example.dispute.remedy.domain.RemedyPlanningSource;
import java.util.List;
import org.junit.jupiter.api.Test;

// 所属模块：【确定性补救规划 / 自动化测试层】类型「RemedyPlannerTest」。
// 类型职责：集中验证补救Planner的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「mapsRegularFlowActionsWithoutReAdjudicating」、「mapsRuleFlowRefundAndCancellationAsHighRiskApprovalGatedActions」、「mapsHearingDraftRecommendationButPreservesItAsNonFinalSource」、「mapsChineseConfirmedSettlementTextToConcreteFulfillmentAction」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：规划器只能决定动作形状和依赖，不能重新裁定事实或直接执行
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class RemedyPlannerTest {

    private final RemedyPlanner planner = new RemedyPlanner();

    // 所属模块：【确定性补救规划 / 自动化测试层】「RemedyPlannerTest.mapsRegularFlowActionsWithoutReAdjudicating()」。
    // 具体功能：「RemedyPlannerTest.mapsRegularFlowActionsWithoutReAdjudicating()」：复现“核对完整业务行为（场景方法「mapsRegularFlowActionsWithoutReAdjudicating」）”场景：驱动 「planner.plan」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_regular」、「LOGISTICS_STATUS_READY」、「QUERY_LOGISTICS」、「PREPARE_STATUS_NOTICE」。
    // 上游调用：「RemedyPlannerTest.mapsRegularFlowActionsWithoutReAdjudicating()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RemedyPlannerTest.mapsRegularFlowActionsWithoutReAdjudicating()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RemedyPlannerTest.mapsRegularFlowActionsWithoutReAdjudicating()」守住「确定性补救规划」的可执行规格，尤其防止 「CASE_regular」、「LOGISTICS_STATUS_READY」、「QUERY_LOGISTICS」、「PREPARE_STATUS_NOTICE」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【确定性补救规划 / 自动化测试层】「RemedyPlannerTest.mapsRuleFlowRefundAndCancellationAsHighRiskApprovalGatedActions()」。
    // 具体功能：「RemedyPlannerTest.mapsRuleFlowRefundAndCancellationAsHighRiskApprovalGatedActions()」：复现“核对完整业务行为（场景方法「mapsRuleFlowRefundAndCancellationAsHighRiskApprovalGatedActions」）”场景：驱动 「planner.plan」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_rule」、「REFUND_OR_CANCEL_RECOMMENDED」、「CANCEL_ORDER」、「REFUND」。
    // 上游调用：「RemedyPlannerTest.mapsRuleFlowRefundAndCancellationAsHighRiskApprovalGatedActions()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RemedyPlannerTest.mapsRuleFlowRefundAndCancellationAsHighRiskApprovalGatedActions()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RemedyPlannerTest.mapsRuleFlowRefundAndCancellationAsHighRiskApprovalGatedActions()」守住「确定性补救规划」的可执行规格，尤其防止 「CASE_rule」、「REFUND_OR_CANCEL_RECOMMENDED」、「CANCEL_ORDER」、「REFUND」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【确定性补救规划 / 自动化测试层】「RemedyPlannerTest.mapsHearingDraftRecommendationButPreservesItAsNonFinalSource()」。
    // 具体功能：「RemedyPlannerTest.mapsHearingDraftRecommendationButPreservesItAsNonFinalSource()」：复现“核对完整业务行为（场景方法「mapsHearingDraftRecommendationButPreservesItAsNonFinalSource」）”场景：驱动 「planner.plan」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_hearing」、「ADJUDICATION_DRAFT」、「DRAFT_1」、「REFUND_AFTER_PLATFORM_REVIEW」。
    // 上游调用：「RemedyPlannerTest.mapsHearingDraftRecommendationButPreservesItAsNonFinalSource()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RemedyPlannerTest.mapsHearingDraftRecommendationButPreservesItAsNonFinalSource()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RemedyPlannerTest.mapsHearingDraftRecommendationButPreservesItAsNonFinalSource()」守住「确定性补救规划」的可执行规格，尤其防止 「CASE_hearing」、「ADJUDICATION_DRAFT」、「DRAFT_1」、「REFUND_AFTER_PLATFORM_REVIEW」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

    // 所属模块：【确定性补救规划 / 自动化测试层】「RemedyPlannerTest.mapsChineseConfirmedSettlementTextToConcreteFulfillmentAction()」。
    // 具体功能：「RemedyPlannerTest.mapsChineseConfirmedSettlementTextToConcreteFulfillmentAction()」：复现“核对完整业务行为（场景方法「mapsChineseConfirmedSettlementTextToConcreteFulfillmentAction」）”场景：驱动 「planner.plan」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_settlement」、「SETTLEMENT_CONFIRMED」、「DRAFT_settlement」、「RESHIP」。
    // 上游调用：「RemedyPlannerTest.mapsChineseConfirmedSettlementTextToConcreteFulfillmentAction()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「RemedyPlannerTest.mapsChineseConfirmedSettlementTextToConcreteFulfillmentAction()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「RemedyPlannerTest.mapsChineseConfirmedSettlementTextToConcreteFulfillmentAction()」守住「确定性补救规划」的可执行规格，尤其防止 「CASE_settlement」、「SETTLEMENT_CONFIRMED」、「DRAFT_settlement」、「RESHIP」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
