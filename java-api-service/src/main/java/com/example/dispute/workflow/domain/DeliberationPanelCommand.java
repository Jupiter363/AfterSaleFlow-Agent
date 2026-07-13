/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义评议Panel跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.domain;

import java.util.List;

// 所属模块：【Temporal 持久化编排 / 领域模型层】类型「DeliberationPanelCommand」。
// 类型职责：定义评议Panel跨层传递时使用的不可变数据契约；本类型显式提供 「DeliberationPanelCommand」、「DeliberationPanelCommand」、「DeliberationPanelCommand」。
// 协作关系：主要由 「FulfillmentDisputeWorkflowImpl.run」、「DeliberationPanelWorkflowTest.command」、「DeliberationPanelWorkflowTest.criticScoreBelowThresholdRequiresRevision」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record DeliberationPanelCommand(
        String caseId,
        String workflowId,
        String draftId,
        long dossierVersion,
        List<String> selectedCritics,
        List<String> triggerReasons,
        int scoreThreshold,
        int maxRevisionAttempts) {

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List,List,int,int)」。
    // 具体功能：「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List,List,int,int)」：在不可变「DeliberationPanelCommand」写入组件前校验 「caseId」(String)、「workflowId」(String)、「draftId」(String)、「dossierVersion」(long)、「selectedCritics」(List)、「triggerReasons」(List)、「scoreThreshold」(int)、「maxRevisionAttempts」(int)，并通过 「DeliberationPolicy.validateScoreThreshold」、「DeliberationPolicy.validateMaxRegenerations」 做标准化或防御性复制。
    // 上游调用：「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List,List,int,int)」的上游创建点包括 「FulfillmentDisputeWorkflowImpl.run」、「DeliberationPanelWorkflowTest.criticScoreBelowThresholdRequiresRevision」、「DeliberationPanelWorkflowTest.command」。
    // 下游影响：「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List,List,int,int)」向下依次触达 「DeliberationPolicy.validateScoreThreshold」、「DeliberationPolicy.validateMaxRegenerations」。
    // 系统意义：「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List,List,int,int)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public DeliberationPanelCommand {
        selectedCritics =
                selectedCritics == null ? List.of() : List.copyOf(selectedCritics);
        triggerReasons =
                triggerReasons == null ? List.of() : List.copyOf(triggerReasons);
        scoreThreshold = DeliberationPolicy.validateScoreThreshold(scoreThreshold);
        maxRevisionAttempts =
                DeliberationPolicy.validateMaxRegenerations(maxRevisionAttempts);
    }

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List)」。
    // 具体功能：「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List)」：使用 「caseId」(String)、「workflowId」(String)、「draftId」(String)、「dossierVersion」(long)、「selectedCritics」(List) 初始化「DeliberationPanelCommand」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List)」的上游创建点包括 「FulfillmentDisputeWorkflowImpl.run」、「DeliberationPanelWorkflowTest.criticScoreBelowThresholdRequiresRevision」、「DeliberationPanelWorkflowTest.command」。
    // 下游影响：「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public DeliberationPanelCommand(
            String caseId,
            String workflowId,
            String draftId,
            long dossierVersion,
            List<String> selectedCritics) {
        this(
                caseId,
                workflowId,
                draftId,
                dossierVersion,
                selectedCritics,
                List.of(),
                80,
                2);
    }

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List,List)」。
    // 具体功能：「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List,List)」：使用 「caseId」(String)、「workflowId」(String)、「draftId」(String)、「dossierVersion」(long)、「selectedCritics」(List)、「triggerReasons」(List) 初始化「DeliberationPanelCommand」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List,List)」的上游创建点包括 「FulfillmentDisputeWorkflowImpl.run」、「DeliberationPanelWorkflowTest.criticScoreBelowThresholdRequiresRevision」、「DeliberationPanelWorkflowTest.command」。
    // 下游影响：「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List,List)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DeliberationPanelCommand.DeliberationPanelCommand(String,String,String,long,List,List)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public DeliberationPanelCommand(
            String caseId,
            String workflowId,
            String draftId,
            long dossierVersion,
            List<String> selectedCritics,
            List<String> triggerReasons) {
        this(
                caseId,
                workflowId,
                draftId,
                dossierVersion,
                selectedCritics,
                triggerReasons,
                80,
                2);
    }
}
