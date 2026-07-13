/*
 * 所属模块：听证准入路由。
 * 文件职责：验证Admissibility庭审Router，覆盖 「transfersRequestsThatAreNotFulfillmentDisputes」、「selectsSimpleHearingOnlyForClearLowRiskDisputesWithEnoughEvidence」、「selectsFullHearingForConflictHighRiskMissingEvidenceOrUnclearRules」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；依据争点、证据充分度和风险把案件分入三种最终听证路线。
 * 关键边界：路由算法必须确定、可测试且可解释，不能在此阶段生成最终赔付决定
 */
package com.example.dispute.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.routing.domain.AdmissibilityContext;
import com.example.dispute.routing.domain.AdmissibilityHearingRouter;
import com.example.dispute.routing.domain.HearingRoute;
import org.junit.jupiter.api.Test;

// 所属模块：【听证准入路由 / 自动化测试层】类型「AdmissibilityHearingRouterTest」。
// 类型职责：集中验证Admissibility庭审Router的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「transfersRequestsThatAreNotFulfillmentDisputes」、「selectsSimpleHearingOnlyForClearLowRiskDisputesWithEnoughEvidence」、「selectsFullHearingForConflictHighRiskMissingEvidenceOrUnclearRules」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：路由算法必须确定、可测试且可解释，不能在此阶段生成最终赔付决定
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class AdmissibilityHearingRouterTest {

    private final AdmissibilityHearingRouter router =
            new AdmissibilityHearingRouter();

    // 所属模块：【听证准入路由 / 自动化测试层】「AdmissibilityHearingRouterTest.transfersRequestsThatAreNotFulfillmentDisputes()」。
    // 具体功能：「AdmissibilityHearingRouterTest.transfersRequestsThatAreNotFulfillmentDisputes()」：复现“核对完整业务行为（场景方法「transfersRequestsThatAreNotFulfillmentDisputes」）”场景：驱动 「router.decide」，再用 「assertThat」 核对返回值、状态变化或协作者调用。
    // 上游调用：「AdmissibilityHearingRouterTest.transfersRequestsThatAreNotFulfillmentDisputes()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AdmissibilityHearingRouterTest.transfersRequestsThatAreNotFulfillmentDisputes()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AdmissibilityHearingRouterTest.transfersRequestsThatAreNotFulfillmentDisputes()」守住「听证准入路由」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void transfersRequestsThatAreNotFulfillmentDisputes() {
        var outcome =
                router.decide(
                        new AdmissibilityContext(
                                false,
                                RiskLevel.LOW,
                                true,
                                false,
                                true));

        assertThat(outcome.route()).isEqualTo(HearingRoute.TRANSFERRED);
        assertThat(outcome.terminalInDisputeSystem()).isTrue();
        assertThat(outcome.requiresAdditionalEvidence()).isFalse();
    }

    // 所属模块：【听证准入路由 / 自动化测试层】「AdmissibilityHearingRouterTest.selectsSimpleHearingOnlyForClearLowRiskDisputesWithEnoughEvidence()」。
    // 具体功能：「AdmissibilityHearingRouterTest.selectsSimpleHearingOnlyForClearLowRiskDisputesWithEnoughEvidence()」：复现“核对完整业务行为（场景方法「selectsSimpleHearingOnlyForClearLowRiskDisputesWithEnoughEvidence」）”场景：驱动 「router.decide」，再用 「assertThat」 核对返回值、状态变化或协作者调用。
    // 上游调用：「AdmissibilityHearingRouterTest.selectsSimpleHearingOnlyForClearLowRiskDisputesWithEnoughEvidence()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AdmissibilityHearingRouterTest.selectsSimpleHearingOnlyForClearLowRiskDisputesWithEnoughEvidence()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AdmissibilityHearingRouterTest.selectsSimpleHearingOnlyForClearLowRiskDisputesWithEnoughEvidence()」守住「听证准入路由」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void selectsSimpleHearingOnlyForClearLowRiskDisputesWithEnoughEvidence() {
        var outcome =
                router.decide(
                        new AdmissibilityContext(
                                true,
                                RiskLevel.LOW,
                                true,
                                false,
                                true));

        assertThat(outcome.route()).isEqualTo(HearingRoute.SIMPLE_HEARING);
        assertThat(outcome.terminalInDisputeSystem()).isFalse();
        assertThat(outcome.requiresDeliberation()).isFalse();
    }

    // 所属模块：【听证准入路由 / 自动化测试层】「AdmissibilityHearingRouterTest.selectsFullHearingForConflictHighRiskMissingEvidenceOrUnclearRules()」。
    // 具体功能：「AdmissibilityHearingRouterTest.selectsFullHearingForConflictHighRiskMissingEvidenceOrUnclearRules()」：复现“核对完整业务行为（场景方法「selectsFullHearingForConflictHighRiskMissingEvidenceOrUnclearRules」）”场景：驱动 「router.decide」，再用 「assertThat」 核对返回值、状态变化或协作者调用。
    // 上游调用：「AdmissibilityHearingRouterTest.selectsFullHearingForConflictHighRiskMissingEvidenceOrUnclearRules()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「AdmissibilityHearingRouterTest.selectsFullHearingForConflictHighRiskMissingEvidenceOrUnclearRules()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「AdmissibilityHearingRouterTest.selectsFullHearingForConflictHighRiskMissingEvidenceOrUnclearRules()」守住「听证准入路由」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void selectsFullHearingForConflictHighRiskMissingEvidenceOrUnclearRules() {
        assertThat(
                        router.decide(
                                        new AdmissibilityContext(
                                                true,
                                                RiskLevel.MEDIUM,
                                                true,
                                                true,
                                                true))
                                .route())
                .isEqualTo(HearingRoute.FULL_HEARING);
        assertThat(
                        router.decide(
                                        new AdmissibilityContext(
                                                true,
                                                RiskLevel.HIGH,
                                                true,
                                                false,
                                                true))
                                .requiresDeliberation())
                .isTrue();
        assertThat(
                        router.decide(
                                        new AdmissibilityContext(
                                                true,
                                                RiskLevel.LOW,
                                                false,
                                                false,
                                                true))
                                .requiresAdditionalEvidence())
                .isTrue();
        assertThat(
                        router.decide(
                                        new AdmissibilityContext(
                                                true,
                                                RiskLevel.LOW,
                                                true,
                                                false,
                                                false))
                                .route())
                .isEqualTo(HearingRoute.FULL_HEARING);
    }
}
