package com.example.dispute.workflow.activity.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.infrastructure.agent.GraphCommandHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphReadinessCoordinator;
import com.example.dispute.workflow.infrastructure.agent.GraphTlsClientMaterial;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportBundle;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportSecurityProof;
import com.example.dispute.workflow.infrastructure.agent.JdkGraphCommandHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.JdkGraphReconciliationHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.LocalGraphTransportFactory;
import com.example.dispute.workflow.infrastructure.agent.TrustedGraphTransportFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

class TrustedGraphTransportFactoryTest {

    private static final char[] KEY_PASSWORD = "changeit".toCharArray();
    private static final char[] TRUST_PASSWORD = "trust-password".toCharArray();

    // Ephemeral test-only EC identity. It is not used by any deployed environment.
    private static final String CLIENT_KEY_STORE_BASE64 = String.join(
            "",
            "MIIETAIBAzCCA/YGCSqGSIb3DQEHAaCCA+cEggPjMIID3zCCATYGCSqGSIb3DQEHAaCCAScE",
            "ggEjMIIBHzCCARsGCyqGSIb3DQEMCgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3",
            "DQEFDDArBBS08UqhSd89BKGdVjfjJTDb9YdD4wICJxACASAwDAYIKoZIhvcNAgkFADAdBglg",
            "hkgBZQMEASoEECLLDJlhH1ReWzWYPYzVgcQEUOQG5kNbhBjmhhItLAwJoYGimSYg422uNGCT",
            "gpIPCA8qv7GKzbGSzUP+epZA/GD0daNumAykxiQu/bmuMB0SnXIljG9vU+6dC2La2NwkZfS8",
            "MUwwJwYJKoZIhvcNAQkUMRoeGABnAHIAYQBwAGgALQBjAGwAaQBlAG4AdDAhBgkqhkiG9w0B",
            "CRUxFAQSVGltZSAxNzg0NDkzMDA5NDQzMIICoQYJKoZIhvcNAQcGoIICkjCCAo4CAQAwggKH",
            "BgkqhkiG9w0BBwEwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFMMnV7c6ESX3D+wU",
            "yBDch9Cz5fKCAgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQZNfyzyxPDQRt",
            "3SqNoUuQg4CCAhDHrRrghyN5tnjXo69U4ni9f9G4yllIdKkIOQncI3ShNhGu7kqo68C81y0",
            "+Iuo8JgglYCdWnI0iJEfr/PqGllsPaiABN23/95yBv38jCGFwFBTcPSlce7MqgnVCQEUpUAn",
            "d6BoHKl6XUjcPrs4YYkq1kBtTWM6BfTj80XsHosMrnje3iEcHj8LXNy1C93xMZJ2JxV9aZs",
            "nDZMDbUCHvvMuxtwOMPw/U0lwEJIu+vAGZd8O4ONsgoo7ggrcwtohCPuiqqcRyBqTR8HORut",
            "jqhVqhJajYgyc1rmfKNUO5CgTSvP8UFRaR1kfCxp4U68GLcU3l7P2EUh9FnBanYbRwsVhF4",
            "NWNwcyw1mXWyb391ZjS1RTPnRTJBxLVIR1SD4j3aPqT1rThXS7P2gn80BpRDT8YICR2AKx2",
            "RO6Z84Veac+9limplm+9mzrJN5JOdcLc0k4WnbkUg9fbVKuA/Ac8a24vMqckS5tOlvj7mV",
            "JSPtF7C9oW3opffYyjmoT56l4iuIschJ0rjBhGrXTkZ74284bGbTRRrj/yGq+nyuZ/W+7/S",
            "Xm7YUX+xYE5PSIUEMigGlEM6AsUEtEndDISZtG5nwLIPBkDR7OH6EzUlhN+oLt8hQS7J+xS",
            "5dKZmNK382EGhA1dM5anHFUKAg84qi9M5Jifat+WjAghD9nQg70Mc+gX9b53u/drLRKnjiA",
            "z4ArWGtQwTTAxMA0GCWCGSAFlAwQCAQUABCAQOLGoW9Nk/NIMup8FUgChExAXiUVzQ7YQmj",
            "5ovznSfgQUwhj8FSAGRagafNCyosCjv+OTbm0CAicQ");

    private static final String CLIENT_CERTIFICATE_BASE64 = String.join(
            "",
            "MIIBhDCCASqgAwIBAgIJAIDfbGYAVnHSMAoGCCqGSM49BAMCMBsxGTAXBgNVBAMTEGphdmEt",
            "YXBpLXNlcnZpY2UwHhcNMjYwNzE5MjAzMDA5WhcNMzYwNzE2MjAzMDA5WjAbMRkwFwYDVQQD",
            "ExBqYXZhLWFwaS1zZXJ2aWNlMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAERjsByFD68Kve",
            "dB6eAuNrGbjElAUaxLb7z0tLsz4nHK0UF0SX1L2IKxyli99czGpousAsvYoO8/hcEPHQBbn+",
            "h6NXMFUwHQYDVR0OBBYEFKLuF0ok5ragWNm3uuWdD6vvbDnmMDQGA1UdEQQtMCuGKXNwaWZm",
            "ZTovL2FmdGVyLXNhbGUtZmxvdy9qYXZhLWFwaS1zZXJ2aWNlMAoGCCqGSM49BAMCA0gAMEUC",
            "IQDTX1h92+mCjAlVqbQ3TRC54aYaBN3/fy/4RaWD/bzTQQIgMdSDM/GykGTMCZmwCrQG48Pb",
            "haPdBRFs5sgiDnVaHnw=");

    @TempDir
    Path temporaryDirectory;

    @Test
    void trustedFactoryBuildsBothTransportsFromOneTls13Proof() throws Exception {
        Path keyStore = writeClientKeyStore();
        Path trustStore = writeTrustStore();
        try (GraphTlsClientMaterial material = material(keyStore, KEY_PASSWORD, trustStore, TRUST_PASSWORD)) {
            GraphTransportBundle bundle =
                    TrustedGraphTransportFactory.create(material, Duration.ofSeconds(2));

            assertThat(bundle.transportProof().mode())
                    .isEqualTo(GraphTransportSecurityProof.Mode.MUTUAL_TLS);
            assertThat(bundle.transportProof().protocol()).isEqualTo("TLSv1.3");
            assertThat(bundle.transportProof().trustedMutualTls()).isTrue();
            assertThat(bundle.commandTransport())
                    .isInstanceOf(JdkGraphCommandHttpTransport.class);
            assertThat(bundle.reconciliationTransport())
                    .isInstanceOf(JdkGraphReconciliationHttpTransport.class);
            assertThat(bundle.commandTransport().transportProof())
                    .isSameAs(bundle.transportProof());
            assertThat(bundle.reconciliationTransport().transportProof())
                    .isSameAs(bundle.transportProof());
        }
    }

    @Test
    void trustedBundleVerifiesReadinessBeforeReusingTheSameClientForACommand() throws Exception {
        URI baseUri = URI.create("https://graph.example.test:8443/graph-base/");
        RecordingHttpClient client = new RecordingHttpClient(new StubResponseSpec(
                200,
                baseUri.resolve("ready/graph"),
                Map.of("Content-Type", List.of("application/json")),
                readyDocument("TARGET_E2E_CANDIDATE")),
                Duration.ofMillis(3100));
        GraphTransportBundle bundle = createTrustedBundle(client, baseUri);

        assertThat(client.requests()).isEmpty();
        long started = System.nanoTime();
        bundle.verifyReadiness(Duration.ofSeconds(4), "TARGET_E2E_CANDIDATE");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(client.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.uri()).isEqualTo(baseUri.resolve("ready/graph"));
            assertThat(request.bodyPublisher()).isEmpty();
            assertThat(request.headers().allValues("Accept"))
                    .containsExactly("application/json");
            assertThat(request.timeout()).contains(Duration.ofSeconds(4));
        });
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofSeconds(3));
        assertThat(client.readinessDemand()).isPositive();
        assertThat(client.readinessCancelled()).isFalse();

        List<String> callbacks = new ArrayList<>();
        URI commandUri = baseUri.resolve("/internal/graphs/commands/stream");
        bundle.commandTransport()
                .stream(
                        new GraphCommandHttpTransport.Request(
                                commandUri,
                                Map.of(
                                        "Authorization", "Bearer signed.token.value",
                                        "Content-Type", "application/json; charset=utf-8",
                                        "Accept", "application/x-ndjson"),
                                "{}".getBytes(StandardCharsets.UTF_8),
                                Duration.ofSeconds(1),
                                1024,
                                4096),
                        new AgentRunCancellationToken(),
                        new GraphCommandHttpTransport.Listener() {
                            @Override
                            public void onResponse(GraphCommandHttpTransport.ResponseHead response) {
                                callbacks.add("head:" + response.statusCode());
                            }

                            @Override
                            public void onLine(String line) {
                                callbacks.add("line:" + line);
                            }
                        });

        assertThat(client.requests()).extracting(HttpRequest::method).containsExactly("GET", "POST");
        assertThat(client.requests().get(1).uri()).isEqualTo(commandUri);
        assertThat(client.synchronousCalls()).isZero();
        assertThat(client.asynchronousCalls()).isEqualTo(2);
        assertThat(callbacks).containsExactly("head:200", "line:{\"sequence\":1}");
    }

    @Test
    void trustedBundleUsesTheSameClientForStrictBoundedIntakePreparation() throws Exception {
        URI baseUri = URI.create("https://graph.example.test:8443/graph-base/");
        URI preparationUri = baseUri.resolve("ready/intake-preparation");
        String ready =
                "{\"schema_version\":\"intake-infrastructure-preparation.v1\","
                        + "\"status\":\"READY\"}";
        RecordingHttpClient client = new RecordingHttpClient(new StubResponseSpec(
                200,
                preparationUri,
                Map.of("Content-Type", List.of("application/json")),
                ready));
        GraphTransportBundle bundle = createTrustedBundle(client, baseUri);

        bundle.prepareIntakeInfrastructure(Duration.ofSeconds(20));

        assertThat(client.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.uri()).isEqualTo(preparationUri);
            assertThat(request.bodyPublisher()).isPresent();
            assertThat(request.bodyPublisher().orElseThrow().contentLength()).isZero();
            assertThat(request.headers().allValues("Accept"))
                    .containsExactly("application/json");
            assertThat(request.timeout()).contains(Duration.ofSeconds(20));
        });
        assertThat(client.synchronousCalls()).isZero();
        assertThat(client.asynchronousCalls()).isEqualTo(1);
        assertThat(client.readinessDemand()).isPositive();
        assertThat(client.readinessCancelled()).isFalse();

        RecordingHttpClient malformedClient = new RecordingHttpClient(new StubResponseSpec(
                200,
                preparationUri,
                Map.of("Content-Type", List.of("application/json")),
                ready.replace("READY", "ready")));
        GraphTransportBundle malformedBundle = createTrustedBundle(malformedClient, baseUri);
        assertThatThrownBy(
                        () -> malformedBundle.prepareIntakeInfrastructure(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Intake infrastructure preparation response was not ready");

        RecordingHttpClient stalledClient = new RecordingHttpClient(
                new StubResponseSpec(
                        200,
                        preparationUri,
                        Map.of("Content-Type", List.of("application/json")),
                        ready),
                Duration.ofMillis(200));
        GraphTransportBundle stalledBundle = createTrustedBundle(stalledClient, baseUri);
        assertThatThrownBy(
                        () -> stalledBundle.prepareIntakeInfrastructure(Duration.ofMillis(25)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Intake infrastructure preparation failed")
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(stalledClient.readinessCancelled()).isTrue();
        assertThat(stalledClient.requests()).extracting(HttpRequest::uri)
                .containsExactly(preparationUri);
    }

    @Test
    void continuousBundleGatesTheSameClientAndOwnsOneBoundedShutdown() throws Exception {
        URI baseUri = URI.create("https://graph.example.test:8443/graph-base/");
        RecordingHttpClient client = new RecordingHttpClient(new StubResponseSpec(
                200,
                baseUri.resolve("ready/graph"),
                Map.of("Content-Type", List.of("application/json")),
                readyDocument("TARGET_E2E_CANDIDATE")));
        client.terminationResults(false, true);
        GraphReadinessCoordinator.Settings settings = new GraphReadinessCoordinator.Settings(
                Duration.ofSeconds(15), Duration.ofSeconds(5), "TARGET_E2E_CANDIDATE");
        GraphTransportBundle bundle = createTrustedBundle(client, baseUri, settings);
        JdkGraphCommandHttpTransport commandTransport =
                (JdkGraphCommandHttpTransport) bundle.commandTransport();
        JdkGraphReconciliationHttpTransport reconciliationTransport =
                (JdkGraphReconciliationHttpTransport) bundle.reconciliationTransport();
        Object coordinator = ReflectionTestUtils.getField(bundle, "readinessCoordinator");
        assertThat(coordinator).isNotNull();
        assertThat(ReflectionTestUtils.getField(commandTransport, "readinessCoordinator"))
                .isSameAs(coordinator);
        assertThat(ReflectionTestUtils.getField(reconciliationTransport, "readinessCoordinator"))
                .isSameAs(coordinator);
        assertThat(ReflectionTestUtils.getField(commandTransport, "httpClient"))
                .isSameAs(client);
        assertThat(ReflectionTestUtils.getField(reconciliationTransport, "httpClient"))
                .isSameAs(client);
        assertThat(commandTransport.transportProof())
                .isSameAs(reconciliationTransport.transportProof())
                .isSameAs(bundle.transportProof());
        bundle.verifyReadiness(settings.probeTimeout(), settings.expectedMode());
        bundle.bindWorkerPolling(() -> {}, () -> {});

        URI commandUri = baseUri.resolve("/internal/graphs/commands/stream");
        bundle.commandTransport().stream(
                new GraphCommandHttpTransport.Request(
                        commandUri,
                        Map.of(),
                        "{}".getBytes(StandardCharsets.UTF_8),
                        Duration.ofSeconds(1),
                        1024,
                        4096),
                new AgentRunCancellationToken(),
                new GraphCommandHttpTransport.Listener() {
                    @Override
                    public void onResponse(GraphCommandHttpTransport.ResponseHead response) {}

                    @Override
                    public void onLine(String line) {}
                });

        assertThat(client.requests()).extracting(HttpRequest::method).containsExactly("GET", "POST");
        assertThat(client.requests().get(0).uri()).isEqualTo(baseUri.resolve("ready/graph"));
        assertThat(client.requests().get(1).uri()).isEqualTo(commandUri);

        bundle.close();
        bundle.close();
        assertThat(client.shutdownCalls()).isEqualTo(1);
        assertThat(client.awaitTerminationCalls()).isEqualTo(2);
        assertThat(client.shutdownNowCalls()).isEqualTo(1);
        assertThatThrownBy(() -> bundle.commandTransport().stream(
                        new GraphCommandHttpTransport.Request(
                                commandUri,
                                Map.of(),
                                "{}".getBytes(StandardCharsets.UTF_8),
                                Duration.ofSeconds(1),
                                1024,
                                4096),
                        new AgentRunCancellationToken(),
                        new GraphCommandHttpTransport.Listener() {
                            @Override
                            public void onResponse(GraphCommandHttpTransport.ResponseHead response) {}

                            @Override
                            public void onLine(String line) {}
                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admission");
        assertThat(client.requests()).hasSize(2);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedReadinessResponses")
    void trustedBundleRejectsInvalidReadinessWithoutIssuingACommand(
            String description,
            int status,
            URI responseUri,
            Map<String, List<String>> headers,
            String responseBody)
            throws Exception {
        URI baseUri = URI.create("https://graph.example.test:8443/graph-base/");
        RecordingHttpClient client = new RecordingHttpClient(
                new StubResponseSpec(status, responseUri, headers, responseBody));
        GraphTransportBundle bundle = createTrustedBundle(client, baseUri);

        assertThatThrownBy(() -> bundle.verifyReadiness(
                        Duration.ofSeconds(1), "TARGET_E2E_CANDIDATE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Graph readiness");

        assertThat(client.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.uri()).isEqualTo(baseUri.resolve("ready/graph"));
        });
        assertThat(client.asynchronousCalls()).isEqualTo(1);
        if (description.equals("oversized body")) {
            assertThat(client.readinessCancelled()).isTrue();
        }
    }

    private static Stream<Arguments> rejectedReadinessResponses() {
        URI exact = URI.create("https://graph.example.test:8443/graph-base/ready/graph");
        return Stream.of(
                Arguments.of(
                        "redirect status",
                        302,
                        exact,
                        Map.of("Content-Type", List.of("application/json")),
                        readyDocument("TARGET_E2E_CANDIDATE")),
                Arguments.of(
                        "response URI drift",
                        200,
                        URI.create("https://graph.example.test:8443/other"),
                        Map.of("Content-Type", List.of("application/json")),
                        readyDocument("TARGET_E2E_CANDIDATE")),
                Arguments.of(
                        "wrong content type",
                        200,
                        exact,
                        Map.of("Content-Type", List.of("text/plain")),
                        readyDocument("TARGET_E2E_CANDIDATE")),
                Arguments.of(
                        "duplicate content type",
                        200,
                        exact,
                        Map.of("Content-Type", List.of("application/json", "application/json")),
                        readyDocument("TARGET_E2E_CANDIDATE")),
                Arguments.of(
                        "not ready",
                        200,
                        exact,
                        Map.of("Content-Type", List.of("application/json")),
                        readyDocument("TARGET_E2E_CANDIDATE").replace(
                                "\"ready\":true", "\"ready\":false")),
                Arguments.of(
                        "not accepting",
                        200,
                        exact,
                        Map.of("Content-Type", List.of("application/json")),
                        readyDocument("TARGET_E2E_CANDIDATE").replace(
                                "\"accepting\":true", "\"accepting\":false")),
                Arguments.of(
                        "mode mismatch",
                        200,
                        exact,
                        Map.of("Content-Type", List.of("application/json")),
                        readyDocument("SHADOW")),
                Arguments.of(
                        "code mismatch",
                        200,
                        exact,
                        Map.of("Content-Type", List.of("application/json")),
                        readyDocument("TARGET_E2E_CANDIDATE").replace(
                                "GRAPH_READY", "GRAPH_NOT_READY")),
                Arguments.of(
                        "persistence mismatch",
                        200,
                        exact,
                        Map.of("Content-Type", List.of("application/json")),
                        readyDocument("TARGET_E2E_CANDIDATE").replace(
                                "GRAPH_PERSISTENCE_READY", "GRAPH_DB_UNAVAILABLE")),
                Arguments.of(
                        "security mismatch",
                        200,
                        exact,
                        Map.of("Content-Type", List.of("application/json")),
                        readyDocument("TARGET_E2E_CANDIDATE").replace(
                                "GRAPH_JWKS_READY", "GRAPH_JWKS_UNAVAILABLE")),
                Arguments.of(
                        "bulkhead mismatch",
                        200,
                        exact,
                        Map.of("Content-Type", List.of("application/json")),
                        readyDocument("TARGET_E2E_CANDIDATE").replace(
                                "GRAPH_BULKHEAD_READY", "GRAPH_BULKHEAD_UNAVAILABLE")),
                Arguments.of(
                        "extra field",
                        200,
                        exact,
                        Map.of("Content-Type", List.of("application/json")),
                        readyDocument("TARGET_E2E_CANDIDATE").replace(
                                "}", ",\"extra\":true}")),
                Arguments.of(
                        "duplicate ready member",
                        200,
                        exact,
                        Map.of("Content-Type", List.of("application/json")),
                        readyDocument("TARGET_E2E_CANDIDATE").replace(
                                "\"ready\":true", "\"ready\":true,\"ready\":true")),
                Arguments.of(
                        "oversized body",
                        200,
                        exact,
                        Map.of("Content-Type", List.of("application/json")),
                        readyDocument("TARGET_E2E_CANDIDATE") + "x".repeat(4096)));
    }

    private static String readyDocument(String mode) {
        return "{\"ready\":true,\"accepting\":true,\"mode\":\""
                + mode
                + "\",\"code\":\"GRAPH_READY\","
                + "\"persistence_code\":\"GRAPH_PERSISTENCE_READY\","
                + "\"security_code\":\"GRAPH_JWKS_READY\","
                + "\"bulkhead_code\":\"GRAPH_BULKHEAD_READY\"}";
    }

    @Test
    void localPlaintextBundleCannotMasqueradeAsMutualTls() {
        GraphTransportBundle bundle = LocalGraphTransportFactory.create(
                LocalGraphTransportFactory.Profile.TEST, Duration.ofSeconds(1));

        assertThat(bundle.transportProof().mode())
                .isEqualTo(GraphTransportSecurityProof.Mode.LOCAL_PLAINTEXT);
        assertThat(bundle.transportProof().protocol()).isEqualTo("PLAINTEXT");
        assertThat(bundle.transportProof().trustedMutualTls()).isFalse();
        assertThat(bundle.commandTransport().transportProof())
                .isSameAs(bundle.reconciliationTransport().transportProof())
                .isSameAs(bundle.transportProof());
    }

    @Test
    void rejectsEmptyOrOversizedPasswordsAndPaths() {
        Path keyStore = temporaryDirectory.resolve("client.p12");
        Path trustStore = temporaryDirectory.resolve("trust.p12");

        assertThatThrownBy(() -> material(keyStore, new char[0], trustStore, TRUST_PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyStorePassword");
        assertThatThrownBy(() -> material(
                        keyStore, "x".repeat(1_025).toCharArray(), trustStore, TRUST_PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyStorePassword");
        assertThatThrownBy(() -> material(keyStore, KEY_PASSWORD, trustStore, new char[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trustStorePassword");
        assertThatThrownBy(() -> material(
                        Path.of("relative-client.p12"),
                        KEY_PASSWORD,
                        trustStore,
                        TRUST_PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyStorePath");
        assertThatThrownBy(() -> material(
                        temporaryDirectory.resolve("x".repeat(4_100)),
                        KEY_PASSWORD,
                        trustStore,
                        TRUST_PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyStorePath");
        assertThatThrownBy(() -> material(keyStore, KEY_PASSWORD, keyStore, TRUST_PASSWORD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct");
    }

    @Test
    void wrongPasswordAndMalformedKeyStoreFailClosed() throws Exception {
        Path keyStore = writeClientKeyStore();
        Path trustStore = writeTrustStore();
        try (GraphTlsClientMaterial wrongPassword =
                material(keyStore, "wrong-password".toCharArray(), trustStore, TRUST_PASSWORD)) {
            assertThatThrownBy(() -> TrustedGraphTransportFactory.create(
                            wrongPassword, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rejected");
        }

        Path malformed = temporaryDirectory.resolve("malformed-client.p12");
        Files.writeString(malformed, "not a PKCS12 key store");
        try (GraphTlsClientMaterial invalid =
                material(malformed, KEY_PASSWORD, trustStore, TRUST_PASSWORD)) {
            assertThatThrownBy(() -> TrustedGraphTransportFactory.create(
                            invalid, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rejected");
        }
    }

    @Test
    void emptyTrustStoreAndDestroyedMaterialFailClosed() throws Exception {
        Path keyStore = writeClientKeyStore();
        Path emptyTrustStore = temporaryDirectory.resolve("empty-trust.p12");
        KeyStore empty = KeyStore.getInstance("PKCS12");
        empty.load(null, TRUST_PASSWORD);
        try (var output = Files.newOutputStream(emptyTrustStore)) {
            empty.store(output, TRUST_PASSWORD);
        }
        try (GraphTlsClientMaterial material =
                material(keyStore, KEY_PASSWORD, emptyTrustStore, TRUST_PASSWORD)) {
            assertThatThrownBy(() -> TrustedGraphTransportFactory.create(
                            material, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rejected");
        }

        Path keyBearingTrustStore = temporaryDirectory.resolve("key-bearing-trust.p12");
        Files.copy(keyStore, keyBearingTrustStore);
        try (GraphTlsClientMaterial material =
                material(keyStore, KEY_PASSWORD, keyBearingTrustStore, KEY_PASSWORD)) {
            assertThatThrownBy(() -> TrustedGraphTransportFactory.create(
                            material, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rejected");
        }

        Path trustStore = writeTrustStore();
        GraphTlsClientMaterial destroyed = material(keyStore, KEY_PASSWORD, trustStore, TRUST_PASSWORD);
        destroyed.close();
        assertThat(destroyed.destroyed()).isTrue();
        assertThatThrownBy(() -> TrustedGraphTransportFactory.create(
                        destroyed, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("destroyed");
    }

    @Test
    void factoriesRejectUnboundedConnectTimeoutsBeforeOpeningMaterial() {
        Path keyStore = temporaryDirectory.resolve("missing-client.p12");
        Path trustStore = temporaryDirectory.resolve("missing-trust.p12");
        try (GraphTlsClientMaterial material = material(keyStore, KEY_PASSWORD, trustStore, TRUST_PASSWORD)) {
            assertThatThrownBy(() -> TrustedGraphTransportFactory.create(
                            material, Duration.ofMillis(99)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("connect timeout");
            assertThatThrownBy(() -> TrustedGraphTransportFactory.create(
                            material, Duration.ofSeconds(31)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("connect timeout");
        }
        assertThatThrownBy(() -> LocalGraphTransportFactory.create(
                        LocalGraphTransportFactory.Profile.LOCAL, Duration.ofSeconds(31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect timeout");
    }

    private GraphTlsClientMaterial material(
            Path keyStore,
            char[] keyPassword,
            Path trustStore,
            char[] trustPassword) {
        return new GraphTlsClientMaterial(keyStore, keyPassword, trustStore, trustPassword);
    }

    private GraphTransportBundle createTrustedBundle(RecordingHttpClient client, URI baseUri)
            throws Exception {
        return createTrustedBundle(client, baseUri, null);
    }

    private GraphTransportBundle createTrustedBundle(
            RecordingHttpClient client,
            URI baseUri,
            GraphReadinessCoordinator.Settings readinessSettings)
            throws Exception {
        HttpClient.Builder builder = mock(HttpClient.Builder.class);
        when(builder.connectTimeout(any())).thenReturn(builder);
        when(builder.followRedirects(any())).thenReturn(builder);
        when(builder.sslContext(any())).thenReturn(builder);
        when(builder.sslParameters(any())).thenReturn(builder);
        when(builder.version(any())).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        try (MockedStatic<HttpClient> factory = mockStatic(HttpClient.class)) {
            factory.when(HttpClient::newBuilder).thenReturn(builder);
            try (GraphTlsClientMaterial material = material(
                    writeClientKeyStore(), KEY_PASSWORD, writeTrustStore(), TRUST_PASSWORD)) {
                GraphTransportBundle bundle = readinessSettings == null
                        ? TrustedGraphTransportFactory.createForEndpoint(
                                material, Duration.ofSeconds(2), baseUri)
                        : TrustedGraphTransportFactory.createForEndpoint(
                                material, Duration.ofSeconds(2), baseUri, readinessSettings);
                verify(builder).connectTimeout(Duration.ofSeconds(2));
                verify(builder).followRedirects(HttpClient.Redirect.NEVER);
                return bundle;
            }
        }
    }

    private static final class RecordingHttpClient extends HttpClient {

        private final SSLContext sslContext;
        private final SSLParameters sslParameters;
        private final StubResponseSpec readinessResponse;
        private final List<HttpRequest> requests = new ArrayList<>();
        private int synchronousCalls;
        private int asynchronousCalls;
        private long readinessDemand;
        private boolean readinessCancelled;
        private final Duration readinessDelay;
        private final List<Boolean> terminationResults = new ArrayList<>(List.of(true));
        private int shutdownCalls;
        private int shutdownNowCalls;
        private int awaitTerminationCalls;

        private RecordingHttpClient(StubResponseSpec readinessResponse)
                throws NoSuchAlgorithmException {
            this(readinessResponse, Duration.ZERO);
        }

        private RecordingHttpClient(
                StubResponseSpec readinessResponse, Duration readinessDelay)
                throws NoSuchAlgorithmException {
            this.readinessResponse = readinessResponse;
            this.readinessDelay = readinessDelay;
            this.sslContext = SSLContext.getInstance("TLSv1.3");
            try {
                this.sslContext.init(null, null, new SecureRandom());
            } catch (java.security.KeyManagementException exception) {
                throw new IllegalStateException(exception);
            }
            this.sslParameters = new SSLParameters();
            this.sslParameters.setProtocols(new String[] {"TLSv1.3"});
            this.sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        }

        private List<HttpRequest> requests() {
            return List.copyOf(requests);
        }

        private int synchronousCalls() {
            return synchronousCalls;
        }

        private int asynchronousCalls() {
            return asynchronousCalls;
        }

        private long readinessDemand() {
            return readinessDemand;
        }

        private boolean readinessCancelled() {
            return readinessCancelled;
        }

        private void terminationResults(Boolean... results) {
            terminationResults.clear();
            terminationResults.addAll(List.of(results));
        }

        private int shutdownCalls() {
            return shutdownCalls;
        }

        private int shutdownNowCalls() {
            return shutdownNowCalls;
        }

        private int awaitTerminationCalls() {
            return awaitTerminationCalls;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(2));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return sslContext;
        }

        @Override
        public SSLParameters sslParameters() {
            return sslParameters;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public void shutdown() {
            shutdownCalls++;
        }

        @Override
        public void shutdownNow() {
            shutdownNowCalls++;
        }

        @Override
        public boolean awaitTermination(Duration duration) {
            int index = awaitTerminationCalls++;
            return index < terminationResults.size()
                    ? terminationResults.get(index)
                    : terminationResults.getLast();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException {
            synchronousCalls++;
            throw new AssertionError("Graph readiness and command transport must use sendAsync");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            asynchronousCalls++;
            requests.add(request);
            boolean readinessRequest = request.method().equals("GET")
                    || request.uri().getPath().endsWith("/ready/intake-preparation");
            StubResponseSpec response = readinessRequest
                    ? readinessResponse
                    : new StubResponseSpec(
                            200,
                            request.uri(),
                            Map.of("Content-Type", List.of("application/x-ndjson")),
                            "{\"sequence\":1}\n");
            HttpResponse.BodySubscriber<T> subscriber = responseBodyHandler.apply(
                    new StubResponseInfo(response.status(), response.headers()));
            Flow.Subscription subscription = new Flow.Subscription() {
                @Override
                public void request(long count) {
                    if (readinessRequest) {
                        readinessDemand = count;
                    }
                }

                @Override
                public void cancel() {
                    if (readinessRequest) {
                        readinessCancelled = true;
                    }
                }
            };
            CompletableFuture<HttpResponse<T>> exchange = new CompletableFuture<>();
            subscriber.onSubscribe(subscription);
            Runnable deliver = () -> {
                subscriber.onNext(List.of(ByteBuffer.wrap(response.body())));
                subscriber.onComplete();
                subscriber.getBody()
                        .whenComplete((body, failure) -> {
                            if (failure != null) {
                                exchange.completeExceptionally(failure);
                            } else {
                                exchange.complete(new StubResponse<>(
                                        response.status(),
                                        response.uri(),
                                        response.headers(),
                                        body,
                                        request));
                            }
                        });
            };
            if (readinessRequest && !readinessDelay.isZero()) {
                CompletableFuture.delayedExecutor(
                                readinessDelay.toMillis(), TimeUnit.MILLISECONDS)
                        .execute(deliver);
            } else {
                deliver.run();
            }
            return exchange;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    private record StubResponseSpec(
            int status,
            URI uri,
            Map<String, List<String>> headers,
            byte[] body) {

        private StubResponseSpec(
                int status,
                URI uri,
                Map<String, List<String>> headers,
                String body) {
            this(status, uri, headers, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record StubResponseInfo(int statusCode, HttpHeaders headers)
            implements HttpResponse.ResponseInfo {

        private StubResponseInfo(int statusCode, Map<String, List<String>> headers) {
            this(statusCode, HttpHeaders.of(headers, (_name, _value) -> true));
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class StubResponse<T> implements HttpResponse<T> {

        private final int status;
        private final URI uri;
        private final HttpHeaders headers;
        private final T body;
        private final HttpRequest request;

        private StubResponse(
                int status,
                URI uri,
                Map<String, List<String>> headers,
                T body,
                HttpRequest request) {
            this.status = status;
            this.uri = uri;
            this.headers = HttpHeaders.of(headers, (_name, _value) -> true);
            this.body = body;
            this.request = request;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public T body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return uri;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }


    private Path writeClientKeyStore() throws Exception {
        Path path = temporaryDirectory.resolve("client-" + System.nanoTime() + ".p12");
        Files.write(path, Base64.getDecoder().decode(CLIENT_KEY_STORE_BASE64));
        return path;
    }

    private Path writeTrustStore() throws Exception {
        Certificate certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(
                        Base64.getDecoder().decode(CLIENT_CERTIFICATE_BASE64)));
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, TRUST_PASSWORD);
        trustStore.setCertificateEntry("graph-test-ca", certificate);
        Path path = temporaryDirectory.resolve("trust-" + System.nanoTime() + ".p12");
        try (var output = Files.newOutputStream(path)) {
            trustStore.store(output, TRUST_PASSWORD);
        }
        return path;
    }
}
