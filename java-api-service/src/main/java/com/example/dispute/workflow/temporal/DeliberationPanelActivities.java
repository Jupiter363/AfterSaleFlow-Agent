/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义评议Panel可由 Temporal 重试的 Activity 契约。
 * 业务链路：核心入口/契约为 「freeze」、「runCritic」、「persistReport」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.CriticActivityResult;
import com.example.dispute.workflow.domain.DeliberationPanelCommand;
import com.example.dispute.workflow.domain.FrozenDeliberationSnapshot;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「DeliberationPanelActivities」。
// 类型职责：定义评议Panel可由 Temporal 重试的 Activity 契约；本类型显式提供 「freeze」、「runCritic」、「persistReport」。
// 协作关系：主要由 「DeliberationPanelWorkflowImpl.run」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
@ActivityInterface
public interface DeliberationPanelActivities {

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DeliberationPanelActivities.freeze(DeliberationPanelCommand)」。
    // 具体功能：「DeliberationPanelActivities.freeze(DeliberationPanelCommand)」：定义「DeliberationPanelActivities」端口方法：接收 「command」(DeliberationPanelCommand)，返回「FrozenDeliberationSnapshot」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「DeliberationPanelActivities.freeze(DeliberationPanelCommand)」的上游调用点包括 「DeliberationPanelWorkflowImpl.run」。
    // 下游影响：「DeliberationPanelActivities.freeze(DeliberationPanelCommand)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「DeliberationPanelActivities.freeze(DeliberationPanelCommand)」负责主链路中的“冻结评议快照”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    FrozenDeliberationSnapshot freeze(DeliberationPanelCommand command);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DeliberationPanelActivities.runCritic(FrozenDeliberationSnapshot,String)」。
    // 具体功能：「DeliberationPanelActivities.runCritic(FrozenDeliberationSnapshot,String)」：定义「DeliberationPanelActivities」端口方法：接收 「snapshot」(FrozenDeliberationSnapshot)、「critic」(String)，返回「CriticActivityResult」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「DeliberationPanelActivities.runCritic(FrozenDeliberationSnapshot,String)」由使用「DeliberationPanelActivities」的控制器、应用服务、Workflow Activity 或测试场景触发。
    // 下游影响：「DeliberationPanelActivities.runCritic(FrozenDeliberationSnapshot,String)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「DeliberationPanelActivities.runCritic(FrozenDeliberationSnapshot,String)」负责主链路中的“评审角色”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    CriticActivityResult runCritic(
            FrozenDeliberationSnapshot snapshot,
            String critic);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「DeliberationPanelActivities.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」。
    // 具体功能：「DeliberationPanelActivities.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」：定义「DeliberationPanelActivities」端口方法：接收 「command」(DeliberationPanelCommand)、「snapshot」(FrozenDeliberationSnapshot)、「reports」(List)、「panelResult」(String)，返回「String」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「DeliberationPanelActivities.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」的上游调用点包括 「DeliberationPanelWorkflowImpl.run」。
    // 下游影响：「DeliberationPanelActivities.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「DeliberationPanelActivities.persistReport(DeliberationPanelCommand,FrozenDeliberationSnapshot,List,String)」负责主链路中的“Report”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    String persistReport(
            DeliberationPanelCommand command,
            FrozenDeliberationSnapshot snapshot,
            List<CriticActivityResult> reports,
            String panelResult);
}
