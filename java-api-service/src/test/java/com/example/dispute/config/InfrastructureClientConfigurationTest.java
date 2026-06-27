package com.example.dispute.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.MinioClient;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class InfrastructureClientConfigurationTest {

    @Test
    void createsMinioAndTemporalClientsFromCentralProperties() {
        AppProperties properties =
                new AppProperties(
                        "test",
                        new AppProperties.Security("java-secret"),
                        new AppProperties.Integration(
                                "http://agent:8000", "agent-secret", 120000),
                        new AppProperties.Integration(
                                "http://ocr:8010", "ocr-secret", 120000),
                        new AppProperties.Temporal(
                                "localhost:7233", "default", "case-dispute-task-queue"),
                        new AppProperties.Minio(
                                "http://localhost:19000", "minio-user", "minio-password"),
                        new AppProperties.Elasticsearch("http://localhost:19200"),
                        new AppProperties.Feature(true, true, true, true, true, true, true),
                        new AppProperties.Logging(true, true));

        new ApplicationContextRunner()
                .withBean(AppProperties.class, () -> properties)
                .withUserConfiguration(InfrastructureClientConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(MinioClient.class);
                            assertThat(context).hasSingleBean(WorkflowServiceStubs.class);
                            assertThat(context).hasSingleBean(WorkflowClient.class);
                            assertThat(
                                            context.getBean(WorkflowServiceStubs.class)
                                                    .getOptions()
                                                    .getTarget())
                                    .isEqualTo("localhost:7233");
                        });
    }
}
