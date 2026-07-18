package com.example.dispute.workflow.activity.agent;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.temporal.agentrun.AgentRunTemporalPolicy;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.ActivityCompletionException;
import io.temporal.failure.ApplicationFailure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Executes one idempotent graph command and leaves formal domain finalization to a later Activity. */
public final class ExecuteAgentRunActivityImpl implements ExecuteAgentRunActivity {

    public static final String RETRYABLE_FAILURE_TYPE = "AgentRunRetryableFailure";
    public static final String NON_RETRYABLE_FAILURE_TYPE = "AgentRunNonRetryableFailure";

    private static final AtomicLong HEARTBEAT_THREAD_SEQUENCE = new AtomicLong();

    private final AgentRunLedger ledger;
    private final AgentRunExecutionGateway gateway;
    private final AgentRunActivityContextProvider contextProvider;
    private final Clock clock;
    private final Duration heartbeatInterval;
    private final Supplier<ScheduledExecutorService> schedulerFactory;

    public ExecuteAgentRunActivityImpl(
            AgentRunLedger ledger,
            AgentRunExecutionGateway gateway) {
        this(
                ledger,
                gateway,
                new TemporalAgentRunActivityContextProvider(),
                Clock.systemUTC(),
                AgentRunTemporalPolicy.PROGRESS_HEARTBEAT_INTERVAL,
                ExecuteAgentRunActivityImpl::newHeartbeatScheduler);
    }

    public ExecuteAgentRunActivityImpl(
            AgentRunLedger ledger,
            AgentRunExecutionGateway gateway,
            AgentRunActivityContextProvider contextProvider,
            Clock clock,
            Duration heartbeatInterval,
            Supplier<ScheduledExecutorService> schedulerFactory) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.contextProvider = Objects.requireNonNull(contextProvider, "contextProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.schedulerFactory = Objects.requireNonNull(schedulerFactory, "schedulerFactory");
    }

    @Override
    public ExecuteAgentRunResult execute(ExecuteAgentRunRequest request) {
        if (request == null) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "agent run request is required", NON_RETRYABLE_FAILURE_TYPE);
        }
        AgentRunActivityContext context =
                Objects.requireNonNull(contextProvider.current(), "activityContext");
        if (context.temporalAttempt() < 1) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "invalid Temporal Activity attempt", NON_RETRYABLE_FAILURE_TYPE);
        }

        AgentRunLedger.Attempt attempt = startAttempt(request, context);
        validateAttempt(request, attempt);
        ExecutionMode executionMode = executionMode(request, attempt, context);

        AgentRunCancellationToken cancellationToken = new AgentRunCancellationToken();
        AgentRunHeartbeatMonitor heartbeat = new AgentRunHeartbeatMonitor(
                request,
                attempt,
                ledger,
                context,
                clock,
                heartbeatInterval,
                cancellationToken,
                schedulerFactory.get());
        try (heartbeat) {
            heartbeat.start();
            AgentRunExecutionGateway.Completion completion =
                    gateway.execute(
                            request,
                            executionMode,
                            heartbeat::progress,
                            cancellationToken);
            // A heartbeat can discover cancellation while the adapter is returning a late final.
            cancellationToken.throwIfCancellationRequested();
            validateCompletion(request, completion);
            heartbeat.progress(new AgentRunProgress(
                    completion.lastSequenceNo(),
                    completion.publicOutputEmitted(),
                    true));
            cancellationToken.throwIfCancellationRequested();

            RoomGraphResult graphResult = completion.graphResult();
            ExecuteAgentRunResult result = new ExecuteAgentRunResult(
                    ExecuteAgentRunResult.SCHEMA_VERSION,
                    request.agentRunId(),
                    request.logicalRunId(),
                    request.attemptId(),
                    request.attemptNo(),
                    ExecuteAgentRunResult.Outcome.COMPLETED,
                    graphResult,
                    graphResult.outputHash(),
                    completion.lastSequenceNo(),
                    heartbeat.snapshot().publicOutputEmitted(),
                    null,
                    false,
                    clock.instant());
            heartbeat.close();
            ledger.recordResultReady(result);
            return result;
        } catch (RuntimeException failure) {
            RuntimeException termination = cancellationToken.terminationCause();
            return handleFailure(
                    request,
                    attempt,
                    executionMode,
                    context,
                    heartbeat.snapshot(),
                    termination == null ? failure : termination);
        }
    }

    private AgentRunLedger.Attempt startAttempt(
            ExecuteAgentRunRequest request,
            AgentRunActivityContext context) {
        try {
            return ledger.startNextAttempt(request.agentRunId(), request, clock.instant());
        } catch (ActivityCompletionException completionFailure) {
            throw completionFailure;
        } catch (ApplicationFailure applicationFailure) {
            throw applicationFailure;
        } catch (IllegalArgumentException | IllegalStateException deterministicFailure) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "agent run attempt allocation was rejected",
                    NON_RETRYABLE_FAILURE_TYPE,
                    request.agentRunId(),
                    request.attemptId(),
                    "AGENT_RUN_ATTEMPT_REJECTED");
        } catch (RuntimeException infrastructureFailure) {
            int retryLimit = Math.max(1, allowedActivityAttempts(request.command()));
            if (clock.instant().isBefore(request.command().deadlineAt())
                    && context.temporalAttempt() < retryLimit) {
                throw retryFailure(
                        request,
                        context.temporalAttempt(),
                        "AGENT_RUN_ATTEMPT_ALLOCATION_FAILED");
            }
            throw ApplicationFailure.newNonRetryableFailure(
                    "agent run attempt allocation failed",
                    NON_RETRYABLE_FAILURE_TYPE,
                    request.agentRunId(),
                    request.attemptId(),
                    "AGENT_RUN_ATTEMPT_ALLOCATION_FAILED");
        }
    }

    private ExecuteAgentRunResult handleFailure(
            ExecuteAgentRunRequest request,
            AgentRunLedger.Attempt attempt,
            ExecutionMode executionMode,
            AgentRunActivityContext context,
            AgentRunProgress heartbeat,
            RuntimeException failure) {
        if (failure instanceof ActivityCanceledException cancelled) {
            recordCancellationPreserving(request, cancelled);
            throw cancelled;
        }
        if (failure instanceof ActivityCompletionException completionFailure) {
            // Worker shutdown/not-found must leave the attempt recoverable by a compatible worker.
            throw completionFailure;
        }

        AgentRunExecutionException executionFailure;
        if (failure instanceof AgentRunExecutionException typed) {
            executionFailure = typed;
        } else if (failure instanceof IllegalArgumentException) {
            executionFailure = AgentRunExecutionException.nonRetryable(
                    "AGENT_RUN_REQUEST_OR_RESULT_INVALID",
                    "agent run request or result is invalid",
                    heartbeat.lastSequenceNo(),
                    heartbeat.publicOutputEmitted(),
                    failure);
        } else {
            executionFailure = AgentRunExecutionException.retryable(
                    "AGENT_RUN_ACTIVITY_FAILED",
                    "agent run activity failed",
                    heartbeat.finalFrameObserved(),
                    heartbeat.lastSequenceNo(),
                    heartbeat.publicOutputEmitted(),
                    failure);
        }
        long lastSequenceNo =
                Math.max(heartbeat.lastSequenceNo(), executionFailure.lastSequenceNo());
        boolean publicOutputEmitted =
                heartbeat.publicOutputEmitted() || executionFailure.publicOutputEmitted();
        boolean completionObserved =
                attempt.status() == AgentRunAttemptStatus.RESULT_READY
                        || heartbeat.finalFrameObserved();
        boolean safeAfterVisibleOutput =
                executionFailure.commandReplaySafe()
                        || completionObserved
                        || executionMode == ExecutionMode.RECONCILE_ONLY;
        boolean withinRecoveryWindow =
                completionObserved || clock.instant().isBefore(request.command().deadlineAt());
        int retryLimit = Math.max(1, allowedActivityAttempts(request.command()));
        boolean retryable =
                executionFailure.retryable()
                        && (!publicOutputEmitted || safeAfterVisibleOutput)
                        && withinRecoveryWindow
                        && context.temporalAttempt() < retryLimit;
        if (retryable) {
            // Stable request/command/attempt identities make this a command-ledger replay, not a
            // second logical run. Temporal applies the bounded, deterministic jittered delay.
            throw retryFailure(
                    request,
                    context.temporalAttempt(),
                    executionFailure.errorCode());
        }

        if (completionObserved) {
            // RESULT_READY/final-observed is forward-only. Keep it recoverable for a later
            // workflow or operator instead of corrupting it into a terminal failure.
            throw ApplicationFailure.newNonRetryableFailure(
                    "completed agent run result could not be reconciled",
                    NON_RETRYABLE_FAILURE_TYPE,
                    request.agentRunId(),
                    request.attemptId(),
                    executionFailure.errorCode());
        }

        AgentRunAttemptStatus status =
                publicOutputEmitted
                        ? AgentRunAttemptStatus.ABORTED
                        : AgentRunAttemptStatus.FAILED;
        try {
            ledger.recordAttemptFailure(
                    request.agentRunId(),
                    request.attemptId(),
                    request.attemptNo(),
                    status,
                    executionFailure.errorCode(),
                    false,
                    clock.instant());
        } catch (RuntimeException persistenceFailure) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "agent run terminal failure could not be persisted",
                    NON_RETRYABLE_FAILURE_TYPE,
                    request.agentRunId(),
                    request.attemptId(),
                    executionFailure.errorCode());
        }
        return failedResult(
                request,
                executionFailure.errorCode(),
                lastSequenceNo,
                publicOutputEmitted);
    }

    private ExecuteAgentRunResult failedResult(
            ExecuteAgentRunRequest request,
            String errorCode,
            long lastSequenceNo,
            boolean publicOutputEmitted) {
        return new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                request.agentRunId(),
                request.logicalRunId(),
                request.attemptId(),
                request.attemptNo(),
                ExecuteAgentRunResult.Outcome.FAILED,
                null,
                null,
                lastSequenceNo,
                publicOutputEmitted,
                errorCode,
                false,
                clock.instant());
    }

    private void recordCancellationPreserving(
            ExecuteAgentRunRequest request,
            ActivityCanceledException cancellation) {
        try {
            ledger.recordAttemptFailure(
                    request.agentRunId(),
                    request.attemptId(),
                    request.attemptNo(),
                    AgentRunAttemptStatus.CANCELLED,
                    "AGENT_RUN_CANCELLED",
                    false,
                    clock.instant());
        } catch (RuntimeException ledgerFailure) {
            cancellation.addSuppressed(ledgerFailure);
        }
    }

    private static void validateAttempt(
            ExecuteAgentRunRequest request,
            AgentRunLedger.Attempt attempt) {
        if (attempt == null
                || !request.agentRunId().equals(attempt.agentRunId())
                || !request.attemptId().equals(attempt.attemptId())
                || request.attemptNo() != attempt.attemptNo()) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "attempt identity does not match the execution request",
                    NON_RETRYABLE_FAILURE_TYPE);
        }
        if (attempt.status() != AgentRunAttemptStatus.RUNNING
                && attempt.status() != AgentRunAttemptStatus.RESULT_READY) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "attempt is not executable", NON_RETRYABLE_FAILURE_TYPE);
        }
    }

    private static void validateCompletion(
            ExecuteAgentRunRequest request,
            AgentRunExecutionGateway.Completion completion) {
        if (completion == null) {
            throw AgentRunExecutionException.nonRetryable(
                    "AGENT_RUN_RESULT_MISSING",
                    "execution gateway returned no completion",
                    0,
                    false,
                    null);
        }
        RoomGraphCommand command = request.command();
        RoomGraphResult result = completion.graphResult();
        if (!command.commandId().equals(result.commandId())
                || !request.logicalRunId().equals(result.logicalRunId())
                || !request.attemptId().equals(result.attemptId())
                || !command.graphKey().equals(result.graphKey())
                || !command.graphVersion().equals(result.graphVersion())
                || result.outputHash() == null
                || !result.outputHash().matches("[0-9a-f]{64}")) {
            throw AgentRunExecutionException.nonRetryable(
                    "AGENT_RUN_RESULT_IDENTITY_MISMATCH",
                    "graph result identity does not match the command",
                    completion.lastSequenceNo(),
                    completion.publicOutputEmitted(),
                    null);
        }
    }

    private static int allowedActivityAttempts(RoomGraphCommand command) {
        return AgentRunTemporalPolicy.boundedActivityAttempts(
                command.retryBudget().activityAttemptsRemaining());
    }

    private ExecutionMode executionMode(
            ExecuteAgentRunRequest request,
            AgentRunLedger.Attempt attempt,
            AgentRunActivityContext context) {
        int allowedAttempts = allowedActivityAttempts(request.command());
        boolean executionWindowClosed =
                allowedAttempts < 1
                        || context.temporalAttempt() > allowedAttempts
                        || !clock.instant().isBefore(request.command().deadlineAt());
        boolean existingVisibleAttempt =
                attempt.status() == AgentRunAttemptStatus.RESULT_READY
                        || attempt.publicOutputEmitted();
        return executionWindowClosed || existingVisibleAttempt
                ? ExecutionMode.RECONCILE_ONLY
                : ExecutionMode.EXECUTE_OR_RECONCILE;
    }

    private ApplicationFailure retryFailure(
            ExecuteAgentRunRequest request,
            int temporalAttempt,
            String errorCode) {
        ApplicationFailure retryFailure = ApplicationFailure.newFailure(
                "agent run infrastructure failure",
                RETRYABLE_FAILURE_TYPE,
                request.agentRunId(),
                request.attemptId(),
                errorCode);
        retryFailure.setNextRetryDelay(retryDelay(request.command(), temporalAttempt));
        return retryFailure;
    }

    private Duration retryDelay(RoomGraphCommand command, int temporalAttempt) {
        int exponent = Math.min(4, Math.max(0, temporalAttempt - 1));
        long baseMillis = 1_000L << exponent;
        int jitterPercent = 80 + Math.floorMod(
                Objects.hash(command.commandId(), temporalAttempt),
                41);
        long jitteredMillis = Math.min(30_000L, baseMillis * jitterPercent / 100L);
        long remainingMillis = Math.max(
                1L,
                Duration.between(clock.instant(), command.deadlineAt()).toMillis());
        return Duration.ofMillis(Math.min(jitteredMillis, remainingMillis));
    }

    private static ScheduledExecutorService newHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual()
                        .name(
                                "agent-run-heartbeat-"
                                        + HEARTBEAT_THREAD_SEQUENCE.incrementAndGet())
                        .factory());
    }
}
