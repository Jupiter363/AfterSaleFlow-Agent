package com.example.dispute.workflow.infrastructure.agent;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded JDK HTTP transport. The injected client must carry the production mTLS identity. */
public final class JdkGraphReconciliationHttpTransport
        implements GraphReconciliationHttpTransport {

    private final HttpClient httpClient;

    public JdkGraphReconciliationHttpTransport(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException(
                    "Graph reconciliation transport must not follow redirects");
        }
    }

    @Override
    public Response exchange(Request request, AgentRunCancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken")
                .throwIfCancellationRequested();
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(request.timeout())
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.body()));
        request.headers().forEach(builder::header);
        CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(
                builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        AtomicReference<InputStream> activeBody = new AtomicReference<>();
        try (AgentRunCancellationToken.Registration ignored =
                cancellationToken.onCancellation(() -> {
                    future.cancel(true);
                    closeQuietly(activeBody.get());
                })) {
            HttpResponse<InputStream> response = await(future, cancellationToken);
            if (!response.uri().equals(request.uri())) {
                closeQuietly(response.body());
                throw GraphReconciliationTransportException.protocolViolation(
                        "Graph reconciliation response URI differs from the request");
            }
            try (InputStream input = response.body()) {
                activeBody.set(input);
                cancellationToken.throwIfCancellationRequested();
                byte[] body = boundedBody(input, request.maximumResponseBytes());
                cancellationToken.throwIfCancellationRequested();
                return new Response(response.statusCode(), response.headers().map(), body);
            } catch (IOException exception) {
                cancellationToken.throwIfCancellationRequested();
                throw new GraphReconciliationTransportException(
                        "Graph reconciliation response body failed", exception);
            } finally {
                activeBody.set(null);
            }
        }
    }

    private static HttpResponse<InputStream> await(
            CompletableFuture<HttpResponse<InputStream>> future,
            AgentRunCancellationToken cancellationToken) {
        try {
            return future.get();
        } catch (CancellationException exception) {
            cancellationToken.throwIfCancellationRequested();
            throw new GraphReconciliationTransportException(
                    "Graph reconciliation HTTP request was cancelled", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancellationToken.throwIfCancellationRequested();
            throw new GraphReconciliationTransportException(
                    "Graph reconciliation HTTP request was interrupted", exception);
        } catch (ExecutionException exception) {
            cancellationToken.throwIfCancellationRequested();
            throw new GraphReconciliationTransportException(
                    "Graph reconciliation HTTP exchange failed", exception.getCause());
        }
    }

    private static byte[] boundedBody(InputStream input, int maximumBytes) throws IOException {
        byte[] body = input.readNBytes(maximumBytes + 1);
        if (body.length > maximumBytes) {
            throw GraphReconciliationTransportException.protocolViolation(
                    "Graph reconciliation response exceeds its byte limit");
        }
        return body;
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // Cancellation already owns the outcome.
        }
    }
}
