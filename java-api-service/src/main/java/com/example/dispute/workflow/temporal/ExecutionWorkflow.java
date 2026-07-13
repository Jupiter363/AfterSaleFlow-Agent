/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义执行的 Temporal Workflow、Signal 和 Query 协议。
 * 业务链路：核心入口/契约为 「run」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.ExecutionCommand;
import com.example.dispute.workflow.domain.ExecutionResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「ExecutionWorkflow」。
// 类型职责：定义执行的 Temporal Workflow、Signal 和 Query 协议；本类型显式提供 「run」。
// 协作关系：由 「ExecutionWorkflowImpl」、「StubExecutionWorkflow」 实现。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
@WorkflowInterface
public interface ExecutionWorkflow {

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「ExecutionWorkflow.run(ExecutionCommand)」。
    // 具体功能：「ExecutionWorkflow.run(ExecutionCommand)」：定义 Temporal Workflow 入口「run」，接收 「command」(ExecutionCommand)，其确定性实现由 「ExecutionWorkflowImpl」、「StubExecutionWorkflow」 提供。
    // 上游调用：「ExecutionWorkflow.run(ExecutionCommand)」由 Java 应用服务或父 Workflow 通过 Temporal Client 启动。
    // 下游影响：「ExecutionWorkflow.run(ExecutionCommand)」的下游由 「ExecutionWorkflowImpl」、「StubExecutionWorkflow」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ExecutionWorkflow.run(ExecutionCommand)」属于可重放控制面；保持确定性才能让进程重启、超时和重试后从同一 history 恢复到相同状态。
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @WorkflowMethod
    ExecutionResult run(ExecutionCommand command);
}
