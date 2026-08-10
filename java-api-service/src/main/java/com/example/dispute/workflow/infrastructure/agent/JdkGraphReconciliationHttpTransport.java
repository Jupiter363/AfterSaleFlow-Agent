package com.example.dispute.workflow.infrastructure.agent;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Bounded JDK HTTP transport. The injected client must carry the production mTLS identity. */
public final class JdkGraphReconciliationHttpTransport
        implements GraphReconciliationHttpTransport {

    private static final long MAXIMUM_WAIT_SLICE_NANOS = Duration.ofMillis(100).toNanos();

    private final HttpClient httpClient;
    private final GraphTransportSecurityProof transportProof;
    private final GraphReadinessCoordinator readinessCoordinator;
    private final LongSupplier nanoTime;

    public JdkGraphReconciliationHttpTransport(HttpClient httpClient) {
        this(httpClient, GraphTransportSecurityProof.unverified());
    }

    JdkGraphReconciliationHttpTransport(
            HttpClient httpClient, GraphTransportSecurityProof transportProof) {
        this(httpClient, transportProof, null);
    }

    JdkGraphReconciliationHttpTransport(
            HttpClient httpClient,
            GraphTransportSecurityProof transportProof,
            GraphReadinessCoordinator readinessCoordinator) {
        this(httpClient, transportProof, readinessCoordinator, System::nanoTime);
    }

    JdkGraphReconciliationHttpTransport(
            HttpClient httpClient,
            GraphTransportSecurityProof transportProof,
            GraphReadinessCoordinator readinessCoordinator,
            LongSupplier nanoTime) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.transportProof = Objects.requireNonNull(transportProof, "transportProof");
        this.readinessCoordinator = readinessCoordinator;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException(
                    "Graph reconciliation transport must not follow redirects");
        }
        requireTls13Client(httpClient, transportProof);
    }

    @Override
    public GraphTransportSecurityProof transportProof() {
        return transportProof;
    }

    @Override
    public Response exchange(Request request, AgentRunCancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken")
                .throwIfCancellationRequested();
        Deadline deadline = new Deadline(request.timeout(), nanoTime);
        AtomicReference<CompletableFuture<HttpResponse<byte[]>>> activeExchange =
                new AtomicReference<>();
        AtomicReference<BoundedBodySubscriber> activeSubscriber = new AtomicReference<>();
        try (AgentRunCancellationToken.Registration ignored =
                cancellationToken.onCancellation(() -> {
                    cancel(activeExchange.get(), activeSubscriber.get());
                })) {
            Supplier<CompletableFuture<HttpResponse<byte[]>>> submission = () -> submit(
                    request, cancellationToken, deadline, activeSubscriber);
            CompletableFuture<HttpResponse<byte[]>> future = readinessCoordinator == null
                    ? submission.get()
                    : readinessCoordinator.submitReconciliation(
                            deadline::remainingNanos,
                            cancellationToken::isCancellationRequested,
                            submission);
            activeExchange.set(future);
            try {
                cancellationToken.throwIfCancellationRequested();
            } catch (RuntimeException failure) {
                cancel(future, activeSubscriber.get());
                throw failure;
            }
            HttpResponse<byte[]> response = await(
                    future, activeSubscriber, cancellationToken, deadline);
            if (!response.uri().equals(request.uri())) {
                throw GraphReconciliationTransportException.protocolViolation(
                        "Graph reconciliation response URI differs from the request");
            }
            cancellationToken.throwIfCancellationRequested();
            return new Response(response.statusCode(), response.headers().map(), response.body());
        }
    }

    private CompletableFuture<HttpResponse<byte[]>> submit(
            Request request,
            AgentRunCancellationToken cancellationToken,
            Deadline deadline,
            AtomicReference<BoundedBodySubscriber> activeSubscriber) {
        cancellationToken.throwIfCancellationRequested();
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(deadline.remainingDuration())
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.body()));
        request.headers().forEach(builder::header);
        return httpClient.sendAsync(
                builder.build(),
                responseInfo -> {
                    BoundedBodySubscriber subscriber =
                            new BoundedBodySubscriber(request.maximumResponseBytes());
                    if (!activeSubscriber.compareAndSet(null, subscriber)) {
                        throw new IllegalStateException(
                                "Graph reconciliation response subscriber was duplicated");
                    }
                    return subscriber;
                });
    }

    private static HttpResponse<byte[]> await(
            CompletableFuture<HttpResponse<byte[]>> future,
            AtomicReference<BoundedBodySubscriber> activeSubscriber,
            AgentRunCancellationToken cancellationToken,
            Deadline deadline) {
        while (true) {
            cancellationToken.throwIfCancellationRequested();
            long remainingNanos = deadline.remainingNanos();
            if (remainingNanos <= 0) {
                cancel(future, activeSubscriber.get());
                throw deadlineExceeded();
            }
            try {
                return future.get(
                        Math.min(remainingNanos, MAXIMUM_WAIT_SLICE_NANOS),
                        TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                if (deadline.remainingNanos() <= 0) {
                    cancel(future, activeSubscriber.get());
                    throw deadlineExceeded();
                }
            } catch (CancellationException exception) {
                cancellationToken.throwIfCancellationRequested();
                throw new GraphReconciliationTransportException(
                        "Graph reconciliation HTTP request was cancelled", exception);
            } catch (InterruptedException exception) {
                cancel(future, activeSubscriber.get());
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
    }

    private static GraphReconciliationTransportException deadlineExceeded() {
        return new GraphReconciliationTransportException(
                "Graph reconciliation HTTP exchange timed out", new TimeoutException());
    }

    private static void cancel(
            CompletableFuture<HttpResponse<byte[]>> exchange,
            BoundedBodySubscriber subscriber) {
        if (subscriber != null) {
            subscriber.cancel();
        }
        if (exchange != null) {
            exchange.cancel(true);
        }
    }

    private static final class Deadline {

        private final long timeoutNanos;
        private final long startedNanos;
        private final LongSupplier nanoTime;

        private Deadline(Duration timeout, LongSupplier nanoTime) {
            this.nanoTime = nanoTime;
            try {
                timeoutNanos = Objects.requireNonNull(timeout, "timeout").toNanos();
            } catch (ArithmeticException failure) {
                throw new IllegalArgumentException(
                        "Graph reconciliation timeout is invalid", failure);
            }
            if (timeoutNanos <= 0) {
                throw new IllegalArgumentException(
                        "Graph reconciliation timeout must be positive");
            }
            startedNanos = nanoTime.getAsLong();
        }

        private long remainingNanos() {
            long elapsedNanos = nanoTime.getAsLong() - startedNanos;
            return elapsedNanos < 0
                    ? 0
                    : timeoutNanos - Math.min(timeoutNanos, elapsedNanos);
        }

        private Duration remainingDuration() {
            long remainingNanos = remainingNanos();
            if (remainingNanos <= 0) {
                throw deadlineExceeded();
            }
            return Duration.ofNanos(remainingNanos);
        }
    }

    private static final class BoundedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {

        private final int maximumBytes;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean cancelled;

        private BoundedBodySubscriber(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription candidate) {
            Objects.requireNonNull(candidate, "subscription");
            synchronized (this) {
                if (subscription != null) {
                    candidate.cancel();
                    body.completeExceptionally(new IllegalStateException(
                            "Graph reconciliation response subscription was duplicated"));
                    return;
                }
                subscription = candidate;
                if (cancelled) {
                    candidate.cancel();
                    return;
                }
                candidate.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                int remaining = item.remaining();
                if (remaining > maximumBytes - bytes.size()) {
                    body.completeExceptionally(
                            GraphReconciliationTransportException.protocolViolation(
                                    "Graph reconciliation response exceeds its byte limit"));
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

        private synchronized void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            body.completeExceptionally(
                    new CancellationException("Graph reconciliation response was cancelled"));
            if (subscription != null) {
                subscription.cancel();
            }
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
                    "Trusted Graph reconciliation transport requires HTTPS-verified TLSv1.3");
        }
    }
}
