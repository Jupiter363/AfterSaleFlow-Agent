package com.example.dispute.workflow.config;

import com.example.dispute.workflow.infrastructure.security.GraphJwkSetProvider;
import com.example.dispute.workflow.infrastructure.security.MountedPemGraphEnvelopeKeySet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Publishes verification keys without granting the API deployment private-key access. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    GraphJwksProperties.class,
    GraphCommandClientProperties.class
})
@ConditionalOnProperty(name = "app.graph-jwks.enabled", havingValue = "true")
public class GraphJwksConfiguration {

    @Bean
    GraphJwkSetProvider graphJwkSetProvider(
            GraphJwksProperties properties,
            GraphCommandClientProperties graphClientProperties) {
        if (graphClientProperties.mode() != GraphCommandClientProperties.Mode.DISABLED) {
            throw new IllegalStateException(
                    "Public Graph JWKS requires the signing client to remain disabled");
        }
        return MountedPemGraphEnvelopeKeySet.loadPublicOnly(properties.keyDirectory());
    }
}
