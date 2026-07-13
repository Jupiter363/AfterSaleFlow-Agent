/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义人工审核可由 Temporal 重试的 Activity 契约。
 * 业务链路：核心入口/契约为 「recordInvalidDecision」、「persistDecision」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.HumanReviewCommand;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「HumanReviewActivities」。
// 类型职责：定义人工审核可由 Temporal 重试的 Activity 契约；本类型显式提供 「recordInvalidDecision」、「persistDecision」。
// 协作关系：主要由 「HumanReviewWorkflowImpl.run」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
@ActivityInterface
public interface HumanReviewActivities {

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「HumanReviewActivities.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」。
    // 具体功能：「HumanReviewActivities.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」：定义「HumanReviewActivities」端口方法：接收 「command」(HumanReviewCommand)、「signal」(HumanReviewSignal)、「reason」(String)，返回「void」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「HumanReviewActivities.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」的上游调用点包括 「HumanReviewWorkflowImpl.run」。
    // 下游影响：「HumanReviewActivities.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HumanReviewActivities.recordInvalidDecision(HumanReviewCommand,HumanReviewSignal,String)」负责主链路中的“非法决定”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    void recordInvalidDecision(
            HumanReviewCommand command,
            HumanReviewSignal signal,
            String reason);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「HumanReviewActivities.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」。
    // 具体功能：「HumanReviewActivities.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」：定义「HumanReviewActivities」端口方法：接收 「command」(HumanReviewCommand)、「signal」(HumanReviewSignal)、「status」(String)，返回「String」；具体副作用由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「HumanReviewActivities.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」的上游调用点包括 「HumanReviewWorkflowImpl.run」。
    // 下游影响：「HumanReviewActivities.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」的下游由 「FinalWorkflowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「HumanReviewActivities.persistDecision(HumanReviewCommand,HumanReviewSignal,String)」负责主链路中的“决定”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    @ActivityMethod
    String persistDecision(
            HumanReviewCommand command,
            HumanReviewSignal signal,
            String status);
}
