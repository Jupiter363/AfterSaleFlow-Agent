package com.example.dispute.caseintake.infrastructure;

import com.example.dispute.config.AppProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AgentClientConfiguration {

    @Bean("agentRestClient")
    RestClient agentRestClient(AppProperties properties) {
        AppProperties.Integration agent = properties.agent();
        Duration timeout = Duration.ofMillis(agent.timeoutMs());
        return RestClient.builder()
                .baseUrl(agent.baseUrl())
                .defaultHeader("X-Service-Secret", agent.serviceSecret())
                .requestFactory(http1RequestFactory(timeout))
                .build();
    }

    static JdkClientHttpRequestFactory http1RequestFactory(Duration timeout) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(timeout)
                                .build());
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }
}
