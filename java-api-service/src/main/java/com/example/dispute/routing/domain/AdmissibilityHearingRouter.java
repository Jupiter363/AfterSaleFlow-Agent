/*
 * 所属模块：听证准入路由。
 * 文件职责：承载Admissibility庭审Router在当前业务模块中的规则与协作边界。
 * 业务链路：核心入口/契约为 「decide」；依据争点、证据充分度和风险把案件分入三种最终听证路线。
 * 关键边界：路由算法必须确定、可测试且可解释，不能在此阶段生成最终赔付决定
 */
package com.example.dispute.routing.domain;

import com.example.dispute.domain.model.RiskLevel;

/**
 * Selects a final hearing path without adjudicating liability or planning an action.
 *
 * <p>The router deliberately has no repository, Agent, approval, or execution dependency. Keeping
 * this decision pure prevents an intake recommendation from becoming a hidden final decision.
 */
// 所属模块：【听证准入路由 / 领域模型层】类型「AdmissibilityHearingRouter」。
// 类型职责：承载Admissibility庭审Router在当前业务模块中的规则与协作边界；本类型显式提供 「decide」、「fullHearingReason」。
// 协作关系：主要由 「AdmissibilityHearingRouterTest.selectsFullHearingForConflictHighRiskMissingEvidenceOrUnclearRules」、「AdmissibilityHearingRouterTest.selectsSimpleHearingOnlyForClearLowRiskDisputesWithEnoughEvidence」、「AdmissibilityHearingRouterTest.transfersRequestsThatAreNotFulfillmentDisputes」 使用。
// 边界意义：路由算法必须确定、可测试且可解释，不能在此阶段生成最终赔付决定
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
public final class AdmissibilityHearingRouter {

    // 所属模块：【听证准入路由 / 领域模型层】「AdmissibilityHearingRouter.decide(AdmissibilityContext)」。
    // 具体功能：「AdmissibilityHearingRouter.decide(AdmissibilityContext)」：作出决定庭审路由结果；实际协作者为 「context.fulfillmentDispute」、「context.riskLevel」、「context.conflictDetected」、「context.evidenceSufficient」；处理的关键状态/协议值包括 「NOT_A_FULFILLMENT_DISPUTE」、「CLEAR_LOW_RISK_DISPUTE」，最终返回「HearingRoutingOutcome」。
    // 上游调用：「AdmissibilityHearingRouter.decide(AdmissibilityContext)」的上游调用点包括 「AdmissibilityHearingRouterTest.transfersRequestsThatAreNotFulfillmentDisputes」、「AdmissibilityHearingRouterTest.selectsSimpleHearingOnlyForClearLowRiskDisputesWithEnoughEvidence」、「AdmissibilityHearingRouterTest.selectsFullHearingForConflictHighRiskMissingEvidenceOrUnclearRules」。
    // 下游影响：「AdmissibilityHearingRouter.decide(AdmissibilityContext)」向下依次触达 「context.fulfillmentDispute」、「context.riskLevel」、「context.conflictDetected」、「context.evidenceSufficient」；计算结果以「HearingRoutingOutcome」交给调用方。
    // 系统意义：「AdmissibilityHearingRouter.decide(AdmissibilityContext)」负责主链路中的“庭审路由结果”；路由算法必须确定、可测试且可解释，不能在此阶段生成最终赔付决定
    public HearingRoutingOutcome decide(AdmissibilityContext context) {
        if (!context.fulfillmentDispute()) {
            return new HearingRoutingOutcome(
                    HearingRoute.TRANSFERRED,
                    "NOT_A_FULFILLMENT_DISPUTE",
                    true,
                    false,
                    false);
        }

        boolean highRisk =
                context.riskLevel() == RiskLevel.HIGH
                        || context.riskLevel() == RiskLevel.CRITICAL;
        if (highRisk
                || context.conflictDetected()
                || !context.evidenceSufficient()
                || !context.ruleClear()) {
            return new HearingRoutingOutcome(
                    HearingRoute.FULL_HEARING,
                    fullHearingReason(context, highRisk),
                    false,
                    !context.evidenceSufficient(),
                    highRisk);
        }

        return new HearingRoutingOutcome(
                HearingRoute.SIMPLE_HEARING,
                "CLEAR_LOW_RISK_DISPUTE",
                false,
                false,
                false);
    }

    // 所属模块：【听证准入路由 / 领域模型层】「AdmissibilityHearingRouter.fullHearingReason(AdmissibilityContext,boolean)」。
    // 具体功能：「AdmissibilityHearingRouter.fullHearingReason(AdmissibilityContext,boolean)」：构建full庭审原因；实际协作者为 「context.conflictDetected」、「context.evidenceSufficient」；处理的关键状态/协议值包括 「HIGH_RISK_REQUIRES_FULL_HEARING」、「PARTY_FACTS_CONFLICT」、「KEY_EVIDENCE_INSUFFICIENT」、「RULE_APPLICATION_UNCLEAR」，最终返回「String」。
    // 上游调用：「AdmissibilityHearingRouter.fullHearingReason(AdmissibilityContext,boolean)」的上游调用点包括 「AdmissibilityHearingRouter.decide」。
    // 下游影响：「AdmissibilityHearingRouter.fullHearingReason(AdmissibilityContext,boolean)」向下依次触达 「context.conflictDetected」、「context.evidenceSufficient」；计算结果以「String」交给调用方。
    // 系统意义：「AdmissibilityHearingRouter.fullHearingReason(AdmissibilityContext,boolean)」负责主链路中的“full庭审原因”；路由算法必须确定、可测试且可解释，不能在此阶段生成最终赔付决定
    private String fullHearingReason(
            AdmissibilityContext context, boolean highRisk) {
        if (highRisk) {
            return "HIGH_RISK_REQUIRES_FULL_HEARING";
        }
        if (context.conflictDetected()) {
            return "PARTY_FACTS_CONFLICT";
        }
        if (!context.evidenceSufficient()) {
            return "KEY_EVIDENCE_INSUFFICIENT";
        }
        return "RULE_APPLICATION_UNCLEAR";
    }
}
