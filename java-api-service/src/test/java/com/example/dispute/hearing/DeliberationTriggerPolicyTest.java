/*
 * 所属模块：共享小法庭。
 * 文件职责：验证评议Trigger，覆盖 「onlyLowRiskHighConfidenceAgreedCasesSkipThePanel」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.hearing.application.DeliberationTriggerContext;
import com.example.dispute.hearing.application.DeliberationTriggerPolicy;
import org.junit.jupiter.api.Test;

// 所属模块：【共享小法庭 / 自动化测试层】类型「DeliberationTriggerPolicyTest」。
// 类型职责：集中验证评议Trigger的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「onlyLowRiskHighConfidenceAgreedCasesSkipThePanel」、「decision」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class DeliberationTriggerPolicyTest {

    private final DeliberationTriggerPolicy policy =
            new DeliberationTriggerPolicy();

    // 所属模块：【共享小法庭 / 自动化测试层】「DeliberationTriggerPolicyTest.onlyLowRiskHighConfidenceAgreedCasesSkipThePanel()」。
    // 具体功能：「DeliberationTriggerPolicyTest.onlyLowRiskHighConfidenceAgreedCasesSkipThePanel()」：复现“核对完整业务行为（场景方法「onlyLowRiskHighConfidenceAgreedCasesSkipThePanel」）”场景：驱动 「policy.evaluate」，再用 「assertThat」 核对返回值、状态变化或协作者调用。
    // 上游调用：「DeliberationTriggerPolicyTest.onlyLowRiskHighConfidenceAgreedCasesSkipThePanel()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「DeliberationTriggerPolicyTest.onlyLowRiskHighConfidenceAgreedCasesSkipThePanel()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「DeliberationTriggerPolicyTest.onlyLowRiskHighConfidenceAgreedCasesSkipThePanel()」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void onlyLowRiskHighConfidenceAgreedCasesSkipThePanel() {
        assertThat(
                        policy.evaluate(
                                        new DeliberationTriggerContext(
                                                RiskLevel.LOW,
                                                true,
                                                0.9,
                                                false,
                                                false))
                                .shouldDeliberate())
                .isFalse();
        assertThat(decision(RiskLevel.HIGH, true, 0.9, false, false)).isTrue();
        assertThat(decision(RiskLevel.LOW, false, 0.9, false, false)).isTrue();
        assertThat(decision(RiskLevel.LOW, true, 0.4, false, false)).isTrue();
        assertThat(decision(RiskLevel.LOW, true, 0.9, true, false)).isTrue();
        assertThat(decision(RiskLevel.LOW, true, 0.9, false, true)).isTrue();
    }

    // 所属模块：【共享小法庭 / 自动化测试层】「DeliberationTriggerPolicyTest.decision(RiskLevel,boolean,double,boolean,boolean)」。
    // 具体功能：「DeliberationTriggerPolicyTest.decision(RiskLevel,boolean,double,boolean,boolean)」：作为测试辅助方法为“核对完整业务行为（场景方法「decision」）”组装或读取「DeliberationTriggerContext」 输入夹具，供本测试类的场景方法复用。
    // 上游调用：「DeliberationTriggerPolicyTest.decision(RiskLevel,boolean,double,boolean,boolean)」由本测试类中的 「DeliberationTriggerPolicyTest.onlyLowRiskHighConfidenceAgreedCasesSkipThePanel」 调用。
    // 下游影响：「DeliberationTriggerPolicyTest.decision(RiskLevel,boolean,double,boolean,boolean)」的下游是测试夹具或被测对象，不写入生产数据库，也不发起真实线上副作用。
    // 系统意义：「DeliberationTriggerPolicyTest.decision(RiskLevel,boolean,double,boolean,boolean)」守住「共享小法庭」的可执行规格；后续重构若破坏契约会在进入集成环境前失败。
    private boolean decision(
            RiskLevel risk,
            boolean settlement,
            double confidence,
            boolean conflict,
            boolean uncertain) {
        return policy.evaluate(
                        new DeliberationTriggerContext(
                                risk,
                                settlement,
                                confidence,
                                conflict,
                                uncertain))
                .shouldDeliberate();
    }
}
