/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义执行跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.domain;

import java.util.List;

// 所属模块：【Temporal 持久化编排 / 领域模型层】类型「ExecutionCommand」。
// 类型职责：定义执行跨层传递时使用的不可变数据契约；本类型显式提供 「ExecutionCommand」。
// 协作关系：主要由 「FulfillmentDisputeWorkflowImpl.run」、「ExecutionWorkflowTest.command」、「ExecutionWorkflowTest.invalidOrExpiredApprovalNeverCallsExternalAction」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record ExecutionCommand(
        String caseId,
        String reviewId,
        int reviewPacketVersion,
        String actionHash,
        boolean approved,
        long approvalExpiresAtEpochMillis,
        List<ExecutionAction> actions) {

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「ExecutionCommand.ExecutionCommand(String,String,int,String,boolean,long,List)」。
    // 具体功能：「ExecutionCommand.ExecutionCommand(String,String,int,String,boolean,long,List)」：在不可变「ExecutionCommand」写入组件前校验 「caseId」(String)、「reviewId」(String)、「reviewPacketVersion」(int)、「actionHash」(String)、「approved」(boolean)、「approvalExpiresAtEpochMillis」(long)、「actions」(List)，并统一规范 record 组件值。
    // 上游调用：「ExecutionCommand.ExecutionCommand(String,String,int,String,boolean,long,List)」的上游创建点包括 「FulfillmentDisputeWorkflowImpl.run」、「ExecutionWorkflowTest.invalidOrExpiredApprovalNeverCallsExternalAction」、「ExecutionWorkflowTest.command」。
    // 下游影响：「ExecutionCommand.ExecutionCommand(String,String,int,String,boolean,long,List)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「ExecutionCommand.ExecutionCommand(String,String,int,String,boolean,long,List)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public ExecutionCommand {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
