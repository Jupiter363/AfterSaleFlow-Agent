package com.example.dispute.workflow.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Fail-closed configuration for the signed Java-to-Graph transport. */
@ConfigurationProperties(prefix = "app.agent-run-v2.graph-client")
public record GraphCommandClientProperties(
        @DefaultValue("DISABLED") Mode mode,
        URI baseUri,
        @DefaultValue("PT10M") Duration requestTimeout,
        @DefaultValue("false") boolean allowPlaintextTransport) {

    public GraphCommandClientProperties {
        if (mode == null) {
            throw new IllegalArgumentException("graph client mode is required");
        }
        if (requestTimeout == null
                || requestTimeout.isZero()
                || requestTimeout.isNegative()
                || requestTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("graph client timeout must be inside 1ns..10m");
        }
        if (mode == Mode.SHADOW && baseUri == null) {
            throw new IllegalArgumentException("SHADOW graph client requires a base URI");
        }
        if (baseUri != null
                && (!baseUri.isAbsolute()
                        || baseUri.getHost() == null
                        || baseUri.getUserInfo() != null
                        || baseUri.getQuery() != null
                        || baseUri.getFragment() != null)) {
            throw new IllegalArgumentException("graph client base URI is invalid");
        }
        if (baseUri != null) {
            String scheme = baseUri.getScheme();
            boolean trustedScheme = "https".equalsIgnoreCase(scheme)
                    || (allowPlaintextTransport && "http".equalsIgnoreCase(scheme));
            if (!trustedScheme) {
                throw new IllegalArgumentException("graph client base URI requires trusted transport");
            }
        }
        if (mode == Mode.DISABLED && allowPlaintextTransport) {
            throw new IllegalArgumentException(
                    "plaintext graph transport cannot be enabled while the client is disabled");
        }
    }

    public enum Mode {
        DISABLED,
        SHADOW
    }
}
