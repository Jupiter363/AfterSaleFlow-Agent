package com.example.dispute.workflow.config;

import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies Java-authoritative visibility and registry policies for signed Graph client modes. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GraphShadowRegistryProperties.class)
@ConditionalOnExpression(
        "'${app.agent-run-v2.graph-client.mode:DISABLED}' == 'SHADOW' || "
                + "'${app.agent-run-v2.graph-client.mode:DISABLED}' == 'TARGET_E2E_CANDIDATE'")
public class GraphShadowRegistryConfiguration {

    @Bean
    GraphStreamVisibilityPolicy graphStreamVisibilityPolicy(
            GraphShadowRegistryProperties properties) {
        return properties.visibilityPolicy();
    }

    @Bean
    GraphRegistryBindingPolicy graphRegistryBindingPolicy(
            GraphShadowRegistryProperties properties) {
        return properties.registryBindingPolicy();
    }
}
