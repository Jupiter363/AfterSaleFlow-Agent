package com.example.dispute.workflow.infrastructure.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Business-neutral startup proof for the exact Graph readiness endpoint. */
final class GraphReadinessHandshake {

    private static final int MAXIMUM_BODY_BYTES = 4096;
    private static final Duration MAXIMUM_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String INTAKE_PREPARATION_SCHEMA =
            "intake-infrastructure-preparation.v1";
    private static final String INTAKE_PREPARATION_READY = "READY";
    private static final String SHADOW_MODE = "SHADOW";
    private static final String TARGET_E2E_CANDIDATE_MODE = "TARGET_E2E_CANDIDATE";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final HttpClient httpClient;
    private final GraphTransportSecurityProof transportProof;
    private final URI readinessUri;
    private final URI intakePreparationUri;

    GraphReadinessHandshake(
            HttpClient httpClient,
            GraphTransportSecurityProof transportProof,
            URI boundBaseUri) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.transportProof = Objects.requireNonNull(transportProof, "transportProof");
        URI baseUri = Objects.requireNonNull(boundBaseUri, "boundBaseUri");
        if (httpClient.followRedirects() != HttpClient.Redirect.NEVER
                || !transportProof.trustedMutualTls()
                || transportProof.boundBaseUri().isEmpty()
                || !transportProof.boundBaseUri().orElseThrow().equals(baseUri)) {
            throw new IllegalArgumentException(
                    "Graph readiness handshake requires the factory-bound mTLS transport");
        }
        this.readinessUri = baseUri.resolve("ready/graph");
        this.intakePreparationUri = baseUri.resolve("ready/intake-preparation");
        if (!"https".equalsIgnoreCase(readinessUri.getScheme())
                || readinessUri.getUserInfo() != null
                || readinessUri.getQuery() != null
                || readinessUri.getFragment() != null
                || !Objects.equals(baseUri.getScheme(), readinessUri.getScheme())
                || !Objects.equals(baseUri.getRawAuthority(), readinessUri.getRawAuthority())
                || !readinessUri.getRawPath().endsWith("/ready/graph")
                || !"https".equalsIgnoreCase(intakePreparationUri.getScheme())
                || intakePreparationUri.getUserInfo() != null
                || intakePreparationUri.getQuery() != null
                || intakePreparationUri.getFragment() != null
                || !Objects.equals(baseUri.getScheme(), intakePreparationUri.getScheme())
                || !Objects.equals(
                        baseUri.getRawAuthority(), intakePreparationUri.getRawAuthority())
                || !intakePreparationUri
                        .getRawPath()
                        .endsWith("/ready/intake-preparation")) {
            throw new IllegalArgumentException("Graph readiness URI is invalid");
        }
    }

    GraphTransportSecurityProof transportProof() {
        return transportProof;
    }

    void verify(Duration requestTimeout, String expectedMode) {
        Duration timeout = requireRequestTimeout(requestTimeout);
        String mode = requireExpectedMode(expectedMode);
        HttpRequest request = HttpRequest.newBuilder(readinessUri)
                .timeout(timeout)
                .header("Accept", JSON_CONTENT_TYPE)
                .GET()
                .build();
        AtomicReference<BoundedBodySubscriber> activeSubscriber = new AtomicReference<>();
        CompletableFuture<HttpResponse<byte[]>> exchange = httpClient.sendAsync(
                request,
                responseInfo -> {
                    BoundedBodySubscriber subscriber = new BoundedBodySubscriber();
                    if (!activeSubscriber.compareAndSet(null, subscriber)) {
                        throw new IllegalStateException(
                                "Graph readiness response subscriber was duplicated");
                    }
                    return subscriber;
                });
        try {
            HttpResponse<byte[]> response = exchange.get(
                    timeout.toNanos(), TimeUnit.NANOSECONDS);
            verifyResponse(response, mode);
        } catch (InterruptedException exception) {
            cancel(exchange, activeSubscriber.get());
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Graph readiness handshake failed", exception);
        } catch (ExecutionException | TimeoutException | IOException exception) {
            cancel(exchange, activeSubscriber.get());
            throw new IllegalStateException("Graph readiness handshake failed", exception);
        }
    }

    void prepareIntake(Duration requestTimeout) {
        Duration timeout = requireRequestTimeout(requestTimeout);
        HttpRequest request = HttpRequest.newBuilder(intakePreparationUri)
                .timeout(timeout)
                .header("Accept", JSON_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        AtomicReference<BoundedBodySubscriber> activeSubscriber = new AtomicReference<>();
        CompletableFuture<HttpResponse<byte[]>> exchange = httpClient.sendAsync(
                request,
                responseInfo -> {
                    BoundedBodySubscriber subscriber = new BoundedBodySubscriber();
                    if (!activeSubscriber.compareAndSet(null, subscriber)) {
                        throw new IllegalStateException(
                                "Intake preparation response subscriber was duplicated");
                    }
                    return subscriber;
                });
        try {
            HttpResponse<byte[]> response = exchange.get(
                    timeout.toNanos(), TimeUnit.NANOSECONDS);
            verifyIntakePreparationResponse(response);
        } catch (InterruptedException exception) {
            cancel(exchange, activeSubscriber.get());
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Intake infrastructure preparation failed", exception);
        } catch (ExecutionException | TimeoutException | IOException exception) {
            cancel(exchange, activeSubscriber.get());
            throw new IllegalStateException(
                    "Intake infrastructure preparation failed", exception);
        }
    }

    private void verifyResponse(HttpResponse<byte[]> response, String expectedMode)
            throws IOException {
        Objects.requireNonNull(response, "response");
        if (!readinessUri.equals(response.uri())
                || response.statusCode() != 200
                || !hasExactJsonContentType(response)) {
            throw new IllegalStateException("Graph readiness handshake contract was rejected");
        }
        requireReadyDocument(
                Objects.requireNonNull(response.body(), "response.body"), expectedMode);
    }

    private static boolean hasExactJsonContentType(HttpResponse<?> response) {
        List<String> values = response.headers().allValues("Content-Type");
        return values.size() == 1 && JSON_CONTENT_TYPE.equalsIgnoreCase(values.getFirst().trim());
    }

    private void verifyIntakePreparationResponse(HttpResponse<byte[]> response)
            throws IOException {
        Objects.requireNonNull(response, "response");
        if (!intakePreparationUri.equals(response.uri())
                || response.statusCode() != 200
                || !hasExactJsonContentType(response)) {
            throw new IllegalStateException(
                    "Intake infrastructure preparation contract was rejected");
        }
        byte[] document = Objects.requireNonNull(response.body(), "response.body");
        if (document.length == 0) {
            throw new IllegalStateException(
                    "Intake infrastructure preparation response was empty");
        }
        try (JsonParser parser = JSON.createParser(document)) {
            JsonNode root = JSON.readTree(parser);
            if (root == null
                    || !root.isObject()
                    || root.size() != 2
                    || !hasExactText(root, "schema_version", INTAKE_PREPARATION_SCHEMA)
                    || !hasExactText(root, "status", INTAKE_PREPARATION_READY)
                    || parser.nextToken() != null) {
                throw new IllegalStateException(
                        "Intake infrastructure preparation response was not ready");
            }
        }
    }

    private static void requireReadyDocument(byte[] document, String expectedMode)
            throws IOException {
        if (document.length == 0) {
            throw new IllegalStateException("Graph readiness response was empty");
        }
        try (JsonParser parser = JSON.createParser(document)) {
            JsonNode root = JSON.readTree(parser);
            JsonNode ready = root == null || !root.isObject() ? null : root.get("ready");
            JsonNode accepting = root == null || !root.isObject() ? null : root.get("accepting");
            if (ready == null
                    || !ready.isBoolean()
                    || !ready.booleanValue()
                    || accepting == null
                    || !accepting.isBoolean()
                    || !accepting.booleanValue()
                    || root.size() != 7
                    || !hasExactText(root, "mode", expectedMode)
                    || !hasExactText(root, "code", "GRAPH_READY")
                    || !hasExactText(
                            root, "persistence_code", "GRAPH_PERSISTENCE_READY")
                    || !hasExactText(root, "security_code", "GRAPH_JWKS_READY")
                    || !hasExactText(root, "bulkhead_code", "GRAPH_BULKHEAD_READY")
                    || parser.nextToken() != null) {
                throw new IllegalStateException("Graph readiness response was not ready");
            }
        }
    }

    private static boolean hasExactText(JsonNode root, String field, String expected) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() && expected.equals(value.textValue());
    }

    private static Duration requireRequestTimeout(Duration candidate) {
        Duration timeout = Objects.requireNonNull(candidate, "requestTimeout");
        if (timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(MAXIMUM_REQUEST_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "Graph readiness timeout must be inside 1ns..30s");
        }
        return timeout;
    }

    private static String requireExpectedMode(String candidate) {
        String mode = Objects.requireNonNull(candidate, "expectedMode");
        if (!SHADOW_MODE.equals(mode) && !TARGET_E2E_CANDIDATE_MODE.equals(mode)) {
            throw new IllegalArgumentException("Graph readiness mode is invalid");
        }
        return mode;
    }

    private static void cancel(
            CompletableFuture<HttpResponse<byte[]>> exchange,
            BoundedBodySubscriber subscriber) {
        if (subscriber != null) {
            subscriber.cancel();
        }
        exchange.cancel(true);
    }

    private static final class BoundedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription candidate) {
            if (subscription != null) {
                candidate.cancel();
                body.completeExceptionally(new IllegalStateException(
                        "Graph readiness response subscription was duplicated"));
                return;
            }
            subscription = Objects.requireNonNull(candidate, "subscription");
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                int remaining = item.remaining();
                if (remaining > MAXIMUM_BODY_BYTES - bytes.size()) {
                    body.completeExceptionally(new IllegalStateException(
                            "Graph readiness response exceeded its byte limit"));
                    cancel();
                    return;
                }
                byte[] chunk = new byte[remaining];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(Objects.requireNonNull(throwable, "throwable"));
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }

        void cancel() {
            Flow.Subscription current = subscription;
            if (current != null) {
                current.cancel();
            }
        }
    }
}
