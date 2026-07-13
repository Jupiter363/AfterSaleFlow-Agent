/*
 * 所属模块：争议路由应用层。
 * 文件职责：定义路由上下文跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；汇集案件、证据和规则上下文并选择常规、规则或听证路线。
 * 关键边界：路由只决定下一条处理路径，不拥有终审或工具执行权限
 */
package com.example.dispute.router.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.util.Objects;

// 所属模块：【争议路由应用层 / 领域模型层】类型「RoutingContext」。
// 类型职责：定义路由上下文跨层传递时使用的不可变数据契约；本类型显式提供 「RoutingContext」。
// 协作关系：主要由 「RouterApplicationService.route」、「DisputeRouterTest.doesNotUseRuleFlowWhenEvidenceIsInsufficient」、「DisputeRouterTest.routesConflictingOrHighRiskCasesToDisputeHearing」、「DisputeRouterTest.routesOrdinaryLogisticsRequestsToRegularFulfillment」 使用。
// 边界意义：路由只决定下一条处理路径，不拥有终审或工具执行权限
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record RoutingContext(
        String caseType,
        String disputeType,
        RiskLevel riskLevel,
        boolean evidenceSufficient,
        boolean conflictDetected,
        boolean policyMatched) {

    // 所属模块：【争议路由应用层 / 领域模型层】「RoutingContext.RoutingContext(String,String,RiskLevel,boolean,boolean,boolean)」。
    // 具体功能：「RoutingContext.RoutingContext(String,String,RiskLevel,boolean,boolean,boolean)」：在不可变「RoutingContext」写入组件前校验 「caseType」(String)、「disputeType」(String)、「riskLevel」(RiskLevel)、「evidenceSufficient」(boolean)、「conflictDetected」(boolean)、「policyMatched」(boolean)，非法输入会抛出 「IllegalArgumentException」；并通过 「Objects.requireNonNull」 做标准化或防御性复制。
    // 上游调用：「RoutingContext.RoutingContext(String,String,RiskLevel,boolean,boolean,boolean)」的上游创建点包括 「RouterApplicationService.route」、「DisputeRouterTest.routesOrdinaryLogisticsRequestsToRegularFulfillment」、「DisputeRouterTest.routesSufficientAndPolicyMatchedCasesToRuleBasedResolution」、「DisputeRouterTest.routesConflictingOrHighRiskCasesToDisputeHearing」。
    // 下游影响：「RoutingContext.RoutingContext(String,String,RiskLevel,boolean,boolean,boolean)」向下依次触达 「Objects.requireNonNull」。
    // 系统意义：「RoutingContext.RoutingContext(String,String,RiskLevel,boolean,boolean,boolean)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public RoutingContext {
        if (caseType == null || caseType.isBlank()) {
            throw new IllegalArgumentException("caseType must not be blank");
        }
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
    }
}
