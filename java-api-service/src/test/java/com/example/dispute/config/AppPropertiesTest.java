package com.example.dispute.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AppPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertiesConfiguration.class)
                    .withPropertyValues(
                            "app.env=test",
                            "app.security.service-secret=java-secret",
                            "app.agent.base-url=http://agent:8000",
                            "app.agent.service-secret=agent-secret",
                            "app.agent.timeout-ms=120000",
                            "app.ocr.base-url=http://ocr:8010",
                            "app.ocr.service-secret=ocr-secret",
                            "app.ocr.timeout-ms=120000",
                            "app.temporal.address=temporal:7233",
                            "app.temporal.namespace=default",
                            "app.temporal.task-queue=case-dispute-task-queue",
                            "app.minio.endpoint=http://minio:9000",
                            "app.minio.access-key=minio-user",
                            "app.minio.secret-key=minio-password",
                            "app.elasticsearch.url=http://elasticsearch:9200",
                            "app.feature.agent-intake-enabled=true",
                            "app.feature.agent-hearing-enabled=true",
                            "app.feature.agent-evaluation-enabled=true",
                            "app.feature.ocr-enabled=true",
                            "app.feature.human-review-required=true",
                            "app.feature.tool-executor-simulation=true",
                            "app.feature.auto-close-enabled=true",
                            "app.logging.audit-enabled=true",
                            "app.logging.sensitive-masking-enabled=true");

    @Test
    void bindsCentralizedIntegrationAndSafetyConfiguration() {
        contextRunner.run(
                context -> {
                    assertThat(context).hasSingleBean(AppProperties.class);
                    AppProperties properties = context.getBean(AppProperties.class);

                    assertThat(properties.temporal().taskQueue())
                            .isEqualTo("case-dispute-task-queue");
                    assertThat(properties.agent().timeoutMs()).isEqualTo(120000);
                    assertThat(properties.feature().humanReviewRequired()).isTrue();
                    assertThat(properties.feature().toolExecutorSimulation()).isTrue();
                    assertThat(properties.logging().sensitiveMaskingEnabled()).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class PropertiesConfiguration {}
}
