package com.example.dispute.workflow.config;

import com.example.dispute.workflow.infrastructure.agent.GraphTlsClientMaterial;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.agent.LocalGraphTransportFactory;
import com.example.dispute.workflow.infrastructure.agent.TrustedGraphTransportFactory;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Owns construction of both Graph transports from one non-self-attested security context. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    GraphCommandClientProperties.class,
    GraphTlsClientProperties.class
})
@ConditionalOnProperty(
        name = "app.agent-run-v2.graph-client.mode",
        havingValue = "SHADOW")
public class GraphTransportConfiguration {

    @Bean
    GraphTransportBundle graphTransportBundle(
            GraphCommandClientProperties client,
            GraphTlsClientProperties tls,
            Environment environment) {
        String scheme = client.baseUri().getScheme();
        if ("http".equalsIgnoreCase(scheme)) {
            requireLocalPlaintext(client, environment);
            tls.close();
            LocalGraphTransportFactory.Profile profile = environment.acceptsProfiles(
                            Profiles.of("test"))
                    ? LocalGraphTransportFactory.Profile.TEST
                    : LocalGraphTransportFactory.Profile.LOCAL;
            return LocalGraphTransportFactory.create(profile, tls.connectTimeout());
        }
        if (!"https".equalsIgnoreCase(scheme) || !tls.complete()) {
            tls.close();
            throw new IllegalStateException(
                    "HTTPS Graph transport requires complete PKCS12 client and trust material");
        }

        char[] keyPassword = tls.keyStorePassword();
        char[] trustPassword = tls.trustStorePassword();
        try (GraphTlsClientMaterial material = new GraphTlsClientMaterial(
                tls.keyStorePath(),
                keyPassword,
                tls.trustStorePath(),
                trustPassword)) {
            return TrustedGraphTransportFactory.createForEndpoint(
                    material, tls.connectTimeout(), client.baseUri());
        } finally {
            Arrays.fill(keyPassword, '\0');
            Arrays.fill(trustPassword, '\0');
            tls.close();
        }
    }

    private static void requireLocalPlaintext(
            GraphCommandClientProperties client,
            Environment environment) {
        if (!client.allowPlaintextTransport()
                || !environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalStateException(
                    "plaintext Graph transport is restricted to an explicit local or test profile");
        }
    }
}
