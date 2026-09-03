package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class IntakeEpochSelectionPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsToLegacyWithNoShadowCohort() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            IntakeEpochSelectionProperties properties =
                    context.getBean(IntakeEpochSelectionProperties.class);
            assertThat(properties.mode()).isEqualTo(WriterMode.LEGACY);
            assertThat(properties.shadowCohortBasisPoints()).isZero();
            assertThat(properties.cohortPolicyVersion()).isNull();
            assertThat(properties.signedSyntheticShadowEnabled()).isFalse();
            assertThat(properties.shadowSelectionConfigured()).isFalse();
        });
    }

    @Test
    void partialShadowConfigurationRemainsIneligible() {
        runner.withPropertyValues(
                        "app.orchestration.intake-epoch-selection.mode=SHADOW",
                        "app.orchestration.intake-epoch-selection.shadow-cohort-basis-points=10000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IntakeEpochSelectionProperties.class)
                                    .shadowSelectionConfigured())
                            .isFalse();
                });

        assertThat(new IntakeEpochSelectionProperties(null, 0, null, false).mode())
                .isEqualTo(WriterMode.LEGACY);
    }

    @Test
    void requiresAllExplicitShadowLocks() {
        runner.withPropertyValues(
                        "app.orchestration.intake-epoch-selection.mode=SHADOW",
                        "app.orchestration.intake-epoch-selection.shadow-cohort-basis-points=2500",
                        "app.orchestration.intake-epoch-selection.cohort-policy-version=intake.synthetic.v1",
                        "app.orchestration.intake-epoch-selection.signed-synthetic-shadow-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IntakeEpochSelectionProperties.class)
                                    .shadowSelectionConfigured())
                            .isTrue();
                });
    }

    @Test
    void rejectsTemporalAndOutOfRangeCohortsAtBindingTime() {
        runner.withPropertyValues(
                        "app.orchestration.intake-epoch-selection.mode=TEMPORAL")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("TEMPORAL Intake epoch selection is forbidden");
                });

        runner.withPropertyValues(
                        "app.orchestration.intake-epoch-selection.shadow-cohort-basis-points=10001")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("between 0 and 10000");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IntakeEpochSelectionProperties.class)
    static class PropertiesConfiguration {}
}
