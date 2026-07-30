package com.example.dispute.agentstream.application;

import com.example.dispute.agentstream.application.AgentRunLedger.Attempt;
import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.agentstream.application.AgentRunLedger.RecoveryState;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional Java authority for allocating or replaying one AgentRun attempt. */
@Service
public class AgentRunV2RecoveryService {

    private final AgentRunLedger ledger;
    private final AgentRunV2NextAttemptFactory nextAttemptFactory;
    private final AgentRunStreamEventService streamEventService;
    private final List<AgentRunV2RetryPreparation> preparations;
    private final Clock clock;

    public AgentRunV2RecoveryService(
            AgentRunLedger ledger,
            AgentRunV2NextAttemptFactory nextAttemptFactory,
            AgentRunStreamEventService streamEventService,
            List<AgentRunV2RetryPreparation> preparations,
            Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.nextAttemptFactory = Objects.requireNonNull(nextAttemptFactory, "nextAttemptFactory");
        this.streamEventService = Objects.requireNonNull(streamEventService, "streamEventService");
        this.preparations = List.copyOf(Objects.requireNonNull(preparations, "preparations"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Whether this process owns at least one lane-specific durable retry preparation adapter. */
    public boolean isRecoveryConfigured() {
        return !preparations.isEmpty();
    }

    @Transactional
    public Optional<ExecuteAgentRunRequest> prepare(String agentRunId) {
        RecoveryState state = ledger.lockV2RecoveryState(agentRunId).orElse(null);
        if (state == null) {
            return Optional.empty();
        }
        return switch (state.logicalRun().status()) {
            case "PENDING" -> allocate(state);
            case "RUNNING" -> replayAllocated(state);
            default -> throw new IllegalStateException(
                    "V2 recovery candidate has an unsupported logical run status");
        };
    }

    private Optional<ExecuteAgentRunRequest> allocate(RecoveryState state) {
        Attempt predecessor = state.latestAttempt();
        if (predecessor.status() != AgentRunAttemptStatus.FAILED
                && predecessor.status() != AgentRunAttemptStatus.ABORTED) {
            return terminalize(state, "AGENT_RUN_RECOVERY_UNSUPPORTED_STATE", clock.instant());
        }
        ExecuteAgentRunResult failure = predecessor.durableFailureResult();
        if (!retryableFailure(state, failure)) {
            return terminalize(
                    state, "AGENT_RUN_RECOVERY_AUTHORIZATION_INVALID", clock.instant());
        }
        Instant now = clock.instant();
        if (state.logicalRun().deadlineAt() == null) {
            return terminalize(state, "AGENT_RUN_RECOVERY_DEADLINE_MISSING", now);
        }
        if (!now.isBefore(state.logicalRun().deadlineAt())) {
            return terminalize(state, "AGENT_RUN_RECOVERY_DEADLINE_EXCEEDED", now);
        }
        if (predecessor.attemptNo() >= state.logicalRun().attemptLimit()) {
            return terminalize(state, "AGENT_RUN_RECOVERY_ATTEMPT_LIMIT_EXHAUSTED", now);
        }
        RoomGraphCommand predecessorCommand;
        try {
            predecessorCommand = nextAttemptFactory.verifiedCommand(state);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return terminalize(state, "AGENT_RUN_RECOVERY_COMMAND_INVALID", now);
        }
        if (predecessorCommand.retryBudget().providerAttemptsRemaining() < 1) {
            return terminalize(state, "AGENT_RUN_RECOVERY_PROVIDER_BUDGET_EXHAUSTED", now);
        }

        AgentRunV2RetryPreparation preparation = preparation(state).orElse(null);
        if (preparation == null) {
            return terminalize(state, "AGENT_RUN_RECOVERY_PREPARER_MISSING", now);
        }
        AttemptAllocation allocation = Objects.requireNonNull(
                preparation.prepareNextAttempt(state, nextAttemptFactory, now),
                "retry preparer returned no allocation");
        if (allocation.attemptNo() != predecessor.attemptNo() + 1) {
            throw new IllegalStateException("retry preparer did not allocate the next attempt number");
        }
        Attempt attempt = ledger.startNextAttempt(
                state.logicalRun().agentRunId(), allocation, now);
        ExecuteAgentRunRequest request = request(state, attempt, allocation.command());
        preparation.persistAllocatedRequest(state, request);
        return Optional.of(request);
    }

    private Optional<ExecuteAgentRunRequest> replayAllocated(RecoveryState state) {
        Attempt attempt = state.latestAttempt();
        if (attempt.status() != AgentRunAttemptStatus.RUNNING) {
            return terminalize(state, "AGENT_RUN_RECOVERY_UNSUPPORTED_STATE", clock.instant());
        }
        Instant now = clock.instant();
        if (state.logicalRun().deadlineAt() == null) {
            return terminalize(state, "AGENT_RUN_RECOVERY_DEADLINE_MISSING", now);
        }
        if (!now.isBefore(state.logicalRun().deadlineAt())) {
            return terminalize(state, "AGENT_RUN_RECOVERY_DEADLINE_EXCEEDED", now);
        }
        if (attempt.attemptNo() > state.logicalRun().attemptLimit()) {
            return terminalize(state, "AGENT_RUN_RECOVERY_ATTEMPT_LIMIT_EXHAUSTED", now);
        }
        RoomGraphCommand command;
        try {
            command = nextAttemptFactory.verifiedCommand(state);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return terminalize(state, "AGENT_RUN_RECOVERY_COMMAND_INVALID", now);
        }
        ExecuteAgentRunRequest request = request(state, attempt, command);
        AgentRunV2RetryPreparation preparation = preparation(state).orElse(null);
        if (preparation == null) {
            return terminalize(state, "AGENT_RUN_RECOVERY_PREPARER_MISSING", now);
        }
        preparation.verifyAllocatedRequest(state, request);
        return Optional.of(request);
    }

    private Optional<ExecuteAgentRunRequest> terminalize(
            RecoveryState state, String errorCode, Instant completedAt) {
        ledger.terminalizeV2RecoveryCandidate(
                state.logicalRun().agentRunId(),
                state.latestAttempt().attemptId(),
                state.latestAttempt().attemptNo(),
                errorCode,
                completedAt);
        streamEventService.wakeUpAfterCommit(
                state.logicalRun().agentRunId(),
                state.latestAttempt().attemptId(),
                Math.addExact(state.latestAttempt().lastSequenceNo(), 1L));
        return Optional.empty();
    }

    private Optional<AgentRunV2RetryPreparation> preparation(RecoveryState state) {
        List<AgentRunV2RetryPreparation> matches = preparations.stream()
                .filter(candidate -> candidate.supports(state))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "V2 recovery has multiple matching lane retry preparers");
        }
        return matches.stream().findFirst();
    }

    private static boolean retryableFailure(
            RecoveryState state, ExecuteAgentRunResult failure) {
        Attempt predecessor = state.latestAttempt();
        return failure != null
                && failure.retryable()
                && failure.outcome() == ExecuteAgentRunResult.Outcome.FAILED
                && failure.recoveryAction() == AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT
                && failure.agentRunId().equals(state.logicalRun().agentRunId())
                && failure.attemptId().equals(predecessor.attemptId())
                && failure.attemptNo() == predecessor.attemptNo();
    }

    private static ExecuteAgentRunRequest request(
            RecoveryState state, Attempt attempt, RoomGraphCommand command) {
        if (!attempt.commandId().equals(command.commandId())
                || !attempt.commandRequestHash().equals(command.requestHash())
                || !attempt.logicalInputHash().equals(state.logicalRun().logicalInputHash())) {
            throw new IllegalStateException(
                    "allocated recovery attempt conflicts with its command binding");
        }
        return new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                state.logicalRun().agentRunId(),
                attempt.attemptNo(),
                state.logicalRun().attemptLimit(),
                "agent-stream.v2",
                attempt.logicalInputHash(),
                attempt.previousAttemptId(),
                attempt.resetRequired(),
                attempt.publicSequenceOffset(),
                command);
    }
}
