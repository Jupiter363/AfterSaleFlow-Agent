/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义庭审Analysis跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「mustProduceFinalPlan」、「allowSupplementalRequest」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.domain;

// 所属模块：【Temporal 持久化编排 / 领域模型层】类型「HearingAnalysisActivityCommand」。
// 类型职责：定义庭审Analysis跨层传递时使用的不可变数据契约；本类型显式提供 「HearingAnalysisActivityCommand」、「mustProduceFinalPlan」、「allowSupplementalRequest」。
// 协作关系：主要由 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing」、「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream」、「CaseFulfillmentDisputeActivitiesImpl.buildAgentRequest」、「CaseFulfillmentDisputeActivitiesImpl.finalizeResult」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record HearingAnalysisActivityCommand(
        String caseId,
        String workflowId,
        int roundNo,
        boolean evidenceTimedOut,
        boolean evidenceReceived,
        boolean finalConvergence,
        int maxStatementRounds) {

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「HearingAnalysisActivityCommand.HearingAnalysisActivityCommand(String,String,int,boolean,boolean)」。
    // 具体功能：「HearingAnalysisActivityCommand.HearingAnalysisActivityCommand(String,String,int,boolean,boolean)」：使用 「caseId」(String)、「workflowId」(String)、「roundNo」(int)、「evidenceTimedOut」(boolean)、「evidenceReceived」(boolean) 初始化「HearingAnalysisActivityCommand」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「HearingAnalysisActivityCommand.HearingAnalysisActivityCommand(String,String,int,boolean,boolean)」的上游创建点包括 「CaseFulfillmentDisputeActivitiesImpl.finalizeResult」、「FinalWorkflowActivitiesAdapter.runStage」、「CaseFulfillmentDisputeWorkflowImpl.run」、「CaseFulfillmentDisputeActivitiesImplTest.finalConvergenceCannotRunBeforeAnyHearingRoundIsSealed」。
    // 下游影响：「HearingAnalysisActivityCommand.HearingAnalysisActivityCommand(String,String,int,boolean,boolean)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「HearingAnalysisActivityCommand.HearingAnalysisActivityCommand(String,String,int,boolean,boolean)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public HearingAnalysisActivityCommand(
            String caseId,
            String workflowId,
            int roundNo,
            boolean evidenceTimedOut,
            boolean evidenceReceived) {
        this(
                caseId,
                workflowId,
                roundNo,
                evidenceTimedOut,
                evidenceReceived,
                false,
                0);
    }

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「HearingAnalysisActivityCommand.mustProduceFinalPlan()」。
    // 具体功能：「HearingAnalysisActivityCommand.mustProduceFinalPlan()」：判断mustProduce终态方案，最终返回「boolean」。
    // 上游调用：「HearingAnalysisActivityCommand.mustProduceFinalPlan()」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.buildAgentRequest」、「HearingAnalysisActivityCommand.allowSupplementalRequest」。
    // 下游影响：「HearingAnalysisActivityCommand.mustProduceFinalPlan()」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「boolean」交给调用方。
    // 系统意义：「HearingAnalysisActivityCommand.mustProduceFinalPlan()」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    public boolean mustProduceFinalPlan() {
        return finalConvergence || evidenceTimedOut;
    }

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「HearingAnalysisActivityCommand.allowSupplementalRequest()」。
    // 具体功能：「HearingAnalysisActivityCommand.allowSupplementalRequest()」：判断是否允许Supplemental请求；实际协作者为 「mustProduceFinalPlan」，最终返回「boolean」。
    // 上游调用：「HearingAnalysisActivityCommand.allowSupplementalRequest()」的上游调用点包括 「CaseFulfillmentDisputeActivitiesImpl.analyzeHearing」、「CaseFulfillmentDisputeActivitiesImpl.analyzeHearingThroughStream」、「CaseFulfillmentDisputeActivitiesImpl.buildAgentRequest」。
    // 下游影响：「HearingAnalysisActivityCommand.allowSupplementalRequest()」向下依次触达 「mustProduceFinalPlan」；计算结果以「boolean」交给调用方。
    // 系统意义：「HearingAnalysisActivityCommand.allowSupplementalRequest()」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    public boolean allowSupplementalRequest() {
        return !mustProduceFinalPlan();
    }
}
