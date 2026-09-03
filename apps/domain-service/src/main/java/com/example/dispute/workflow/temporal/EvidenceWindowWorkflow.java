/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义证据Window的 Temporal Workflow、Signal 和 Query 协议。
 * 业务链路：核心入口/契约为 「run」、「partyCompleted」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.EvidenceWindowCommand;
import com.example.dispute.workflow.domain.EvidenceWindowResult;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「EvidenceWindowWorkflow」。
// 类型职责：定义证据Window的 Temporal Workflow、Signal 和 Query 协议；本类型显式提供 「run」、「partyCompleted」。
// 协作关系：由 「EvidenceWindowWorkflowImpl」 实现。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
@WorkflowInterface
public interface EvidenceWindowWorkflow {

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「EvidenceWindowWorkflow.run(EvidenceWindowCommand)」。
    // 具体功能：「EvidenceWindowWorkflow.run(EvidenceWindowCommand)」：定义 Temporal Workflow 入口「run」，接收 「command」(EvidenceWindowCommand)，其确定性实现由 「EvidenceWindowWorkflowImpl」 提供。
    // 上游调用：「EvidenceWindowWorkflow.run(EvidenceWindowCommand)」由 Java 应用服务或父 Workflow 通过 Temporal Client 启动。
    // 下游影响：「EvidenceWindowWorkflow.run(EvidenceWindowCommand)」的下游由 「EvidenceWindowWorkflowImpl」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceWindowWorkflow.run(EvidenceWindowCommand)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @WorkflowMethod
    EvidenceWindowResult run(EvidenceWindowCommand command);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「EvidenceWindowWorkflow.partyCompleted(String)」。
    // 具体功能：「EvidenceWindowWorkflow.partyCompleted(String)」：定义 Temporal Signal「partyCompleted」，把 「role」(String) 异步送入正在运行的 Workflow；实现位于 「EvidenceWindowWorkflowImpl」。
    // 上游调用：「EvidenceWindowWorkflow.partyCompleted(String)」的上游是 API/协调服务向指定 Workflow ID 发送的 Temporal Signal；Signal 可在 Workflow 等待期间到达。
    // 下游影响：「EvidenceWindowWorkflow.partyCompleted(String)」的下游由 「EvidenceWindowWorkflowImpl」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceWindowWorkflow.partyCompleted(String)」负责主链路中的“当事方完成”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @SignalMethod
    void partyCompleted(String role);
}
