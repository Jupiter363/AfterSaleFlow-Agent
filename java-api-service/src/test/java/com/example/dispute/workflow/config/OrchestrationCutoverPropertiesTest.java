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

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrchestrationCutoverProperties.class)
    static class PropertiesConfiguration {}
}
