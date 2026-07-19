package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GraphCommandClientPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultsToDisabledWithoutAConfiguredEndpoint() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            GraphCommandClientProperties properties =
                    context.getBean(GraphCommandClientProperties.class);
            assertThat(properties.mode()).isEqualTo(GraphCommandClientProperties.Mode.DISABLED);
            assertThat(properties.baseUri()).isNull();
            assertThat(properties.allowPlaintextTransport()).isFalse();
        });
    }

    @Test
    void shadowRequiresAnExplicitTrustedEndpoint() {
        runner.withPropertyValues("app.agent-run-v2.graph-client.mode=SHADOW")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("requires a base URI");
                });

        runner.withPropertyValues(
                        "app.agent-run-v2.graph-client.mode=SHADOW",
                        "app.agent-run-v2.graph-client.base-uri=http://python-agent-service:8000")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("trusted transport");
                });
    }

    @Test
    void explicitLocalPlaintextDoesNotChangeTheDisabledDefault() {
        runner.withPropertyValues(
                        "app.agent-run-v2.graph-client.mode=SHADOW",
                        "app.agent-run-v2.graph-client.base-uri=http://127.0.0.1:18000",
                        "app.agent-run-v2.graph-client.allow-plaintext-transport=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GraphCommandClientProperties properties =
                            context.getBean(GraphCommandClientProperties.class);
                    assertThat(properties.mode())
                            .isEqualTo(GraphCommandClientProperties.Mode.SHADOW);
                    assertThat(properties.allowPlaintextTransport()).isTrue();
                });
    }

    @Test
    void rejectsTimeoutsAboveTheActivityBudget() {
        runner.withPropertyValues(
                        "app.agent-run-v2.graph-client.request-timeout=PT10M1S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("inside 1ns..10m");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GraphCommandClientProperties.class)
    static class PropertiesConfiguration {}
}
