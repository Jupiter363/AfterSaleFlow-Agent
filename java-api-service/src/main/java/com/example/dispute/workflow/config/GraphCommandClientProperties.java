package com.example.dispute.workflow.config;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Fail-closed configuration for the signed Java-to-Graph transport. */
@ConfigurationProperties(prefix = "app.agent-run-v2.graph-client")
public record GraphCommandClientProperties(
        @DefaultValue("DISABLED") Mode mode,
        URI baseUri,
        String activationId,
        @DefaultValue("PT10M") Duration requestTimeout,
        @DefaultValue("false") boolean allowPlaintextTransport) {

    private static final Pattern TARGET_ACTIVATION_ID =
            Pattern.compile("p9act\\.v1\\.[0-9a-f]{32}");

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
        if (mode != Mode.DISABLED && baseUri == null) {
            throw new IllegalArgumentException(mode + " graph client requires a base URI");
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
        if (mode == Mode.TARGET_E2E_CANDIDATE) {
            if (activationId == null || !TARGET_ACTIVATION_ID.matcher(activationId).matches()) {
                throw new IllegalArgumentException(
                        "TARGET_E2E_CANDIDATE graph client requires a bounded activation ID");
            }
            if (allowPlaintextTransport
                    || baseUri == null
                    || !"https".equalsIgnoreCase(baseUri.getScheme())) {
                throw new IllegalArgumentException(
                        "TARGET_E2E_CANDIDATE graph client requires HTTPS mutual TLS transport");
            }
        }
    }

    public enum Mode {
        DISABLED,
        SHADOW,
        TARGET_E2E_CANDIDATE
    }
}
