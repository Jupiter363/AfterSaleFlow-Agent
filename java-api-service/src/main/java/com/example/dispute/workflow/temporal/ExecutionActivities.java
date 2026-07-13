/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义执行可由 Temporal 重试的 Activity 契约。
 * 业务链路：核心入口/契约为 「validateApproval」、「executeAction」、「lookupAction」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.ApprovalValidationResult;
import com.example.dispute.workflow.domain.ExecutionAction;
import com.example.dispute.workflow.domain.ExecutionActionActivityResult;
import com.example.dispute.workflow.domain.ExecutionCommand;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「ExecutionActivities」。
// 类型职责：定义执行可由 Temporal 重试的 Activity 契约；本类型显式提供 「validateApproval」、「executeAction」、「lookupAction」。
// 协作关系：主要由 「ExecutionWorkflowImpl.run」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
@ActivityInterface
public interface ExecutionActivities {

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「ExecutionActivities.validateApproval(ExecutionCommand)」。
    // 具体功能：「ExecutionActivities.validateApproval(ExecutionCommand)」：定义「ExecutionActivities」端口方法：接收 「command」(ExecutionCommand)，返回「ApprovalValidationResult」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「ExecutionActivities.validateApproval(ExecutionCommand)」的上游调用点包括 「ExecutionWorkflowImpl.run」。
    // 下游影响：「ExecutionActivities.validateApproval(ExecutionCommand)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ExecutionActivities.validateApproval(ExecutionCommand)」在“审批”进入下游前阻断非法状态；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    ApprovalValidationResult validateApproval(ExecutionCommand command);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「ExecutionActivities.executeAction(String,ExecutionAction)」。
    // 具体功能：「ExecutionActivities.executeAction(String,ExecutionAction)」：定义「ExecutionActivities」端口方法：接收 「caseId」(String)、「action」(ExecutionAction)，返回「ExecutionActionActivityResult」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「ExecutionActivities.executeAction(String,ExecutionAction)」的上游调用点包括 「ExecutionWorkflowImpl.run」。
    // 下游影响：「ExecutionActivities.executeAction(String,ExecutionAction)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ExecutionActivities.executeAction(String,ExecutionAction)」负责主链路中的“动作”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    ExecutionActionActivityResult executeAction(
            String caseId,
            ExecutionAction action);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「ExecutionActivities.lookupAction(String,ExecutionAction)」。
    // 具体功能：「ExecutionActivities.lookupAction(String,ExecutionAction)」：定义「ExecutionActivities」端口方法：接收 「caseId」(String)、「action」(ExecutionAction)，返回「ExecutionActionActivityResult」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「ExecutionActivities.lookupAction(String,ExecutionAction)」的上游调用点包括 「ExecutionWorkflowImpl.run」。
    // 下游影响：「ExecutionActivities.lookupAction(String,ExecutionAction)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「ExecutionActivities.lookupAction(String,ExecutionAction)」负责主链路中的“lookup动作”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    ExecutionActionActivityResult lookupAction(
            String caseId,
            ExecutionAction action);
}
