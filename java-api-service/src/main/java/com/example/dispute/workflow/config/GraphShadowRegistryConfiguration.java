package com.example.dispute.workflow.config;

import com.example.dispute.workflow.activity.agent.GraphRegistryBindingPolicy;
import com.example.dispute.workflow.activity.agent.GraphStreamVisibilityPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies Java-authoritative visibility and registry policies only for synthetic SHADOW. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GraphShadowRegistryProperties.class)
@ConditionalOnProperty(
        name = "app.agent-run-v2.graph-client.mode",
        havingValue = "SHADOW")
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
