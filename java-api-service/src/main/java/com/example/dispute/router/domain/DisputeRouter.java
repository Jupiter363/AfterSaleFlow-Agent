/*
 * 所属模块：争议路由应用层。
 * 文件职责：承载争议Router在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「decide」；汇集案件、证据和规则上下文并选择常规、规则或听证路线。
 * 关键边界：路由只决定下一条处理路径，不拥有终审或工具执行权限
 */
package com.example.dispute.router.domain;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.domain.model.RouteType;
import java.util.Set;

// 所属模块：【争议路由应用层 / 领域模型层】类型「DisputeRouter」。
// 类型职责：承载争议Router在当前业务模块中的规则与协作边界；本类型显式提供 「decide」、「hearing」。
// 协作关系：主要由 「RouterApplicationService.route」、「DisputeRouterTest.doesNotUseRuleFlowWhenEvidenceIsInsufficient」、「DisputeRouterTest.routesConflictingOrHighRiskCasesToDisputeHearing」、「DisputeRouterTest.routesOrdinaryLogisticsRequestsToRegularFulfillment」 使用。
// 边界意义：路由只决定下一条处理路径，不拥有终审或工具执行权限
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public final class DisputeRouter {

    private static final Set<String> REGULAR_CASE_TYPES =
            Set.of(
                    "TRANSFERRED",
                    "LOGISTICS_QUERY",
                    "DELIVERY_STATUS",
                    "DELIVERY_REMINDER");

    // 所属模块：【争议路由应用层 / 领域模型层】「DisputeRouter.decide(RoutingContext)」。
    // 具体功能：「DisputeRouter.decide(RoutingContext)」：作出决定路由结果；实际协作者为 「context.riskLevel」、「context.evidenceSufficient」、「context.conflictDetected」、「context.disputeType」；处理的关键状态/协议值包括 「HIGH_RISK_REQUIRES_HEARING」、「PARTY_STATEMENTS_CONFLICT」、「ORDINARY_FULFILLMENT_REQUEST」、「POLICY_MATCHED_AND_EVIDENCE_SUFFICIENT」，最终返回「RoutingOutcome」。
    // 上游调用：「DisputeRouter.decide(RoutingContext)」的上游调用点包括 「RouterApplicationService.route」、「DisputeRouterTest.routesOrdinaryLogisticsRequestsToRegularFulfillment」、「DisputeRouterTest.routesSufficientAndPolicyMatchedCasesToRuleBasedResolution」、「DisputeRouterTest.routesConflictingOrHighRiskCasesToDisputeHearing」。
    // 下游影响：「DisputeRouter.decide(RoutingContext)」向下依次触达 「context.riskLevel」、「context.evidenceSufficient」、「context.conflictDetected」、「context.disputeType」；计算结果以「RoutingOutcome」交给调用方。
    // 系统意义：「DisputeRouter.decide(RoutingContext)」负责主链路中的“路由结果”；路由只决定下一条处理路径，不拥有终审或工具执行权限
    public RoutingOutcome decide(RoutingContext context) {
        if (context.riskLevel() == RiskLevel.HIGH
                || context.riskLevel() == RiskLevel.CRITICAL) {
            return hearing(
                    "HIGH_RISK_REQUIRES_HEARING", !context.evidenceSufficient());
        }
        if (context.conflictDetected()
                || (context.disputeType() != null && !context.disputeType().isBlank())) {
            return hearing("PARTY_STATEMENTS_CONFLICT", false);
        }
        if (REGULAR_CASE_TYPES.contains(context.caseType())) {
            return new RoutingOutcome(
                    RouteType.TRANSFERRED,
                    "ORDINARY_FULFILLMENT_REQUEST",
                    false);
        }
        if (context.policyMatched() && context.evidenceSufficient()) {
            return new RoutingOutcome(
                    RouteType.SIMPLE_HEARING,
                    "POLICY_MATCHED_AND_EVIDENCE_SUFFICIENT",
                    false);
        }
        return hearing(
                context.evidenceSufficient()
                        ? "COMPLEX_CASE_REQUIRES_HEARING"
                        : "KEY_EVIDENCE_INSUFFICIENT",
                !context.evidenceSufficient());
    }

    // 所属模块：【争议路由应用层 / 领域模型层】「DisputeRouter.hearing(String,boolean)」。
    // 具体功能：「DisputeRouter.hearing(String,boolean)」：构建庭审，最终返回「RoutingOutcome」。
    // 上游调用：「DisputeRouter.hearing(String,boolean)」的上游调用点包括 「DisputeRouter.decide」。
    // 下游影响：「DisputeRouter.hearing(String,boolean)」只产生当前对象的返回值或字段变化，不访问额外基础设施；计算结果以「RoutingOutcome」交给调用方。
    // 系统意义：「DisputeRouter.hearing(String,boolean)」负责主链路中的“庭审”；路由只决定下一条处理路径，不拥有终审或工具执行权限
    private static RoutingOutcome hearing(String reasonCode, boolean requiresEvidence) {
        return new RoutingOutcome(
                RouteType.FULL_HEARING, reasonCode, requiresEvidence);
    }
}
