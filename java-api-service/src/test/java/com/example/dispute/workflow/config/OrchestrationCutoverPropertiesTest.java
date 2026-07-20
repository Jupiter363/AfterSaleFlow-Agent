package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OrchestrationCutoverPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsNewEpochsToLegacy() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OrchestrationCutoverProperties.class).newEpochMode())
                            .isEqualTo(WriterMode.LEGACY);
                    assertThat(
                                    context
                                            .getBean(OrchestrationCutoverProperties.class)
                                            .nonLegacyEpochAllocationEnabled())
                            .isFalse();
                    assertThat(
                                    context
                                            .getBean(OrchestrationCutoverProperties.class)
                                            .temporalWriterEnabled())
                            .isFalse();
                });
    }

    @Test
    void bindsAnExplicitImmutableEpochSelection() {
        contextRunner
                .withPropertyValues("app.orchestration.new-epoch-mode=SHADOW")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            context
                                                    .getBean(
                                                            OrchestrationCutoverProperties.class)
                                                    .newEpochMode())
                                    .isEqualTo(WriterMode.SHADOW);
                        });
    }

    @Test
    void rejectsUnknownEpochModesAtStartup() {
        contextRunner
                .withPropertyValues("app.orchestration.new-epoch-mode=DUAL_WRITE")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .rootCause()
                                    .hasMessageContaining("DUAL_WRITE");
                        });
    }

    @Test
    void bindsTheTwoIndependentNonLegacyActivationLocks() {
        contextRunner
                .withPropertyValues(
                        "app.orchestration.new-epoch-mode=TEMPORAL",
                        "app.orchestration.non-legacy-epoch-allocation-enabled=true",
                        "app.orchestration.temporal-writer-enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            OrchestrationCutoverProperties properties =
                                    context.getBean(OrchestrationCutoverProperties.class);
                            assertThat(properties.newEpochMode()).isEqualTo(WriterMode.TEMPORAL);
                            assertThat(properties.nonLegacyEpochAllocationEnabled()).isTrue();
                            assertThat(properties.temporalWriterEnabled()).isTrue();
                        });
    }

    @Test
    void rejectsTemporalActivationWithoutTheNonLegacyLock() {
        contextRunner
                .withPropertyValues("app.orchestration.temporal-writer-enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .rootCause()
                                    .hasMessageContaining(
                                            "temporalWriterEnabled requires nonLegacyEpochAllocationEnabled");
                        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrchestrationCutoverProperties.class)
    static class PropertiesConfiguration {}
}
