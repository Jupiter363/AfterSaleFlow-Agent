package com.example.dispute.workflow.activity.agent;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.example.dispute.workflow.temporal.agentrun.AgentRunTemporalPolicy;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.ActivityCompletionException;
import io.temporal.failure.ApplicationFailure;
import jakarta.persistence.OptimisticLockException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;

/** Executes one idempotent graph command and leaves formal domain finalization to a later Activity. */
public final class ExecuteAgentRunActivityImpl implements ExecuteAgentRunActivity {

    public static final String RETRYABLE_FAILURE_TYPE = "AgentRunRetryableFailure";
    public static final String NON_RETRYABLE_FAILURE_TYPE = "AgentRunNonRetryableFailure";

    private static final Logger LOG = LoggerFactory.getLogger(ExecuteAgentRunActivityImpl.class);
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

        AgentRunLedger.Attempt attempt = loadAttempt(request, context);
        validateAttempt(request, attempt);
        if (attempt.durableFailureResult() != null) {
            return attempt.durableFailureResult();
        }
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
            validateCompletion(request, completion);
            // A valid Completion proves a durable public final. From here the attempt is forward-only.
            if (completion != null) {
                heartbeat.durableFinal(new AgentRunProgress(
                        completion.lastSequenceNo(), completion.publicOutputEmitted(), true));
            }

            ExecuteAgentRunResult durableResult = completion.durableResult();
            if (durableResult != null) {
                if (!ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())
                        || !"agent-stream.v4".equals(request.streamProtocol())) {
                    throw AgentRunExecutionException.failLogicalRun(
                            "AGENT_RUN_DURABLE_RESULT_PROFILE_INVALID",
                            "only the explicit parallel Intake lane may return a durable result",
                            completion.lastSequenceNo(),
                            completion.publicOutputEmitted(),
                            null);
                }
                heartbeat.close();
                return durableResult;
            }

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
                    null,
                    durableTimestamp());
            heartbeat.close();
            ledger.recordResultReady(result);
            return result;
        } catch (RuntimeException failure) {
            AgentRunProgress durableProgress = heartbeat.snapshot();
            RuntimeException termination = cancellationToken.terminationCause();
            return handleFailure(
                    request,
                    attempt,
                    context,
                    durableProgress,
                    termination == null || durableProgress.finalFrameObserved()
                            ? failure
                            : termination);
        }
    }

    private AgentRunLedger.Attempt loadAttempt(
            ExecuteAgentRunRequest request,
            AgentRunActivityContext context) {
        try {
            return ledger.requireAllocatedAttempt(request);
        } catch (ActivityCompletionException completionFailure) {
            throw completionFailure;
        } catch (ApplicationFailure applicationFailure) {
            throw applicationFailure;
        } catch (IllegalArgumentException | IllegalStateException deterministicFailure) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "agent run attempt lineage was rejected",
                    NON_RETRYABLE_FAILURE_TYPE,
                    request.agentRunId(),
                    request.attemptId(),
                    "AGENT_RUN_LINEAGE_CONFLICT");
        } catch (RuntimeException infrastructureFailure) {
            int retryLimit = Math.max(1, allowedActivityAttempts(request.command()));
            if (clock.instant().isBefore(request.command().deadlineAt())
                    && context.temporalAttempt() < retryLimit) {
                ApplicationFailure retryFailure = ApplicationFailure.newFailureWithCause(
                        "agent run infrastructure failure",
                        RETRYABLE_FAILURE_TYPE,
                        sanitizedAttemptLoadCause(infrastructureFailure),
                        request.agentRunId(),
                        request.attemptId(),
                        "AGENT_RUN_ATTEMPT_LOAD_FAILED",
                        AgentRunRecoveryAction.RETRY_SAME_COMMAND.name());
                retryFailure.setNextRetryDelay(
                        retryDelay(request.command(), context.temporalAttempt()));
                throw retryFailure;
            }
            throw ApplicationFailure.newNonRetryableFailureWithCause(
                    "agent run attempt load failed",
                    NON_RETRYABLE_FAILURE_TYPE,
                    sanitizedAttemptLoadCause(infrastructureFailure),
                    request.agentRunId(),
                    request.attemptId(),
                    "AGENT_RUN_ATTEMPT_LOAD_FAILED");
        }
    }

    private static ApplicationFailure sanitizedAttemptLoadCause(
            RuntimeException infrastructureFailure) {
        Throwable rootCause = infrastructureFailure;
        String sqlState = "UNAVAILABLE";
        Throwable current = infrastructureFailure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            rootCause = current;
            if (current instanceof SQLException sqlFailure
                    && sqlFailure.getSQLState() != null
                    && sqlFailure.getSQLState().matches("[0-9A-Z]{5}")) {
                sqlState = sqlFailure.getSQLState();
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        ApplicationFailure sanitized = ApplicationFailure.newNonRetryableFailure(
                "sanitized agent run attempt load cause",
                infrastructureFailure.getClass().getName(),
                rootCause.getClass().getName(),
                sqlState,
                "MESSAGE_REDACTED");
        sanitized.setStackTrace(infrastructureFailure.getStackTrace());
        return sanitized;
    }

    private ExecuteAgentRunResult handleFailure(
            ExecuteAgentRunRequest request,
            AgentRunLedger.Attempt attempt,
            AgentRunActivityContext context,
            AgentRunProgress heartbeat,
            RuntimeException failure) {
        if (failure instanceof ActivityCanceledException cancelled
                && !heartbeat.finalFrameObserved()) {
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
            executionFailure = AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_REQUEST_OR_RESULT_INVALID",
                    "agent run request or result is invalid",
                    heartbeat.lastSequenceNo(),
                    heartbeat.publicOutputEmitted(),
                    failure);
        } else {
            executionFailure = heartbeat.finalFrameObserved()
                    ? AgentRunExecutionException.reconcileTerminal(
                            "AGENT_RUN_ACTIVITY_FAILED",
                            "agent run activity failed after a durable final",
                            heartbeat.lastSequenceNo(),
                            heartbeat.publicOutputEmitted(),
                            failure)
                    : AgentRunExecutionException.retrySameCommand(
                            "AGENT_RUN_ACTIVITY_FAILED",
                            "agent run activity failed",
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
        boolean withinRecoveryWindow =
                completionObserved || clock.instant().isBefore(request.command().deadlineAt());
        int retryLimit = Math.max(1, allowedActivityAttempts(request.command()));
        AgentRunRecoveryAction recoveryAction = executionFailure.recoveryAction();
        boolean sameAttemptRetryable =
                (recoveryAction == AgentRunRecoveryAction.RETRY_SAME_COMMAND
                                || recoveryAction
                                        == AgentRunRecoveryAction.RECONCILE_TERMINAL)
                        && withinRecoveryWindow
                        && context.temporalAttempt() < retryLimit;
        if (sameAttemptRetryable) {
            // Stable request/command/attempt identities make this a command-ledger replay, not a
            // second logical run. Temporal applies the bounded, deterministic jittered delay.
            throw retryFailure(
                    request,
                    context.temporalAttempt(),
                    executionFailure.errorCode(),
                    recoveryAction);
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
        boolean nextAttemptAllowed =
                recoveryAction == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT
                        && withinRecoveryWindow
                        && request.attemptNo() < request.attemptLimit();
        Instant completedAt = durableTimestamp();
        ExecuteAgentRunResult result =
                failedResult(
                        request,
                        executionFailure.errorCode(),
                        lastSequenceNo,
                        publicOutputEmitted,
                        nextAttemptAllowed
                                ? AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT
                                : AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                        completedAt);
        return persistTerminalFailureResult(
                request,
                status,
                result,
                executionFailure.errorCode());
    }

    /**
     * Persists the exact terminal projection without ever re-entering provider execution.
     *
     * <p>The ledger is a Spring proxy, so an exception from the first invocation is observed only
     * after that transaction has rolled back. A single replay is therefore safe for explicit
     * transaction conflicts and optimistic-lock races: it starts a fresh transaction and the
     * ledger's immutable result/event bindings make an ambiguously committed first invocation an
     * exact replay. Deterministic contract failures are never retried.
     */
    private ExecuteAgentRunResult persistTerminalFailureResult(
            ExecuteAgentRunRequest request,
            AgentRunAttemptStatus status,
            ExecuteAgentRunResult result,
            String executionErrorCode) {
        try {
            return ledger.recordAttemptFailureResult(status, result);
        } catch (RuntimeException firstFailure) {
            if (isRetryableTerminalPersistenceFailure(firstFailure)) {
                LOG.warn(
                        "agent_run_terminal_persistence_retry logical_run_id={} attempt_id={} "
                                + "error_code={} persistence_type={} sql_state={}",
                        request.agentRunId(),
                        request.attemptId(),
                        executionErrorCode,
                        firstFailure.getClass().getName(),
                        terminalPersistenceSqlState(firstFailure),
                        firstFailure);
                try {
                    return ledger.recordAttemptFailureResult(status, result);
                } catch (RuntimeException replayFailure) {
                    replayFailure.addSuppressed(firstFailure);
                    throw terminalPersistenceFailure(
                            request,
                            executionErrorCode,
                            replayFailure);
                }
            }
            throw terminalPersistenceFailure(
                    request,
                    executionErrorCode,
                    firstFailure);
        }
    }

    private static ApplicationFailure terminalPersistenceFailure(
            ExecuteAgentRunRequest request,
            String executionErrorCode,
            RuntimeException persistenceFailure) {
        LOG.error(
                "agent_run_terminal_persistence_failed logical_run_id={} attempt_id={} "
                        + "error_code={} persistence_type={} sql_state={}",
                request.agentRunId(),
                request.attemptId(),
                executionErrorCode,
                persistenceFailure.getClass().getName(),
                terminalPersistenceSqlState(persistenceFailure),
                persistenceFailure);
        return ApplicationFailure.newNonRetryableFailureWithCause(
                "agent run terminal failure could not be persisted",
                NON_RETRYABLE_FAILURE_TYPE,
                sanitizedTerminalPersistenceCause(persistenceFailure),
                request.agentRunId(),
                request.attemptId(),
                executionErrorCode);
    }

    private static boolean isRetryableTerminalPersistenceFailure(
            RuntimeException persistenceFailure) {
        Throwable current = persistenceFailure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof TransientDataAccessException
                    || current instanceof OptimisticLockException) {
                return true;
            }
            if (current instanceof SQLException sqlFailure
                    && hasRetryableTransactionSqlState(sqlFailure)) {
                return true;
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        return false;
    }

    private static boolean hasRetryableTransactionSqlState(SQLException failure) {
        SQLException current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if ("40001".equals(current.getSQLState())
                    || "40P01".equals(current.getSQLState())) {
                return true;
            }
            SQLException next = current.getNextException();
            current = next == current ? null : next;
        }
        return false;
    }

    private static String terminalPersistenceSqlState(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof SQLException sqlFailure) {
                SQLException chained = sqlFailure;
                for (int sqlDepth = 0;
                        chained != null && sqlDepth < 16;
                        sqlDepth++) {
                    String sqlState = chained.getSQLState();
                    if (sqlState != null && sqlState.matches("[0-9A-Z]{5}")) {
                        return sqlState;
                    }
                    SQLException next = chained.getNextException();
                    chained = next == chained ? null : next;
                }
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        return "UNAVAILABLE";
    }

    private static ApplicationFailure sanitizedTerminalPersistenceCause(
            RuntimeException persistenceFailure) {
        Throwable rootCause = persistenceFailure;
        Throwable current = persistenceFailure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            rootCause = current;
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        ApplicationFailure sanitized = ApplicationFailure.newNonRetryableFailure(
                "sanitized agent run terminal persistence cause",
                persistenceFailure.getClass().getName(),
                rootCause.getClass().getName(),
                terminalPersistenceSqlState(persistenceFailure),
                "MESSAGE_REDACTED");
        sanitized.setStackTrace(persistenceFailure.getStackTrace());
        return sanitized;
    }

    private ExecuteAgentRunResult failedResult(
            ExecuteAgentRunRequest request,
            String errorCode,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            AgentRunRecoveryAction recoveryAction,
            Instant completedAt) {
        boolean retryable = recoveryAction == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT;
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
                retryable,
                recoveryAction,
                completedAt);
    }

    private Instant durableTimestamp() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
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
                    AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
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
                || request.attemptNo() != attempt.attemptNo()
                || !request.logicalInputHash().equals(attempt.logicalInputHash())
                || !Objects.equals(request.previousAttemptId(), attempt.previousAttemptId())
                || request.resetRequired() != attempt.resetRequired()
                || request.publicSequenceOffset() != attempt.publicSequenceOffset()
                || !request.command().commandId().equals(attempt.commandId())
                || !request.command().requestHash().equals(attempt.commandRequestHash())) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "attempt identity does not match the execution request",
                    NON_RETRYABLE_FAILURE_TYPE);
        }
        if (attempt.durableFailureResult() != null) {
            if (attempt.status() != AgentRunAttemptStatus.FAILED
                    && attempt.status() != AgentRunAttemptStatus.ABORTED) {
                throw ApplicationFailure.newNonRetryableFailure(
                        "durable failure result conflicts with attempt status",
                        NON_RETRYABLE_FAILURE_TYPE);
            }
            return;
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
            throw AgentRunExecutionException.failLogicalRun(
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
            throw AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_RESULT_IDENTITY_MISMATCH",
                    "graph result identity does not match the command",
                    completion.lastSequenceNo(),
                    completion.publicOutputEmitted(),
                    null);
        }
        ExecuteAgentRunResult durableResult = completion.durableResult();
        if (durableResult != null
                && (!request.agentRunId().equals(durableResult.agentRunId())
                        || !request.logicalRunId().equals(durableResult.logicalRunId())
                        || !request.attemptId().equals(durableResult.attemptId())
                        || request.attemptNo() != durableResult.attemptNo()
                        || durableResult.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED
                        || !result.equals(durableResult.graphResult())
                        || !result.outputHash().equals(durableResult.resultHash())
                        || completion.lastSequenceNo() != durableResult.lastSequenceNo()
                        || completion.publicOutputEmitted()
                                != durableResult.publicOutputEmitted())) {
            throw AgentRunExecutionException.failLogicalRun(
                    "AGENT_RUN_DURABLE_RESULT_MISMATCH",
                    "durable result differs from the execution request or completion",
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
        Instant observedAt = clock.instant();
        boolean deadlineOpen = observedAt.isBefore(request.command().deadlineAt());
        boolean executionWindowClosed =
                allowedAttempts < 1
                        || context.temporalAttempt() > allowedAttempts
                        || !deadlineOpen;
        boolean resumableParallelAttempt =
                ExecuteAgentRunRequest.isParallelIntakeCommand(request.command())
                        && "agent-stream.v4".equals(request.streamProtocol())
                        && attempt.status() == AgentRunAttemptStatus.RUNNING;
        boolean existingVisibleAttempt =
                attempt.status() == AgentRunAttemptStatus.RESULT_READY
                        || (!resumableParallelAttempt
                                && (attempt.publicOutputEmitted()
                                        || attempt.finalFrameObserved()));
        ExecutionMode mode = executionWindowClosed || existingVisibleAttempt
                ? ExecutionMode.RECONCILE_ONLY
                : ExecutionMode.EXECUTE_OR_RECONCILE;
        LOG.info(
                "agent_run_execution_mode logical_run_id={} attempt_id={} command_id={} mode={} "
                        + "allowed_activity_attempts={} temporal_attempt={} observed_at={} deadline_at={} "
                        + "deadline_open={} attempt_status={} public_output_emitted={} "
                        + "final_frame_observed={}",
                request.logicalRunId(),
                request.attemptId(),
                request.command().commandId(),
                mode,
                allowedAttempts,
                context.temporalAttempt(),
                observedAt,
                request.command().deadlineAt(),
                deadlineOpen,
                attempt.status(),
                attempt.publicOutputEmitted(),
                attempt.finalFrameObserved());
        return mode;
    }

    private ApplicationFailure retryFailure(
            ExecuteAgentRunRequest request,
            int temporalAttempt,
            String errorCode,
            AgentRunRecoveryAction recoveryAction) {
        ApplicationFailure retryFailure = ApplicationFailure.newFailure(
                "agent run infrastructure failure",
                RETRYABLE_FAILURE_TYPE,
                request.agentRunId(),
                request.attemptId(),
                errorCode,
                recoveryAction.name());
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
