package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class HearingEpochSelectionPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsToClosedLegacySelection() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            HearingEpochSelectionProperties properties =
                    context.getBean(HearingEpochSelectionProperties.class);
            assertThat(properties.mode()).isEqualTo(WriterMode.LEGACY);
            assertThat(properties.syntheticShadowCohortBasisPoints()).isZero();
            assertThat(properties.signedSyntheticShadowEnabled()).isFalse();
            assertThat(properties.signedSyntheticSelectionConfigured()).isFalse();
        });
    }

    @Test
    void partialShadowConfigurationStaysClosed() {
        runner.withPropertyValues(
                        "app.orchestration.hearing-epoch-selection.mode=SHADOW",
                        "app.orchestration.hearing-epoch-selection.synthetic-shadow-cohort-basis-points=10000")
                .run(context -> assertThat(context
                                .getBean(HearingEpochSelectionProperties.class)
                                .signedSyntheticSelectionConfigured())
                        .isFalse());
    }

    @Test
    void completeSignedSyntheticConfigurationOpensOnlyShadowSelection() {
        runner.withPropertyValues(
                        "app.orchestration.hearing-epoch-selection.mode=SHADOW",
                        "app.orchestration.hearing-epoch-selection.synthetic-shadow-cohort-basis-points=10000",
                        "app.orchestration.hearing-epoch-selection.cohort-policy-version=hearing.synthetic.v1",
                        "app.orchestration.hearing-epoch-selection.signed-synthetic-shadow-enabled=true")
                .run(context -> assertThat(context
                                .getBean(HearingEpochSelectionProperties.class)
                                .signedSyntheticSelectionConfigured())
                        .isTrue());
    }

    @Test
    void temporalUnknownModeAndInvalidBoundsFailAtBinding() {
        runner.withPropertyValues("app.orchestration.hearing-epoch-selection.mode=TEMPORAL")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("TEMPORAL Hearing epoch selection is forbidden"));
        runner.withPropertyValues("app.orchestration.hearing-epoch-selection.mode=ACTIVE")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                        "app.orchestration.hearing-epoch-selection.synthetic-shadow-cohort-basis-points=10001")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("between 0 and 10000"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HearingEpochSelectionProperties.class)
    static class PropertiesConfiguration {}
}
