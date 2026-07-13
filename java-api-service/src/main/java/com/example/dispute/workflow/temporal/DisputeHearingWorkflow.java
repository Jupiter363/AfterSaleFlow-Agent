/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义争议庭审的 Temporal Workflow、Signal 和 Query 协议。
 * 业务链路：核心入口/契约为 「run」、「submitEvidence」、「hearingRoundCompleted」、「settlementConfirmed」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.domain.HearingWorkflowResult;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「DisputeHearingWorkflow」。
// 类型职责：定义争议庭审的 Temporal Workflow、Signal 和 Query 协议；本类型显式提供 「run」、「submitEvidence」、「hearingRoundCompleted」、「settlementConfirmed」。
// 协作关系：主要由 「FulfillmentDisputeWorkflowImpl.flushEvidence」、「FulfillmentDisputeWorkflowImpl.run」、「FulfillmentDisputeWorkflowImpl.submitEvidence」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
@WorkflowInterface
public interface DisputeHearingWorkflow {

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingWorkflow.run(HearingWorkflowCommand)」。
    // 具体功能：「DisputeHearingWorkflow.run(HearingWorkflowCommand)」：定义 Temporal Workflow 入口「run」，接收 「command」(HearingWorkflowCommand)，其确定性实现由 「DisputeHearingWorkflowImpl」、「StubHearingWorkflow」 提供。
    // 上游调用：「DisputeHearingWorkflow.run(HearingWorkflowCommand)」由 Java 应用服务或父 Workflow 通过 Temporal Client 启动。
    // 下游影响：「DisputeHearingWorkflow.run(HearingWorkflowCommand)」的下游由 「DisputeHearingWorkflowImpl」、「StubHearingWorkflow」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「DisputeHearingWorkflow.run(HearingWorkflowCommand)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @WorkflowMethod
    HearingWorkflowResult run(HearingWorkflowCommand command);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingWorkflow.submitEvidence(EvidenceSubmissionSignal)」。
    // 具体功能：「DisputeHearingWorkflow.submitEvidence(EvidenceSubmissionSignal)」：定义 Temporal Signal「submitEvidence」，把 「signal」(EvidenceSubmissionSignal) 异步送入正在运行的 Workflow；实现位于 「DisputeHearingWorkflowImpl」、「StubHearingWorkflow」。
    // 上游调用：「DisputeHearingWorkflow.submitEvidence(EvidenceSubmissionSignal)」的上游是 API/协调服务向指定 Workflow ID 发送的 Temporal Signal；Signal 可在 Workflow 等待期间到达。
    // 下游影响：「DisputeHearingWorkflow.submitEvidence(EvidenceSubmissionSignal)」的下游由 「DisputeHearingWorkflowImpl」、「StubHearingWorkflow」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「DisputeHearingWorkflow.submitEvidence(EvidenceSubmissionSignal)」负责主链路中的“证据”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @SignalMethod
    void submitEvidence(EvidenceSubmissionSignal signal);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingWorkflow.hearingRoundCompleted(int,boolean)」。
    // 具体功能：「DisputeHearingWorkflow.hearingRoundCompleted(int,boolean)」：定义 Temporal Signal「hearingRoundCompleted」，把 「roundNo」(int)、「factsSufficient」(boolean) 异步送入正在运行的 Workflow；实现位于 「DisputeHearingWorkflowImpl」、「StubHearingWorkflow」。
    // 上游调用：「DisputeHearingWorkflow.hearingRoundCompleted(int,boolean)」的上游是 API/协调服务向指定 Workflow ID 发送的 Temporal Signal；Signal 可在 Workflow 等待期间到达。
    // 下游影响：「DisputeHearingWorkflow.hearingRoundCompleted(int,boolean)」的下游由 「DisputeHearingWorkflowImpl」、「StubHearingWorkflow」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「DisputeHearingWorkflow.hearingRoundCompleted(int,boolean)」负责主链路中的“庭审轮次完成”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @SignalMethod
    void hearingRoundCompleted(int roundNo, boolean factsSufficient);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DisputeHearingWorkflow.settlementConfirmed(int)」。
    // 具体功能：「DisputeHearingWorkflow.settlementConfirmed(int)」：定义 Temporal Signal「settlementConfirmed」，把 「settlementVersion」(int) 异步送入正在运行的 Workflow；实现位于 「DisputeHearingWorkflowImpl」、「StubHearingWorkflow」。
    // 上游调用：「DisputeHearingWorkflow.settlementConfirmed(int)」的上游是 API/协调服务向指定 Workflow ID 发送的 Temporal Signal；Signal 可在 Workflow 等待期间到达。
    // 下游影响：「DisputeHearingWorkflow.settlementConfirmed(int)」的下游由 「DisputeHearingWorkflowImpl」、「StubHearingWorkflow」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「DisputeHearingWorkflow.settlementConfirmed(int)」负责主链路中的“和解Confirmed”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @SignalMethod
    void settlementConfirmed(int settlementVersion);
}
