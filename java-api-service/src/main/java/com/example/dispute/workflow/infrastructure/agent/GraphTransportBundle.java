package com.example.dispute.workflow.infrastructure.agent;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;

/** Command and reconciliation transports created from one security context and one proof. */
public final class GraphTransportBundle implements SmartLifecycle, AutoCloseable {

    private static final Duration CLIENT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final GraphCommandHttpTransport commandTransport;
    private final GraphReconciliationHttpTransport reconciliationTransport;
    private final GraphTransportSecurityProof transportProof;
    private final GraphReadinessHandshake readinessHandshake;
    private final GraphReadinessCoordinator readinessCoordinator;
    private final HttpClient sharedHttpClient;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean running;

    GraphTransportBundle(
            GraphCommandHttpTransport commandTransport,
            GraphReconciliationHttpTransport reconciliationTransport,
            GraphTransportSecurityProof transportProof) {
        this(commandTransport, reconciliationTransport, transportProof, null, null);
    }

    GraphTransportBundle(
            GraphCommandHttpTransport commandTransport,
            GraphReconciliationHttpTransport reconciliationTransport,
            GraphTransportSecurityProof transportProof,
            GraphReadinessHandshake readinessHandshake) {
        this(
                commandTransport,
                reconciliationTransport,
                transportProof,
                readinessHandshake,
                null);
    }

    GraphTransportBundle(
            GraphCommandHttpTransport commandTransport,
            GraphReconciliationHttpTransport reconciliationTransport,
            GraphTransportSecurityProof transportProof,
            GraphReadinessHandshake readinessHandshake,
            GraphReadinessCoordinator readinessCoordinator) {
        this.commandTransport = Objects.requireNonNull(commandTransport, "commandTransport");
        this.reconciliationTransport =
                Objects.requireNonNull(reconciliationTransport, "reconciliationTransport");
        this.transportProof = Objects.requireNonNull(transportProof, "transportProof");
        this.readinessHandshake = readinessHandshake;
        this.readinessCoordinator = readinessCoordinator;
        this.sharedHttpClient = commandTransport instanceof JdkGraphCommandHttpTransport jdkTransport
                ? jdkTransport.httpClient()
                : null;
        if (commandTransport.transportProof() != transportProof
                || reconciliationTransport.transportProof() != transportProof
                || (readinessHandshake != null
                        && readinessHandshake.transportProof() != transportProof)
                || (readinessCoordinator != null && readinessHandshake == null)
                || transportProof.mode() == GraphTransportSecurityProof.Mode.UNVERIFIED) {
            throw new IllegalArgumentException(
                    "Graph transport bundle must share one factory-issued proof");
        }
    }

    public GraphCommandHttpTransport commandTransport() {
        return commandTransport;
    }

    public GraphReconciliationHttpTransport reconciliationTransport() {
        return reconciliationTransport;
    }

    public GraphTransportSecurityProof transportProof() {
        return transportProof;
    }

    /** Blocks until the factory-bound Graph endpoint proves its public readiness contract. */
    public void verifyReadiness(Duration timeout, String expectedMode) {
        if (readinessHandshake == null) {
            throw new IllegalStateException(
                    "Graph readiness handshake is unavailable for this transport bundle");
        }
        if (readinessCoordinator == null) {
            readinessHandshake.verify(timeout, expectedMode);
        } else {
            readinessCoordinator.verifyStartup(timeout, expectedMode);
        }
    }

    /** Uses the factory-bound shared client for a business-neutral Intake preparation proof. */
    public void prepareIntakeInfrastructure(Duration timeout) {
        if (closed.get()) {
            throw new IllegalStateException("Graph transport bundle is closed");
        }
        if (readinessHandshake == null) {
            throw new IllegalStateException(
                    "Intake infrastructure preparation is unavailable for this transport bundle");
        }
        readinessHandshake.prepareIntake(timeout);
    }

    public void bindWorkerPolling(Runnable suspendPolling, Runnable resumePolling) {
        if (readinessCoordinator == null) {
            throw new IllegalStateException(
                    "Continuous Graph readiness is unavailable for this transport bundle");
        }
        readinessCoordinator.bindWorkerPolling(suspendPolling, resumePolling);
    }

    @Override
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Graph transport bundle is closed");
        }
        if (readinessCoordinator != null) {
            readinessCoordinator.startMonitoring();
        }
        running = true;
    }

    @Override
    public void stop() {
        try {
            if (readinessCoordinator != null) {
                readinessCoordinator.stopMonitoring();
            }
        } finally {
            running = false;
        }
    }

    @Override
    public void stop(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running && !closed.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        try {
            stop();
        } catch (RuntimeException failure) {
            runtimeFailure = failure;
        } catch (Error failure) {
            errorFailure = failure;
        }
        try {
            closeSharedHttpClient();
        } catch (RuntimeException | Error cleanupFailure) {
            if (runtimeFailure != null) {
                if (cleanupFailure != runtimeFailure) {
                    runtimeFailure.addSuppressed(cleanupFailure);
                }
            } else if (errorFailure != null) {
                if (cleanupFailure != errorFailure) {
                    errorFailure.addSuppressed(cleanupFailure);
                }
            } else if (cleanupFailure instanceof RuntimeException failure) {
                runtimeFailure = failure;
            } else {
                errorFailure = (Error) cleanupFailure;
            }
        }
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
        if (errorFailure != null) {
            throw errorFailure;
        }
    }

    GraphReadinessCoordinator readinessCoordinator() {
        return readinessCoordinator;
    }

    private void closeSharedHttpClient() {
        if (sharedHttpClient == null) {
            return;
        }
        sharedHttpClient.shutdown();
        try {
            if (sharedHttpClient.awaitTermination(CLIENT_SHUTDOWN_TIMEOUT)) {
                return;
            }
            sharedHttpClient.shutdownNow();
            if (!sharedHttpClient.awaitTermination(CLIENT_SHUTDOWN_TIMEOUT)) {
                throw new IllegalStateException("Graph HTTP client did not terminate");
            }
        } catch (InterruptedException failure) {
            sharedHttpClient.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Graph HTTP client shutdown was interrupted", failure);
        }
    }
}
