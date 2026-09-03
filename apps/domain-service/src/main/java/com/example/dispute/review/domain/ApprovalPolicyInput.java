/*
 * 所属模块：平台人工终审。
 * 文件职责：定义审批输入跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

// 所属模块：【平台人工终审 / 领域模型层】类型「ApprovalPolicyInput」。
// 类型职责：定义审批输入跨层传递时使用的不可变数据契约；本类型显式提供 「ApprovalPolicyInput」。
// 协作关系：主要由 「ReviewApplicationService.createForWorkflow」、「ApprovalPolicyEngineTest.everyPlanRequiresRealPlatformReview」、「ApprovalPolicyEngineTest.highValueRefundAndReshipBecomeUrgentRiskReview」、「ApprovalPolicyEngineTest.insufficientEvidenceIsNeverAutoApproved」 使用。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record ApprovalPolicyInput(
        RiskLevel riskLevel,
        BigDecimal totalAmount,
        List<String> actionTypes,
        String disputeType,
        boolean evidenceInsufficient) {

    // 所属模块：【平台人工终审 / 领域模型层】「ApprovalPolicyInput.ApprovalPolicyInput(RiskLevel,BigDecimal,List,String,boolean)」。
    // 具体功能：「ApprovalPolicyInput.ApprovalPolicyInput(RiskLevel,BigDecimal,List,String,boolean)」：在不可变「ApprovalPolicyInput」写入组件前校验 「riskLevel」(RiskLevel)、「totalAmount」(BigDecimal)、「actionTypes」(List)、「disputeType」(String)、「evidenceInsufficient」(boolean)，非法输入会抛出 「IllegalArgumentException」；并通过 「Objects.requireNonNull」 做标准化或防御性复制。
    // 上游调用：「ApprovalPolicyInput.ApprovalPolicyInput(RiskLevel,BigDecimal,List,String,boolean)」的上游创建点包括 「ReviewApplicationService.createForWorkflow」、「ApprovalPolicyEngineTest.everyPlanRequiresRealPlatformReview」、「ApprovalPolicyEngineTest.highValueRefundAndReshipBecomeUrgentRiskReview」、「ApprovalPolicyEngineTest.insufficientEvidenceIsNeverAutoApproved」。
    // 下游影响：「ApprovalPolicyInput.ApprovalPolicyInput(RiskLevel,BigDecimal,List,String,boolean)」向下依次触达 「Objects.requireNonNull」。
    // 系统意义：「ApprovalPolicyInput.ApprovalPolicyInput(RiskLevel,BigDecimal,List,String,boolean)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public ApprovalPolicyInput {
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        totalAmount = totalAmount == null ? BigDecimal.ZERO : totalAmount;
        actionTypes = actionTypes == null ? List.of() : List.copyOf(actionTypes);
        if (actionTypes.isEmpty()) {
            throw new IllegalArgumentException("actionTypes must not be empty");
        }
    }
}
