/*
 * 所属模块：证据与版本化卷宗。
 * 文件职责：定义证据提交跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；接收原始证据、触发 OCR、执行可信度核验、控制角色可见性并冻结版本化卷宗。
 * 关键边界：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
 */
package com.example.dispute.evidence.application;

import java.util.List;

// 所属模块：【证据与版本化卷宗 / 应用编排层】类型「EvidenceSubmissionCommand」。
// 类型职责：定义证据提交跨层传递时使用的不可变数据契约；本类型显式提供 「EvidenceSubmissionCommand」。
// 协作关系：主要由 「EvidenceSubmissionRequest.toCommand」、「EvidenceSubmissionServiceTest.submitsHearingSupplementEvidenceToTheHearingRoom」、「EvidenceSubmissionServiceTest.submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk」 使用。
// 边界意义：原件不可被摘要替代；迟到材料、脱敏内容和卷宗版本必须可追溯
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record EvidenceSubmissionCommand(List<String> evidenceIds, String batchNote) {
    // 所属模块：【证据与版本化卷宗 / 应用编排层】「EvidenceSubmissionCommand.EvidenceSubmissionCommand(List,String)」。
    // 具体功能：「EvidenceSubmissionCommand.EvidenceSubmissionCommand(List,String)」：在不可变「EvidenceSubmissionCommand」写入组件前校验 「evidenceIds」(List)、「batchNote」(String)，并统一规范 record 组件值。
    // 上游调用：「EvidenceSubmissionCommand.EvidenceSubmissionCommand(List,String)」的上游创建点包括 「EvidenceSubmissionRequest.toCommand」、「EvidenceSubmissionServiceTest.submitsPendingEvidenceAsOneBatchAndPostsEvidenceReferenceToClerk」、「EvidenceSubmissionServiceTest.submitsHearingSupplementEvidenceToTheHearingRoom」。
    // 下游影响：「EvidenceSubmissionCommand.EvidenceSubmissionCommand(List,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「EvidenceSubmissionCommand.EvidenceSubmissionCommand(List,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public EvidenceSubmissionCommand {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        batchNote = batchNote == null ? "" : batchNote.trim();
    }
}
