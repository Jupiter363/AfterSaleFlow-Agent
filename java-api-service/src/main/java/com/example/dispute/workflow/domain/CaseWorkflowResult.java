/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义案件跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.domain;

// 所属模块：【Temporal 持久化编排 / 领域模型层】类型「CaseWorkflowResult」。
// 类型职责：定义案件跨层传递时使用的不可变数据契约；本类型显式提供 框架生成的默认访问器。
// 协作关系：由同模块控制器、应用服务或框架生命周期创建和调用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record CaseWorkflowResult(
        String caseId,
        String workflowId,
        String workflowStatus,
        String nextStage,
        boolean manualRequired,
        boolean evidenceTimedOut,
        String draftId,
        String remedyPlanId,
        String reviewTaskId) {}
