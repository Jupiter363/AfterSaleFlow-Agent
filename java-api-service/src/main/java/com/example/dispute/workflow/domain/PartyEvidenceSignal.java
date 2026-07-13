/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义当事方证据信号跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.domain;

import java.util.List;

// 所属模块：【Temporal 持久化编排 / 领域模型层】类型「PartyEvidenceSignal」。
// 类型职责：定义当事方证据信号跨层传递时使用的不可变数据契约；本类型显式提供 「PartyEvidenceSignal」。
// 协作关系：主要由 「FinalWorkflowActivitiesAdapter.recordEvidence」、「CaseFulfillmentDisputeWorkflowTest.pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning」、「CaseFulfillmentDisputeWorkflowTest.reviewerCanRequestEvidenceThenApproveTheResumedWorkflow」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record PartyEvidenceSignal(
        String partyType, String submissionId, List<String> evidenceIds) {

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「PartyEvidenceSignal.PartyEvidenceSignal(String,String,List)」。
    // 具体功能：「PartyEvidenceSignal.PartyEvidenceSignal(String,String,List)」：在不可变「PartyEvidenceSignal」写入组件前校验 「partyType」(String)、「submissionId」(String)、「evidenceIds」(List)，非法输入会抛出 「IllegalArgumentException」。
    // 上游调用：「PartyEvidenceSignal.PartyEvidenceSignal(String,String,List)」的上游创建点包括 「FinalWorkflowActivitiesAdapter.recordEvidence」、「CaseFulfillmentDisputeWorkflowTest.pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning」、「CaseFulfillmentDisputeWorkflowTest.reviewerCanRequestEvidenceThenApproveTheResumedWorkflow」。
    // 下游影响：「PartyEvidenceSignal.PartyEvidenceSignal(String,String,List)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「PartyEvidenceSignal.PartyEvidenceSignal(String,String,List)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public PartyEvidenceSignal {
        if (partyType == null || partyType.isBlank()) {
            throw new IllegalArgumentException("partyType must not be blank");
        }
        if (submissionId == null || submissionId.isBlank()) {
            throw new IllegalArgumentException("submissionId must not be blank");
        }
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
