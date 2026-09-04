package com.example.dispute.workflow.infrastructure.agent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Continuously proves Graph readiness and gates command and result-only admission. */
public final class GraphReadinessCoordinator implements AutoCloseable {

    private static final Duration MINIMUM_INTERVAL = Duration.ofSeconds(5);
    private static final Duration MAXIMUM_INTERVAL = Duration.ofSeconds(25);
    private static final Duration MINIMUM_PROBE_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAXIMUM_PROBE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MAXIMUM_RECONCILIATION_WAIT_SLICE = Duration.ofMillis(100);
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final Object stateLock = new Object();
    private final Settings settings;
    private final Probe probe;
    private final LongSupplier nanoTime;
    private final ScheduledExecutorService scheduler;

    private Availability availability = Availability.NEW;
    private PollingReconciliation polling = PollingReconciliation.UNBOUND;
    private WorkerPollingControl pollingControl;
    private CompletableFuture<Void> probeInFlight;
    private long lastSuccessNanos = Long.MIN_VALUE;
    private long recoverySuccessNanos = Long.MIN_VALUE;
    private long transitionVersion;
    private boolean monitoring;

    GraphReadinessCoordinator(GraphReadinessHandshake handshake, Settings settings) {
        this(
                settings,
                Objects.requireNonNull(handshake, "handshake")::verify,
                System::nanoTime,
                newScheduler());
    }

    GraphReadinessCoordinator(
            Settings settings,
            Probe probe,
            LongSupplier nanoTime,
            ScheduledExecutorService scheduler) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    void verifyStartup(Duration timeout, String expectedMode) {
        settings.requireExactProbeContract(timeout, expectedMode);
        synchronized (stateLock) {
            if (availability != Availability.NEW) {
                throw new IllegalStateException("Graph startup readiness was already decided");
            }
        }
        requireProbe(ProbePurpose.STARTUP);
    }

    void bindWorkerPolling(Runnable suspendPolling, Runnable resumePolling) {
        Objects.requireNonNull(suspendPolling, "suspendPolling");
        Objects.requireNonNull(resumePolling, "resumePolling");
        WorkerPollingControl control = new WorkerPollingControl() {
            @Override
            public void suspendPolling() {
                suspendPolling.run();
            }

            @Override
            public void resumePolling() {
                resumePolling.run();
            }
        };
        boolean reconcileUnavailable;
        synchronized (stateLock) {
            requireNotClosed();
            if (pollingControl != null) {
                throw new IllegalStateException("Graph worker polling control was already bound");
            }
            pollingControl = control;
            reconcileUnavailable = availability == Availability.UNAVAILABLE;
            polling = reconcileUnavailable
                    ? PollingReconciliation.SUSPEND_PENDING
                    : PollingReconciliation.RUNNING;
        }
        if (reconcileUnavailable) {
            attemptSuspend();
        }
    }

    void startMonitoring() {
        synchronized (stateLock) {
            requireNotClosed();
            if (availability == Availability.NEW) {
                throw new IllegalStateException("Graph startup readiness was not verified");
            }
            if (pollingControl == null) {
                throw new IllegalStateException("Graph worker polling control is unavailable");
            }
            if (monitoring) {
                return;
            }
            monitoring = true;
        }
        try {
            scheduler.scheduleWithFixedDelay(
                    this::maintenanceSafely,
                    settings.interval().toNanos(),
                    settings.interval().toNanos(),
                    TimeUnit.NANOSECONDS);
        } catch (RuntimeException | Error failure) {
            synchronized (stateLock) {
                if (availability != Availability.CLOSED) {
                    availability = Availability.UNAVAILABLE;
                    transitionVersion++;
                    polling = PollingReconciliation.SUSPEND_PENDING;
                }
                monitoring = false;
            }
            attemptSuspend();
            throw failure;
        }
    }

    void stopMonitoring() {
        synchronized (stateLock) {
            if (availability == Availability.CLOSED) {
                return;
            }
            availability = Availability.CLOSED;
            transitionVersion++;
            monitoring = false;
            stateLock.notifyAll();
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(
                    settings.probeTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("Graph readiness scheduler did not terminate");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Graph readiness scheduler shutdown was interrupted", failure);
        }
    }

    boolean isMonitoring() {
        synchronized (stateLock) {
            return monitoring && availability != Availability.CLOSED;
        }
    }

    void requireCommandAdmission() {
        submitCommand(() -> null);
    }

    <T> T submitCommand(Supplier<T> submission) {
        Objects.requireNonNull(submission, "submission");
        while (true) {
            synchronized (stateLock) {
                if (availability != Availability.AVAILABLE) {
                    throw new IllegalStateException("Graph command admission is unavailable");
                }
                if (isFresh(lastSuccessNanos, nanoTime.getAsLong())) {
                    return submission.get();
                }
            }
            requireProbe(ProbePurpose.COMMAND_JIT);
        }
    }

    /**
     * Waits for recovery acknowledgement and linearizes one result-only HTTP registration.
     *
     * <p>Reconciliation cannot start a Graph command or provider call, so an already-running
     * Activity waits instead of consuming its bounded execution attempts while command admission
     * is unavailable. The submission callback must only register asynchronous work; this lock is
     * never retained while headers or response content are awaited.
     */
    <T> T submitReconciliation(
            LongSupplier remainingNanos,
            BooleanSupplier cancellationRequested,
            Supplier<T> submission) {
        Objects.requireNonNull(remainingNanos, "remainingNanos");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        Objects.requireNonNull(submission, "submission");
        while (true) {
            if (cancellationRequested.getAsBoolean()) {
                throw new IllegalStateException("Graph reconciliation wait was cancelled");
            }
            synchronized (stateLock) {
                if (cancellationRequested.getAsBoolean()) {
                    throw new IllegalStateException("Graph reconciliation wait was cancelled");
                }
                long remaining = remainingNanos.getAsLong();
                if (remaining <= 0) {
                    throw new IllegalStateException(
                            "Graph reconciliation readiness wait timed out");
                }
                if (availability == Availability.AVAILABLE) {
                    return submission.get();
                }
                requireNotClosed();
                if (availability == Availability.NEW) {
                    throw new IllegalStateException(
                            "Graph startup readiness was not verified");
                }
                long waitNanos = Math.min(
                        remaining, MAXIMUM_RECONCILIATION_WAIT_SLICE.toNanos());
                try {
                    TimeUnit.NANOSECONDS.timedWait(stateLock, waitNanos);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Graph reconciliation readiness wait was interrupted", failure);
                }
            }
        }
    }

    Snapshot snapshot() {
        synchronized (stateLock) {
            return new Snapshot(
                    availability,
                    polling,
                    transitionVersion,
                    lastSuccessNanos,
                    recoverySuccessNanos,
                    probeInFlight != null,
                    monitoring);
        }
    }

    Settings settings() {
        return settings;
    }

    @Override
    public void close() {
        stopMonitoring();
    }

    private void maintenanceSafely() {
        try {
            maintenance();
        } catch (RuntimeException | Error ignored) {
            // State and polling acknowledgement retain the fail-closed outcome for the next tick.
        }
    }

    private void maintenance() {
        Availability current;
        PollingReconciliation pollingState;
        synchronized (stateLock) {
            current = availability;
            pollingState = polling;
        }
        if (current == Availability.CLOSED || current == Availability.NEW) {
            return;
        }
        if (current == Availability.AVAILABLE) {
            requireProbe(ProbePurpose.PERIODIC);
            return;
        }

        if (pollingState == PollingReconciliation.SUSPEND_PENDING) {
            attemptSuspend();
        }
        synchronized (stateLock) {
            if (availability != Availability.UNAVAILABLE) {
                return;
            }
            if (polling == PollingReconciliation.RESUME_PENDING
                    && !isFresh(recoverySuccessNanos, nanoTime.getAsLong())) {
                polling = PollingReconciliation.SUSPENDED;
                recoverySuccessNanos = Long.MIN_VALUE;
            }
            pollingState = polling;
        }
        if (pollingState == PollingReconciliation.RESUME_PENDING) {
            attemptResume();
        } else if (pollingState == PollingReconciliation.SUSPENDED) {
            requireProbe(ProbePurpose.RECOVERY);
        }
    }

    private void requireProbe(ProbePurpose purpose) {
        CompletableFuture<Void> shared;
        boolean owner = false;
        synchronized (stateLock) {
            requireNotClosed();
            if (probeInFlight == null) {
                probeInFlight = new CompletableFuture<>();
                owner = true;
            }
            shared = probeInFlight;
        }
        if (owner) {
            executeProbe(shared, purpose);
        }
        awaitProbe(shared);
    }

    private void executeProbe(CompletableFuture<Void> shared, ProbePurpose purpose) {
        boolean suspend = false;
        boolean resume = false;
        try {
            RuntimeException runtimeFailure = null;
            Error errorFailure = null;
            try {
                probe.verify(settings.probeTimeout(), settings.expectedMode());
            } catch (RuntimeException failure) {
                runtimeFailure = failure;
            } catch (Error failure) {
                errorFailure = failure;
            }
            if (runtimeFailure == null && errorFailure == null) {
                try {
                    resume = publishProbeSuccess(purpose);
                    shared.complete(null);
                } catch (RuntimeException | Error stateFailure) {
                    shared.completeExceptionally(stateFailure);
                }
            } else {
                Throwable probeFailure = runtimeFailure == null ? errorFailure : runtimeFailure;
                try {
                    suspend = publishProbeFailure(purpose);
                } catch (RuntimeException | Error stateFailure) {
                    if (stateFailure != probeFailure) {
                        probeFailure.addSuppressed(stateFailure);
                    }
                } finally {
                    shared.completeExceptionally(probeFailure);
                }
            }
        } finally {
            synchronized (stateLock) {
                if (probeInFlight == shared) {
                    probeInFlight = null;
                }
            }
        }
        if (suspend) {
            attemptSuspend();
        }
        if (resume) {
            attemptResume();
        }
    }

    private boolean publishProbeSuccess(ProbePurpose purpose) {
        boolean resume = false;
        long successNanos = nanoTime.getAsLong();
        synchronized (stateLock) {
            if (availability == Availability.CLOSED) {
                return false;
            }
            if (purpose == ProbePurpose.STARTUP) {
                if (availability != Availability.NEW) {
                    throw new IllegalStateException("Graph startup readiness state changed");
                }
                availability = Availability.AVAILABLE;
                lastSuccessNanos = successNanos;
                transitionVersion++;
                stateLock.notifyAll();
                return false;
            }
            if (availability == Availability.AVAILABLE) {
                lastSuccessNanos = successNanos;
                return false;
            }
            if (availability == Availability.UNAVAILABLE
                    && (polling == PollingReconciliation.SUSPENDED
                            || polling == PollingReconciliation.RESUME_PENDING)) {
                recoverySuccessNanos = successNanos;
                polling = PollingReconciliation.RESUME_PENDING;
                resume = true;
            }
        }
        return resume;
    }

    private boolean publishProbeFailure(ProbePurpose purpose) {
        boolean suspend = false;
        synchronized (stateLock) {
            if (availability == Availability.CLOSED) {
                return false;
            }
            recoverySuccessNanos = Long.MIN_VALUE;
            if (purpose == ProbePurpose.STARTUP) {
                availability = Availability.UNAVAILABLE;
                transitionVersion++;
                return false;
            }
            if (availability == Availability.AVAILABLE) {
                availability = Availability.UNAVAILABLE;
                transitionVersion++;
                if (pollingControl != null) {
                    polling = PollingReconciliation.SUSPEND_PENDING;
                    suspend = true;
                }
            } else if (availability == Availability.UNAVAILABLE
                    && (polling == PollingReconciliation.RESUME_PENDING
                            || polling == PollingReconciliation.RESUMING)) {
                polling = PollingReconciliation.SUSPENDED;
            }
        }
        return suspend;
    }

    private void attemptSuspend() {
        WorkerPollingControl control;
        synchronized (stateLock) {
            if (availability != Availability.UNAVAILABLE
                    || polling != PollingReconciliation.SUSPEND_PENDING
                    || pollingControl == null) {
                return;
            }
            polling = PollingReconciliation.SUSPENDING;
            control = pollingControl;
        }
        boolean acknowledged = false;
        try {
            control.suspendPolling();
            acknowledged = true;
        } catch (RuntimeException | Error ignored) {
            // The next unavailable maintenance tick retries until polling suspension is acknowledged.
        } finally {
            synchronized (stateLock) {
                if (availability == Availability.UNAVAILABLE
                        && polling == PollingReconciliation.SUSPENDING) {
                    polling = acknowledged
                            ? PollingReconciliation.SUSPENDED
                            : PollingReconciliation.SUSPEND_PENDING;
                }
            }
        }
    }

    private void attemptResume() {
        WorkerPollingControl control;
        synchronized (stateLock) {
            if (availability != Availability.UNAVAILABLE
                    || polling != PollingReconciliation.RESUME_PENDING
                    || pollingControl == null) {
                return;
            }
            if (!isFresh(recoverySuccessNanos, nanoTime.getAsLong())) {
                polling = PollingReconciliation.SUSPENDED;
                recoverySuccessNanos = Long.MIN_VALUE;
                return;
            }
            polling = PollingReconciliation.RESUMING;
            control = pollingControl;
        }
        boolean acknowledged = false;
        try {
            control.resumePolling();
            acknowledged = true;
        } catch (RuntimeException | Error ignored) {
            // Availability remains unpublished until a later resume attempt is acknowledged.
        } finally {
            synchronized (stateLock) {
                if (availability == Availability.UNAVAILABLE
                        && polling == PollingReconciliation.RESUMING) {
                    if (acknowledged) {
                        availability = Availability.AVAILABLE;
                        polling = PollingReconciliation.RUNNING;
                        lastSuccessNanos = recoverySuccessNanos;
                        recoverySuccessNanos = Long.MIN_VALUE;
                        transitionVersion++;
                        stateLock.notifyAll();
                    } else {
                        polling = PollingReconciliation.RESUME_PENDING;
                    }
                }
            }
        }
    }

    private void awaitProbe(CompletableFuture<Void> shared) {
        try {
            shared.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Graph readiness probe was interrupted", failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Graph readiness probe failed", cause);
        }
    }

    private boolean isFresh(long successNanos, long nowNanos) {
        return successNanos != Long.MIN_VALUE
                && nowNanos - successNanos >= 0
                && nowNanos - successNanos <= settings.freshness().toNanos();
    }

    private void requireNotClosed() {
        if (availability == Availability.CLOSED) {
            throw new IllegalStateException("Graph readiness coordinator is closed");
        }
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "graph-readiness-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public record Settings(Duration interval, Duration probeTimeout, String expectedMode) {

        public Settings {
            interval = Objects.requireNonNull(interval, "interval");
            probeTimeout = Objects.requireNonNull(probeTimeout, "probeTimeout");
            expectedMode = Objects.requireNonNull(expectedMode, "expectedMode");
            if (interval.compareTo(MINIMUM_INTERVAL) < 0
                    || interval.compareTo(MAXIMUM_INTERVAL) > 0) {
                throw new IllegalArgumentException(
                        "Graph readiness interval must be between 5s and 25s");
            }
            if (probeTimeout.compareTo(MINIMUM_PROBE_TIMEOUT) < 0
                    || probeTimeout.compareTo(MAXIMUM_PROBE_TIMEOUT) > 0
                    || probeTimeout.compareTo(interval) >= 0) {
                throw new IllegalArgumentException(
                        "Graph readiness timeout must be between 100ms and 15s and less than interval");
            }
            if (!"SHADOW".equals(expectedMode)
                    && !"PRODUCTION".equals(expectedMode)) {
                throw new IllegalArgumentException("Graph readiness mode is invalid");
            }
        }

        public Duration freshness() {
            return interval.plus(probeTimeout);
        }

        private void requireExactProbeContract(Duration timeout, String mode) {
            if (!probeTimeout.equals(timeout) || !expectedMode.equals(mode)) {
                throw new IllegalArgumentException("Graph readiness probe contract mismatch");
            }
        }
    }

    enum Availability {
        NEW,
        AVAILABLE,
        UNAVAILABLE,
        CLOSED
    }

    enum PollingReconciliation {
        UNBOUND,
        RUNNING,
        SUSPEND_PENDING,
        SUSPENDING,
        SUSPENDED,
        RESUME_PENDING,
        RESUMING
    }

    record Snapshot(
            Availability availability,
            PollingReconciliation polling,
            long transitionVersion,
            long lastSuccessNanos,
            long recoverySuccessNanos,
            boolean probeInFlight,
            boolean monitoring) {}

    @FunctionalInterface
    interface Probe {
        void verify(Duration timeout, String expectedMode);
    }

    private interface WorkerPollingControl {
        void suspendPolling();

        void resumePolling();
    }

    private enum ProbePurpose {
        STARTUP,
        PERIODIC,
        COMMAND_JIT,
        RECOVERY
    }
}
