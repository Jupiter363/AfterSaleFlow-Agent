package com.example.dispute.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String env,
        Security security,
        Integration agent,
        Integration ocr,
        Temporal temporal,
        Minio minio,
        Elasticsearch elasticsearch,
        Feature feature,
        Logging logging) {

    public record Security(String serviceSecret) {}

    public record Integration(String baseUrl, String serviceSecret, int timeoutMs) {}

    public record Temporal(String address, String namespace, String taskQueue) {}

    public record Minio(String endpoint, String accessKey, String secretKey) {}

    public record Elasticsearch(String url) {}

    public record Feature(
            boolean agentIntakeEnabled,
            boolean agentHearingEnabled,
            boolean agentEvaluationEnabled,
            boolean ocrEnabled,
            boolean humanReviewRequired,
            boolean toolExecutorSimulation,
            boolean autoCloseEnabled) {}

    public record Logging(boolean auditEnabled, boolean sensitiveMaskingEnabled) {}
}
