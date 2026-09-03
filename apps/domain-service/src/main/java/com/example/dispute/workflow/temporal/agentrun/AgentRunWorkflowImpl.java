package com.example.dispute.workflow.temporal.agentrun;

import com.example.dispute.workflow.activity.agent.ExecuteAgentRunActivity;
import com.example.dispute.workflow.activity.agent.FinalizeAgentRunActivity;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand.InvocationContext;
import com.example.dispute.workflow.contract.v1.RoomGraphResult;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class AgentRunWorkflowImpl implements AgentRunWorkflow {

    private static final String UPDATE_REJECTED = "AgentRunAttemptUpdateRejected";
    private static final String RESULT_REJECTED = "AgentRunAttemptResultRejected";
    private static final String FINALIZATION_REJECTED = "AgentRunFinalizationReceiptRejected";

    private final Map<Long, AcceptedAttempt> acceptedAttempts = new LinkedHashMap<>();
    private final Map<Long, ExecuteAgentRunResult> completedAttempts = new LinkedHashMap<>();

    private ExecuteAgentRunRequest initialRequest;
    private ExecuteAgentRunResult lastResult;
    private AcceptedAttempt pendingAttempt;
    private RuntimeException terminalFailure;
    private long lastAcceptedAttemptNo;
    private boolean closed;

    @Override
    public ExecuteAgentRunResult run(ExecuteAgentRunRequest request) {
        requireInitialRequest(request);
        initialRequest = request;
        lastAcceptedAttemptNo = request.attemptNo();
        acceptedAttempts.put(request.attemptNo(), AcceptedAttempt.initial(request));

        try {
            completeAttempt(request, null);
        } catch (RuntimeException failure) {
            closeWithFailure(failure);
        }

        while (!closed) {
            Duration remaining = remainingUntil(initialRequest.command().deadlineAt());
            if (remaining.isZero()
                    || !Workflow.await(remaining, () -> pendingAttempt != null || closed)) {
                closed = true;
                break;
            }

            AcceptedAttempt accepted = pendingAttempt;
            pendingAttempt = null;
            try {
                completeAttempt(accepted.request(), accepted);
            } catch (RuntimeException failure) {
                accepted.fail(failure);
                closeWithFailure(failure);
            }
        }

        rejectPendingAttempt();
        Workflow.await(Workflow::isEveryHandlerFinished);
        if (terminalFailure != null) {
            throw terminalFailure;
        }
        return lastResult;
    }

    @Override
    public ExecuteAgentRunResult executeAttempt(ExecuteAgentRunRequest request) {
        requireExpectedUpdateId(request);
        Workflow.await(() -> initialRequest != null || closed);

        AcceptedAttempt accepted = acceptedAttempts.get(request.attemptNo());
        if (accepted != null) {
            requireSameRequest(accepted.request(), request);
            ExecuteAgentRunResult completed = completedAttempts.get(request.attemptNo());
            if (completed != null) {
                return completed;
            }
            if (accepted.completion() != null) {
                return accepted.completion().get();
            }
            throw rejected("attempt one must be started by the Workflow method");
        }

        if (closed || pendingAttempt != null) {
            throw rejected("logical AgentRun is not ready for another attempt");
        }
        validateNewAttempt(request);

        AcceptedAttempt next =
                AcceptedAttempt.updated(request, Workflow.<ExecuteAgentRunResult>newPromise());
        acceptedAttempts.put(request.attemptNo(), next);
        lastAcceptedAttemptNo = request.attemptNo();
        pendingAttempt = next;
        return next.completion().get();
    }

    @Override
    public void validateAttempt(ExecuteAgentRunRequest request) {
        requireExpectedUpdateId(request);
        if (request.attemptNo() == 1) {
            throw new IllegalArgumentException(
                    "attempt one must be started by the Workflow method");
        }
        if (request.attemptNo() > request.attemptLimit()) {
            throw new IllegalArgumentException("AgentRun attempt limit is exhausted");
        }
        if (initialRequest == null) {
            if (request.attemptNo() != 2) {
                throw new IllegalArgumentException("AgentRun attempts must be sequential");
            }
            return;
        }

        AcceptedAttempt accepted = acceptedAttempts.get(request.attemptNo());
        if (accepted != null) {
            requireSameRequest(accepted.request(), request);
            return;
        }
        if (closed || pendingAttempt != null) {
            throw new IllegalStateException("logical AgentRun is not ready for another attempt");
        }
        validateNewAttempt(request);
    }

    private void completeAttempt(
            ExecuteAgentRunRequest request, AcceptedAttempt accepted) {
        ExecuteAgentRunResult result = executeAndFinalize(request);
        requireResultMatchesRequest(request, result);
        completedAttempts.put(request.attemptNo(), result);
        lastResult = result;
        closed = !canAcceptAnotherAttempt(request, result);
        if (accepted != null) {
            accepted.complete(result);
        }
        if (closed) {
            rejectPendingAttempt();
        }
    }

    private ExecuteAgentRunResult executeAndFinalize(ExecuteAgentRunRequest request) {
        int remainingAttempts = request.command().retryBudget().activityAttemptsRemaining();
        ExecuteAgentRunActivity executeActivity =
                Workflow.newActivityStub(
                        ExecuteAgentRunActivity.class,
                        AgentRunTemporalPolicy.activityOptions(remainingAttempts));
        ExecuteAgentRunResult result = executeActivity.execute(request);
        requireResultMatchesRequest(request, result);
        if (result.outcome() == ExecuteAgentRunResult.Outcome.COMPLETED) {
            FinalizeAgentRunActivity finalizerActivity =
                    Workflow.newActivityStub(
                            FinalizeAgentRunActivity.class,
                            AgentRunTemporalPolicy.finalizerActivityOptions());
            AgentRunFinalizationReceipt receipt =
                    finalizerActivity.finalizeResult(request, result);
            requireReceiptMatchesResult(request, result, receipt);
        }
        return result;
    }

    private void validateNewAttempt(ExecuteAgentRunRequest request) {
        if (request.attemptNo() != lastAcceptedAttemptNo + 1
                || request.attemptNo() > initialRequest.attemptLimit()) {
            throw new IllegalArgumentException("AgentRun attempts must be sequential and bounded");
        }
        requireSameLogicalRun(initialRequest, request);
        AcceptedAttempt predecessor = acceptedAttempts.get(lastAcceptedAttemptNo);
        ExecuteAgentRunResult predecessorResult = completedAttempts.get(lastAcceptedAttemptNo);
        if (predecessor == null
                || !predecessor.request().attemptId().equals(request.previousAttemptId())) {
            throw new IllegalArgumentException(
                    "AgentRun attempt must bind its immediate predecessor");
        }
        if (!retryBudgetDoesNotIncrease(
                predecessor.request().command(), request.command())) {
            throw new IllegalArgumentException(
                    "AgentRun residual retry budget cannot increase");
        }
        if (predecessorResult != null
                && (predecessorResult.outcome() != ExecuteAgentRunResult.Outcome.FAILED
                || predecessorResult.recoveryAction()
                        != AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT)) {
            throw new IllegalStateException(
                    "the preceding attempt did not authorize another AgentRun attempt");
        }
        for (AcceptedAttempt accepted : acceptedAttempts.values()) {
            RoomGraphCommand previous = accepted.request().command();
            if (previous.commandId().equals(request.command().commandId())) {
                throw new IllegalArgumentException(
                        "a new AgentRun attempt requires a new commandId");
            }
            if (previous.attemptId().equals(request.attemptId())) {
                throw new IllegalArgumentException(
                        "a new AgentRun attempt requires a new attemptId");
            }
        }
    }

    private static boolean canAcceptAnotherAttempt(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        return result.outcome() == ExecuteAgentRunResult.Outcome.FAILED
                && result.recoveryAction() == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT
                && request.attemptNo() < request.attemptLimit()
                && Workflow.currentTimeMillis() < request.command().deadlineAt().toEpochMilli();
    }

    private static Duration remainingUntil(Instant deadline) {
        long remainingMillis = deadline.toEpochMilli() - Workflow.currentTimeMillis();
        return remainingMillis <= 0 ? Duration.ZERO : Duration.ofMillis(remainingMillis);
    }

    private void closeWithFailure(RuntimeException failure) {
        terminalFailure = Objects.requireNonNull(failure, "failure");
        closed = true;
        rejectPendingAttempt();
    }

    private void rejectPendingAttempt() {
        AcceptedAttempt pending = pendingAttempt;
        pendingAttempt = null;
        if (pending != null) {
            pending.fail(
                    terminalFailure != null
                            ? terminalFailure
                            : rejected("logical AgentRun no longer accepts attempts"));
        }
    }

    private static void requireInitialRequest(ExecuteAgentRunRequest request) {
        if (request == null || request.attemptNo() != 1) {
            throw rejected("logical AgentRun must start with attempt one");
        }
    }

    private static void requireExpectedUpdateId(ExecuteAgentRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("AgentRun attempt request is required");
        }
        String updateId =
                Workflow.getCurrentUpdateInfo()
                        .map(info -> info.getUpdateId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "AgentRun attempt must execute as a Temporal Update"));
        if (!request.attemptId().equals(updateId)) {
            throw new IllegalArgumentException(
                    "Temporal update id must equal the AgentRun attemptId");
        }
    }

    private static void requireSameLogicalRun(
            ExecuteAgentRunRequest initial, ExecuteAgentRunRequest candidate) {
        RoomGraphCommand expected = initial.command();
        RoomGraphCommand actual = candidate.command();
        if (!initial.agentRunId().equals(candidate.agentRunId())
                || !initial.logicalRunId().equals(candidate.logicalRunId())
                || initial.attemptLimit() != candidate.attemptLimit()
                || !initial.streamProtocol().equals(candidate.streamProtocol())
                || !initial.logicalInputHash().equals(candidate.logicalInputHash())
                || !expected.tenantSurrogate().equals(actual.tenantSurrogate())
                || !expected.caseId().equals(actual.caseId())
                || expected.roomType() != actual.roomType()
                || expected.roomEpoch() != actual.roomEpoch()
                || !expected.graphKey().equals(actual.graphKey())
                || !expected.graphVersion().equals(actual.graphVersion())
                || !expected.checkpointSchemaVersion().equals(actual.checkpointSchemaVersion())
                || !expected.threadId().equals(actual.threadId())
                || !expected.actorScope().equals(actual.actorScope())
                || expected.processRevision() != actual.processRevision()
                || !expected.stageCode().equals(actual.stageCode())
                || expected.stageSequence() != actual.stageSequence()
                || !expected.domainSnapshotRef().equals(actual.domainSnapshotRef())
                || !Objects.equals(expected.eventRef(), actual.eventRef())
                || !sameInvocationPolicy(
                        expected.invocationContext(), actual.invocationContext())
                || !expected.deadlineAt().equals(actual.deadlineAt())
                || !retryBudgetDoesNotIncrease(expected, actual)) {
            throw new IllegalArgumentException("AgentRun attempt conflicts with its logical run");
        }
    }

    private static boolean sameInvocationPolicy(
            InvocationContext expected, InvocationContext actual) {
        return expected.agentProfileId().equals(actual.agentProfileId())
                && expected.promptProfileId().equals(actual.promptProfileId())
                && expected.modelProfileId().equals(actual.modelProfileId())
                && expected.outputSchemaVersion().equals(actual.outputSchemaVersion())
                && expected.policyVersion().equals(actual.policyVersion())
                && expected.guardrailVersion().equals(actual.guardrailVersion())
                && expected.toolCapabilities().equals(actual.toolCapabilities());
    }

    private static boolean retryBudgetDoesNotIncrease(
            RoomGraphCommand expected, RoomGraphCommand actual) {
        return actual.retryBudget().providerAttemptsRemaining()
                        <= expected.retryBudget().providerAttemptsRemaining()
                && actual.retryBudget().activityAttemptsRemaining()
                        <= expected.retryBudget().activityAttemptsRemaining()
                && actual.retryBudget().repairsRemaining()
                        <= expected.retryBudget().repairsRemaining();
    }

    private static void requireResultMatchesRequest(
            ExecuteAgentRunRequest request, ExecuteAgentRunResult result) {
        if (result == null
                || !request.agentRunId().equals(result.agentRunId())
                || !request.logicalRunId().equals(result.logicalRunId())
                || !request.attemptId().equals(result.attemptId())
                || request.attemptNo() != result.attemptNo()) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "AgentRun Activity returned a stale or conflicting attempt result",
                    RESULT_REJECTED);
        }
        if (result.outcome() != ExecuteAgentRunResult.Outcome.COMPLETED) {
            return;
        }
        RoomGraphResult graphResult = result.graphResult();
        RoomGraphCommand command = request.command();
        if (graphResult == null
                || !command.commandId().equals(graphResult.commandId())
                || !command.graphKey().equals(graphResult.graphKey())
                || !command.graphVersion().equals(graphResult.graphVersion())) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "AgentRun Activity returned a conflicting graph result", RESULT_REJECTED);
        }
    }

    private static void requireReceiptMatchesResult(
            ExecuteAgentRunRequest request,
            ExecuteAgentRunResult result,
            AgentRunFinalizationReceipt receipt) {
        if (receipt == null
                || !request.agentRunId().equals(receipt.agentRunId())
                || !request.logicalRunId().equals(receipt.logicalRunId())
                || !request.attemptId().equals(receipt.attemptId())
                || request.attemptNo() != receipt.attemptNo()
                || !result.resultHash().equals(receipt.finalResultHash())
                || result.lastSequenceNo() != receipt.finalStreamSequenceNo()) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Finalizer returned a stale or conflicting receipt", FINALIZATION_REJECTED);
        }
    }

    private static void requireSameRequest(
            ExecuteAgentRunRequest expected, ExecuteAgentRunRequest actual) {
        if (expected == null || !expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "AgentRun attempt identity was reused with another request");
        }
    }

    private static ApplicationFailure rejected(String message) {
        return ApplicationFailure.newNonRetryableFailure(message, UPDATE_REJECTED);
    }

    private record AcceptedAttempt(
            ExecuteAgentRunRequest request,
            CompletablePromise<ExecuteAgentRunResult> completion) {

        static AcceptedAttempt initial(ExecuteAgentRunRequest request) {
            return new AcceptedAttempt(request, null);
        }

        static AcceptedAttempt updated(
                ExecuteAgentRunRequest request,
                CompletablePromise<ExecuteAgentRunResult> completion) {
            return new AcceptedAttempt(request, completion);
        }

        void complete(ExecuteAgentRunResult result) {
            completion.complete(result);
        }

        void fail(RuntimeException failure) {
            completion.completeExceptionally(failure);
        }
    }
}
