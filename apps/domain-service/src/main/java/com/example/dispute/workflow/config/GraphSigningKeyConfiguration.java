package com.example.dispute.workflow.config;

import com.example.dispute.workflow.infrastructure.security.MountedPemGraphEnvelopeKeySet;
import com.example.dispute.workflow.infrastructure.security.GraphEnvelopeSigningKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fail-closed adapter for mounted signing material shared by signed Graph client modes. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    GraphSigningKeyProperties.class,
    GraphJwksProperties.class
})
@ConditionalOnExpression(
        "'${app.agent-run-v2.graph-client.mode:DISABLED}' == 'SHADOW' || "
                + "'${app.agent-run-v2.graph-client.mode:DISABLED}' == 'PRODUCTION'")
public class GraphSigningKeyConfiguration {

    @Bean
    MountedPemGraphEnvelopeKeySet graphEnvelopeKeySet(
            GraphSigningKeyProperties properties,
            GraphJwksProperties jwksProperties) {
        if (jwksProperties.enabled()) {
            throw new IllegalStateException(
                    "Graph signing and public JWKS capabilities require separate processes");
        }
        properties.requireConfigured();
        return MountedPemGraphEnvelopeKeySet.load(properties.keyDirectory());
    }

    @Bean
    GraphEnvelopeSigningKey activeGraphEnvelopeSigningKey(
            MountedPemGraphEnvelopeKeySet keys,
            GraphSigningKeyProperties properties) {
        return keys.resolve(properties.activeKeyId());
    }
}
