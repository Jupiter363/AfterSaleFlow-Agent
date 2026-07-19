package com.example.dispute.workflow.activity.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.dispute.workflow.infrastructure.agent.GraphCommandHttpTransport;
import com.example.dispute.workflow.infrastructure.agent.GraphCommandTransportException;
import com.example.dispute.workflow.infrastructure.agent.GraphTransportSecurityProof;
import com.example.dispute.workflow.infrastructure.agent.JdkGraphCommandHttpTransport;
import com.sun.net.httpserver.HttpServer;
import io.temporal.client.ActivityCanceledException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class JdkGraphCommandHttpTransportTest {

    @Test
    void arbitraryHttpClientCanOnlyCreateAnUnverifiedTransport() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkGraphCommandHttpTransport transport = new JdkGraphCommandHttpTransport(client);

        assertThat(transport.transportProof().mode())
                .isEqualTo(GraphTransportSecurityProof.Mode.UNVERIFIED);
        assertThat(JdkGraphCommandHttpTransport.class.getConstructors())
                .hasSize(1)
                .allSatisfy(constructor -> assertThat(constructor.getParameterTypes())
                        .containsExactly(HttpClient.class));
    }

    @Test
    void deliversTheResponseHeadAndLinesIncrementally() throws Exception {
        CountDownLatch firstLineDelivered = new CountDownLatch(1);
        CountDownLatch releaseSecondLine = new CountDownLatch(1);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.createContext("/internal/graphs/commands/stream", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("{\"sequence\":1}\r\n"
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                if (!releaseSecondLine.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("second line was not released");
                }
                exchange.getResponseBody().write("{\"sequence\":2}"
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Throwable failure) {
                serverFailure.set(failure);
            } finally {
                exchange.close();
            }
        });
        server.start();

        List<String> callbacks = java.util.Collections.synchronizedList(new ArrayList<>());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var transport = transport();
            Future<?> task = executor.submit(() -> transport.stream(
                    request(serverUri(server), 1024, 8192),
                    new AgentRunCancellationToken(),
                    new GraphCommandHttpTransport.Listener() {
                        @Override
                        public void onResponse(GraphCommandHttpTransport.ResponseHead response) {
                            callbacks.add("head:" + response.statusCode());
                        }

                        @Override
                        public void onLine(String line) {
                            callbacks.add("line:" + line);
                            if (line.contains("1")) {
                                firstLineDelivered.countDown();
                            }
                        }
                    }));

            assertThat(firstLineDelivered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(task.isDone()).isFalse();
            assertThat(callbacks).containsExactly(
                    "head:200", "line:{\"sequence\":1}");
            releaseSecondLine.countDown();
            task.get(5, TimeUnit.SECONDS);

            assertThat(callbacks).containsExactly(
                    "head:200",
                    "line:{\"sequence\":1}",
                    "line:{\"sequence\":2}");
            assertThat(serverFailure.get()).isNull();
        } finally {
            releaseSecondLine.countDown();
            server.stop(0);
            serverExecutor.close();
        }
    }

    @Test
    void exposesANonSuccessResponseBodyWithoutBufferingPolicyIntoTheTransport() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] response = "{\"code\":\"COMMAND_REJECTED\"}"
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/internal/graphs/commands/stream", exchange -> {
            exchange.sendResponseHeaders(422, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        List<String> callbacks = new ArrayList<>();
        try {
            transport().stream(
                    request(serverUri(server), 1024, 8192),
                    new AgentRunCancellationToken(),
                    new GraphCommandHttpTransport.Listener() {
                        @Override
                        public void onResponse(GraphCommandHttpTransport.ResponseHead head) {
                            callbacks.add("head:" + head.statusCode());
                        }

                        @Override
                        public void onLine(String line) {
                            callbacks.add("line:" + line);
                        }
                    });

            assertThat(callbacks).containsExactly(
                    "head:422", "line:{\"code\":\"COMMAND_REJECTED\"}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsBlankMalformedOversizedAndOverTotalResponses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/blank", exchange -> respond(exchange, " \t\r\n"
                .getBytes(StandardCharsets.UTF_8)));
        server.createContext("/malformed", exchange -> respond(
                exchange, new byte[] {(byte) 0xc3, 0x28, '\n'}));
        server.createContext("/line", exchange -> respond(
                exchange, "12345\n".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/total", exchange -> respond(
                exchange, "a\nb\nc\n".getBytes(StandardCharsets.UTF_8)));
        server.start();
        try {
            assertProtocolViolation(
                    () -> streamIgnoring(serverUri(server).resolve("/blank"), 16, 32),
                    "blank line");
            assertProtocolViolation(
                    () -> streamIgnoring(serverUri(server).resolve("/malformed"), 16, 32),
                    "UTF-8");
            assertProtocolViolation(
                    () -> streamIgnoring(serverUri(server).resolve("/line"), 4, 32),
                    "line exceeds");
            assertProtocolViolation(
                    () -> streamIgnoring(serverUri(server).resolve("/total"), 2, 5),
                    "response exceeds");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requestRejectsInvalidUrisBodiesTimeoutsAndBounds() {
        assertThatThrownBy(() -> request(URI.create("file:///tmp/stream"), 16, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URI");
        assertThatThrownBy(() -> request(
                        URI.create("http://user@localhost/stream"), 16, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URI");
        assertThatThrownBy(() -> new GraphCommandHttpTransport.Request(
                        URI.create("http://localhost/stream"),
                        Map.of(),
                        new byte[0],
                        Duration.ofSeconds(1),
                        16,
                        32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 KiB");
        assertThatThrownBy(() -> new GraphCommandHttpTransport.Request(
                        URI.create("http://localhost/stream"),
                        Map.of(),
                        new byte[65_537],
                        Duration.ofSeconds(1),
                        16,
                        32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64 KiB");
        assertThatThrownBy(() -> new GraphCommandHttpTransport.Request(
                        URI.create("http://localhost/stream"),
                        Map.of(),
                        new byte[] {1},
                        Duration.ZERO,
                        16,
                        32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> request(
                        URI.create("http://localhost/stream"),
                        GraphCommandHttpTransport.MAXIMUM_LINE_BYTES + 1,
                        GraphCommandHttpTransport.MAXIMUM_RESPONSE_BYTES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumLineBytes");
        assertThatThrownBy(() -> request(
                        URI.create("http://localhost/stream"), 32, 31))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumResponseBytes");
        assertThatThrownBy(() -> request(
                        URI.create("http://localhost/stream"),
                        32,
                        GraphCommandHttpTransport.MAXIMUM_RESPONSE_BYTES + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumResponseBytes");
    }

    @Test
    void rejectsRedirectCapableClientsAndMismatchedResponseUrisBeforeCallbacks() {
        assertThatThrownBy(() -> new JdkGraphCommandHttpTransport(
                        HttpClient.newBuilder()
                                .followRedirects(HttpClient.Redirect.ALWAYS)
                                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redirects");

        URI requestUri = URI.create("https://graph.internal/commands/stream");
        CloseTrackingInputStream body = new CloseTrackingInputStream("{}\n"
                .getBytes(StandardCharsets.UTF_8));
        StubHttpClient client = StubHttpClient.completed(response(
                URI.create("https://redirected.internal/commands/stream"), 200, body));
        AtomicInteger callbacks = new AtomicInteger();

        assertThatThrownBy(() -> new JdkGraphCommandHttpTransport(client).stream(
                        request(requestUri, 16, 32),
                        new AgentRunCancellationToken(),
                        countingListener(callbacks)))
                .isInstanceOf(GraphCommandTransportException.class)
                .extracting(failure -> ((GraphCommandTransportException) failure)
                        .protocolViolation())
                .isEqualTo(true);
        assertThat(callbacks).hasValue(0);
        assertThat(body.closed()).isTrue();
    }

    @Test
    void cancellationWhileAwaitingHeadersCancelsTheHttpFuture() throws Exception {
        CompletableFuture<HttpResponse<InputStream>> responseFuture = new CompletableFuture<>();
        StubHttpClient client = new StubHttpClient(responseFuture);
        AgentRunCancellationToken cancellation = new AgentRunCancellationToken();
        ActivityCanceledException cancelled = new ActivityCanceledException();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> task = executor.submit(() -> new JdkGraphCommandHttpTransport(client).stream(
                    request(URI.create("https://graph.internal/commands/stream"), 16, 32),
                    cancellation,
                    countingListener(new AtomicInteger())));
            assertThat(client.requestStarted.await(5, TimeUnit.SECONDS)).isTrue();

            cancellation.requestCancellation(cancelled);

            assertThatThrownBy(() -> task.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(cancelled);
            assertThat(responseFuture.isCancelled()).isTrue();
        }
    }

    @Test
    void cancellationWhileReadingClosesTheActiveResponseBody() throws Exception {
        URI uri = URI.create("https://graph.internal/commands/stream");
        BlockingInputStream body = new BlockingInputStream();
        StubHttpClient client = StubHttpClient.completed(response(uri, 200, body));
        AgentRunCancellationToken cancellation = new AgentRunCancellationToken();
        ActivityCanceledException cancelled = new ActivityCanceledException();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> task = executor.submit(() -> new JdkGraphCommandHttpTransport(client).stream(
                    request(uri, 16, 32),
                    cancellation,
                    countingListener(new AtomicInteger())));
            assertThat(body.readStarted.await(5, TimeUnit.SECONDS)).isTrue();

            cancellation.requestCancellation(cancelled);

            assertThatThrownBy(() -> task.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCause(cancelled);
            assertThat(body.closed()).isTrue();
        }
    }

    @Test
    void listenerFailurePropagatesUnchangedAndClosesTheBody() {
        URI uri = URI.create("https://graph.internal/commands/stream");
        CloseTrackingInputStream body = new CloseTrackingInputStream("{}\n"
                .getBytes(StandardCharsets.UTF_8));
        RuntimeException listenerFailure = new IllegalStateException("listener failed");
        StubHttpClient client = StubHttpClient.completed(response(uri, 200, body));

        Throwable thrown = catchThrowable(() -> new JdkGraphCommandHttpTransport(client).stream(
                request(uri, 16, 32),
                new AgentRunCancellationToken(),
                new GraphCommandHttpTransport.Listener() {
                    @Override
                    public void onResponse(GraphCommandHttpTransport.ResponseHead response) {}

                    @Override
                    public void onLine(String line) {
                        throw listenerFailure;
                    }
                }));

        assertThat(thrown).isSameAs(listenerFailure);
        assertThat(body.closed()).isTrue();
    }

    private static JdkGraphCommandHttpTransport transport() {
        return new JdkGraphCommandHttpTransport(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    private static URI serverUri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/internal/graphs/commands/stream");
    }

    private static GraphCommandHttpTransport.Request request(
            URI uri, int maximumLineBytes, int maximumResponseBytes) {
        return new GraphCommandHttpTransport.Request(
                uri,
                Map.of(
                        "Authorization", "Bearer signed.token.value",
                        "Content-Type", "application/json; charset=utf-8",
                        "Accept", "application/x-ndjson"),
                "{}".getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(10),
                maximumLineBytes,
                maximumResponseBytes);
    }

    private static void streamIgnoring(URI uri, int maximumLineBytes, int maximumResponseBytes) {
        transport().stream(
                request(uri, maximumLineBytes, maximumResponseBytes),
                new AgentRunCancellationToken(),
                new GraphCommandHttpTransport.Listener() {
                    @Override
                    public void onResponse(GraphCommandHttpTransport.ResponseHead response) {}

                    @Override
                    public void onLine(String line) {}
                });
    }

    private static void assertProtocolViolation(Runnable invocation, String message) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(GraphCommandTransportException.class)
                .hasMessageContaining(message)
                .extracting(failure -> ((GraphCommandTransportException) failure)
                        .protocolViolation())
                .isEqualTo(true);
    }

    private static GraphCommandHttpTransport.Listener countingListener(AtomicInteger callbacks) {
        return new GraphCommandHttpTransport.Listener() {
            @Override
            public void onResponse(GraphCommandHttpTransport.ResponseHead response) {
                callbacks.incrementAndGet();
            }

            @Override
            public void onLine(String line) {
                callbacks.incrementAndGet();
            }
        };
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] response)
            throws IOException {
        try {
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } finally {
            exchange.close();
        }
    }

    private static HttpResponse<InputStream> response(
            URI uri, int statusCode, InputStream body) {
        return new StubHttpResponse(uri, statusCode, body);
    }

    private static final class StubHttpClient extends HttpClient {

        private final CompletableFuture<HttpResponse<InputStream>> responseFuture;
        private final CountDownLatch requestStarted = new CountDownLatch(1);

        private StubHttpClient(CompletableFuture<HttpResponse<InputStream>> responseFuture) {
            this.responseFuture = responseFuture;
        }

        private static StubHttpClient completed(HttpResponse<InputStream> response) {
            return new StubHttpClient(CompletableFuture.completedFuture(response));
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
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
            try {
                return SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
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
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            requestStarted.countDown();
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) responseFuture;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    private record StubHttpResponse(URI uri, int statusCode, InputStream body)
            implements HttpResponse<InputStream> {

        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(uri).build();
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(
                    Map.of("Content-Type", List.of("application/x-ndjson")),
                    (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {

        private final AtomicBoolean closed = new AtomicBoolean();

        private CloseTrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }

        private boolean closed() {
            return closed.get();
        }
    }

    private static final class BlockingInputStream extends InputStream {

        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch closedSignal = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            readStarted.countDown();
            try {
                if (!closedSignal.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test stream timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("test stream interrupted", exception);
            }
            throw new IOException("stream closed");
        }

        @Override
        public void close() {
            closed.set(true);
            closedSignal.countDown();
        }

        private boolean closed() {
            return closed.get();
        }
    }
}
