/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义庭审跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.domain;

// 所属模块：【Temporal 持久化编排 / 领域模型层】类型「HearingWorkflowResult」。
// 类型职责：定义庭审跨层传递时使用的不可变数据契约；本类型显式提供 「HearingWorkflowResult」。
// 协作关系：主要由 「DisputeHearingWorkflowImpl.interrupt」、「DisputeHearingWorkflowImpl.run」、「StubHearingWorkflow.run」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record HearingWorkflowResult(
        String draftId,
        boolean manualRequired,
        boolean evidenceTimedOut,
        long dossierVersion,
        String status,
        String stopReason) {

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「HearingWorkflowResult.HearingWorkflowResult(String,boolean,boolean,long,String)」。
    // 具体功能：「HearingWorkflowResult.HearingWorkflowResult(String,boolean,boolean,long,String)」：使用 「draftId」(String)、「manualRequired」(boolean)、「evidenceTimedOut」(boolean)、「dossierVersion」(long)、「status」(String) 初始化「HearingWorkflowResult」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「HearingWorkflowResult.HearingWorkflowResult(String,boolean,boolean,long,String)」的上游创建点包括 「DisputeHearingWorkflowImpl.run」、「DisputeHearingWorkflowImpl.interrupt」、「StubHearingWorkflow.run」。
    // 下游影响：「HearingWorkflowResult.HearingWorkflowResult(String,boolean,boolean,long,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingWorkflowResult.HearingWorkflowResult(String,boolean,boolean,long,String)」负责主链路中的“庭审工作流结果”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public HearingWorkflowResult(
            String draftId,
            boolean manualRequired,
            boolean evidenceTimedOut,
            long dossierVersion,
            String status) {
        this(
                draftId,
                manualRequired,
                evidenceTimedOut,
                dossierVersion,
                status,
                null);
    }
}
