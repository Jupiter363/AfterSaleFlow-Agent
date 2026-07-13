/*
 * 所属模块：共享小法庭。
 * 文件职责：以确定性规则计算评议Trigger，输出可解释且可测试的决策。
 * 业务链路：核心入口/契约为 「evaluate」；管理固定轮次陈述、庭审时钟、和解版本、Agent 协作消息以及非最终裁决草案。
 * 关键边界：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
 */
package com.example.dispute.hearing.application;

import com.example.dispute.domain.model.RiskLevel;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

// 所属模块：【共享小法庭 / 应用编排层】类型「DeliberationTriggerPolicy」。
// 类型职责：以确定性规则计算评议Trigger，输出可解释且可测试的决策；本类型显式提供 「evaluate」。
// 协作关系：主要由 「DeliberationTriggerPolicyTest.decision」、「DeliberationTriggerPolicyTest.onlyLowRiskHighConfidenceAgreedCasesSkipThePanel」 使用。
// 边界意义：最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
// Java 语法：class 同时封装状态与方法；final 依赖通过构造器注入后不可重新指向。
@Component
public class DeliberationTriggerPolicy {

    private static final double SKIP_CONFIDENCE = 0.8;

    // 所属模块：【共享小法庭 / 应用编排层】「DeliberationTriggerPolicy.evaluate(DeliberationTriggerContext)」。
    // 具体功能：「DeliberationTriggerPolicy.evaluate(DeliberationTriggerContext)」：评估评议Trigger决定；实际协作者为 「context.riskLevel」、「context.settlementConfirmed」、「context.confidence」、「context.majorEvidenceConflict」；处理的关键状态/协议值包括 「RISK_NOT_LOW」、「NO_CONFIRMED_SETTLEMENT」、「LOW_CONFIDENCE」、「MAJOR_EVIDENCE_CONFLICT」，最终返回「DeliberationTriggerDecision」。
    // 上游调用：「DeliberationTriggerPolicy.evaluate(DeliberationTriggerContext)」的上游调用点包括 「DeliberationTriggerPolicyTest.onlyLowRiskHighConfidenceAgreedCasesSkipThePanel」、「DeliberationTriggerPolicyTest.decision」。
    // 下游影响：「DeliberationTriggerPolicy.evaluate(DeliberationTriggerContext)」向下依次触达 「context.riskLevel」、「context.settlementConfirmed」、「context.confidence」、「context.majorEvidenceConflict」；计算结果以「DeliberationTriggerDecision」交给调用方。
    // 系统意义：「DeliberationTriggerPolicy.evaluate(DeliberationTriggerContext)」负责主链路中的“评议Trigger决定”；最多三轮且受三小时时钟约束；AI 输出必须进入平台终审而非直接生效
    public DeliberationTriggerDecision evaluate(
            DeliberationTriggerContext context) {
        var reasons = new ArrayList<String>();
        if (context.riskLevel() != RiskLevel.LOW) {
            reasons.add("RISK_NOT_LOW");
        }
        if (!context.settlementConfirmed()) {
            reasons.add("NO_CONFIRMED_SETTLEMENT");
        }
        if (context.confidence() < SKIP_CONFIDENCE) {
            reasons.add("LOW_CONFIDENCE");
        }
        if (context.majorEvidenceConflict()) {
            reasons.add("MAJOR_EVIDENCE_CONFLICT");
        }
        if (context.ruleUncertain()) {
            reasons.add("RULE_UNCERTAIN");
        }
        return new DeliberationTriggerDecision(
                !reasons.isEmpty(), java.util.List.copyOf(reasons));
    }
}
