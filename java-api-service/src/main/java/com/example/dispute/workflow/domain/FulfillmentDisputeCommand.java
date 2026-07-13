/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：定义履约争议跨层传递时使用的不可变数据契约。
 * 业务链路：核心入口/契约为 「deliberationRequired」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.domain;

import com.example.dispute.domain.model.RouteType;
import java.time.Duration;
import java.util.Objects;

// 所属模块：【Temporal 持久化编排 / 领域模型层】类型「FulfillmentDisputeCommand」。
// 类型职责：定义履约争议跨层传递时使用的不可变数据契约；本类型显式提供 「FulfillmentDisputeCommand」、「FulfillmentDisputeCommand」、「deliberationRequired」、「requireText」。
// 协作关系：主要由 「FulfillmentDisputeWorkflowImpl.run」、「WorkflowApplicationService.start」、「FinalWorkflowOrchestrationTest.command」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record FulfillmentDisputeCommand(
        String caseId,
        String workflowId,
        RouteType routeType,
        long dossierVersion,
        Duration evidenceWaitTimeout,
        Duration reviewWaitTimeout,
        int maxEvidenceRounds,
        String riskLevel,
        DeliberationInterventionMode deliberationMode,
        String deliberationMinimumRiskLevel,
        int deliberationScoreThreshold,
        int deliberationMaxRegenerations) {

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「FulfillmentDisputeCommand.FulfillmentDisputeCommand(String,String,RouteType,long,Duration,Duration,int,String,DeliberationInterventionMode,String,int,int)」。
    // 具体功能：「FulfillmentDisputeCommand.FulfillmentDisputeCommand(String,String,RouteType,long,Duration,Duration,int,String,DeliberationInterventionMode,String,int,int)」：在不可变「FulfillmentDisputeCommand」写入组件前校验 「caseId」(String)、「workflowId」(String)、「routeType」(RouteType)、「dossierVersion」(long)、「evidenceWaitTimeout」(Duration)、「reviewWaitTimeout」(Duration)、「maxEvidenceRounds」(int)、「riskLevel」(String)、「deliberationMode」(DeliberationInterventionMode)、「deliberationMinimumRiskLevel」(String)、「deliberationScoreThreshold」(int)、「deliberationMaxRegenerations」(int)，非法输入会抛出 「IllegalArgumentException」；并通过 「DeliberationPolicy.validateScoreThreshold」、「DeliberationPolicy.validateMaxRegenerations」、「Objects.requireNonNull」、「requireText」 做标准化或防御性复制。
    // 上游调用：「FulfillmentDisputeCommand.FulfillmentDisputeCommand(String,String,RouteType,long,Duration,Duration,int,String,DeliberationInterventionMode,String,int,int)」的上游创建点包括 「WorkflowApplicationService.start」、「FinalWorkflowOrchestrationTest.command」。
    // 下游影响：「FulfillmentDisputeCommand.FulfillmentDisputeCommand(String,String,RouteType,long,Duration,Duration,int,String,DeliberationInterventionMode,String,int,int)」向下依次触达 「DeliberationPolicy.validateScoreThreshold」、「DeliberationPolicy.validateMaxRegenerations」、「Objects.requireNonNull」、「requireText」。
    // 系统意义：「FulfillmentDisputeCommand.FulfillmentDisputeCommand(String,String,RouteType,long,Duration,Duration,int,String,DeliberationInterventionMode,String,int,int)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public FulfillmentDisputeCommand {
        requireText(caseId, "caseId");
        requireText(workflowId, "workflowId");
        Objects.requireNonNull(routeType, "routeType must not be null");
        requireText(riskLevel, "riskLevel");
        requireText(
                deliberationMinimumRiskLevel,
                "deliberationMinimumRiskLevel");
        Objects.requireNonNull(
                deliberationMode, "deliberationMode must not be null");
        Objects.requireNonNull(
                evidenceWaitTimeout, "evidenceWaitTimeout must not be null");
        Objects.requireNonNull(
                reviewWaitTimeout, "reviewWaitTimeout must not be null");
        if (dossierVersion < 1) {
            throw new IllegalArgumentException("dossierVersion must be positive");
        }
        if (maxEvidenceRounds < 1 || maxEvidenceRounds > 5) {
            throw new IllegalArgumentException(
                    "maxEvidenceRounds must be between 1 and 5");
        }
        DeliberationPolicy.validateScoreThreshold(deliberationScoreThreshold);
        DeliberationPolicy.validateMaxRegenerations(
                deliberationMaxRegenerations);
    }

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「FulfillmentDisputeCommand.FulfillmentDisputeCommand(String,String,RouteType,long,Duration,Duration,int,boolean)」。
    // 具体功能：「FulfillmentDisputeCommand.FulfillmentDisputeCommand(String,String,RouteType,long,Duration,Duration,int,boolean)」：使用 「caseId」(String)、「workflowId」(String)、「routeType」(RouteType)、「dossierVersion」(long)、「evidenceWaitTimeout」(Duration)、「reviewWaitTimeout」(Duration)、「maxEvidenceRounds」(int)、「deliberationRequired」(boolean) 初始化「FulfillmentDisputeCommand」的不可变状态或协作参数，使后续方法不必依赖半初始化对象。
    // 上游调用：「FulfillmentDisputeCommand.FulfillmentDisputeCommand(String,String,RouteType,long,Duration,Duration,int,boolean)」的上游创建点包括 「WorkflowApplicationService.start」、「FinalWorkflowOrchestrationTest.command」。
    // 下游影响：「FulfillmentDisputeCommand.FulfillmentDisputeCommand(String,String,RouteType,long,Duration,Duration,int,boolean)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FulfillmentDisputeCommand.FulfillmentDisputeCommand(String,String,RouteType,long,Duration,Duration,int,boolean)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    public FulfillmentDisputeCommand(
            String caseId,
            String workflowId,
            RouteType routeType,
            long dossierVersion,
            Duration evidenceWaitTimeout,
            Duration reviewWaitTimeout,
            int maxEvidenceRounds,
            boolean deliberationRequired) {
        this(
                caseId,
                workflowId,
                routeType,
                dossierVersion,
                evidenceWaitTimeout,
                reviewWaitTimeout,
                maxEvidenceRounds,
                deliberationRequired ? "HIGH" : "LOW",
                deliberationRequired
                        ? DeliberationInterventionMode.FINAL_ONLY
                        : DeliberationInterventionMode.DISABLED,
                "HIGH",
                80,
                2);
    }

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「FulfillmentDisputeCommand.deliberationRequired()」。
    // 具体功能：「FulfillmentDisputeCommand.deliberationRequired()」：判断评议是否需要；实际协作者为 「DeliberationPolicy.shouldRunFinalPanel」，最终返回「boolean」。
    // 上游调用：「FulfillmentDisputeCommand.deliberationRequired()」的上游调用点包括 「FulfillmentDisputeWorkflowImpl.run」。
    // 下游影响：「FulfillmentDisputeCommand.deliberationRequired()」向下依次触达 「DeliberationPolicy.shouldRunFinalPanel」；计算结果以「boolean」交给调用方。
    // 系统意义：「FulfillmentDisputeCommand.deliberationRequired()」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    public boolean deliberationRequired() {
        return DeliberationPolicy.shouldRunFinalPanel(
                routeType,
                riskLevel,
                deliberationMode,
                deliberationMinimumRiskLevel);
    }

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「FulfillmentDisputeCommand.requireText(String,String)」。
    // 具体功能：「FulfillmentDisputeCommand.requireText(String,String)」：强制校验文本；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「void」。
    // 上游调用：「FulfillmentDisputeCommand.requireText(String,String)」的上游调用点包括 「FulfillmentDisputeCommand.FulfillmentDisputeCommand」。
    // 下游影响：「FulfillmentDisputeCommand.requireText(String,String)」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「FulfillmentDisputeCommand.requireText(String,String)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 自动生成组件访问器、equals、hashCode 和 toString，适合传递不可变业务快照。
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
