/*
 * 所属模块：争议路由应用层。
 * 文件职责：验证争议Router，覆盖 「routesOrdinaryLogisticsRequestsToRegularFulfillment」、「routesSufficientAndPolicyMatchedCasesToRuleBasedResolution」、「routesConflictingOrHighRiskCasesToDisputeHearing」、「doesNotUseRuleFlowWhenEvidenceIsInsufficient」、「routingMovesOnlyADossierBuiltCaseToRoutedWithoutClosingIt」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；汇集案件、证据和规则上下文并选择常规、规则或听证路线。
 * 关键边界：路由只决定下一条处理路径，不拥有终审或工具执行权限
 */
package com.example.dispute.router;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import com.example.dispute.domain.model.CaseStatus;
import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import com.example.dispute.router.domain.DisputeRouter;
import com.example.dispute.router.domain.RoutingContext;
import org.junit.jupiter.api.Test;

// 所属模块：【争议路由应用层 / 自动化测试层】类型「DisputeRouterTest」。
// 类型职责：集中验证争议Router的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「routesOrdinaryLogisticsRequestsToRegularFulfillment」、「routesSufficientAndPolicyMatchedCasesToRuleBasedResolution」、「routesConflictingOrHighRiskCasesToDisputeHearing」、「doesNotUseRuleFlowWhenEvidenceIsInsufficient」、「routingMovesOnlyADossierBuiltCaseToRoutedWithoutClosingIt」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：路由只决定下一条处理路径，不拥有终审或工具执行权限
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class DisputeRouterTest {

    private final DisputeRouter router = new DisputeRouter();

    // 所属模块：【争议路由应用层 / 自动化测试层】「DisputeRouterTest.routesOrdinaryLogisticsRequestsToRegularFulfillment()」。
    // 具体功能：「DisputeRouterTest.routesOrdinaryLogisticsRequestsToRegularFulfillment()」：复现“核对完整业务行为（场景方法「routesOrdinaryLogisticsRequestsToRegularFulfillment」）”场景：驱动 「router.decide」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「LOGISTICS_QUERY」、「ORDINARY_FULFILLMENT_REQUEST」。
    // 上游调用：「DisputeRouterTest.routesOrdinaryLogisticsRequestsToRegularFulfillment()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeRouterTest.routesOrdinaryLogisticsRequestsToRegularFulfillment()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeRouterTest.routesOrdinaryLogisticsRequestsToRegularFulfillment()」守住「争议路由应用层」的可执行规格，尤其防止 「LOGISTICS_QUERY」、「ORDINARY_FULFILLMENT_REQUEST」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

        assertThat(outcome.routeType()).isEqualTo(RouteType.TRANSFERRED);
        assertThat(outcome.reasonCode()).isEqualTo("ORDINARY_FULFILLMENT_REQUEST");
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「DisputeRouterTest.routesSufficientAndPolicyMatchedCasesToRuleBasedResolution()」。
    // 具体功能：「DisputeRouterTest.routesSufficientAndPolicyMatchedCasesToRuleBasedResolution()」：复现“核对完整业务行为（场景方法「routesSufficientAndPolicyMatchedCasesToRuleBasedResolution」）”场景：驱动 「router.decide」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「UNSHIPPED_CANCEL」、「POLICY_MATCHED_AND_EVIDENCE_SUFFICIENT」。
    // 上游调用：「DisputeRouterTest.routesSufficientAndPolicyMatchedCasesToRuleBasedResolution()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeRouterTest.routesSufficientAndPolicyMatchedCasesToRuleBasedResolution()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeRouterTest.routesSufficientAndPolicyMatchedCasesToRuleBasedResolution()」守住「争议路由应用层」的可执行规格，尤其防止 「UNSHIPPED_CANCEL」、「POLICY_MATCHED_AND_EVIDENCE_SUFFICIENT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

        assertThat(outcome.routeType()).isEqualTo(RouteType.SIMPLE_HEARING);
        assertThat(outcome.reasonCode()).isEqualTo("POLICY_MATCHED_AND_EVIDENCE_SUFFICIENT");
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「DisputeRouterTest.routesConflictingOrHighRiskCasesToDisputeHearing()」。
    // 具体功能：「DisputeRouterTest.routesConflictingOrHighRiskCasesToDisputeHearing()」：复现“核对完整业务行为（场景方法「routesConflictingOrHighRiskCasesToDisputeHearing」）”场景：驱动 「router.decide」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「REFUND_REQUEST」、「FULFILLMENT_CONFLICT」、「UNSHIPPED_CANCEL」。
    // 上游调用：「DisputeRouterTest.routesConflictingOrHighRiskCasesToDisputeHearing()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeRouterTest.routesConflictingOrHighRiskCasesToDisputeHearing()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeRouterTest.routesConflictingOrHighRiskCasesToDisputeHearing()」守住「争议路由应用层」的可执行规格，尤其防止 「REFUND_REQUEST」、「FULFILLMENT_CONFLICT」、「UNSHIPPED_CANCEL」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
                .isEqualTo(RouteType.FULL_HEARING);
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
                .isEqualTo(RouteType.FULL_HEARING);
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「DisputeRouterTest.doesNotUseRuleFlowWhenEvidenceIsInsufficient()」。
    // 具体功能：「DisputeRouterTest.doesNotUseRuleFlowWhenEvidenceIsInsufficient()」：复现“核对完整业务行为（场景方法「doesNotUseRuleFlowWhenEvidenceIsInsufficient」）”场景：驱动 「router.decide」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「UNSHIPPED_CANCEL」。
    // 上游调用：「DisputeRouterTest.doesNotUseRuleFlowWhenEvidenceIsInsufficient()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeRouterTest.doesNotUseRuleFlowWhenEvidenceIsInsufficient()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeRouterTest.doesNotUseRuleFlowWhenEvidenceIsInsufficient()」守住「争议路由应用层」的可执行规格，尤其防止 「UNSHIPPED_CANCEL」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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

        assertThat(outcome.routeType()).isEqualTo(RouteType.FULL_HEARING);
        assertThat(outcome.requiresAdditionalEvidence()).isTrue();
    }

    // 所属模块：【争议路由应用层 / 自动化测试层】「DisputeRouterTest.routingMovesOnlyADossierBuiltCaseToRoutedWithoutClosingIt()」。
    // 具体功能：「DisputeRouterTest.routingMovesOnlyADossierBuiltCaseToRoutedWithoutClosingIt()」：复现“核对完整业务行为（场景方法「routingMovesOnlyADossierBuiltCaseToRoutedWithoutClosingIt」）”场景：驱动 「FulfillmentCaseEntity.create」、「disputeCase.completeIntake」、「disputeCase.applyRoute」、「disputeCase.markDossierBuilt」，再用 「assertThatThrownBy」、「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CASE_route」、「ORDER_route」、「USER_route」、「MERCHANT_route」。
    // 上游调用：「DisputeRouterTest.routingMovesOnlyADossierBuiltCaseToRoutedWithoutClosingIt()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DisputeRouterTest.routingMovesOnlyADossierBuiltCaseToRoutedWithoutClosingIt()」的下游是被测服务、仓储或外部客户端替身；「assertThatThrownBy、assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DisputeRouterTest.routingMovesOnlyADossierBuiltCaseToRoutedWithoutClosingIt()」守住「争议路由应用层」的可执行规格，尤其防止 「CASE_route」、「ORDER_route」、「USER_route」、「MERCHANT_route」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
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
                                        RouteType.TRANSFERRED,
                                        "USER_route"))
                .isInstanceOf(IllegalStateException.class);

        disputeCase.markDossierBuilt("USER_route");
        disputeCase.applyRoute(RouteType.TRANSFERRED, "USER_route");

        assertThat(disputeCase.getCaseStatus()).isEqualTo(CaseStatus.ROUTED);
        assertThat(disputeCase.getRouteType())
                .isEqualTo(RouteType.TRANSFERRED);
    }
}
