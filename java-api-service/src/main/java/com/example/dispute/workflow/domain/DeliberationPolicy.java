/*
 * 所属模块：Temporal 持久化编排。
 * 文件职责：以确定性规则计算评议，输出可解释且可测试的决策。
 * 业务链路：核心入口/契约为 「shouldRunFinalPanel」、「validateScoreThreshold」、「validateMaxRegenerations」；串联举证窗口、共享庭审、按需评议、人工终审、确定性执行和结案恢复。
 * 关键边界：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
 */
package com.example.dispute.workflow.domain;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import java.util.Locale;
import java.util.Objects;

// 所属模块：【Temporal 持久化编排 / 领域模型层】类型「DeliberationPolicy」。
// 类型职责：以确定性规则计算评议，输出可解释且可测试的决策；本类型显式提供 「DeliberationPolicy」、「shouldRunFinalPanel」、「validateScoreThreshold」、「validateMaxRegenerations」、「risk」、「rank」。
// 协作关系：主要由 「DeliberationPanelCommand.DeliberationPanelCommand」、「FulfillmentDisputeCommand.FulfillmentDisputeCommand」、「FulfillmentDisputeCommand.deliberationRequired」、「DeliberationPolicyTest.disabledModeNeverRunsPanel」 使用。
// 边界意义：Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public final class DeliberationPolicy {

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「DeliberationPolicy.DeliberationPolicy()」。
    // 具体功能：「DeliberationPolicy.DeliberationPolicy()」：创建「DeliberationPolicy」实例并保留框架或测试所需的无参构造入口；真正的业务状态由后续工厂方法、JPA 或字段赋值完成。
    // 上游调用：「DeliberationPolicy.DeliberationPolicy()」由应用层、序列化框架或测试夹具创建。
    // 下游影响：「DeliberationPolicy.DeliberationPolicy()」只产生当前对象的返回值或字段变化，不访问额外基础设施。
    // 系统意义：「DeliberationPolicy.DeliberationPolicy()」负责主链路中的“评议政策规则”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    // Java 语法：构造器名称与类名相同且没有返回类型；参数通常由 Spring 按类型注入。
    private DeliberationPolicy() {}

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「DeliberationPolicy.shouldRunFinalPanel(RouteType,String,DeliberationInterventionMode,String)」。
    // 具体功能：「DeliberationPolicy.shouldRunFinalPanel(RouteType,String,DeliberationInterventionMode,String)」：计算是否需要运行终态Panel；实际协作者为 「rank」、「risk」，最终返回「boolean」。
    // 上游调用：「DeliberationPolicy.shouldRunFinalPanel(RouteType,String,DeliberationInterventionMode,String)」的上游调用点包括 「FulfillmentDisputeCommand.deliberationRequired」、「DeliberationPolicyTest.finalOnlyPanelRunsOnlyForHighRiskFullHearings」、「DeliberationPolicyTest.disabledModeNeverRunsPanel」。
    // 下游影响：「DeliberationPolicy.shouldRunFinalPanel(RouteType,String,DeliberationInterventionMode,String)」向下依次触达 「rank」、「risk」；计算结果以「boolean」交给调用方。
    // 系统意义：「DeliberationPolicy.shouldRunFinalPanel(RouteType,String,DeliberationInterventionMode,String)」负责主链路中的“运行终态Panel”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    public static boolean shouldRunFinalPanel(
            RouteType routeType,
            String caseRiskLevel,
            DeliberationInterventionMode interventionMode,
            String minimumRiskLevel) {
        if (routeType != RouteType.FULL_HEARING) {
            return false;
        }
        if (interventionMode == DeliberationInterventionMode.DISABLED) {
            return false;
        }
        return rank(risk(caseRiskLevel)) >= rank(risk(minimumRiskLevel));
    }

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「DeliberationPolicy.validateScoreThreshold(int)」。
    // 具体功能：「DeliberationPolicy.validateScoreThreshold(int)」：校验分数Threshold；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「int」。
    // 上游调用：「DeliberationPolicy.validateScoreThreshold(int)」的上游调用点包括 「DeliberationPanelCommand.DeliberationPanelCommand」、「FulfillmentDisputeCommand.FulfillmentDisputeCommand」、「DeliberationPolicyTest.validatesScoreThresholdAndRegenerationBudget」。
    // 下游影响：「DeliberationPolicy.validateScoreThreshold(int)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「DeliberationPolicy.validateScoreThreshold(int)」在“分数Threshold”进入下游前阻断非法状态；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    public static int validateScoreThreshold(int scoreThreshold) {
        if (scoreThreshold < 1 || scoreThreshold > 100) {
            throw new IllegalArgumentException(
                    "deliberation score threshold must be between 1 and 100");
        }
        return scoreThreshold;
    }

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「DeliberationPolicy.validateMaxRegenerations(int)」。
    // 具体功能：「DeliberationPolicy.validateMaxRegenerations(int)」：校验较高风险等级Regenerations；不满足前置条件时抛出 「IllegalArgumentException」，最终返回「int」。
    // 上游调用：「DeliberationPolicy.validateMaxRegenerations(int)」的上游调用点包括 「DeliberationPanelCommand.DeliberationPanelCommand」、「FulfillmentDisputeCommand.FulfillmentDisputeCommand」、「DeliberationPolicyTest.validatesScoreThresholdAndRegenerationBudget」。
    // 下游影响：「DeliberationPolicy.validateMaxRegenerations(int)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「DeliberationPolicy.validateMaxRegenerations(int)」在“较高风险等级Regenerations”进入下游前阻断非法状态；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    public static int validateMaxRegenerations(int maxRegenerations) {
        if (maxRegenerations < 0 || maxRegenerations > 2) {
            throw new IllegalArgumentException(
                    "deliberation max regenerations must be between 0 and 2");
        }
        return maxRegenerations;
    }

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「DeliberationPolicy.risk(String)」。
    // 具体功能：「DeliberationPolicy.risk(String)」：构建风险；实际协作者为 「Objects.requireNonNull」，最终返回「RiskLevel」。
    // 上游调用：「DeliberationPolicy.risk(String)」的上游调用点包括 「DeliberationPolicy.shouldRunFinalPanel」。
    // 下游影响：「DeliberationPolicy.risk(String)」向下依次触达 「Objects.requireNonNull」；计算结果以「RiskLevel」交给调用方。
    // 系统意义：「DeliberationPolicy.risk(String)」负责主链路中的“风险”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    private static RiskLevel risk(String value) {
        String normalized =
                Objects.requireNonNull(value, "risk level must not be null")
                        .trim()
                        .toUpperCase(Locale.ROOT);
        return RiskLevel.valueOf(normalized);
    }

    // 所属模块：【Temporal 持久化编排 / 领域模型层】「DeliberationPolicy.rank(RiskLevel)」。
    // 具体功能：「DeliberationPolicy.rank(RiskLevel)」：构建风险等级序号，最终返回「int」。
    // 上游调用：「DeliberationPolicy.rank(RiskLevel)」的上游调用点包括 「DeliberationPolicy.shouldRunFinalPanel」。
    // 下游影响：「DeliberationPolicy.rank(RiskLevel)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「int」交给调用方。
    // 系统意义：「DeliberationPolicy.rank(RiskLevel)」负责主链路中的“风险等级序号”；Workflow 代码必须可重放且不直接做网络或数据库 I/O；副作用放入 Activity
    private static int rank(RiskLevel riskLevel) {
        return switch (riskLevel) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case CRITICAL -> 3;
        };
    }
}
