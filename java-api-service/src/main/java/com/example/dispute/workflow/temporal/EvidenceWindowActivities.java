/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义证据Window可由 Temporal 重试的 Activity 契约。
 * 业务链路：核心入口/契约为 「warn」、「expire」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.temporal;

import io.temporal.activity.ActivityInterface;

// 所属模块：【Temporal 持久化编排 / 核心业务层】类型「EvidenceWindowActivities」。
// 类型职责：定义证据Window可由 Temporal 重试的 Activity 契约；本类型显式提供 「warn」、「expire」。
// 协作关系：主要由 「EvidenceWindowWorkflowImpl.run」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
@ActivityInterface
public interface EvidenceWindowActivities {
    // 所属模块：【Temporal 持久化编排 / 核心业务层】「EvidenceWindowActivities.warn(String)」。
    // 具体功能：「EvidenceWindowActivities.warn(String)」：定义「EvidenceWindowActivities」端口方法：接收 「caseId」(String)，返回「void」；具体副作用由 「EvidenceWindowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「EvidenceWindowActivities.warn(String)」的上游调用点包括 「EvidenceWindowWorkflowImpl.run」。
    // 下游影响：「EvidenceWindowActivities.warn(String)」的下游由 「EvidenceWindowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceWindowActivities.warn(String)」负责主链路中的“证据Window”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    void warn(String caseId);

    // 所属模块：【Temporal 持久化编排 / 核心业务层】「EvidenceWindowActivities.expire(String)」。
    // 具体功能：「EvidenceWindowActivities.expire(String)」：定义「EvidenceWindowActivities」端口方法：接收 「caseId」(String)，返回「void」；具体副作用由 「EvidenceWindowActivitiesAdapter」、「RecordingActivities」 承担。
    // 上游调用：「EvidenceWindowActivities.expire(String)」的上游调用点包括 「EvidenceWindowWorkflowImpl.run」。
    // 下游影响：「EvidenceWindowActivities.expire(String)」的下游由 「EvidenceWindowActivitiesAdapter」、「RecordingActivities」 接管，并把返回值交还当前模块调用方。
    // 系统意义：「EvidenceWindowActivities.expire(String)」负责主链路中的“证据Window”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
    void expire(String caseId);
}
