/*
 * 所属模块：共享小法庭。
 * 文件职责：定义Submit庭审轮次跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「toCommand」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.api;

import com.example.dispute.hearing.application.SubmitHearingRoundCommand;
import jakarta.validation.constraints.Min;

// 所属模块：【共享小法庭 / HTTP 接口层】类型「SubmitHearingRoundRequest」。
// 类型职责：定义Submit庭审轮次跨层传递时使用的不可变数据契约；本类型显式提供 「toCommand」。
// 协作关系：主要由 「HearingCollaborationController.submitCurrentRound」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record SubmitHearingRoundRequest(
        @Min(1) int dossierVersion,
        String statementJson) {
    // 所属模块：【共享小法庭 / HTTP 接口层】「SubmitHearingRoundRequest.toCommand()」。
    // 具体功能：「SubmitHearingRoundRequest.toCommand()」：转换命令，最终返回「SubmitHearingRoundCommand」。
    // 上游调用：「SubmitHearingRoundRequest.toCommand()」的上游调用点包括 「HearingCollaborationController.submitCurrentRound」。
    // 下游影响：「SubmitHearingRoundRequest.toCommand()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「SubmitHearingRoundCommand」交给调用方。
    // 系统意义：「SubmitHearingRoundRequest.toCommand()」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    SubmitHearingRoundCommand toCommand() {
        return new SubmitHearingRoundCommand(dossierVersion, statementJson);
    }
}
