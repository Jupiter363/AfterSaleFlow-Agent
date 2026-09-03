/*
 * 所属模块：平台人工终审。
 * 文件职责：验证审批，覆盖 「everyPlanRequiresRealPlatformReview」、「highValueRefundAndReshipBecomeUrgentRiskReview」、「insufficientEvidenceIsNeverAutoApproved」。
 * 业务链路：JUnit 构造夹具并驱动真实服务或 Mock 协作者，断言返回值、持久化状态和调用边界；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.review.domain.ApprovalPolicyEngine;
import com.example.dispute.review.domain.ApprovalPolicyInput;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

// 所属模块：【平台人工终审 / 自动化测试层】类型「ApprovalPolicyEngineTest」。
// 类型职责：集中验证审批的业务场景、权限边界和持久化/外部协作契约；本类型显式提供 「everyPlanRequiresRealPlatformReview」、「highValueRefundAndReshipBecomeUrgentRiskReview」、「insufficientEvidenceIsNeverAutoApproved」。
// 协作关系：由 JUnit 发现并执行其中带 @Test 的场景。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
class ApprovalPolicyEngineTest {

    private final ApprovalPolicyEngine engine =
            new ApprovalPolicyEngine(
                    new BigDecimal("500.00"), new BigDecimal("300.00"));

    // 所属模块：【平台人工终审 / 自动化测试层】「ApprovalPolicyEngineTest.everyPlanRequiresRealPlatformReview()」。
    // 具体功能：「ApprovalPolicyEngineTest.everyPlanRequiresRealPlatformReview()」：复现“核对完整业务行为（场景方法「everyPlanRequiresRealPlatformReview」）”场景：驱动 「engine.evaluate」、「decision.requiredRole」、「decision.requiredApprovals」、「decision.autoApprove」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「QUERY_LOGISTICS」、「PLATFORM_REVIEWER」、「PLATFORM_HUMAN_REVIEW」、「approval-policy-v1」。
    // 上游调用：「ApprovalPolicyEngineTest.everyPlanRequiresRealPlatformReview()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ApprovalPolicyEngineTest.everyPlanRequiresRealPlatformReview()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ApprovalPolicyEngineTest.everyPlanRequiresRealPlatformReview()」守住「平台人工终审」的可执行规格，尤其防止 「QUERY_LOGISTICS」、「PLATFORM_REVIEWER」、「PLATFORM_HUMAN_REVIEW」、「approval-policy-v1」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void everyPlanRequiresRealPlatformReview() {
        var decision =
                engine.evaluate(
                        new ApprovalPolicyInput(
                                RiskLevel.LOW,
                                BigDecimal.ZERO,
                                List.of("QUERY_LOGISTICS"),
                                null,
                                false));

        assertThat(decision.requiredRole()).isEqualTo("PLATFORM_REVIEWER");
        assertThat(decision.requiredApprovals())
                .containsExactly("PLATFORM_HUMAN_REVIEW");
        assertThat(decision.autoApprove()).isFalse();
        assertThat(decision.policyVersion()).isEqualTo("approval-policy-v1");
        assertThat(decision.requiredReviewCount()).isEqualTo(1);
        assertThat(decision.allowedActions())
                .containsExactly("QUERY_LOGISTICS");
        assertThat(decision.forbiddenActions())
                .contains("REFUND", "RESHIP", "CLOSE_AFTER_SALE");
    }

    // 所属模块：【平台人工终审 / 自动化测试层】「ApprovalPolicyEngineTest.highValueRefundAndReshipBecomeUrgentRiskReview()」。
    // 具体功能：「ApprovalPolicyEngineTest.highValueRefundAndReshipBecomeUrgentRiskReview()」：复现“核对完整业务行为（场景方法「highValueRefundAndReshipBecomeUrgentRiskReview」）”场景：驱动 「engine.evaluate」、「refund.priority」、「refund.requiredApprovals」、「refund.requiredReviewCount」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「800.00」、「REFUND」、「ITEM_SWAP_DISPUTE」、「350.00」。
    // 上游调用：「ApprovalPolicyEngineTest.highValueRefundAndReshipBecomeUrgentRiskReview()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ApprovalPolicyEngineTest.highValueRefundAndReshipBecomeUrgentRiskReview()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ApprovalPolicyEngineTest.highValueRefundAndReshipBecomeUrgentRiskReview()」守住「平台人工终审」的可执行规格，尤其防止 「800.00」、「REFUND」、「ITEM_SWAP_DISPUTE」、「350.00」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void highValueRefundAndReshipBecomeUrgentRiskReview() {
        var refund =
                engine.evaluate(
                        new ApprovalPolicyInput(
                                RiskLevel.HIGH,
                                new BigDecimal("800.00"),
                                List.of("REFUND"),
                                "ITEM_SWAP_DISPUTE",
                                false));
        var reship =
                engine.evaluate(
                        new ApprovalPolicyInput(
                                RiskLevel.MEDIUM,
                                new BigDecimal("350.00"),
                                List.of("RESHIP"),
                                null,
                                false));

        assertThat(refund.priority()).isEqualTo("URGENT");
        assertThat(refund.requiredApprovals())
                .contains(
                        "PLATFORM_HUMAN_REVIEW",
                        "RISK_CONTROL_REVIEW");
        assertThat(refund.requiredReviewCount()).isEqualTo(1);
        assertThat(refund.allowedActions()).contains("REFUND");
        assertThat(refund.forbiddenActions()).doesNotContain("REFUND");
        assertThat(refund.riskFlags())
                .contains("HIGH_VALUE_REFUND", "ITEM_SWAP_DISPUTE");
        assertThat(reship.riskFlags()).contains("HIGH_VALUE_RESHIP");
    }

    // 所属模块：【平台人工终审 / 自动化测试层】「ApprovalPolicyEngineTest.insufficientEvidenceIsNeverAutoApproved()」。
    // 具体功能：「ApprovalPolicyEngineTest.insufficientEvidenceIsNeverAutoApproved()」：复现“核对完整业务行为（场景方法「insufficientEvidenceIsNeverAutoApproved」）”场景：驱动 「engine.evaluate」、「decision.priority」、「decision.riskFlags」、「decision.autoApprove」，再用 「assertThat」 核对返回值、状态变化或协作者调用，重点覆盖状态/错误码 「50.00」、「REFUND」、「NON_RECEIPT」、「URGENT」。
    // 上游调用：「ApprovalPolicyEngineTest.insufficientEvidenceIsNeverAutoApproved()」由 JUnit 测试运行器调用；夹具、Mock 和输入均在本用例内创建，不依赖生产请求。
    // 下游影响：「ApprovalPolicyEngineTest.insufficientEvidenceIsNeverAutoApproved()」的下游是被测服务、仓储或外部客户端替身；「assertThat」把结果与预期状态、异常或调用次数锁定。
    // 系统意义：「ApprovalPolicyEngineTest.insufficientEvidenceIsNeverAutoApproved()」守住「平台人工终审」的可执行规格，尤其防止 「50.00」、「REFUND」、「NON_RECEIPT」、「URGENT」 语义漂移；后续重构若破坏契约会在进入集成环境前失败。
    @Test
    void insufficientEvidenceIsNeverAutoApproved() {
        var decision =
                engine.evaluate(
                        new ApprovalPolicyInput(
                                RiskLevel.MEDIUM,
                                new BigDecimal("50.00"),
                                List.of("REFUND"),
                                "NON_RECEIPT",
                                true));

        assertThat(decision.priority()).isEqualTo("URGENT");
        assertThat(decision.riskFlags()).contains("EVIDENCE_INSUFFICIENT");
        assertThat(decision.autoApprove()).isFalse();
    }
}
