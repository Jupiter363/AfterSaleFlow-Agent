/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：验证评议，覆盖 「finalOnlyPanelRunsOnlyForHighRiskFullHearings」、「disabledModeNeverRunsPanel」、「validatesScoreThresholdAndRegenerationBudget」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.domain.model.RouteType;
import com.example.dispute.workflow.domain.DeliberationInterventionMode;
import com.example.dispute.workflow.domain.DeliberationPolicy;
import org.junit.jupiter.api.Test;

// 所属模块：【Temporal 持久化编排 / 自动化测试层】类型「DeliberationPolicyTest」。
// 类型职责：集中验证评议的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「finalOnlyPanelRunsOnlyForHighRiskFullHearings」、「disabledModeNeverRunsPanel」、「validatesScoreThresholdAndRegenerationBudget」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class DeliberationPolicyTest {

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DeliberationPolicyTest.finalOnlyPanelRunsOnlyForHighRiskFullHearings()」。
    // 具体功能：「DeliberationPolicyTest.finalOnlyPanelRunsOnlyForHighRiskFullHearings()」：复现“核对完整业务行为（场景方法「finalOnlyPanelRunsOnlyForHighRiskFullHearings」）”场景：驱动 「DeliberationPolicy.shouldRunFinalPanel」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「HIGH」、「CRITICAL」、「MEDIUM」。
    // 上游调用：「DeliberationPolicyTest.finalOnlyPanelRunsOnlyForHighRiskFullHearings()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DeliberationPolicyTest.finalOnlyPanelRunsOnlyForHighRiskFullHearings()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DeliberationPolicyTest.finalOnlyPanelRunsOnlyForHighRiskFullHearings()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「HIGH」、「CRITICAL」、「MEDIUM」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void finalOnlyPanelRunsOnlyForHighRiskFullHearings() {
        assertThat(
                        DeliberationPolicy.shouldRunFinalPanel(
                                RouteType.FULL_HEARING,
                                "HIGH",
                                DeliberationInterventionMode.FINAL_ONLY,
                                "HIGH"))
                .isTrue();
        assertThat(
                        DeliberationPolicy.shouldRunFinalPanel(
                                RouteType.FULL_HEARING,
                                "CRITICAL",
                                DeliberationInterventionMode.FINAL_ONLY,
                                "HIGH"))
                .isTrue();
        assertThat(
                        DeliberationPolicy.shouldRunFinalPanel(
                                RouteType.FULL_HEARING,
                                "MEDIUM",
                                DeliberationInterventionMode.FINAL_ONLY,
                                "HIGH"))
                .isFalse();
        assertThat(
                        DeliberationPolicy.shouldRunFinalPanel(
                                RouteType.SIMPLE_HEARING,
                                "HIGH",
                                DeliberationInterventionMode.FINAL_ONLY,
                                "HIGH"))
                .isFalse();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DeliberationPolicyTest.disabledModeNeverRunsPanel()」。
    // 具体功能：「DeliberationPolicyTest.disabledModeNeverRunsPanel()」：复现“核对完整业务行为（场景方法「disabledModeNeverRunsPanel」）”场景：驱动 「DeliberationPolicy.shouldRunFinalPanel」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「CRITICAL」、「HIGH」。
    // 上游调用：「DeliberationPolicyTest.disabledModeNeverRunsPanel()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DeliberationPolicyTest.disabledModeNeverRunsPanel()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DeliberationPolicyTest.disabledModeNeverRunsPanel()」守住「Temporal 持久化编排」的可执行规格，尤其防止 「CRITICAL」、「HIGH」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void disabledModeNeverRunsPanel() {
        assertThat(
                        DeliberationPolicy.shouldRunFinalPanel(
                                RouteType.FULL_HEARING,
                                "CRITICAL",
                                DeliberationInterventionMode.DISABLED,
                                "HIGH"))
                .isFalse();
    }

    // 所属模块：【Temporal 持久化编排 / 自动化测试层】「DeliberationPolicyTest.validatesScoreThresholdAndRegenerationBudget()」。
    // 具体功能：「DeliberationPolicyTest.validatesScoreThresholdAndRegenerationBudget()」：复现“校验业务契约（场景方法「validatesScoreThresholdAndRegenerationBudget」）”场景：驱动 「DeliberationPolicy.validateScoreThreshold」、「DeliberationPolicy.validateMaxRegenerations」，再用 「assertThat」、「assertThatThrownBy」 核对返回值、状态变化或协作者调用。
    // 上游调用：「DeliberationPolicyTest.validatesScoreThresholdAndRegenerationBudget()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DeliberationPolicyTest.validatesScoreThresholdAndRegenerationBudget()」的下游是被测服务、仓储或外部客户端替身；「assertThat、assertThatThrownBy」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DeliberationPolicyTest.validatesScoreThresholdAndRegenerationBudget()」守住「Temporal 持久化编排」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void validatesScoreThresholdAndRegenerationBudget() {
        assertThat(DeliberationPolicy.validateScoreThreshold(80)).isEqualTo(80);
        assertThat(DeliberationPolicy.validateMaxRegenerations(2)).isEqualTo(2);
        assertThatThrownBy(() -> DeliberationPolicy.validateScoreThreshold(101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeliberationPolicy.validateMaxRegenerations(3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
