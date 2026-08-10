package com.example.dispute.workflow.infrastructure.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.activity.agent.AgentRunCancellationToken;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class GraphReadinessCoordinatorTest {

    private static final Duration INTERVAL = Duration.ofSeconds(5);
    private static final Duration MINIMUM_PROBE_TIMEOUT = Duration.ofMillis(100);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);
    private static final String MODE = "TARGET_E2E_CANDIDATE";

    @Test
    void settingsEnforceClosedBoundsAndDeriveFreshness() {
        GraphReadinessCoordinator.Settings minimum = settings(
                Duration.ofSeconds(5), MINIMUM_PROBE_TIMEOUT);
        GraphReadinessCoordinator.Settings maximum = settings(
                Duration.ofSeconds(25), Duration.ofSeconds(5));

        assertThat(minimum.freshness())
                .isEqualTo(Duration.ofSeconds(5).plus(MINIMUM_PROBE_TIMEOUT));
        assertThat(maximum.freshness()).isEqualTo(Duration.ofSeconds(30));
        assertThatThrownBy(() -> settings(Duration.ofSeconds(5).minusNanos(1), PROBE_TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(Duration.ofSeconds(25).plusNanos(1), PROBE_TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(INTERVAL, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(
                        INTERVAL, MINIMUM_PROBE_TIMEOUT.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(INTERVAL, Duration.ofSeconds(5).plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(INTERVAL, INTERVAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphReadinessCoordinator.Settings(
                        INTERVAL, PROBE_TIMEOUT, "REVIEW"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startupIsSynchronousExactAndFailClosed() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger calls = new AtomicInteger();
        GraphReadinessCoordinator coordinator = coordinator(
                (timeout, mode) -> {
                    assertThat(timeout).isEqualTo(PROBE_TIMEOUT);
                    assertThat(mode).isEqualTo(MODE);
                    calls.incrementAndGet();
                }, new AtomicLong(11), scheduler);

        assertThatThrownBy(coordinator::requireCommandAdmission)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> coordinator.verifyStartup(Duration.ofMillis(999), MODE))
                .isInstanceOf(IllegalArgumentException.class);

        coordinator.verifyStartup(PROBE_TIMEOUT, MODE);

        assertThat(calls).hasValue(1);
        assertThat(coordinator.snapshot().availability())
                .isEqualTo(GraphReadinessCoordinator.Availability.AVAILABLE);
        assertThat(coordinator.snapshot().transitionVersion()).isEqualTo(1);
        assertThatThrownBy(() -> coordinator.verifyStartup(PROBE_TIMEOUT, MODE))
                .isInstanceOf(IllegalStateException.class);
        coordinator.close();

        ManualScheduler failedScheduler = new ManualScheduler();
        GraphReadinessCoordinator failed = coordinator(
                (_timeout, _mode) -> {
                    throw new IllegalStateException("probe failed");
                }, new AtomicLong(12), failedScheduler);
        assertThatThrownBy(() -> failed.verifyStartup(PROBE_TIMEOUT, MODE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("probe failed");
        assertThat(failed.snapshot().availability())
                .isEqualTo(GraphReadinessCoordinator.Availability.UNAVAILABLE);
        assertThatThrownBy(failed::requireCommandAdmission)
                .isInstanceOf(IllegalStateException.class);
        AtomicInteger synchronizedSuspends = new AtomicInteger();
        failed.bindWorkerPolling(synchronizedSuspends::incrementAndGet, () -> {});
        assertThat(synchronizedSuspends).hasValue(1);
        assertThat(failed.snapshot().polling())
                .isEqualTo(GraphReadinessCoordinator.PollingReconciliation.SUSPENDED);
        failed.close();
    }

    @Test
    void periodicSuccessRefreshesAndFreshAdmissionDoesNotReprobe() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong now = new AtomicLong(100);
        AtomicInteger probes = new AtomicInteger();
        GraphReadinessCoordinator coordinator = coordinator(
                (_timeout, _mode) -> probes.incrementAndGet(), now, scheduler);
        coordinator.verifyStartup(PROBE_TIMEOUT, MODE);
        coordinator.bindWorkerPolling(() -> {}, () -> {});
        coordinator.startMonitoring();

        now.addAndGet(settings().freshness().toNanos());
        coordinator.requireCommandAdmission();
        assertThat(probes).hasValue(1);

        now.incrementAndGet();
        coordinator.requireCommandAdmission();
        assertThat(probes).hasValue(2);
        assertThat(coordinator.snapshot().lastSuccessNanos()).isEqualTo(now.get());

        now.incrementAndGet();
        scheduler.tick();
        assertThat(probes).hasValue(3);
        assertThat(coordinator.snapshot().lastSuccessNanos()).isEqualTo(now.get());
        assertThat(scheduler.initialDelay()).isEqualTo(INTERVAL);
        assertThat(scheduler.delay()).isEqualTo(INTERVAL);
        coordinator.close();
    }

    @Test
    void staleConcurrentAdmissionsAndPeriodicTickCoalesceOneProbe() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong now = new AtomicLong(1);
        BlockingProbe probe = new BlockingProbe();
        GraphReadinessCoordinator coordinator = coordinator(probe, now, scheduler);
        coordinator.verifyStartup(PROBE_TIMEOUT, MODE);
        coordinator.bindWorkerPolling(() -> {}, () -> {});
        coordinator.startMonitoring();
        now.addAndGet(settings().freshness().toNanos() + 1);
        probe.blockNext();

        ExecutorService callers = Executors.newFixedThreadPool(3);
        try {
            Future<?> periodic = callers.submit(scheduler::tick);
            probe.awaitBlocked();
            Future<?> firstAdmission = callers.submit(coordinator::requireCommandAdmission);
            Future<?> secondAdmission = callers.submit(coordinator::requireCommandAdmission);
            awaitProbeJoin(coordinator);

            assertThat(probe.calls()).isEqualTo(2);
            assertThat(firstAdmission.isDone()).isFalse();
            assertThat(secondAdmission.isDone()).isFalse();

            probe.release();
            periodic.get(2, TimeUnit.SECONDS);
            firstAdmission.get(2, TimeUnit.SECONDS);
            secondAdmission.get(2, TimeUnit.SECONDS);
            assertThat(probe.calls()).isEqualTo(2);
        } finally {
            probe.release();
            callers.shutdownNow();
            coordinator.close();
        }
    }

    @Test
    void suspendFailureRetriesWithoutRecoveryProbeOrDuplicateTransition() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong now = new AtomicLong(10);
        ScriptedProbe probe = new ScriptedProbe(false, true, true);
        AtomicInteger suspends = new AtomicInteger();
        AtomicInteger resumes = new AtomicInteger();
        GraphReadinessCoordinator coordinator = coordinator(probe, now, scheduler);
        coordinator.verifyStartup(PROBE_TIMEOUT, MODE);
        coordinator.bindWorkerPolling(
                () -> {
                    if (suspends.incrementAndGet() <= 2) {
                        throw new IllegalStateException("suspend unavailable");
                    }
                },
                resumes::incrementAndGet);
        coordinator.startMonitoring();

        scheduler.tick();
        assertUnavailable(coordinator, GraphReadinessCoordinator.PollingReconciliation.SUSPEND_PENDING);
        assertThat(coordinator.snapshot().transitionVersion()).isEqualTo(2);
        assertThat(probe.calls()).isEqualTo(2);

        scheduler.tick();
        assertUnavailable(coordinator, GraphReadinessCoordinator.PollingReconciliation.SUSPEND_PENDING);
        assertThat(probe.calls()).isEqualTo(2);

        scheduler.tick();
        assertUnavailable(coordinator, GraphReadinessCoordinator.PollingReconciliation.SUSPENDED);
        assertThat(suspends).hasValue(3);
        assertThat(resumes).hasValue(0);
        assertThat(probe.calls()).isEqualTo(3);
        assertThat(coordinator.snapshot().transitionVersion()).isEqualTo(2);
        coordinator.close();
    }

    @Test
    void resumeFailureRetainsFreshRecoveryProofUntilAcknowledged() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong now = new AtomicLong(20);
        ScriptedProbe probe = new ScriptedProbe(false, true);
        AtomicInteger resumes = new AtomicInteger();
        GraphReadinessCoordinator coordinator = coordinator(probe, now, scheduler);
        coordinator.verifyStartup(PROBE_TIMEOUT, MODE);
        coordinator.bindWorkerPolling(
                () -> {},
                () -> {
                    if (resumes.incrementAndGet() <= 2) {
                        throw new IllegalStateException("resume unavailable");
                    }
                });
        coordinator.startMonitoring();

        scheduler.tick();
        assertUnavailable(coordinator, GraphReadinessCoordinator.PollingReconciliation.SUSPENDED);
        scheduler.tick();
        assertUnavailable(coordinator, GraphReadinessCoordinator.PollingReconciliation.RESUME_PENDING);
        assertThat(probe.calls()).isEqualTo(3);
        assertThat(resumes).hasValue(1);

        scheduler.tick();
        assertUnavailable(coordinator, GraphReadinessCoordinator.PollingReconciliation.RESUME_PENDING);
        assertThat(probe.calls()).isEqualTo(3);
        assertThat(resumes).hasValue(2);

        scheduler.tick();
        assertThat(coordinator.snapshot().availability())
                .isEqualTo(GraphReadinessCoordinator.Availability.AVAILABLE);
        assertThat(coordinator.snapshot().polling())
                .isEqualTo(GraphReadinessCoordinator.PollingReconciliation.RUNNING);
        assertThat(coordinator.snapshot().transitionVersion()).isEqualTo(3);
        assertThat(probe.calls()).isEqualTo(3);
        assertThat(resumes).hasValue(3);
        coordinator.close();
    }

    @Test
    void staleRecoveryProofForcesExactReprobeBeforeResumeRetry() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicLong now = new AtomicLong(30);
        ScriptedProbe probe = new ScriptedProbe(false, true);
        AtomicInteger resumes = new AtomicInteger();
        GraphReadinessCoordinator coordinator = coordinator(probe, now, scheduler);
        coordinator.verifyStartup(PROBE_TIMEOUT, MODE);
        coordinator.bindWorkerPolling(
                () -> {},
                () -> {
                    if (resumes.incrementAndGet() == 1) {
                        throw new IllegalStateException("resume unavailable");
                    }
                });
        coordinator.startMonitoring();

        scheduler.tick();
        scheduler.tick();
        assertUnavailable(coordinator, GraphReadinessCoordinator.PollingReconciliation.RESUME_PENDING);
        assertThat(probe.calls()).isEqualTo(3);

        now.addAndGet(settings().freshness().toNanos() + 1);
        scheduler.tick();

        assertThat(probe.calls()).isEqualTo(4);
        assertThat(resumes).hasValue(2);
        assertThat(coordinator.snapshot().availability())
                .isEqualTo(GraphReadinessCoordinator.Availability.AVAILABLE);
        coordinator.close();
    }

    @Test
    void closeDuringPendingCallbackWinsAndForbidsRecoveryPublication() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedProbe probe = new ScriptedProbe(false, true);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicInteger resumes = new AtomicInteger();
        GraphReadinessCoordinator coordinator = coordinator(probe, new AtomicLong(40), scheduler);
        coordinator.verifyStartup(PROBE_TIMEOUT, MODE);
        coordinator.bindWorkerPolling(
                () -> {
                    callbackEntered.countDown();
                    await(releaseCallback);
                },
                resumes::incrementAndGet);
        coordinator.startMonitoring();

        ExecutorService callbackThread = Executors.newSingleThreadExecutor();
        try {
            Future<?> failureTick = callbackThread.submit(scheduler::tick);
            assertThat(callbackEntered.await(2, TimeUnit.SECONDS)).isTrue();

            coordinator.close();
            releaseCallback.countDown();
            failureTick.get(2, TimeUnit.SECONDS);

            assertThat(coordinator.snapshot().availability())
                    .isEqualTo(GraphReadinessCoordinator.Availability.CLOSED);
            assertThat(coordinator.snapshot().monitoring()).isFalse();
            assertThat(resumes).hasValue(0);
            assertThatThrownBy(coordinator::requireCommandAdmission)
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            releaseCallback.countDown();
            callbackThread.shutdownNow();
            coordinator.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedProbeBlocksNewHttpSubmissionWithoutCancellingInFlightStream() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedProbe probe = new ScriptedProbe(false, true);
        GraphReadinessCoordinator coordinator = coordinator(probe, new AtomicLong(45), scheduler);
        coordinator.verifyStartup(PROBE_TIMEOUT, MODE);
        coordinator.bindWorkerPolling(() -> {}, () -> {});
        coordinator.startMonitoring();

        URI uri = URI.create("https://graph.example.test/internal/graphs/commands/stream");
        BlockingInputStream body = new BlockingInputStream();
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.uri()).thenReturn(uri);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(
                java.util.Map.of(), (_name, _value) -> true));
        when(response.body()).thenReturn(body);
        HttpClient client = mock(HttpClient.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(client.<InputStream>sendAsync(any(HttpRequest.class), any()))
                .thenReturn(CompletableFuture.completedFuture(response));
        JdkGraphCommandHttpTransport transport = new JdkGraphCommandHttpTransport(
                client, GraphTransportSecurityProof.unverified(), coordinator);
        GraphCommandHttpTransport.Request request = new GraphCommandHttpTransport.Request(
                uri,
                java.util.Map.of(),
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Duration.ofSeconds(2),
                1024,
                4096);

        ExecutorService streamThread = Executors.newSingleThreadExecutor();
        try {
            Future<?> inFlight = streamThread.submit(() -> transport.stream(
                    request, new AgentRunCancellationToken(), noOpListener()));
            body.awaitRead();

            scheduler.tick();
            assertUnavailable(coordinator, GraphReadinessCoordinator.PollingReconciliation.SUSPENDED);
            assertThat(inFlight.isDone()).isFalse();
            assertThat(body.closeCalls()).isZero();
            assertThatThrownBy(() -> transport.stream(
                            request, new AgentRunCancellationToken(), noOpListener()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("admission");
            verify(client, times(1))
                    .<InputStream>sendAsync(any(HttpRequest.class), any());

            body.release();
            inFlight.get(2, TimeUnit.SECONDS);
            assertThat(body.closeCalls()).isEqualTo(1);
        } finally {
            body.release();
            streamThread.shutdownNow();
            coordinator.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void commandSubmissionRegistrationLinearizesBeforeFailurePublication() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger probeCalls = new AtomicInteger();
        CountDownLatch failureProbeEntered = new CountDownLatch(1);
        GraphReadinessCoordinator coordinator = coordinator(
                (_timeout, _mode) -> {
                    if (probeCalls.incrementAndGet() > 1) {
                        failureProbeEntered.countDown();
                        throw new IllegalStateException("periodic failure");
                    }
                },
                new AtomicLong(46),
                scheduler);
        coordinator.verifyStartup(PROBE_TIMEOUT, MODE);
        coordinator.bindWorkerPolling(() -> {}, () -> {});
        coordinator.startMonitoring();

        URI uri = URI.create("https://graph.example.test/internal/graphs/commands/stream");
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.uri()).thenReturn(uri);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(
                java.util.Map.of(), (_name, _value) -> true));
        when(response.body()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        HttpClient client = mock(HttpClient.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(client.<InputStream>sendAsync(any(HttpRequest.class), any()))
                .thenAnswer(_invocation -> {
                    sendEntered.countDown();
                    await(releaseSend);
                    return CompletableFuture.completedFuture(response);
                });
        JdkGraphCommandHttpTransport transport = new JdkGraphCommandHttpTransport(
                client, GraphTransportSecurityProof.unverified(), coordinator);
        GraphCommandHttpTransport.Request request = new GraphCommandHttpTransport.Request(
                uri,
                java.util.Map.of(),
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Duration.ofSeconds(2),
                1024,
                4096);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch failureTickStarted = new CountDownLatch(1);
        try {
            Future<?> stream = threads.submit(() -> transport.stream(
                    request, new AgentRunCancellationToken(), noOpListener()));
            assertThat(sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> failureTick = threads.submit(() -> {
                failureTickStarted.countDown();
                scheduler.tick();
            });
            assertThat(failureTickStarted.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(failureTick.isDone()).isFalse();

            releaseSend.countDown();
            stream.get(2, TimeUnit.SECONDS);
            failureTick.get(2, TimeUnit.SECONDS);
            assertThat(failureProbeEntered.getCount()).isZero();
            assertUnavailable(coordinator, GraphReadinessCoordinator.PollingReconciliation.SUSPENDED);
            verify(client, times(1))
                    .<InputStream>sendAsync(any(HttpRequest.class), any());
        } finally {
            releaseSend.countDown();
            threads.shutdownNow();
            coordinator.close();
        }
    }

    @Test
    void unavailableReconciliationWaitsAndRegistersOnceAfterFreshRecovery() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedProbe probe = new ScriptedProbe(false, true, false);
        GraphReadinessCoordinator coordinator =
                coordinator(probe, new AtomicLong(47), scheduler);
        coordinator.verifyStartup(PROBE_TIMEOUT, MODE);
        coordinator.bindWorkerPolling(() -> {}, () -> {});
        coordinator.startMonitoring();
        scheduler.tick();
        assertUnavailable(
                coordinator, GraphReadinessCoordinator.PollingReconciliation.SUSPENDED);

        AtomicInteger registrations = new AtomicInteger();
        CountDownLatch waitEntered = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> pending = caller.submit(() -> coordinator.submitReconciliation(
                    () -> {
                        waitEntered.countDown();
                        return TimeUnit.SECONDS.toNanos(2);
                    },
                    () -> false,
                    () -> {
                        registrations.incrementAndGet();
                        return "registered";
                    }));
            assertThat(waitEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(pending.isDone()).isFalse();
            assertThat(registrations).hasValue(0);
            assertThatThrownBy(coordinator::requireCommandAdmission)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("admission");

            scheduler.tick();

            assertThat(pending.get(2, TimeUnit.SECONDS)).isEqualTo("registered");
            assertThat(registrations).hasValue(1);
            assertThat(coordinator.snapshot().availability())
                    .isEqualTo(GraphReadinessCoordinator.Availability.AVAILABLE);
            assertThat(probe.calls()).isEqualTo(3);
        } finally {
            caller.shutdownNow();
            coordinator.close();
        }
    }

    @Test
    void reconciliationRegistrationLinearizesBeforeFailurePublication() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        GraphReadinessCoordinator coordinator = coordinator(
                new ScriptedProbe(false, true),
                new AtomicLong(48),
                scheduler);
        coordinator.verifyStartup(PROBE_TIMEOUT, MODE);
        coordinator.bindWorkerPolling(() -> {}, () -> {});
        coordinator.startMonitoring();

        CountDownLatch registrationEntered = new CountDownLatch(1);
        CountDownLatch releaseRegistration = new CountDownLatch(1);
        CountDownLatch failureTickStarted = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<String> registration = threads.submit(() -> coordinator.submitReconciliation(
                    () -> TimeUnit.SECONDS.toNanos(2),
                    () -> false,
                    () -> {
                        registrationEntered.countDown();
                        await(releaseRegistration);
                        return "registered";
                    }));
            assertThat(registrationEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> failureTick = threads.submit(() -> {
                failureTickStarted.countDown();
                scheduler.tick();
            });
            assertThat(failureTickStarted.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(failureTick.isDone()).isFalse();

            releaseRegistration.countDown();
            assertThat(registration.get(2, TimeUnit.SECONDS)).isEqualTo("registered");
            failureTick.get(2, TimeUnit.SECONDS);
            assertUnavailable(
                    coordinator, GraphReadinessCoordinator.PollingReconciliation.SUSPENDED);
        } finally {
            releaseRegistration.countDown();
            threads.shutdownNow();
            coordinator.close();
        }
    }

    @Test
    void reconciliationCancellationAndDeadlineBeforeRecoveryRegisterNothing()
            throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        GraphReadinessCoordinator coordinator = coordinator(
                new ScriptedProbe(false, true), new AtomicLong(49), scheduler);
        coordinator.verifyStartup(PROBE_TIMEOUT, MODE);
        coordinator.bindWorkerPolling(() -> {}, () -> {});
        coordinator.startMonitoring();
        scheduler.tick();
        assertUnavailable(
                coordinator, GraphReadinessCoordinator.PollingReconciliation.SUSPENDED);

        AtomicInteger registrations = new AtomicInteger();
        AtomicBoolean cancellationRequested = new AtomicBoolean();
        CountDownLatch waitEntered = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> cancelled = caller.submit(() -> coordinator.submitReconciliation(
                    () -> {
                        waitEntered.countDown();
                        return TimeUnit.SECONDS.toNanos(2);
                    },
                    cancellationRequested::get,
                    registrations::incrementAndGet));
            assertThat(waitEntered.await(2, TimeUnit.SECONDS)).isTrue();
            cancellationRequested.set(true);

            assertThatThrownBy(() -> cancelled.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseMessage("Graph reconciliation wait was cancelled");
            assertThatThrownBy(() -> coordinator.submitReconciliation(
                            () -> 0L,
                            () -> false,
                            registrations::incrementAndGet))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("timed out");
            assertThat(registrations).hasValue(0);
            assertThatThrownBy(coordinator::requireCommandAdmission)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("admission");
        } finally {
            caller.shutdownNow();
            coordinator.close();
        }
    }

    @Test
    void stopAndCloseAreIdempotentAndSchedulerRegistrationFailureIsNotMonitoring() {
        ManualScheduler scheduler = new ManualScheduler();
        GraphReadinessCoordinator coordinator = coordinator(
                (_timeout, _mode) -> {}, new AtomicLong(50), scheduler);
        coordinator.verifyStartup(PROBE_TIMEOUT, MODE);
        coordinator.bindWorkerPolling(() -> {}, () -> {});
        coordinator.startMonitoring();
        coordinator.startMonitoring();
        assertThat(scheduler.registrations()).isEqualTo(1);

        coordinator.close();
        coordinator.close();
        assertThat(scheduler.shutdownNowCalls()).isEqualTo(1);
        assertThat(coordinator.snapshot().availability())
                .isEqualTo(GraphReadinessCoordinator.Availability.CLOSED);

        ManualScheduler rejecting = new ManualScheduler();
        rejecting.rejectRegistration();
        GraphReadinessCoordinator rejected = coordinator(
                (_timeout, _mode) -> {}, new AtomicLong(51), rejecting);
        rejected.verifyStartup(PROBE_TIMEOUT, MODE);
        rejected.bindWorkerPolling(() -> {}, () -> {});
        assertThatThrownBy(rejected::startMonitoring)
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(rejected.snapshot().monitoring()).isFalse();
        rejected.close();
    }

    private static GraphReadinessCoordinator coordinator(
            GraphReadinessCoordinator.Probe probe,
            AtomicLong now,
            ManualScheduler scheduler) {
        return new GraphReadinessCoordinator(settings(), probe, now::get, scheduler);
    }

    private static GraphReadinessCoordinator.Settings settings() {
        return settings(INTERVAL, PROBE_TIMEOUT);
    }

    private static GraphReadinessCoordinator.Settings settings(
            Duration interval, Duration timeout) {
        return new GraphReadinessCoordinator.Settings(interval, timeout, MODE);
    }

    private static void assertUnavailable(
            GraphReadinessCoordinator coordinator,
            GraphReadinessCoordinator.PollingReconciliation expectedPolling) {
        assertThat(coordinator.snapshot().availability())
                .isEqualTo(GraphReadinessCoordinator.Availability.UNAVAILABLE);
        assertThat(coordinator.snapshot().polling()).isEqualTo(expectedPolling);
        assertThatThrownBy(coordinator::requireCommandAdmission)
                .isInstanceOf(IllegalStateException.class);
    }

    private static GraphCommandHttpTransport.Listener noOpListener() {
        return new GraphCommandHttpTransport.Listener() {
            @Override
            public void onResponse(GraphCommandHttpTransport.ResponseHead response) {}

            @Override
            public void onLine(String line) {}
        };
    }

    private static void awaitProbeJoin(GraphReadinessCoordinator coordinator) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!coordinator.snapshot().probeInFlight() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(coordinator.snapshot().probeInFlight()).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test callback was not released");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static final class BlockingProbe implements GraphReadinessCoordinator.Probe {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);

        @Override
        public void verify(Duration timeout, String expectedMode) {
            assertThat(timeout).isEqualTo(PROBE_TIMEOUT);
            assertThat(expectedMode).isEqualTo(MODE);
            int call = calls.incrementAndGet();
            if (call > 1) {
                entered.countDown();
                await(release);
            }
        }

        void blockNext() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        void awaitBlocked() throws InterruptedException {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        }

        void release() {
            release.countDown();
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class BlockingInputStream extends InputStream {

        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int read() {
            readEntered.countDown();
            await(release);
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return read();
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            super.close();
        }

        void awaitRead() throws InterruptedException {
            assertThat(readEntered.await(2, TimeUnit.SECONDS)).isTrue();
        }

        void release() {
            release.countDown();
        }

        int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class ScriptedProbe implements GraphReadinessCoordinator.Probe {

        private final boolean[] failures;
        private final AtomicInteger calls = new AtomicInteger();

        private ScriptedProbe(boolean... failures) {
            this.failures = failures;
        }

        @Override
        public void verify(Duration timeout, String expectedMode) {
            assertThat(timeout).isEqualTo(PROBE_TIMEOUT);
            assertThat(expectedMode).isEqualTo(MODE);
            int call = calls.getAndIncrement();
            if (call < failures.length && failures[call]) {
                throw new IllegalStateException("scripted probe failure " + call);
            }
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class ManualScheduler extends AbstractExecutorService
            implements ScheduledExecutorService {

        private Runnable fixedDelayTask;
        private Duration initialDelay;
        private Duration delay;
        private boolean shutdown;
        private boolean rejectRegistration;
        private int registrations;
        private int shutdownNowCalls;

        void tick() {
            if (fixedDelayTask == null) {
                throw new IllegalStateException("fixed-delay task was not registered");
            }
            fixedDelayTask.run();
        }

        void rejectRegistration() {
            rejectRegistration = true;
        }

        int registrations() {
            return registrations;
        }

        int shutdownNowCalls() {
            return shutdownNowCalls;
        }

        Duration initialDelay() {
            return initialDelay;
        }

        Duration delay() {
            return delay;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            if (rejectRegistration) {
                throw new RejectedExecutionException("registration rejected");
            }
            this.fixedDelayTask = command;
            this.initialDelay = Duration.ofNanos(unit.toNanos(initialDelay));
            this.delay = Duration.ofNanos(unit.toNanos(delay));
            registrations++;
            return new ManualScheduledFuture();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(
                Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            shutdownNowCalls++;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new RejectedExecutionException("scheduler is shut down");
            }
            command.run();
        }
    }

    private static final class ManualScheduledFuture implements ScheduledFuture<Object> {

        private final CompletableFuture<Object> completion = new CompletableFuture<>();

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return completion.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return completion.isCancelled();
        }

        @Override
        public boolean isDone() {
            return completion.isDone();
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return completion.get();
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return completion.get(timeout, unit);
        }
    }
}
