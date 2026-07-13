/*
 * 所属模块：平台人工终审。
 * 文件职责：定义审批决定跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review.domain;

import java.util.List;

// 所属模块：【平台人工终审 / 领域模型层】类型「ApprovalPolicyDecision」。
// 类型职责：定义审批决定跨层传递时使用的不可变数据契约；本类型显式提供 「ApprovalPolicyDecision」。
// 协作关系：主要由 「ApprovalPolicyEngine.evaluate」 使用。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record ApprovalPolicyDecision(
        String policyVersion,
        String requiredRole,
        int requiredReviewCount,
        String priority,
        List<String> requiredApprovals,
        List<String> riskFlags,
        List<String> allowedActions,
        List<String> forbiddenActions,
        boolean autoApprove) {

    // 所属模块：【平台人工终审 / 领域模型层】「ApprovalPolicyDecision.ApprovalPolicyDecision(String,String,int,String,List,List,List,List,boolean)」。
    // 具体功能：「ApprovalPolicyDecision.ApprovalPolicyDecision(String,String,int,String,List,List,List,List,boolean)」：在不可变「ApprovalPolicyDecision」写入组件前校验 「policyVersion」(String)、「requiredRole」(String)、「requiredReviewCount」(int)、「priority」(String)、「requiredApprovals」(List)、「riskFlags」(List)、「allowedActions」(List)、「forbiddenActions」(List)、「autoApprove」(boolean)，非法输入会抛出 「IllegalArgumentException」。
    // 上游调用：「ApprovalPolicyDecision.ApprovalPolicyDecision(String,String,int,String,List,List,List,List,boolean)」的上游创建点包括 「ApprovalPolicyEngine.evaluate」。
    // 下游影响：「ApprovalPolicyDecision.ApprovalPolicyDecision(String,String,int,String,List,List,List,List,boolean)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ApprovalPolicyDecision.ApprovalPolicyDecision(String,String,int,String,List,List,List,List,boolean)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public ApprovalPolicyDecision {
        requiredApprovals = List.copyOf(requiredApprovals);
        riskFlags = List.copyOf(riskFlags);
        allowedActions = List.copyOf(allowedActions);
        forbiddenActions = List.copyOf(forbiddenActions);
        if (autoApprove) {
            throw new IllegalArgumentException(
                    "AI Native disputes can never be auto-approved");
        }
    }
}
