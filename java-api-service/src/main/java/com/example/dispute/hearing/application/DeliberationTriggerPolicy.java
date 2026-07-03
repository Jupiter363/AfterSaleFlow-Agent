package com.example.dispute.hearing.application;

import com.example.dispute.domain.model.RiskLevel;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

@Component
public class DeliberationTriggerPolicy {

    private static final double SKIP_CONFIDENCE = 0.8;

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
