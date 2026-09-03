package com.example.dispute.room.application;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.AGENT_EXECUTION;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.BusinessException;
import com.example.dispute.common.exception.ForbiddenException;
import com.example.dispute.config.ActorRole;
import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.workflow.activity.system.IntakeInfrastructurePreparationWorkflow;
import com.example.dispute.workflow.activity.system.TemporalWorkerProbeActivities.IntakeInfrastructurePreparationResult;
import com.example.dispute.workflow.projection.intake.IntakeProcessProjectionView;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Authorizes locally, ends the read-only transaction, then prepares target infrastructure. */
@Service
public final class IntakeInfrastructurePreparationService {

    public static final String REASON_UNAVAILABLE =
            "INTAKE_INFRASTRUCTURE_PREPARATION_UNAVAILABLE";
    private static final Duration MAXIMUM_TOTAL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofSeconds(29);
    private static final Duration DEFAULT_PROJECTION_RETRY_INTERVAL = Duration.ofMillis(50);
    private static final String TEMPORAL_WRITER = "TEMPORAL";
    private static final String LEGACY_WRITER = "LEGACY";
    private static final String SHADOW_WRITER = "SHADOW";

    private final IntakeProgressService progressService;
    private final TransactionTemplate readOnlyTransaction;
    private final List<TargetPreparation> targetPreparations;
    private final Duration totalTimeout;
    private final Duration projectionRetryInterval;
    private final LongSupplier nanoTime;
    private final Consumer<Duration> pause;

    @Autowired
    public IntakeInfrastructurePreparationService(
            IntakeProgressService progressService,
            PlatformTransactionManager transactionManager,
            List<TargetPreparation> targetPreparations) {
        this(
                progressService,
                transactionManager,
                targetPreparations,
                DEFAULT_TOTAL_TIMEOUT,
                DEFAULT_PROJECTION_RETRY_INTERVAL,
                System::nanoTime,
                IntakeInfrastructurePreparationService::pauseCurrentThread);
    }

    /** Deterministic timing seam for proving the closed total-budget contract. */
    public IntakeInfrastructurePreparationService(
            IntakeProgressService progressService,
            PlatformTransactionManager transactionManager,
            List<TargetPreparation> targetPreparations,
            Duration totalTimeout,
            Duration projectionRetryInterval,
            LongSupplier nanoTime,
            Consumer<Duration> pause) {
        this.progressService = Objects.requireNonNull(progressService, "progressService");
        this.readOnlyTransaction =
                new TransactionTemplate(
                        Objects.requireNonNull(transactionManager, "transactionManager"));
        this.readOnlyTransaction.setReadOnly(true);
        this.readOnlyTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.targetPreparations = List.copyOf(targetPreparations);
        this.totalTimeout = requireTotalTimeout(totalTimeout);
        this.projectionRetryInterval = requireRetryInterval(
                projectionRetryInterval, this.totalTimeout);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    public IntakeInfrastructurePreparationView prepare(
            String caseId, AuthenticatedActor actor, String idempotencyKey) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(actor, "actor");
        requireIdempotencyKey(idempotencyKey);
        long deadlineNanos = nanoTime.getAsLong() + totalTimeout.toNanos();
        PreparationDecision decision;
        while (true) {
            requireRemaining(deadlineNanos);
            decision = Objects.requireNonNull(
                    readOnlyTransaction.execute(status -> authorize(caseId, actor)),
                    "preparationDecision");
            if (decision != PreparationDecision.RETRY) {
                break;
            }
            Duration remaining = requireRemaining(deadlineNanos);
            pauseOutsideTransaction(min(projectionRetryInterval, remaining));
        }
        if (!decision.target()) {
            return IntakeInfrastructurePreparationView.notRequired();
        }
        Duration remaining = requireRemaining(deadlineNanos);
        requireNoActiveTransaction();
        TargetPreparation target = targetPreparations.size() == 1
                ? targetPreparations.getFirst()
                : null;
        if (target == null) {
            throw unavailable(null);
        }
        try {
            target.prepare(idempotencyKey, remaining);
            requireRemaining(deadlineNanos);
            return IntakeInfrastructurePreparationView.ready();
        } catch (RuntimeException | Error failure) {
            throw unavailable(failure);
        }
    }

    private PreparationDecision authorize(String caseId, AuthenticatedActor actor) {
        if (actor.role() != ActorRole.USER && actor.role() != ActorRole.MERCHANT) {
            throw new ForbiddenException("actor is not a party to this case");
        }
        IntakeStatusView status = progressService.status(caseId, actor);
        if (!status.canUseIntake()) {
            throw new ForbiddenException("intake preparation is unavailable for this case party");
        }
        IntakeProcessProjectionView projection =
                Objects.requireNonNull(status.processProjection(), "processProjection");
        if (IntakeProcessProjectionView.PROCESSING.equals(projection.projectionState())) {
            return PreparationDecision.RETRY;
        }
        if (!IntakeProcessProjectionView.CURRENT.equals(projection.projectionState())) {
            throw unavailable(null);
        }
        String writer = projection.writerMode();
        if (LEGACY_WRITER.equals(writer) || SHADOW_WRITER.equals(writer)) {
            return PreparationDecision.NOT_REQUIRED;
        }
        if (TEMPORAL_WRITER.equals(writer)) {
            return PreparationDecision.TARGET;
        }
        throw unavailable(null);
    }

    public static TargetPreparation temporal(WorkflowClient workflowClient) {
        WorkflowClient client = Objects.requireNonNull(workflowClient, "workflowClient");
        return (idempotencyKey, remainingBudget) -> {
            Duration timeout = requireRemoteBudget(remainingBudget);
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            String workflowId = workflowId(idempotencyKey);
            IntakeInfrastructurePreparationWorkflow workflow = client.newWorkflowStub(
                    IntakeInfrastructurePreparationWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(workflowId)
                            .setTaskQueue(AGENT_EXECUTION)
                            .setWorkflowExecutionTimeout(timeout)
                            .setWorkflowIdReusePolicy(
                                    WorkflowIdReusePolicy
                                            .WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                            .build());
            WorkflowStub execution = WorkflowStub.fromTyped(workflow);
            try {
                WorkflowClient.start(workflow::prepare);
            } catch (WorkflowExecutionAlreadyStarted replay) {
                execution = client.newUntypedWorkflowStub(workflowId);
            }
            try {
                Duration resultTimeout = remaining(deadlineNanos);
                if (resultTimeout.isZero()) {
                    throw new TimeoutException("preparation budget elapsed before result wait");
                }
                IntakeInfrastructurePreparationResult result = execution.getResult(
                        resultTimeout.toNanos(),
                        TimeUnit.NANOSECONDS,
                        IntakeInfrastructurePreparationResult.class);
                if (result == null
                        || !IntakeInfrastructurePreparationResult.SCHEMA_VERSION.equals(
                                result.schemaVersion())
                        || !IntakeInfrastructurePreparationResult.READY.equals(result.status())) {
                    throw new IllegalStateException(
                            "Intake infrastructure preparation result was not ready");
                }
            } catch (TimeoutException failure) {
                throw new IllegalStateException(
                        "Intake infrastructure preparation timed out", failure);
            }
        };
    }

    private Duration requireRemaining(long deadlineNanos) {
        Duration remaining = remaining(deadlineNanos, nanoTime.getAsLong());
        if (remaining.isZero()) {
            throw unavailable(null);
        }
        return remaining;
    }

    private void pauseOutsideTransaction(Duration delay) {
        requireNoActiveTransaction();
        try {
            pause.accept(delay);
        } catch (RuntimeException | Error failure) {
            throw unavailable(failure);
        }
    }

    private static void requireNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw unavailable(null);
        }
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static Duration remaining(long deadlineNanos) {
        return remaining(deadlineNanos, System.nanoTime());
    }

    private static Duration remaining(long deadlineNanos, long currentNanos) {
        long remainingNanos = deadlineNanos - currentNanos;
        return remainingNanos <= 0 ? Duration.ZERO : Duration.ofNanos(remainingNanos);
    }

    private static Duration requireTotalTimeout(Duration candidate) {
        Duration timeout = Objects.requireNonNull(candidate, "totalTimeout");
        if (timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(MAXIMUM_TOTAL_TIMEOUT) >= 0) {
            throw new IllegalArgumentException("totalTimeout must be inside 1ns..<30s");
        }
        return timeout;
    }

    private static Duration requireRetryInterval(Duration candidate, Duration totalTimeout) {
        Duration interval = Objects.requireNonNull(candidate, "projectionRetryInterval");
        if (interval.isZero()
                || interval.isNegative()
                || interval.compareTo(totalTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "projectionRetryInterval must be positive and below totalTimeout");
        }
        return interval;
    }

    private static Duration requireRemoteBudget(Duration candidate) {
        Duration timeout = Objects.requireNonNull(candidate, "remainingBudget");
        if (timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(MAXIMUM_TOTAL_TIMEOUT) >= 0) {
            throw new IllegalArgumentException("remainingBudget must be inside 1ns..<30s");
        }
        return timeout;
    }

    private static void pauseCurrentThread(Duration delay) {
        try {
            TimeUnit.NANOSECONDS.sleep(delay.toNanos());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Intake infrastructure preparation was interrupted", failure);
        }
    }

    private static String workflowId(String idempotencyKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            return "intake-infrastructure-preparation:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireIdempotencyKey(String candidate) {
        if (candidate == null
                || !candidate.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")) {
            throw new IllegalArgumentException("Idempotency-Key is invalid");
        }
    }

    private static BusinessException unavailable(Throwable cause) {
        return new BusinessException(
                ErrorCode.AGENT_SERVICE_UNAVAILABLE,
                "intake infrastructure preparation is unavailable",
                Map.of("reason_code", REASON_UNAVAILABLE),
                cause);
    }

    @FunctionalInterface
    public interface TargetPreparation {
        void prepare(String idempotencyKey, Duration remainingBudget);
    }

    private enum PreparationDecision {
        TARGET,
        NOT_REQUIRED,
        RETRY;

        private boolean target() {
            return this == TARGET;
        }
    }
}
