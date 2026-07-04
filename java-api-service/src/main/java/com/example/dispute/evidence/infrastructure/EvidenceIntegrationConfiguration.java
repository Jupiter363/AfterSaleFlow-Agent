package com.example.dispute.evidence.infrastructure;

import com.example.dispute.config.AppProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class EvidenceIntegrationConfiguration {

    @Bean("ocrRestClient")
    RestClient ocrRestClient(AppProperties properties) {
        AppProperties.Integration ocr = properties.ocr();
        return client(ocr.baseUrl(), ocr.serviceSecret(), ocr.timeoutMs());
    }

    @Bean("evidenceSearchRestClient")
    RestClient evidenceSearchRestClient(AppProperties properties) {
        return client(properties.elasticsearch().url(), null, 5000);
    }

    private static RestClient client(String baseUrl, String serviceSecret, int timeoutMs) {
        Duration timeout = Duration.ofMillis(timeoutMs);
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(timeout)
                                .build());
        factory.setReadTimeout(timeout);
        RestClient.Builder builder =
                RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        if (serviceSecret != null) {
            builder.defaultHeader("X-Service-Secret", serviceSecret);
        }
        return builder.build();
    }
}
