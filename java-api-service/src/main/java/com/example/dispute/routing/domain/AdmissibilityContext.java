/*
 * 所属模块：听证准入路由。
 * 文件职责：定义Admissibility上下文跨层传递时使用的不可变数据契约。
 * 业务链路：该文件主要提供类型或包级契约；依据争点、证据充分度和风险把案件分入三种最终听证路线。
 * 关键边界：路由算法必须确定、可测试且可解释，不能在此阶段生成最终赔付决定
 */
package com.example.dispute.routing.domain;

import com.example.dispute.domain.model.RiskLevel;
import java.util.Objects;

/**
 * Deterministic facts used to choose the hearing route.
 *
 * <p>Free-form model reasoning must be normalized into these fields before routing so the workflow
 * decision is replayable and auditable.
 */
// 所属模块：【听证准入路由 / 领域模型层】类型「AdmissibilityContext」。
// 类型职责：定义Admissibility上下文跨层传递时使用的不可变数据契约；本类型显式提供 「AdmissibilityContext」。
// 协作关系：主要由 「AdmissibilityHearingRouterTest.selectsFullHearingForConflictHighRiskMissingEvidenceOrUnclearRules」、「AdmissibilityHearingRouterTest.selectsSimpleHearingOnlyForClearLowRiskDisputesWithEnoughEvidence」、「AdmissibilityHearingRouterTest.transfersRequestsThatAreNotFulfillmentDisputes」 使用。
// 边界意义：路由算法必须确定、可测试且可解释，不能在此阶段生成最终赔付决定
// Java 语法：record 用于不可变数据载体，编译器会生成组件访问器和值语义方法。
public record AdmissibilityContext(
        boolean fulfillmentDispute,
        RiskLevel riskLevel,
        boolean evidenceSufficient,
        boolean conflictDetected,
        boolean ruleClear) {

    // 所属模块：【听证准入路由 / 领域模型层】「AdmissibilityContext.AdmissibilityContext(boolean,RiskLevel,boolean,boolean,boolean)」。
    // 具体功能：「AdmissibilityContext.AdmissibilityContext(boolean,RiskLevel,boolean,boolean,boolean)」：在不可变「AdmissibilityContext」写入组件前校验 「fulfillmentDispute」(boolean)、「riskLevel」(RiskLevel)、「evidenceSufficient」(boolean)、「conflictDetected」(boolean)、「ruleClear」(boolean)，并通过 「Objects.requireNonNull」 做标准化或防御性复制。
    // 上游调用：「AdmissibilityContext.AdmissibilityContext(boolean,RiskLevel,boolean,boolean,boolean)」的上游创建点包括 「AdmissibilityHearingRouterTest.transfersRequestsThatAreNotFulfillmentDisputes」、「AdmissibilityHearingRouterTest.selectsSimpleHearingOnlyForClearLowRiskDisputesWithEnoughEvidence」、「AdmissibilityHearingRouterTest.selectsFullHearingForConflictHighRiskMissingEvidenceOrUnclearRules」。
    // 下游影响：「AdmissibilityContext.AdmissibilityContext(boolean,RiskLevel,boolean,boolean,boolean)」向下依次触达 「Objects.requireNonNull」。
    // 系统意义：「AdmissibilityContext.AdmissibilityContext(boolean,RiskLevel,boolean,boolean,boolean)」在对象进入事务、消息或 Temporal history 前拒绝非法值，可避免错误数据在异步链路中延迟爆炸。
    // Java 语法：record 紧凑构造器省略参数列表；构造体结束后，参数会自动赋给同名不可变组件。
    public AdmissibilityContext {
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
    }
}
