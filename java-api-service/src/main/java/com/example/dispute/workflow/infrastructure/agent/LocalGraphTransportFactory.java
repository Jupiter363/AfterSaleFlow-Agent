package com.example.dispute.workflow.infrastructure.agent;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Explicit non-production factory for loopback/local integration and unit tests. */
public final class LocalGraphTransportFactory {

    private static final Duration MINIMUM_CONNECT_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAXIMUM_CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private LocalGraphTransportFactory() {}

    public static GraphTransportBundle create(Profile profile, Duration connectTimeout) {
        Objects.requireNonNull(profile, "profile");
        Duration timeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (timeout.compareTo(MINIMUM_CONNECT_TIMEOUT) < 0
                || timeout.compareTo(MAXIMUM_CONNECT_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "Graph transport connect timeout must be between 100ms and 30s");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        LocalPlaintextProof proof =
                new LocalPlaintextProof(profile, UUID.randomUUID().toString());
        return new GraphTransportBundle(
                new JdkGraphCommandHttpTransport(client, proof),
                new JdkGraphReconciliationHttpTransport(client, proof),
                proof);
    }

    public enum Profile {
        LOCAL,
        TEST
    }

    static final class LocalPlaintextProof implements GraphTransportSecurityProof {

        private final Profile profile;
        private final String bundleId;

        private LocalPlaintextProof(Profile profile, String bundleId) {
            this.profile = profile;
            this.bundleId = bundleId;
        }

        Profile profile() {
            return profile;
        }

        @Override
        public Mode mode() {
            return Mode.LOCAL_PLAINTEXT;
        }

        @Override
        public String protocol() {
            return "PLAINTEXT";
        }

        @Override
        public String bundleId() {
            return bundleId;
        }
    }
}
