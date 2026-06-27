package com.example.dispute.config;

import io.minio.MinioClient;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InfrastructureClientConfiguration {

    @Bean
    MinioClient minioClient(AppProperties properties) {
        AppProperties.Minio minio = properties.minio();
        return MinioClient.builder()
                .endpoint(minio.endpoint())
                .credentials(minio.accessKey(), minio.secretKey())
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs workflowServiceStubs(AppProperties properties) {
        WorkflowServiceStubsOptions options =
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(properties.temporal().address())
                        .build();
        return WorkflowServiceStubs.newServiceStubs(options);
    }

    @Bean
    WorkflowClient workflowClient(
            WorkflowServiceStubs serviceStubs, AppProperties properties) {
        WorkflowClientOptions options =
                WorkflowClientOptions.newBuilder()
                        .setNamespace(properties.temporal().namespace())
                        .build();
        return WorkflowClient.newInstance(serviceStubs, options);
    }
}
