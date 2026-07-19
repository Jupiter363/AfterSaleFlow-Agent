package com.example.dispute.workflow.activity.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.infrastructure.agent.GraphReconciliationHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphReconciliationTransportException;
import com.example.dispute.workflow.infrastructure.agent.JdkGraphReconciliationHttpTransport;
import com.sun.net.httpserver.HttpServer;
import io.temporal.client.ActivityCanceledException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JdkGraphReconciliationHttpTransportTest {

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
            cancellation.requestCancellation(cancelled);

            assertThatThrownBy(task::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(cancelled);
        } finally {
            release.countDown();
            server.stop(0);
            serverExecutor.close();
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
}
