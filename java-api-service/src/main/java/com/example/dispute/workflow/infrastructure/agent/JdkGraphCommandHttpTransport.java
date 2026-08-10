package com.example.dispute.workflow.infrastructure.agent;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** Incremental, bounded JDK HTTP transport. The client must carry the service identity. */
public final class JdkGraphCommandHttpTransport implements GraphCommandHttpTransport {

    private static final int READ_BUFFER_BYTES = 8 * 1024;

    private final HttpClient httpClient;
    private final GraphTransportSecurityProof transportProof;
    private final GraphReadinessCoordinator readinessCoordinator;

    public JdkGraphCommandHttpTransport(HttpClient httpClient) {
        this(httpClient, GraphTransportSecurityProof.unverified());
    }

    JdkGraphCommandHttpTransport(
            HttpClient httpClient, GraphTransportSecurityProof transportProof) {
        this(httpClient, transportProof, null);
    }

    JdkGraphCommandHttpTransport(
            HttpClient httpClient,
            GraphTransportSecurityProof transportProof,
            GraphReadinessCoordinator readinessCoordinator) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.transportProof = Objects.requireNonNull(transportProof, "transportProof");
        this.readinessCoordinator = readinessCoordinator;
        if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Graph command transport must not follow redirects");
        }
        requireTls13Client(httpClient, transportProof);
    }

    @Override
    public GraphTransportSecurityProof transportProof() {
        return transportProof;
    }

    HttpClient httpClient() {
        return httpClient;
    }

    @Override
    public void stream(
            Request request,
            AgentRunCancellationToken cancellationToken,
            Listener listener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(cancellationToken, "cancellationToken")
                .throwIfCancellationRequested();

        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(request.timeout())
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.body()));
        request.headers().forEach(builder::header);
        HttpRequest httpRequest = builder.build();
        CompletableFuture<HttpResponse<InputStream>> future = readinessCoordinator == null
                ? send(httpRequest)
                : readinessCoordinator.submitCommand(() -> send(httpRequest));
        AtomicReference<InputStream> activeBody = new AtomicReference<>();

        try (AgentRunCancellationToken.Registration ignored =
                cancellationToken.onCancellation(() -> {
                    future.cancel(true);
                    closeQuietly(activeBody.get());
                })) {
            HttpResponse<InputStream> response = await(future, cancellationToken);
            if (!response.uri().equals(request.uri())) {
                closeQuietly(response.body());
                throw GraphCommandTransportException.protocolViolation(
                        "Graph command response URI differs from the request");
            }

            try (InputStream input = response.body()) {
                activeBody.set(input);
                cancellationToken.throwIfCancellationRequested();
                listener.onResponse(new ResponseHead(
                        response.statusCode(), response.uri(), response.headers().map()));
                cancellationToken.throwIfCancellationRequested();
                streamLines(input, request, cancellationToken, listener);
                cancellationToken.throwIfCancellationRequested();
            } catch (IOException exception) {
                cancellationToken.throwIfCancellationRequested();
                throw new GraphCommandTransportException(
                        "Graph command response body failed", exception);
            } finally {
                activeBody.set(null);
            }
        }
    }

    private CompletableFuture<HttpResponse<InputStream>> send(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static HttpResponse<InputStream> await(
            CompletableFuture<HttpResponse<InputStream>> future,
            AgentRunCancellationToken cancellationToken) {
        try {
            return future.get();
        } catch (CancellationException exception) {
            cancellationToken.throwIfCancellationRequested();
            throw new GraphCommandTransportException(
                    "Graph command HTTP request was cancelled", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancellationToken.throwIfCancellationRequested();
            throw new GraphCommandTransportException(
                    "Graph command HTTP request was interrupted", exception);
        } catch (ExecutionException exception) {
            cancellationToken.throwIfCancellationRequested();
            throw new GraphCommandTransportException(
                    "Graph command HTTP exchange failed", exception.getCause());
        }
    }

    private static void streamLines(
            InputStream input,
            Request request,
            AgentRunCancellationToken cancellationToken,
            Listener listener) throws IOException {
        byte[] readBuffer = new byte[READ_BUFFER_BYTES];
        byte[] lineBuffer = new byte[request.maximumLineBytes()];
        int lineLength = 0;
        long totalBytes = 0;
        int read;
        while ((read = input.read(readBuffer)) != -1) {
            if (read == 0) {
                continue;
            }
            totalBytes += read;
            if (totalBytes > request.maximumResponseBytes()) {
                throw GraphCommandTransportException.protocolViolation(
                        "Graph command response exceeds its byte limit");
            }
            cancellationToken.throwIfCancellationRequested();
            for (int index = 0; index < read; index++) {
                byte next = readBuffer[index];
                if (next == '\n') {
                    emitLine(lineBuffer, lineLength, true, listener);
                    lineLength = 0;
                    cancellationToken.throwIfCancellationRequested();
                    continue;
                }
                if (lineLength == lineBuffer.length) {
                    throw GraphCommandTransportException.protocolViolation(
                            "Graph command response line exceeds its byte limit");
                }
                lineBuffer[lineLength++] = next;
            }
        }
        if (lineLength > 0) {
            emitLine(lineBuffer, lineLength, false, listener);
        }
    }

    private static void emitLine(
            byte[] bytes,
            int length,
            boolean stripCarriageReturn,
            Listener listener) {
        int contentLength = length;
        if (stripCarriageReturn && contentLength > 0 && bytes[contentLength - 1] == '\r') {
            contentLength--;
        }
        String line = decodeUtf8(bytes, contentLength);
        if (line.isBlank()) {
            throw GraphCommandTransportException.protocolViolation(
                    "Graph command response contains a blank line");
        }
        listener.onLine(line);
    }

    private static String decodeUtf8(byte[] bytes, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw GraphCommandTransportException.protocolViolation(
                    "Graph command response is not valid UTF-8");
        }
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // Cancellation or an earlier transport failure already owns the outcome.
        }
    }

    private static void requireTls13Client(
            HttpClient httpClient, GraphTransportSecurityProof transportProof) {
        if (transportProof.mode() != GraphTransportSecurityProof.Mode.MUTUAL_TLS) {
            return;
        }
        String[] protocols = httpClient.sslParameters().getProtocols();
        if (!"TLSv1.3".equals(httpClient.sslContext().getProtocol())
                || protocols.length != 1
                || !"TLSv1.3".equals(protocols[0])
                || !"HTTPS".equals(
                        httpClient.sslParameters().getEndpointIdentificationAlgorithm())) {
            throw new IllegalArgumentException(
                    "Trusted Graph command transport requires HTTPS-verified TLSv1.3");
        }
    }
}
