/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义人工审核的 Temporal Workflow、Signal 和 Query 协议。
 * 业务链路：核心入口/契约为 「run」、「submitDecision」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.HumanReviewCommand;
import com.example.dispute.workflow.domain.HumanReviewResult;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「HumanReviewWorkflow」。
// 类型职责：定义人工审核的 Temporal Workflow、Signal 和 Query 协议；本类型显式提供 「run」、「submitDecision」。
// 协作关系：主要由 「FulfillmentDisputeWorkflowImpl.flushReview」、「FulfillmentDisputeWorkflowImpl.run」、「FulfillmentDisputeWorkflowImpl.submitReviewDecision」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
@WorkflowInterface
public interface HumanReviewWorkflow {

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「HumanReviewWorkflow.run(HumanReviewCommand)」。
    // 具体功能：「HumanReviewWorkflow.run(HumanReviewCommand)」：定义 Temporal Workflow 入口「run」，接收 「command」(HumanReviewCommand)，其确定性实现由 「HumanReviewWorkflowImpl」、「StubReviewWorkflow」 提供。
    // 上游调用：「HumanReviewWorkflow.run(HumanReviewCommand)」由 Java 应用服务或父 Workflow 通过 Temporal Client 启动。
    // 下游影响：「HumanReviewWorkflow.run(HumanReviewCommand)」的下游由 「HumanReviewWorkflowImpl」、「StubReviewWorkflow」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HumanReviewWorkflow.run(HumanReviewCommand)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @WorkflowMethod
    HumanReviewResult run(HumanReviewCommand command);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「HumanReviewWorkflow.submitDecision(HumanReviewSignal)」。
    // 具体功能：「HumanReviewWorkflow.submitDecision(HumanReviewSignal)」：定义 Temporal Signal「submitDecision」，把 「signal」(HumanReviewSignal) 异步送入正在运行的 Workflow；实现位于 「HumanReviewWorkflowImpl」、「StubReviewWorkflow」。
    // 上游调用：「HumanReviewWorkflow.submitDecision(HumanReviewSignal)」的上游是 API/协调服务向指定 Workflow ID 发送的 Temporal Signal；Signal 可在 Workflow 等待期间到达。
    // 下游影响：「HumanReviewWorkflow.submitDecision(HumanReviewSignal)」的下游由 「HumanReviewWorkflowImpl」、「StubReviewWorkflow」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HumanReviewWorkflow.submitDecision(HumanReviewSignal)」负责主链路中的“决定”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @SignalMethod
    void submitDecision(HumanReviewSignal signal);
}
