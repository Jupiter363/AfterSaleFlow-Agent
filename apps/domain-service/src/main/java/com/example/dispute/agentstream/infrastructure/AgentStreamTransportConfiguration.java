package com.example.dispute.agentstream.infrastructure;

import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.infrastructure.agent.GraphTlsClientMaterial;
import com.example.dispute.workflow.infrastructure.agent.TrustedGraphTransportFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit transport identity for API-owned advisory streams, not a domain writer. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentStreamTransportConfiguration.TlsProperties.class)
public class AgentStreamTransportConfiguration {
    @Bean(name = "agentStreamHttpClient", destroyMethod = "close")
    HttpClient agentStreamHttpClient(AppProperties app, TlsProperties tls) {
        try (tls) {
            if (tls.mode() == Mode.SYSTEM) {
                if (tls.hasMaterial()) {
                    throw new IllegalArgumentException("SYSTEM agent transport must not ignore TLS material");
                }
                return systemClient(app);
            }
            URI endpoint = URI.create(app.agent().baseUrl());
            if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                    || endpoint.getFragment() != null) {
                throw new IllegalArgumentException("MUTUAL_TLS agent transport requires a trusted HTTPS base URL");
            }
            char[] keyPassword = tls.keyStorePassword();
            char[] trustPassword = tls.trustStorePassword();
            try (GraphTlsClientMaterial material = new GraphTlsClientMaterial(
                    tls.keyStorePath(), keyPassword, tls.trustStorePath(), trustPassword)) {
                return TrustedGraphTransportFactory.createHttpClient(material, tls.connectTimeout());
            } finally {
                Arrays.fill(keyPassword, '\0');
                Arrays.fill(trustPassword, '\0');
            }
        }
    }

    static HttpClient systemClient(AppProperties app) {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(app.agent().timeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public enum Mode { SYSTEM, MUTUAL_TLS }

    @ConfigurationProperties("app.agent-stream.tls")
    public record TlsProperties(
            @DefaultValue("SYSTEM") Mode mode,
            Path keyStorePath, char[] keyStorePassword,
            Path trustStorePath, char[] trustStorePassword,
            @DefaultValue("PT2S") Duration connectTimeout) implements AutoCloseable {
        public TlsProperties {
            Objects.requireNonNull(mode, "agent TLS mode");
            keyStorePassword = keyStorePassword == null ? new char[0] : keyStorePassword.clone();
            trustStorePassword = trustStorePassword == null ? new char[0] : trustStorePassword.clone();
        }
        @Override public char[] keyStorePassword() { return keyStorePassword.clone(); }
        @Override public char[] trustStorePassword() { return trustStorePassword.clone(); }
        boolean hasMaterial() {
            return keyStorePath != null || trustStorePath != null
                    || keyStorePassword.length > 0 || trustStorePassword.length > 0;
        }
        @Override public void close() {
            Arrays.fill(keyStorePassword, '\0');
            Arrays.fill(trustStorePassword, '\0');
        }
    }
}
