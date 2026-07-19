package com.example.dispute.workflow.activity.agent;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Maintains monotonic durable and Temporal heartbeats for an active attempt. */
public final class AgentRunHeartbeatMonitor implements AutoCloseable {

    private final Object stateLock = new Object();
    private final Object heartbeatLock = new Object();
    private final ExecuteAgentRunRequest request;
    private final AgentRunLedger ledger;
    private final AgentRunActivityContext activityContext;
    private final Clock clock;
    private final Duration interval;
    private final AgentRunCancellationToken cancellationToken;
    private final ScheduledExecutorService scheduler;

    private AgentRunProgress progress;
    private boolean started;
    private boolean closed;
    private ScheduledFuture<?> scheduledHeartbeat;

    public AgentRunHeartbeatMonitor(
            ExecuteAgentRunRequest request,
            AgentRunLedger.Attempt attempt,
            AgentRunLedger ledger,
            AgentRunActivityContext activityContext,
            Clock clock,
            Duration interval,
            AgentRunCancellationToken cancellationToken,
            ScheduledExecutorService scheduler) {
        this.request = Objects.requireNonNull(request, "request");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.activityContext = Objects.requireNonNull(activityContext, "activityContext");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("heartbeat interval must be positive");
        }
        AgentRunLedger.Attempt initialAttempt = Objects.requireNonNull(attempt, "attempt");
        boolean finalObserved =
                initialAttempt.finalFrameObserved()
                        || initialAttempt.status() == AgentRunAttemptStatus.RESULT_READY
                        || initialAttempt.status() == AgentRunAttemptStatus.COMPLETED;
        this.progress = new AgentRunProgress(
                initialAttempt.lastSequenceNo(),
                initialAttempt.publicOutputEmitted(),
                finalObserved);
    }

    /** Sends the initial heartbeat before opening the Python stream. */
    public void start() {
        synchronized (stateLock) {
            if (started) {
                throw new IllegalStateException("heartbeat monitor already started");
            }
            if (closed) {
                throw new IllegalStateException("heartbeat monitor is closed");
            }
            started = true;
        }
        heartbeatNow();
        long intervalNanos = interval.toNanos();
        scheduledHeartbeat = scheduler.scheduleAtFixedRate(
                this::periodicHeartbeat,
                intervalNanos,
                intervalNanos,
                TimeUnit.NANOSECONDS);
    }

    /** Records meaningful durable progress and immediately heartbeats it to Temporal. */
    public void progress(AgentRunProgress update) {
        Objects.requireNonNull(update, "update");
        cancellationToken.throwIfCancellationRequested();
        synchronized (stateLock) {
            requireActive();
            if (update.lastSequenceNo() < progress.lastSequenceNo()) {
                throw AgentRunExecutionException.failLogicalRun(
                        "AGENT_RUN_PROGRESS_REGRESSED",
                        "agent run progress sequence regressed",
                        progress.lastSequenceNo(),
                        progress.publicOutputEmitted(),
                        null);
            }
            progress = new AgentRunProgress(
                    update.lastSequenceNo(),
                    progress.publicOutputEmitted() || update.publicOutputEmitted(),
                    progress.finalFrameObserved() || update.finalFrameObserved());
        }
        heartbeatNow();
    }

    public void heartbeatNow() {
        synchronized (heartbeatLock) {
            cancellationToken.throwIfCancellationRequested();
            AgentRunAttemptHeartbeat heartbeat;
            synchronized (stateLock) {
                requireActive();
                heartbeat = new AgentRunAttemptHeartbeat(
                        AgentRunAttemptHeartbeat.SCHEMA_VERSION,
                        request.agentRunId(),
                        request.attemptId(),
                        request.attemptNo(),
                        progress.lastSequenceNo(),
                        progress.publicOutputEmitted(),
                        progress.finalFrameObserved(),
                        clock.instant());
            }
            try {
                // PostgreSQL is the recovery source; Temporal gets the same public-only snapshot.
                ledger.recordHeartbeat(heartbeat);
                activityContext.heartbeat(heartbeat);
            } catch (RuntimeException failure) {
                cancellationToken.requestCancellation(failure);
                throw failure;
            }
        }
    }

    public AgentRunProgress snapshot() {
        synchronized (stateLock) {
            return progress;
        }
    }

    private void periodicHeartbeat() {
        if (cancellationToken.isCancellationRequested()) {
            return;
        }
        try {
            heartbeatNow();
        } catch (RuntimeException ignored) {
            // The cause is retained by the token and closes the registered stream transport.
        }
    }

    private void requireActive() {
        if (!started) {
            throw new IllegalStateException("heartbeat monitor is not started");
        }
        if (closed) {
            throw new IllegalStateException("heartbeat monitor is closed");
        }
    }

    @Override
    public void close() {
        ScheduledFuture<?> heartbeat;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            heartbeat = scheduledHeartbeat;
        }
        if (heartbeat != null) {
            heartbeat.cancel(true);
        }
        // Taking this lock waits for an in-flight heartbeat before the terminal transition.
        synchronized (heartbeatLock) {
            scheduler.shutdownNow();
        }
    }
}
