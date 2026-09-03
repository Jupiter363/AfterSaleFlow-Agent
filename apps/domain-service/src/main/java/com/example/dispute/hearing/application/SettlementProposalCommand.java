/*
 * 所属模块：共享小法庭。
 * 文件职责：定义和解Proposal跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

// 所属模块：【共享小法庭 / 应用编排层】类型「SettlementProposalCommand」。
// 类型职责：定义和解Proposal跨层传递时使用的不可变数据契约；本类型显式提供 「SettlementProposalCommand」。
// 协作关系：主要由 「SettlementProposalRequest.toCommand」、「HearingCollaborationIntegrationTest.onlyBothConfirmationsOnTheCurrentSettlementVersionConverge」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record SettlementProposalCommand(String proposalText, String proposalJson) {
    // 所属模块：【共享小法庭 / 应用编排层】「SettlementProposalCommand.SettlementProposalCommand(String,String)」。
    // 具体功能：「SettlementProposalCommand.SettlementProposalCommand(String,String)」：在不可变「SettlementProposalCommand」写入组件前校验 「proposalText」(String)、「proposalJson」(String)，非法输入会抛出 「IllegalArgumentException」。
    // 上游调用：「SettlementProposalCommand.SettlementProposalCommand(String,String)」的上游创建点包括 「SettlementProposalRequest.toCommand」、「HearingCollaborationIntegrationTest.onlyBothConfirmationsOnTheCurrentSettlementVersionConverge」。
    // 下游影响：「SettlementProposalCommand.SettlementProposalCommand(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「SettlementProposalCommand.SettlementProposalCommand(String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public SettlementProposalCommand {
        if (proposalText == null || proposalText.isBlank()) {
            throw new IllegalArgumentException("proposalText must not be blank");
        }
        if (proposalJson == null || proposalJson.isBlank()) {
            throw new IllegalArgumentException("proposalJson must not be blank");
        }
    }
}
