package com.example.dispute.workflow.infrastructure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import com.sun.net.httpserver.HttpServer;
import io.temporal.client.ActivityCanceledException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JdkGraphReconciliationHttpTransportTest {

    @Test
    void arbitraryHttpClientCanOnlyCreateAnUnverifiedTransport() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkGraphReconciliationHttpTransport transport =
                new JdkGraphReconciliationHttpTransport(client);

        assertThat(transport.transportProof().mode())
                .isEqualTo(GraphTransportSecurityProof.Mode.UNVERIFIED);
        assertThat(JdkGraphReconciliationHttpTransport.class.getConstructors())
                .hasSize(1)
                .allSatisfy(constructor -> assertThat(constructor.getParameterTypes())
                        .containsExactly(HttpClient.class));
    }

    @Test
    void sendsOneBoundedPostAndReturnsHeadersAndBody() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/graphs/commands/reconcile", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"result\":\"cached\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var transport = new JdkGraphReconciliationHttpTransport(HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build());
            GraphReconciliationHttpTransport.Request request = request(
                    server,
                    "{\"command\":\"exact\"}".getBytes(StandardCharsets.UTF_8),
                    1024);

            GraphReconciliationHttpTransport.Response response = transport.exchange(
                    request, new AgentRunCancellationToken());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(new String(response.body(), StandardCharsets.UTF_8))
                    .isEqualTo("{\"result\":\"cached\"}");
            assertThat(response.headers().entrySet().stream()
                            .filter(entry -> entry.getKey().equalsIgnoreCase("cache-control"))
                            .flatMap(entry -> entry.getValue().stream()))
                    .containsExactly("no-store");
            assertThat(authorization).hasValue("Bearer signed.token.value");
            assertThat(body).hasValue("{\"command\":\"exact\"}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oversizedResponseIsAProtocolViolationAndRedirectsAreForbidden() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/graphs/commands/reconcile", exchange -> {
            byte[] response = "x".repeat(32).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var transport = new JdkGraphReconciliationHttpTransport(HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build());

            assertThatThrownBy(() -> transport.exchange(
                            request(server, "{}".getBytes(StandardCharsets.UTF_8), 16),
                            new AgentRunCancellationToken()))
                    .isInstanceOf(GraphReconciliationTransportException.class)
                    .extracting(failure -> ((GraphReconciliationTransportException) failure)
                            .protocolViolation())
                    .isEqualTo(true);
            assertThatThrownBy(() -> new JdkGraphReconciliationHttpTransport(
                            HttpClient.newBuilder()
                                    .followRedirects(HttpClient.Redirect.ALWAYS)
                                    .build()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("redirects");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void activityCancellationCancelsTheOpenHttpExchange() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.createContext("/internal/graphs/commands/reconcile", exchange -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(503, 0);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var transport = new JdkGraphReconciliationHttpTransport(HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build());
            AgentRunCancellationToken cancellation = new AgentRunCancellationToken();
            var task = executor.submit(() -> transport.exchange(
                    request(server, "{}".getBytes(StandardCharsets.UTF_8), 1024),
                    cancellation));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            ActivityCanceledException cancelled = new ActivityCanceledException();
            requestCancellation(cancellation, cancelled);

            assertThatThrownBy(task::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(cancelled);
        } finally {
            release.countDown();
            server.stop(0);
            serverExecutor.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancellationBeforeBodySubscriptionCancelsLaterSubscriptionWithoutConsumption()
            throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        CompletableFuture<HttpResponse<byte[]>> exchange = new CompletableFuture<>();
        AtomicReference<HttpResponse.BodySubscriber<byte[]>> subscriber =
                new AtomicReference<>();
        CountDownLatch subscriberCreated = new CountDownLatch(1);
        when(client.<byte[]>sendAsync(any(HttpRequest.class), any()))
                .thenAnswer(invocation -> {
                    HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                    subscriber.set(handler.apply(responseInfo()));
                    subscriberCreated.countDown();
                    return exchange;
                });
        JdkGraphReconciliationHttpTransport transport =
                new JdkGraphReconciliationHttpTransport(
                        client, GraphTransportSecurityProof.unverified(), null, System::nanoTime);
        AgentRunCancellationToken cancellation = new AgentRunCancellationToken();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = executor.submit(() -> transport.exchange(
                    request(
                            URI.create("http://graph.example.test/reconcile"),
                            Duration.ofSeconds(2),
                            1024),
                    cancellation));
            assertThat(subscriberCreated.await(2, TimeUnit.SECONDS)).isTrue();

            ActivityCanceledException cancelled = new ActivityCanceledException();
            requestCancellation(cancellation, cancelled);
            assertThatThrownBy(task::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(cancelled);

            RecordingSubscription subscription = new RecordingSubscription();
            ByteBuffer lateBody = ByteBuffer.wrap(new byte[] {1, 2, 3});
            subscriber.get().onSubscribe(subscription);
            subscriber.get().onNext(List.of(lateBody));

            assertThat(subscription.cancelCalls()).isEqualTo(1);
            assertThat(subscription.requestCalls()).isZero();
            assertThat(lateBody.position()).isZero();
            assertThat(exchange.isCancelled()).isTrue();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void totalDeadlineCancelsLateBodySubscriptionWithoutConsumingIt() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        CompletableFuture<HttpResponse<byte[]>> exchange = new CompletableFuture<>();
        AtomicReference<HttpResponse.BodySubscriber<byte[]>> subscriber =
                new AtomicReference<>();
        CountDownLatch subscriberCreated = new CountDownLatch(1);
        when(client.<byte[]>sendAsync(any(HttpRequest.class), any()))
                .thenAnswer(invocation -> {
                    HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                    subscriber.set(handler.apply(responseInfo()));
                    subscriberCreated.countDown();
                    return exchange;
                });
        AtomicLong now = new AtomicLong();
        JdkGraphReconciliationHttpTransport transport =
                new JdkGraphReconciliationHttpTransport(
                        client, GraphTransportSecurityProof.unverified(), null, now::get);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = executor.submit(() -> transport.exchange(
                    request(
                            URI.create("http://graph.example.test/reconcile"),
                            Duration.ofMillis(150),
                            1024),
                    new AgentRunCancellationToken()));
            assertThat(subscriberCreated.await(2, TimeUnit.SECONDS)).isTrue();
            now.set(Duration.ofMillis(151).toNanos());

            assertThatThrownBy(task::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(GraphReconciliationTransportException.class)
                    .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);

            RecordingSubscription subscription = new RecordingSubscription();
            ByteBuffer lateBody = ByteBuffer.wrap(new byte[] {4, 5, 6});
            subscriber.get().onSubscribe(subscription);
            subscriber.get().onNext(List.of(lateBody));

            assertThat(subscription.cancelCalls()).isEqualTo(1);
            assertThat(subscription.requestCalls()).isZero();
            assertThat(lateBody.position()).isZero();
            assertThat(exchange.isCancelled()).isTrue();
        }
    }

    private static GraphReconciliationHttpTransport.Request request(
            HttpServer server,
            byte[] body,
            int maximumResponseBytes) {
        return new GraphReconciliationHttpTransport.Request(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                        + "/internal/graphs/commands/reconcile"),
                Map.of(
                        "Authorization", "Bearer signed.token.value",
                        "Content-Type", "application/json; charset=utf-8",
                        "Accept", "application/json"),
                body,
                Duration.ofSeconds(10),
                maximumResponseBytes);
    }

    private static GraphReconciliationHttpTransport.Request request(
            URI uri, Duration timeout, int maximumResponseBytes) {
        return new GraphReconciliationHttpTransport.Request(
                uri,
                Map.of("Content-Type", "application/json; charset=utf-8"),
                "{}".getBytes(StandardCharsets.UTF_8),
                timeout,
                maximumResponseBytes);
    }

    private static HttpResponse.ResponseInfo responseInfo() {
        HttpResponse.ResponseInfo info = mock(HttpResponse.ResponseInfo.class);
        when(info.statusCode()).thenReturn(200);
        when(info.headers()).thenReturn(HttpHeaders.of(Map.of(), (_name, _value) -> true));
        when(info.version()).thenReturn(HttpClient.Version.HTTP_1_1);
        return info;
    }

    private static void requestCancellation(
            AgentRunCancellationToken cancellation, RuntimeException failure) {
        ReflectionTestUtils.invokeMethod(cancellation, "requestCancellation", failure);
    }

    private static final class RecordingSubscription implements Flow.Subscription {

        private final AtomicInteger requestCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public void request(long count) {
            requestCalls.incrementAndGet();
        }

        @Override
        public void cancel() {
            cancelCalls.incrementAndGet();
        }

        int requestCalls() {
            return requestCalls.get();
        }

        int cancelCalls() {
            return cancelCalls.get();
        }
    }
}
