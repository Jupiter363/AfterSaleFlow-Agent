/*
 * 所属模块：平台人工终审。
 * 文件职责：以确定性规则计算审批，输出可解释且可测试的决策。
 * 业务链路：核心入口/契约为 「evaluate」；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

// 所属模块：【平台人工终审 / 领域模型层】类型「ApprovalPolicyEngine」。
// 类型职责：以确定性规则计算审批，输出可解释且可测试的决策；本类型显式提供 「ApprovalPolicyEngine」、「evaluate」。
// 协作关系：主要由 「ReviewApplicationService.ReviewApplicationService」、「ReviewApplicationService.createForWorkflow」、「ApprovalPolicyEngineTest.everyPlanRequiresRealPlatformReview」、「ApprovalPolicyEngineTest.highValueRefundAndReshipBecomeUrgentRiskReview」 使用。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public final class ApprovalPolicyEngine {

    private static final Set<String> GOVERNED_ACTIONS =
            Set.of(
                    "REFUND",
                    "RESHIP",
                    "REPLACE",
                    "CLOSE_AFTER_SALE",
                    "REJECT_AFTER_SALE",
                    "QUERY_LOGISTICS",
                    "NOTIFY_USER",
                    "NOTIFY_MERCHANT");

    private final BigDecimal refundThreshold;
    private final BigDecimal reshipThreshold;

    // 所属模块：【平台人工终审 / 领域模型层】「ApprovalPolicyEngine.ApprovalPolicyEngine(BigDecimal,BigDecimal)」。
    // 具体功能：「ApprovalPolicyEngine.ApprovalPolicyEngine(BigDecimal,BigDecimal)」：使用 「refundThreshold」(BigDecimal)、「reshipThreshold」(BigDecimal) 初始化「ApprovalPolicyEngine」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「ApprovalPolicyEngine.ApprovalPolicyEngine(BigDecimal,BigDecimal)」的上游创建点包括 「ReviewApplicationService.ReviewApplicationService」。
    // 下游影响：「ApprovalPolicyEngine.ApprovalPolicyEngine(BigDecimal,BigDecimal)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ApprovalPolicyEngine.ApprovalPolicyEngine(BigDecimal,BigDecimal)」负责主链路中的“审批政策规则Engine”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public ApprovalPolicyEngine(
            BigDecimal refundThreshold, BigDecimal reshipThreshold) {
        this.refundThreshold = refundThreshold;
        this.reshipThreshold = reshipThreshold;
    }

    // 所属模块：【平台人工终审 / 领域模型层】「ApprovalPolicyEngine.evaluate(ApprovalPolicyInput)」。
    // 具体功能：「ApprovalPolicyEngine.evaluate(ApprovalPolicyInput)」：评估审批决定；实际协作者为 「input.riskLevel」、「input.actionTypes」、「input.totalAmount」、「input.disputeType」；处理的关键状态/协议值包括 「PLATFORM_HUMAN_REVIEW」、「REFUND」、「HIGH_VALUE_REFUND」、「RESHIP」，最终返回「ApprovalPolicyDecision」。
    // 上游调用：「ApprovalPolicyEngine.evaluate(ApprovalPolicyInput)」的上游调用点包括 「ReviewApplicationService.createForWorkflow」、「ApprovalPolicyEngineTest.everyPlanRequiresRealPlatformReview」、「ApprovalPolicyEngineTest.highValueRefundAndReshipBecomeUrgentRiskReview」、「ApprovalPolicyEngineTest.insufficientEvidenceIsNeverAutoApproved」。
    // 下游影响：「ApprovalPolicyEngine.evaluate(ApprovalPolicyInput)」向下依次触达 「input.riskLevel」、「input.actionTypes」、「input.totalAmount」、「input.disputeType」；计算结果以「ApprovalPolicyDecision」交给调用方。
    // 系统意义：「ApprovalPolicyEngine.evaluate(ApprovalPolicyInput)」负责主链路中的“审批决定”；最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
    // Java 语法：stream/lambda 把集合处理写成管道；lambda 中引用的外部局部变量必须保持 effectively final。
    public ApprovalPolicyDecision evaluate(ApprovalPolicyInput input) {
        List<String> required =
                new ArrayList<>(List.of("PLATFORM_HUMAN_REVIEW"));
        List<String> flags = new ArrayList<>();
        boolean riskReview = input.riskLevel().ordinal() >= RiskLevel.HIGH.ordinal();
        if (input.actionTypes().contains("REFUND")
                && input.totalAmount().compareTo(refundThreshold) >= 0) {
            flags.add("HIGH_VALUE_REFUND");
            riskReview = true;
        }
        if ((input.actionTypes().contains("RESHIP")
                        || input.actionTypes().contains("REPLACE"))
                && input.totalAmount().compareTo(reshipThreshold) >= 0) {
            flags.add("HIGH_VALUE_RESHIP");
            riskReview = true;
        }
        if (input.disputeType() != null
                && input.disputeType().contains("ITEM_SWAP")) {
            flags.add("ITEM_SWAP_DISPUTE");
            riskReview = true;
        }
        if (input.evidenceInsufficient()) {
            flags.add("EVIDENCE_INSUFFICIENT");
            riskReview = true;
        }
        if (riskReview) {
            required.add("RISK_CONTROL_REVIEW");
        }
        String priority =
                riskReview
                        ? "URGENT"
                        : input.riskLevel() == RiskLevel.MEDIUM ? "HIGH" : "NORMAL";
        List<String> allowedActions =
                List.copyOf(new LinkedHashSet<>(input.actionTypes()));
        List<String> forbiddenActions =
                GOVERNED_ACTIONS.stream()
                        .filter(action -> !allowedActions.contains(action))
                        .sorted()
                        .toList();
        return new ApprovalPolicyDecision(
                "approval-policy-v1",
                "PLATFORM_REVIEWER",
                1,
                priority,
                required,
                flags,
                allowedActions,
                forbiddenActions,
                false);
    }
}
