/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：定义证据提交跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「toCommand」；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.api;

import com.example.dispute.evidence.application.EvidenceSubmissionCommand;
import jakarta.validation.constraints.Size;
import java.util.List;

// 所属模块：【证据与版本化卷宗 / HTTP 接口层】类型「EvidenceSubmissionRequest」。
// 类型职责：定义证据提交跨层传递时使用的不可变数据契约；本类型显式提供 「toCommand」。
// 协作关系：主要由 「EvidenceController.submitBatch」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record EvidenceSubmissionRequest(
        @Size(min = 1, max = 50) List<String> evidenceIds,
        @Size(max = 1000) String batchNote) {

    // 所属模块：【证据与版本化卷宗 / HTTP 接口层】「EvidenceSubmissionRequest.toCommand()」。
    // 具体功能：「EvidenceSubmissionRequest.toCommand()」：转换命令，最终返回「EvidenceSubmissionCommand」。
    // 上游调用：「EvidenceSubmissionRequest.toCommand()」的上游调用点包括 「EvidenceController.submitBatch」。
    // 下游影响：「EvidenceSubmissionRequest.toCommand()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「EvidenceSubmissionCommand」交给调用方。
    // 系统意义：「EvidenceSubmissionRequest.toCommand()」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    EvidenceSubmissionCommand toCommand() {
        return new EvidenceSubmissionCommand(evidenceIds, batchNote);
    }
}
