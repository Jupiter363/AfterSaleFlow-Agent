package com.example.dispute.workflow.config;

import com.example.dispute.workflow.infrastructure.agent.GraphReadinessCoordinator;
import com.example.dispute.workflow.infrastructure.agent.GraphTlsClientMaterial;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.agent.LocalGraphTransportFactory;
import com.example.dispute.workflow.infrastructure.agent.TrustedGraphTransportFactory;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Owns construction of both Graph transports from one non-self-attested security context. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    GraphCommandClientProperties.class,
    GraphTlsClientProperties.class,
    GraphContinuousReadinessProperties.class
})
@ConditionalOnExpression(
        "'${app.agent-run-v2.graph-client.mode:DISABLED}' == 'SHADOW' || "
                + "'${app.agent-run-v2.graph-client.mode:DISABLED}' == 'TARGET_E2E_CANDIDATE'")
public class GraphTransportConfiguration {

    @Bean(destroyMethod = "close")
    @Lazy(false)
    GraphTransportBundle graphTransportBundle(
            GraphCommandClientProperties client,
            GraphTlsClientProperties tls,
            GraphContinuousReadinessProperties readiness,
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
            boolean agentWorker = environment.acceptsProfiles(Profiles.of("agent-worker"));
            GraphReadinessCoordinator.Settings readinessSettings =
                    readiness.settings(client.mode().name());
            GraphTransportBundle transports = agentWorker
                    ? TrustedGraphTransportFactory.createForEndpoint(
                            material,
                            tls.connectTimeout(),
                            client.baseUri(),
                            readinessSettings)
                    : TrustedGraphTransportFactory.createForEndpoint(
                            material, tls.connectTimeout(), client.baseUri());
            try {
                if (agentWorker) {
                    transports.verifyReadiness(
                            readinessSettings.probeTimeout(), readinessSettings.expectedMode());
                }
                return transports;
            } catch (RuntimeException | Error failure) {
                try {
                    transports.close();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (cleanupFailure != failure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        } finally {
            Arrays.fill(keyPassword, '\0');
            Arrays.fill(trustPassword, '\0');
            tls.close();
        }
    }

    GraphTransportBundle graphTransportBundle(
            GraphCommandClientProperties client,
            GraphTlsClientProperties tls,
            Environment environment) {
        return graphTransportBundle(
                client,
                tls,
                new GraphContinuousReadinessProperties(
                        Duration.ofSeconds(15), Duration.ofSeconds(5)),
                environment);
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

@ConfigurationProperties(prefix = "app.agent-run-v2.graph-client.readiness")
record GraphContinuousReadinessProperties(
        @DefaultValue("PT15S") Duration interval,
        @DefaultValue("PT5S") Duration timeout) {

    private static final Duration MINIMUM_TIMEOUT = Duration.ofMillis(100);

    GraphContinuousReadinessProperties {
        if (interval == null
                || interval.compareTo(Duration.ofSeconds(5)) < 0
                || interval.compareTo(Duration.ofSeconds(25)) > 0) {
            throw new IllegalArgumentException(
                    "Graph readiness interval must be between 5s and 25s");
        }
        if (timeout == null
                || timeout.compareTo(MINIMUM_TIMEOUT) < 0
                || timeout.compareTo(Duration.ofSeconds(5)) > 0
                || timeout.compareTo(interval) >= 0) {
            throw new IllegalArgumentException(
                    "Graph readiness timeout must be between 100ms and 5s and less than interval");
        }
    }

    GraphReadinessCoordinator.Settings settings(String expectedMode) {
        return new GraphReadinessCoordinator.Settings(interval, timeout, expectedMode);
    }
}
