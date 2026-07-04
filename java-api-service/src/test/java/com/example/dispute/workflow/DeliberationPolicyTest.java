package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.domain.model.RouteType;
import com.example.dispute.workflow.domain.DeliberationInterventionMode;
import com.example.dispute.workflow.domain.DeliberationPolicy;
import org.junit.jupiter.api.Test;

class DeliberationPolicyTest {

    @Test
    void finalOnlyPanelRunsOnlyForHighRiskFullHearings() {
        assertThat(
                        DeliberationPolicy.shouldRunFinalPanel(
                                RouteType.FULL_HEARING,
                                "HIGH",
                                DeliberationInterventionMode.FINAL_ONLY,
                                "HIGH"))
                .isTrue();
        assertThat(
                        DeliberationPolicy.shouldRunFinalPanel(
                                RouteType.FULL_HEARING,
                                "CRITICAL",
                                DeliberationInterventionMode.FINAL_ONLY,
                                "HIGH"))
                .isTrue();
        assertThat(
                        DeliberationPolicy.shouldRunFinalPanel(
                                RouteType.FULL_HEARING,
                                "MEDIUM",
                                DeliberationInterventionMode.FINAL_ONLY,
                                "HIGH"))
                .isFalse();
        assertThat(
                        DeliberationPolicy.shouldRunFinalPanel(
                                RouteType.SIMPLE_HEARING,
                                "HIGH",
                                DeliberationInterventionMode.FINAL_ONLY,
                                "HIGH"))
                .isFalse();
    }

    @Test
    void disabledModeNeverRunsPanel() {
        assertThat(
                        DeliberationPolicy.shouldRunFinalPanel(
                                RouteType.FULL_HEARING,
                                "CRITICAL",
                                DeliberationInterventionMode.DISABLED,
                                "HIGH"))
                .isFalse();
    }

    @Test
    void validatesScoreThresholdAndRegenerationBudget() {
        assertThat(DeliberationPolicy.validateScoreThreshold(80)).isEqualTo(80);
        assertThat(DeliberationPolicy.validateMaxRegenerations(2)).isEqualTo(2);
        assertThatThrownBy(() -> DeliberationPolicy.validateScoreThreshold(101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeliberationPolicy.validateMaxRegenerations(3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
