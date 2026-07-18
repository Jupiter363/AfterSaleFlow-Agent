package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.config.AgentRunV2Properties.SchedulerMode;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AgentRunV2PropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsToDisabledV1LegacyExecution() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AgentRunV2Properties properties = context.getBean(AgentRunV2Properties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.protocolDefault()).isEqualTo(AgentRunProtocol.V1);
            assertThat(properties.schedulerMode()).isEqualTo(SchedulerMode.EXECUTOR);
        });
    }

    @Test
    void acceptsV2OnlyWithTemporalExclusiveScheduling() {
        contextRunner
                .withPropertyValues(
                        "app.agent-run-v2.enabled=true",
                        "app.agent-run-v2.protocol-default=V2",
                        "app.agent-run-v2.scheduler-mode=DETECTOR")
                .run(context -> assertThat(context).hasNotFailed());

        contextRunner
                .withPropertyValues(
                        "app.agent-run-v2.enabled=true",
                        "app.agent-run-v2.protocol-default=V2",
                        "app.agent-run-v2.scheduler-mode=EXECUTOR")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("cannot execute V2");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentRunV2Properties.class)
    static class PropertiesConfiguration {}
}
