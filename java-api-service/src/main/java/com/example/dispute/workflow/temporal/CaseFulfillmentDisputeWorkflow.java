/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义案件履约争议的 Temporal Workflow、Signal 和 Query 协议。
 * 业务链路：核心入口/契约为 「run」、「submitPartyEvidence」、「submitReviewerSignal」、「queryState」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.CaseWorkflowResult;
import com.example.dispute.workflow.domain.CaseWorkflowSnapshot;
import com.example.dispute.workflow.domain.PartyEvidenceSignal;
import com.example.dispute.workflow.domain.ReviewerWorkflowSignal;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「CaseFulfillmentDisputeWorkflow」。
// 类型职责：定义案件履约争议的 Temporal Workflow、Signal 和 Query 协议；本类型显式提供 「run」、「submitPartyEvidence」、「submitReviewerSignal」、「queryState」。
// 协作关系：由 「CaseFulfillmentDisputeWorkflowImpl」 实现。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
@WorkflowInterface
public interface CaseFulfillmentDisputeWorkflow {

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeWorkflow.run(CaseWorkflowInput)」。
    // 具体功能：「CaseFulfillmentDisputeWorkflow.run(CaseWorkflowInput)」：定义 Temporal Workflow 入口「run」，接收 「input」(CaseWorkflowInput)，其确定性实现由 「CaseFulfillmentDisputeWorkflowImpl」 提供。
    // 上游调用：「CaseFulfillmentDisputeWorkflow.run(CaseWorkflowInput)」由 Java 应用服务或父 Workflow 通过 Temporal Client 启动。
    // 下游影响：「CaseFulfillmentDisputeWorkflow.run(CaseWorkflowInput)」的下游由 「CaseFulfillmentDisputeWorkflowImpl」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseFulfillmentDisputeWorkflow.run(CaseWorkflowInput)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @WorkflowMethod
    CaseWorkflowResult run(CaseWorkflowInput input);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeWorkflow.submitPartyEvidence(PartyEvidenceSignal)」。
    // 具体功能：「CaseFulfillmentDisputeWorkflow.submitPartyEvidence(PartyEvidenceSignal)」：定义 Temporal Signal「submitPartyEvidence」，把 「signal」(PartyEvidenceSignal) 异步送入正在运行的 Workflow；实现位于 「CaseFulfillmentDisputeWorkflowImpl」。
    // 上游调用：「CaseFulfillmentDisputeWorkflow.submitPartyEvidence(PartyEvidenceSignal)」的上游是 API/协调服务向指定 Workflow ID 发送的 Temporal Signal；Signal 可在 Workflow 等待期间到达。
    // 下游影响：「CaseFulfillmentDisputeWorkflow.submitPartyEvidence(PartyEvidenceSignal)」的下游由 「CaseFulfillmentDisputeWorkflowImpl」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseFulfillmentDisputeWorkflow.submitPartyEvidence(PartyEvidenceSignal)」负责主链路中的“当事方证据”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @SignalMethod
    void submitPartyEvidence(PartyEvidenceSignal signal);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeWorkflow.submitReviewerSignal(ReviewerWorkflowSignal)」。
    // 具体功能：「CaseFulfillmentDisputeWorkflow.submitReviewerSignal(ReviewerWorkflowSignal)」：定义 Temporal Signal「submitReviewerSignal」，把 「signal」(ReviewerWorkflowSignal) 异步送入正在运行的 Workflow；实现位于 「CaseFulfillmentDisputeWorkflowImpl」。
    // 上游调用：「CaseFulfillmentDisputeWorkflow.submitReviewerSignal(ReviewerWorkflowSignal)」的上游是 API/协调服务向指定 Workflow ID 发送的 Temporal Signal；Signal 可在 Workflow 等待期间到达。
    // 下游影响：「CaseFulfillmentDisputeWorkflow.submitReviewerSignal(ReviewerWorkflowSignal)」的下游由 「CaseFulfillmentDisputeWorkflowImpl」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseFulfillmentDisputeWorkflow.submitReviewerSignal(ReviewerWorkflowSignal)」负责主链路中的“审核员信号”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @SignalMethod
    void submitReviewerSignal(ReviewerWorkflowSignal signal);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「CaseFulfillmentDisputeWorkflow.queryState()」。
    // 具体功能：「CaseFulfillmentDisputeWorkflow.queryState()」：定义 Temporal Query「queryState」，只读返回 Workflow 内存快照，不得推进状态；实现位于 「CaseFulfillmentDisputeWorkflowImpl」。
    // 上游调用：「CaseFulfillmentDisputeWorkflow.queryState()」由 Temporal Client 发起只读状态查询，不应改变 Workflow history。
    // 下游影响：「CaseFulfillmentDisputeWorkflow.queryState()」的下游由 「CaseFulfillmentDisputeWorkflowImpl」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「CaseFulfillmentDisputeWorkflow.queryState()」负责主链路中的“状态”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @QueryMethod
    CaseWorkflowSnapshot queryState();
}
