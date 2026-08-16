package com.example.dispute.workflow.agentrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunLedger;
import com.example.dispute.workflow.activity.agent.AgentRunActivityContext;
import com.example.dispute.workflow.activity.agent.AgentRunActivityContextProvider;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionException;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway;
import com.example.dispute.workflow.activity.agent.AgentRunExecutionGateway.ExecutionMode;
import com.example.dispute.workflow.activity.agent.AgentRunProgress;
import com.example.dispute.workflow.activity.agent.ExecuteAgentRunActivityImpl;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.temporal.failure.ApplicationFailure;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecuteAgentRunActivityTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Path FIXTURES =
            Path.of("..", "contracts", "agent-platform", "v1", "fixtures", "valid");
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @Test
    void duplicateExecutionReusesTheSameLogicalRunAndAttempt() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult graphResult = graphResult();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        AgentRunActivityContext context = context(1);
        when(ledger.requireAllocatedAttempt(request))
                .thenReturn(runningAttempt(0, false), resultReadyAttempt(7, true));
        when(gateway.execute(eq(request), any(ExecutionMode.class), any(), any()))
                .thenAnswer(invocation -> {
                    if (invocation.getArgument(1) == ExecutionMode.EXECUTE_OR_RECONCILE) {
                        AgentRunExecutionGateway.ProgressListener listener =
                                invocation.getArgument(2);
                        listener.onProgress(new AgentRunProgress(2, true, false));
                    }
                    return new AgentRunExecutionGateway.Completion(graphResult, 7, true);
                });
        ExecuteAgentRunActivityImpl activity = activity(ledger, gateway, () -> context);

        ExecuteAgentRunResult first = activity.execute(request);
        ExecuteAgentRunResult duplicate = activity.execute(request);

        assertThat(first).isEqualTo(duplicate);
        assertThat(first.outcome()).isEqualTo(ExecuteAgentRunResult.Outcome.COMPLETED);
        assertThat(first.resultHash()).isEqualTo(graphResult.outputHash());
        verify(ledger, times(2)).requireAllocatedAttempt(request);
        verify(ledger, times(2)).recordResultReady(first);
        verify(gateway).execute(
                eq(request),
                eq(ExecutionMode.EXECUTE_OR_RECONCILE),
                any(),
                any());
        verify(gateway).execute(
                eq(request),
                eq(ExecutionMode.RECONCILE_ONLY),
                any(),
                any());
        verify(ledger, never()).createOrLoad(any());
    }

    @Test
    void completionLossRetriesTheStableCommandAndAcceptsTheCachedHash() throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult graphResult = graphResult();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        AgentRunActivityContext firstContext = context(1);
        AgentRunActivityContext secondContext = context(2);
        AgentRunActivityContextProvider contexts = mock(AgentRunActivityContextProvider.class);
        when(contexts.current()).thenReturn(firstContext, secondContext);
        when(ledger.requireAllocatedAttempt(request))
                .thenReturn(runningAttempt(0, false), runningAttempt(3, true));
        List<ExecuteAgentRunRequest> gatewayRequests = new ArrayList<>();
        List<ExecutionMode> executionModes = new ArrayList<>();
        AtomicInteger invocation = new AtomicInteger();
        when(gateway.execute(eq(request), any(ExecutionMode.class), any(), any()))
                .thenAnswer(call -> {
                    gatewayRequests.add(call.getArgument(0));
                    executionModes.add(call.getArgument(1));
                    AgentRunExecutionGateway.ProgressListener listener = call.getArgument(2);
                    if (invocation.getAndIncrement() == 0) {
                        listener.onProgress(new AgentRunProgress(3, true, false));
                        throw AgentRunExecutionException.retrySameCommand(
                                "AGENT_RESPONSE_LOST",
                                "response lost after command-ledger commit",
                                3,
                                true,
                                null);
                    }
                    return new AgentRunExecutionGateway.Completion(graphResult, 7, true);
                });
        ExecuteAgentRunActivityImpl activity = activity(ledger, gateway, contexts);

        assertThatThrownBy(() -> activity.execute(request))
                .isInstanceOfSatisfying(
                        ApplicationFailure.class,
                        failure -> {
                            assertThat(failure.getType())
                                    .isEqualTo(ExecuteAgentRunActivityImpl.RETRYABLE_FAILURE_TYPE);
                            assertThat(failure.getNextRetryDelay())
                                    .isPositive()
                                    .isLessThanOrEqualTo(Duration.ofSeconds(30));
                        });
        ExecuteAgentRunResult cached = activity.execute(request);

        assertThat(gatewayRequests).containsExactly(request, request);
        assertThat(executionModes)
                .containsExactly(
                        ExecutionMode.EXECUTE_OR_RECONCILE,
                        ExecutionMode.RECONCILE_ONLY);
        assertThat(gatewayRequests)
                .allSatisfy(replayed -> {
                    assertThat(replayed.command().commandId())
                            .isEqualTo(request.command().commandId());
                    assertThat(replayed.attemptId()).isEqualTo(request.attemptId());
                    assertThat(replayed.attemptNo()).isEqualTo(request.attemptNo());
                });
        assertThat(cached.resultHash()).isEqualTo(graphResult.outputHash());
        verify(ledger, never()).recordAttemptFailureResult(any(), any());
    }

    @Test
    void durableFinalHeartbeatForcesResultOnlyReconciliationWithoutVisibleOutput()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult graphResult = graphResult();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        when(ledger.requireAllocatedAttempt(request))
                .thenReturn(runningAttempt(2, false, true));
        when(gateway.execute(
                        eq(request),
                        eq(ExecutionMode.RECONCILE_ONLY),
                        any(),
                        any()))
                .thenReturn(new AgentRunExecutionGateway.Completion(graphResult, 2, false));

        ExecuteAgentRunResult result =
                activity(ledger, gateway, () -> context(2)).execute(request);

        assertThat(result.outcome()).isEqualTo(ExecuteAgentRunResult.Outcome.COMPLETED);
        assertThat(result.recoveryAction()).isNull();
        verify(gateway).execute(
                eq(request),
                eq(ExecutionMode.RECONCILE_ONLY),
                any(),
                any());
    }

    @Test
    void visibleOutputFailureMarksTheAttemptForResetBeforeTheNextLogicalAttempt()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        when(ledger.requireAllocatedAttempt(request))
                .thenReturn(runningAttempt(0, false));
        when(gateway.execute(
                        eq(request),
                        eq(ExecutionMode.EXECUTE_OR_RECONCILE),
                        any(),
                        any()))
                .thenAnswer(call -> {
                    AgentRunExecutionGateway.ProgressListener listener = call.getArgument(2);
                    listener.onProgress(new AgentRunProgress(4, true, false));
                    throw AgentRunExecutionException.createNextAttempt(
                            "AGENT_STREAM_INTERRUPTED",
                            "stream interrupted before durable completion",
                            4,
                            true,
                            null);
                });
        when(ledger.recordAttemptFailureResult(
                        eq(AgentRunAttemptStatus.ABORTED), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        ExecuteAgentRunActivityImpl activity = activity(ledger, gateway, () -> context(1));

        ExecuteAgentRunResult result = activity.execute(request);

        assertThat(result.outcome()).isEqualTo(ExecuteAgentRunResult.Outcome.FAILED);
        assertThat(result.publicOutputEmitted()).isTrue();
        assertThat(result.retryable()).isTrue();
        assertThat(result.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT);
        verify(ledger).recordAttemptFailureResult(AgentRunAttemptStatus.ABORTED, result);
    }

    @Test
    void exhaustedCommandLedgerRecoveryNeverEscalatesToAFreshCommand() throws Exception {
        ExecuteAgentRunRequest request = request();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        when(ledger.requireAllocatedAttempt(request))
                .thenReturn(runningAttempt(4, true));
        when(gateway.execute(
                        eq(request),
                        eq(ExecutionMode.RECONCILE_ONLY),
                        any(),
                        any()))
                .thenThrow(AgentRunExecutionException.retrySameCommand(
                        "AGENT_RESPONSE_LOST",
                        "the exact command remains recoverable from its ledger",
                        4,
                        true,
                        null));
        when(ledger.recordAttemptFailureResult(
                        eq(AgentRunAttemptStatus.ABORTED), any()))
                .thenAnswer(invocation -> {
                    ExecuteAgentRunResult source = invocation.getArgument(1);
                    return withLastSequence(source, source.lastSequenceNo() + 1);
                });
        ExecuteAgentRunActivityImpl activity = activity(ledger, gateway, () -> context(3));

        ExecuteAgentRunResult result = activity.execute(request);

        assertThat(result.outcome()).isEqualTo(ExecuteAgentRunResult.Outcome.FAILED);
        assertThat(result.retryable()).isFalse();
        assertThat(result.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        assertThat(result.lastSequenceNo()).isEqualTo(5);
        verify(ledger).recordAttemptFailureResult(
                AgentRunAttemptStatus.ABORTED, withLastSequence(result, 4));
    }

    @Test
    void replaysADurableFailureWithoutCallingTheGatewayAfterCompletionLoss()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        ExecuteAgentRunResult durable = new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                request.agentRunId(),
                request.logicalRunId(),
                request.attemptId(),
                request.attemptNo(),
                ExecuteAgentRunResult.Outcome.FAILED,
                null,
                null,
                4,
                true,
                "AGENT_STREAM_INTERRUPTED",
                true,
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                NOW);
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        when(ledger.requireAllocatedAttempt(request))
                .thenReturn(durableFailureAttempt(durable));

        ExecuteAgentRunResult replayed =
                activity(ledger, gateway, () -> context(2)).execute(request);

        assertThat(replayed).isEqualTo(durable);
        verifyNoInteractions(gateway);
        verify(ledger, never()).recordAttemptFailureResult(any(), any());
    }

    @Test
    void configuredAttemptLimitPreventsCreatingAnUnallocatableAttempt()
            throws Exception {
        ExecuteAgentRunRequest request = withAttemptLimit(request(), 1);
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        when(ledger.requireAllocatedAttempt(request)).thenReturn(runningAttempt(0, false));
        when(gateway.execute(eq(request), any(), any(), any()))
                .thenThrow(AgentRunExecutionException.createNextAttempt(
                        "PROVIDER_UNAVAILABLE",
                        "provider is unavailable",
                        0,
                        false,
                        null));
        when(ledger.recordAttemptFailureResult(
                        eq(AgentRunAttemptStatus.FAILED), any()))
                .thenAnswer(invocation -> {
                    ExecuteAgentRunResult source = invocation.getArgument(1);
                    return withLastSequence(source, source.lastSequenceNo() + 1);
                });

        ExecuteAgentRunResult result =
                activity(
                                ledger,
                                gateway,
                                () -> context(1),
                                Clock.fixed(NOW.plusNanos(123), ZoneOffset.UTC))
                        .execute(request);

        assertThat(result.recoveryAction())
                .isEqualTo(AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        assertThat(result.retryable()).isFalse();
        assertThat(result.completedAt()).isEqualTo(NOW);
        assertThat(result.lastSequenceNo()).isEqualTo(1);
        verify(ledger).recordAttemptFailureResult(
                AgentRunAttemptStatus.FAILED, withLastSequence(result, 0));
    }

    @Test
    void deterministicAttemptConflictIsNonRetryableAndNeverReachesPython()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        when(ledger.requireAllocatedAttempt(request))
                .thenThrow(new IllegalStateException("attempt request hash conflicts"));
        ExecuteAgentRunActivityImpl activity = activity(ledger, gateway, () -> context(1));

        assertThatThrownBy(() -> activity.execute(request))
                .isInstanceOfSatisfying(
                        ApplicationFailure.class,
                        failure -> {
                            assertThat(failure.getType())
                                    .isEqualTo(
                                            ExecuteAgentRunActivityImpl.NON_RETRYABLE_FAILURE_TYPE);
                            assertThat(failure.isNonRetryable()).isTrue();
                        });
        verify(gateway, never()).execute(any(), any(), any(), any());
    }

    @Test
    void transientAttemptAllocationFailureUsesTheBoundedTemporalRetryType()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        when(ledger.requireAllocatedAttempt(request))
                .thenThrow(new RuntimeException("database failover"));
        ExecuteAgentRunActivityImpl activity = activity(ledger, gateway, () -> context(1));

        assertThatThrownBy(() -> activity.execute(request))
                .isInstanceOfSatisfying(
                        ApplicationFailure.class,
                        failure -> {
                            assertThat(failure.getType())
                                    .isEqualTo(ExecuteAgentRunActivityImpl.RETRYABLE_FAILURE_TYPE);
                            assertThat(failure.isNonRetryable()).isFalse();
                            assertThat(failure.getNextRetryDelay()).isPositive();
                        });
        verify(gateway, never()).execute(any(), any(), any(), any());
    }

    @Test
    void attemptLoadFailureRetainsSanitizedRootCauseAcrossBoundedRetries()
            throws Exception {
        ExecuteAgentRunRequest request = requestWithActivityBudget(3);
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        AgentRunActivityContextProvider contexts = mock(AgentRunActivityContextProvider.class);
        StackTraceElement sourceFrame = new StackTraceElement(
                "org.postgresql.core.v3.QueryExecutorImpl",
                "receiveErrorResponse",
                "QueryExecutorImpl.java",
                2714);
        when(ledger.requireAllocatedAttempt(request)).thenAnswer(ignored -> {
            throw attemptLoadInfrastructureFailure(sourceFrame);
        });
        when(contexts.current()).thenReturn(context(1), context(2), context(3));
        ExecuteAgentRunActivityImpl activity = activity(ledger, gateway, contexts);

        List<ApplicationFailure> failures = new ArrayList<>();
        for (int temporalAttempt = 1; temporalAttempt <= 3; temporalAttempt++) {
            assertThatThrownBy(() -> activity.execute(request))
                    .isInstanceOfSatisfying(ApplicationFailure.class, failures::add);
        }

        assertThat(failures).hasSize(3);
        assertThat(failures.subList(0, 2))
                .allSatisfy(failure -> {
                    assertThat(failure.getType())
                            .isEqualTo(ExecuteAgentRunActivityImpl.RETRYABLE_FAILURE_TYPE);
                    assertThat(failure.isNonRetryable()).isFalse();
                    assertThat(failure.getNextRetryDelay()).isPositive();
                    assertAttemptLoadDetails(failure, request, true);
                    assertSanitizedLoadCause(failure, sourceFrame);
                });
        ApplicationFailure terminal = failures.get(2);
        assertThat(terminal.getType())
                .isEqualTo(ExecuteAgentRunActivityImpl.NON_RETRYABLE_FAILURE_TYPE);
        assertThat(terminal.isNonRetryable()).isTrue();
        assertAttemptLoadDetails(terminal, request, false);
        assertSanitizedLoadCause(terminal, sourceFrame);

        for (RuntimeException lineageConflict : List.of(
                new IllegalArgumentException("attempt identity conflicts"),
                new IllegalStateException("attempt lineage conflicts"))) {
            AgentRunLedger conflictLedger = mock(AgentRunLedger.class);
            AgentRunExecutionGateway conflictGateway = mock(AgentRunExecutionGateway.class);
            when(conflictLedger.requireAllocatedAttempt(request)).thenThrow(lineageConflict);

            assertThatThrownBy(
                    () -> activity(conflictLedger, conflictGateway, () -> context(1))
                            .execute(request))
                    .isInstanceOfSatisfying(
                            ApplicationFailure.class,
                            failure -> {
                                assertThat(failure.getType())
                                        .isEqualTo(
                                                ExecuteAgentRunActivityImpl
                                                        .NON_RETRYABLE_FAILURE_TYPE);
                                assertThat(failure.isNonRetryable()).isTrue();
                                assertThat(failure.getDetails().get(2, String.class))
                                        .isEqualTo("AGENT_RUN_LINEAGE_CONFLICT");
                            });
            verify(conflictLedger).requireAllocatedAttempt(request);
            verifyNoInteractions(conflictGateway);
        }

        verify(ledger, times(3)).requireAllocatedAttempt(request);
        verifyNoInteractions(gateway);
        verify(ledger, never()).recordAttemptFailureResult(any(), any());
    }

    private static RuntimeException attemptLoadInfrastructureFailure(
            StackTraceElement sourceFrame) {
        SQLException sqlFailure = new SQLException(
                "select secret_token from private_attempts where credential='do-not-leak'",
                "40001");
        RuntimeException infrastructureFailure = new RuntimeException(
                "jdbc:postgresql://private-host/agent?password=do-not-leak",
                sqlFailure);
        infrastructureFailure.setStackTrace(new StackTraceElement[] {sourceFrame});
        return infrastructureFailure;
    }

    private static void assertAttemptLoadDetails(
            ApplicationFailure failure,
            ExecuteAgentRunRequest request,
            boolean retryable) {
        assertThat(failure.getDetails().get(0, String.class)).isEqualTo(request.agentRunId());
        assertThat(failure.getDetails().get(1, String.class)).isEqualTo(request.attemptId());
        assertThat(failure.getDetails().get(2, String.class))
                .isEqualTo("AGENT_RUN_ATTEMPT_LOAD_FAILED");
        if (retryable) {
            assertThat(failure.getDetails().get(3, String.class))
                    .isEqualTo(AgentRunRecoveryAction.RETRY_SAME_COMMAND.name());
        }
    }

    private static void assertSanitizedLoadCause(
            ApplicationFailure failure,
            StackTraceElement sourceFrame) {
        assertThat(failure.getCause()).isInstanceOf(ApplicationFailure.class);
        ApplicationFailure cause = (ApplicationFailure) failure.getCause();
        assertThat(cause.getType()).isEqualTo(RuntimeException.class.getName());
        assertThat(cause.getMessage()).contains("sanitized agent run attempt load cause");
        assertThat(cause.getMessage())
                .doesNotContain("secret_token", "credential", "password", "private-host");
        assertThat(cause.getDetails().get(0, String.class))
                .isEqualTo(SQLException.class.getName());
        assertThat(cause.getDetails().get(1, String.class)).isEqualTo("40001");
        assertThat(cause.getDetails().get(2, String.class)).isEqualTo("MESSAGE_REDACTED");
        assertThat(cause.getStackTrace()).containsExactly(sourceFrame);
    }

    @Test
    void resultReadyAfterDeadlineAndBudgetExhaustionReconcilesTheCachedHash()
            throws Exception {
        ExecuteAgentRunRequest request = requestWithActivityBudget(0);
        RoomGraphResult graphResult = graphResult();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        Instant afterDeadline = request.command().deadlineAt().plusSeconds(30);
        when(ledger.requireAllocatedAttempt(request))
                .thenReturn(resultReadyAttempt(7, true));
        when(gateway.execute(
                        eq(request),
                        eq(ExecutionMode.RECONCILE_ONLY),
                        any(),
                        any()))
                .thenReturn(new AgentRunExecutionGateway.Completion(graphResult, 7, true));
        ExecuteAgentRunActivityImpl activity = activity(
                ledger,
                gateway,
                () -> context(1),
                Clock.fixed(afterDeadline, ZoneOffset.UTC));

        ExecuteAgentRunResult reconciled = activity.execute(request);

        assertThat(reconciled.outcome()).isEqualTo(ExecuteAgentRunResult.Outcome.COMPLETED);
        assertThat(reconciled.resultHash()).isEqualTo(graphResult.outputHash());
        verify(ledger).recordResultReady(reconciled);
        verify(ledger, never()).recordAttemptFailureResult(any(), any());
    }

    @Test
    void resultReadyCacheMissFailsClosedWithoutReversingTheDurableAttempt()
            throws Exception {
        ExecuteAgentRunRequest request = requestWithActivityBudget(0);
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        Instant afterDeadline = request.command().deadlineAt().plusSeconds(30);
        when(ledger.requireAllocatedAttempt(request))
                .thenReturn(resultReadyAttempt(7, true));
        when(gateway.execute(
                        eq(request),
                        eq(ExecutionMode.RECONCILE_ONLY),
                        any(),
                        any()))
                .thenThrow(AgentRunExecutionException.failLogicalRun(
                        "AGENT_RUN_RECONCILIATION_MISS",
                        "cached command result was not found",
                        7,
                        true,
                        null));
        ExecuteAgentRunActivityImpl activity = activity(
                ledger,
                gateway,
                () -> context(1),
                Clock.fixed(afterDeadline, ZoneOffset.UTC));

        assertThatThrownBy(() -> activity.execute(request))
                .isInstanceOfSatisfying(
                        ApplicationFailure.class,
                        failure -> {
                            assertThat(failure.getType())
                                    .isEqualTo(
                                            ExecuteAgentRunActivityImpl.NON_RETRYABLE_FAILURE_TYPE);
                            assertThat(failure.isNonRetryable()).isTrue();
                        });
        verify(ledger, never()).recordResultReady(any());
        verify(ledger, never()).recordAttemptFailureResult(any(), any());
    }

    @Test
    void resultReadyPersistenceLossAfterDurableCompletionRequiresTerminalReconciliation()
            throws Exception {
        ExecuteAgentRunRequest request = request();
        RoomGraphResult graphResult = graphResult();
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRunExecutionGateway gateway = mock(AgentRunExecutionGateway.class);
        when(ledger.requireAllocatedAttempt(request)).thenReturn(runningAttempt(0, false));
        when(gateway.execute(
                        eq(request),
                        eq(ExecutionMode.EXECUTE_OR_RECONCILE),
                        any(),
                        any()))
                .thenReturn(new AgentRunExecutionGateway.Completion(graphResult, 7, true));
        doThrow(new IllegalStateException("result-ready commit unavailable"))
                .when(ledger)
                .recordResultReady(any());

        assertThatThrownBy(() -> activity(ledger, gateway, () -> context(1)).execute(request))
                .isInstanceOfSatisfying(
                        ApplicationFailure.class,
                        failure -> {
                            assertThat(failure.getType())
                                    .isEqualTo(ExecuteAgentRunActivityImpl.RETRYABLE_FAILURE_TYPE);
                            assertThat(failure.getDetails().get(2, String.class))
                                    .isEqualTo("AGENT_RUN_ACTIVITY_FAILED");
                            assertThat(failure.getDetails().get(3, String.class))
                                    .isEqualTo(AgentRunRecoveryAction.RECONCILE_TERMINAL.name());
                        });
        verify(ledger).recordResultReady(any());
        verify(ledger, never()).recordAttemptFailureResult(any(), any());
    }

    private static ExecuteAgentRunActivityImpl activity(
            AgentRunLedger ledger,
            AgentRunExecutionGateway gateway,
            AgentRunActivityContextProvider contexts) {
        return activity(
                ledger,
                gateway,
                contexts,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ExecuteAgentRunActivityImpl activity(
            AgentRunLedger ledger,
            AgentRunExecutionGateway gateway,
            AgentRunActivityContextProvider contexts,
            Clock clock) {
        return new ExecuteAgentRunActivityImpl(
                ledger,
                gateway,
                contexts,
                clock,
                Duration.ofHours(1),
                Executors::newSingleThreadScheduledExecutor);
    }

    private static AgentRunActivityContext context(int temporalAttempt) {
        return new AgentRunActivityContext() {
            @Override
            public int temporalAttempt() {
                return temporalAttempt;
            }

            @Override
            public void heartbeat(
                    com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat details) {}
        };
    }

    private static AgentRunLedger.Attempt runningAttempt(
            long lastSequenceNo,
            boolean publicOutputEmitted) {
        return runningAttempt(lastSequenceNo, publicOutputEmitted, false);
    }

    private static AgentRunLedger.Attempt runningAttempt(
            long lastSequenceNo,
            boolean publicOutputEmitted,
            boolean finalFrameObserved) {
        return attempt(
                AgentRunAttemptStatus.RUNNING,
                lastSequenceNo,
                publicOutputEmitted,
                finalFrameObserved);
    }

    private static AgentRunLedger.Attempt resultReadyAttempt(
            long lastSequenceNo,
            boolean publicOutputEmitted) {
        return attempt(
                AgentRunAttemptStatus.RESULT_READY,
                lastSequenceNo,
                publicOutputEmitted,
                true);
    }

    private static AgentRunLedger.Attempt durableFailureAttempt(
            ExecuteAgentRunResult result) {
        return new AgentRunLedger.Attempt(
                result.attemptId(),
                result.agentRunId(),
                result.attemptNo(),
                AgentRunAttemptStatus.ABORTED,
                result.publicOutputEmitted(),
                false,
                result.lastSequenceNo(),
                NOW,
                NOW.minusSeconds(1),
                result.completedAt(),
                1,
                "agent-run-attempt-lineage.v1",
                "graph-cmd-001",
                "78aa57b57feda88e27adf9bc1b2cacd6aa3c2deb4281fb89533e9f8fb774e430",
                "b".repeat(64),
                "{}",
                null,
                false,
                0,
                result.recoveryAction().name(),
                result.errorCode(),
                result);
    }

    private static ExecuteAgentRunResult withLastSequence(
            ExecuteAgentRunResult source, long lastSequenceNo) {
        return new ExecuteAgentRunResult(
                source.schemaVersion(),
                source.agentRunId(),
                source.logicalRunId(),
                source.attemptId(),
                source.attemptNo(),
                source.outcome(),
                source.graphResult(),
                source.resultHash(),
                lastSequenceNo,
                source.publicOutputEmitted(),
                source.errorCode(),
                source.retryable(),
                source.recoveryAction(),
                source.completedAt());
    }

    private static AgentRunLedger.Attempt attempt(
            AgentRunAttemptStatus status,
            long lastSequenceNo,
            boolean publicOutputEmitted,
            boolean finalFrameObserved) {
        return new AgentRunLedger.Attempt(
                "attempt-001",
                "run-001",
                1,
                status,
                publicOutputEmitted,
                finalFrameObserved,
                lastSequenceNo,
                null,
                NOW,
                null,
                0,
                "agent-run-attempt-lineage.v1",
                "graph-cmd-001",
                "78aa57b57feda88e27adf9bc1b2cacd6aa3c2deb4281fb89533e9f8fb774e430",
                "b".repeat(64),
                "{}",
                null,
                false,
                0,
                null);
    }

    private static ExecuteAgentRunRequest request() throws Exception {
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                "run-001",
                1,
                "agent-stream.v2",
                "b".repeat(64),
                null,
                false,
                0,
                fixture("room-graph-command-valid.json", RoomGraphCommand.class));
    }

    private static ExecuteAgentRunRequest withAttemptLimit(
            ExecuteAgentRunRequest request, int attemptLimit) {
        return new ExecuteAgentRunRequest(
                request.schemaVersion(),
                request.agentRunId(),
                request.attemptNo(),
                attemptLimit,
                request.streamProtocol(),
                request.logicalInputHash(),
                request.previousAttemptId(),
                request.resetRequired(),
                request.publicSequenceOffset(),
                request.command());
    }

    private static ExecuteAgentRunRequest requestWithActivityBudget(int remaining)
            throws Exception {
        JsonNode wrapper = MAPPER.readTree(
                FIXTURES.resolve("room-graph-command-valid.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrapper
                        .required("instance")
                        .required("retry_budget"))
                .put("activity_attempts_remaining", remaining);
        RoomGraphCommand command =
                MAPPER.treeToValue(wrapper.required("instance"), RoomGraphCommand.class);
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                "run-001",
                1,
                "agent-stream.v2",
                "b".repeat(64),
                null,
                false,
                0,
                command);
    }

    private static RoomGraphResult graphResult() throws Exception {
        return fixture("room-graph-result-valid.json", RoomGraphResult.class);
    }

    private static <T> T fixture(String file, Class<T> type) throws Exception {
        JsonNode wrapper = MAPPER.readTree(FIXTURES.resolve(file).toFile());
        return MAPPER.treeToValue(wrapper.required("instance"), type);
    }
}
