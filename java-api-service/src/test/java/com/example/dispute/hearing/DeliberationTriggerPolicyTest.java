package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RiskLevel;
import com.example.dispute.hearing.application.DeliberationTriggerContext;
import com.example.dispute.hearing.application.DeliberationTriggerPolicy;
import org.junit.jupiter.api.Test;

class DeliberationTriggerPolicyTest {

    private final DeliberationTriggerPolicy policy =
            new DeliberationTriggerPolicy();

    @Test
    void onlyLowRiskHighConfidenceAgreedCasesSkipThePanel() {
        assertThat(
                        policy.evaluate(
                                        new DeliberationTriggerContext(
                                                RiskLevel.LOW,
                                                true,
                                                0.9,
                                                false,
                                                false))
                                .shouldDeliberate())
                .isFalse();
        assertThat(decision(RiskLevel.HIGH, true, 0.9, false, false)).isTrue();
        assertThat(decision(RiskLevel.LOW, false, 0.9, false, false)).isTrue();
        assertThat(decision(RiskLevel.LOW, true, 0.4, false, false)).isTrue();
        assertThat(decision(RiskLevel.LOW, true, 0.9, true, false)).isTrue();
        assertThat(decision(RiskLevel.LOW, true, 0.9, false, true)).isTrue();
    }

    private boolean decision(
            RiskLevel risk,
            boolean settlement,
            double confidence,
            boolean conflict,
            boolean uncertain) {
        return policy.evaluate(
                        new DeliberationTriggerContext(
                                risk,
                                settlement,
                                confidence,
                                conflict,
                                uncertain))
                .shouldDeliberate();
    }
}
