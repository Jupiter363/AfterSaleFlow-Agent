package com.example.dispute.workflow.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Secret-backed PKCS12 material used only while constructing the shared Graph mTLS bundle. */
@ConfigurationProperties(prefix = "app.agent-run-v2.graph-client.tls")
public record GraphTlsClientProperties(
        Path keyStorePath,
        char[] keyStorePassword,
        Path trustStorePath,
        char[] trustStorePassword,
        @DefaultValue("PT2S") Duration connectTimeout)
        implements AutoCloseable {

    public GraphTlsClientProperties {
        keyStorePassword = copy(keyStorePassword);
        trustStorePassword = copy(trustStorePassword);
        if (connectTimeout == null
                || connectTimeout.compareTo(Duration.ofMillis(100)) < 0
                || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(
                    "Graph TLS connect timeout must be between 100ms and 30s");
        }
    }

    @Override
    public char[] keyStorePassword() {
        return keyStorePassword.clone();
    }

    @Override
    public char[] trustStorePassword() {
        return trustStorePassword.clone();
    }

    public boolean complete() {
        return keyStorePath != null
                && trustStorePath != null
                && keyStorePassword.length > 0
                && trustStorePassword.length > 0;
    }

    @Override
    public void close() {
        Arrays.fill(keyStorePassword, '\0');
        Arrays.fill(trustStorePassword, '\0');
    }

    private static char[] copy(char[] value) {
        return value == null ? new char[0] : value.clone();
    }
}
