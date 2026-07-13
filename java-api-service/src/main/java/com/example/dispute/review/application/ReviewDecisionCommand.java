/*
 * 所属模块：平台人工终审。
 * 文件职责：定义审核决定跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；冻结 ReviewPacket、执行审批策略并记录审核员对具体版本和动作哈希的最终决定。
 * 关键边界：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
 */
package com.example.dispute.review.application;

import com.example.dispute.domain.model.ApprovalDecisionType;
import com.fasterxml.jackson.databind.JsonNode;

// 所属模块：【平台人工终审 / 应用编排层】类型「ReviewDecisionCommand」。
// 类型职责：定义审核决定跨层传递时使用的不可变数据契约；本类型显式提供 「ReviewDecisionCommand」。
// 协作关系：主要由 「CaseOutcomeService.confirmDraft」、「CaseOutcomeService.modifyDraft」、「ReviewController.decide」、「ReviewApplicationServiceIntegrationTest.announcesManualHandoffWhenTheReviewerEscalates」 使用。
// 边界意义：最终决定权属于具备平台审核角色的人；过期、改版或哈希不一致的审批必须失效
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record ReviewDecisionCommand(
        ApprovalDecisionType decision,
        String reason,
        JsonNode approvedPlan,
        String idempotencyKey) {

    // 所属模块：【平台人工终审 / 应用编排层】「ReviewDecisionCommand.ReviewDecisionCommand(ApprovalDecisionType,String,JsonNode,String)」。
    // 具体功能：「ReviewDecisionCommand.ReviewDecisionCommand(ApprovalDecisionType,String,JsonNode,String)」：在不可变「ReviewDecisionCommand」写入组件前校验 「decision」(ApprovalDecisionType)、「reason」(String)、「approvedPlan」(JsonNode)、「idempotencyKey」(String)，非法输入会抛出 「IllegalArgumentException」。
    // 上游调用：「ReviewDecisionCommand.ReviewDecisionCommand(ApprovalDecisionType,String,JsonNode,String)」的上游创建点包括 「CaseOutcomeService.confirmDraft」、「CaseOutcomeService.modifyDraft」、「ReviewController.decide」、「ReviewApplicationServiceIntegrationTest.createsPacketAndOnlyReviewerCanModifyApproveWithDiff」。
    // 下游影响：「ReviewDecisionCommand.ReviewDecisionCommand(ApprovalDecisionType,String,JsonNode,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ReviewDecisionCommand.ReviewDecisionCommand(ApprovalDecisionType,String,JsonNode,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public ReviewDecisionCommand {
        if (decision == null) {
            throw new IllegalArgumentException("decision is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("review reason is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotency key is required");
        }
    }
}
