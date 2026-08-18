package com.example.dispute.agentstream.application;

import com.example.dispute.agentstream.application.AgentRunLedger.Attempt;
import com.example.dispute.agentstream.application.AgentRunLedger.AttemptAllocation;
import com.example.dispute.agentstream.application.AgentRunLedger.CreateLogicalRun;
import com.example.dispute.agentstream.application.AgentRunLedger.LogicalRun;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory.Binding;
import com.example.dispute.agentstream.application.AgentRunCommandBindingFactory.Context;
import com.example.dispute.workflow.application.AgentRunV2WorkflowLaunchException;
import com.example.dispute.workflow.application.AgentRunV2WorkflowLauncher;
import com.example.dispute.workflow.application.AgentRunV2WorkflowLauncher.StartDisposition;
import com.example.dispute.workflow.application.AgentRunV2WorkflowLauncher.StartReceipt;
import com.example.dispute.workflow.application.TemporalAgentRunV2WorkflowLauncher;
import com.example.dispute.workflow.config.AgentRunV2Properties;
import com.example.dispute.workflow.config.AgentRunV2Properties.SchedulerMode;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunExecutorKind;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunRequest;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import com.example.dispute.workflow.temporal.agentrun.AgentRunTemporalPolicy;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Explicit SHADOW-only entry point for a persisted logical run and its first-class attempt. */
@Service
public final class AgentRunV2Coordinator {

    private final AgentRunLedger ledger;
    private final AgentRunCommandBindingFactory commandBindingFactory;
    private final AgentRunV2WorkflowLauncher workflowLauncher;
    private final AgentRunV2Properties properties;
    private final Clock clock;

    public AgentRunV2Coordinator(
            AgentRunLedger ledger,
            AgentRunCommandBindingFactory commandBindingFactory,
            AgentRunV2WorkflowLauncher workflowLauncher,
            AgentRunV2Properties properties,
            Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.commandBindingFactory =
                Objects.requireNonNull(commandBindingFactory, "commandBindingFactory");
        this.workflowLauncher = Objects.requireNonNull(workflowLauncher, "workflowLauncher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StartOutcome start(StartCommand command) {
        Objects.requireNonNull(command, "command");
        requireShadowSelection(command.selection());
        RoomGraphCommand graphCommand = command.graphCommand();
        Instant startedAt = clock.instant();
        Binding binding = commandBindingFactory.bind(
                new Context(
                        command.roomId(),
                        command.roomEpochId(),
                        command.operation(),
                        command.logicalIdempotencyKey()),
                graphCommand);
        CreateLogicalRun create =
                new CreateLogicalRun(
                        graphCommand.logicalRunId(),
                        graphCommand.tenantSurrogate(),
                        graphCommand.caseId(),
                        command.roomId(),
                        command.operation(),
                        command.logicalIdempotencyKey(),
                        AgentRunProtocol.V3,
                        AgentRunExecutorKind.TEMPORAL_ACTIVITY,
                        command.roomEpochId(),
                        graphCommand.roomType(),
                        graphCommand.roomEpoch(),
                        graphCommand.processRevision(),
                        command.fencingToken(),
                        graphCommand.requestHash(),
                        binding.logicalInputHash(),
                        command.attemptLimit(),
                        graphCommand.deadlineAt(),
                        startedAt);
        LogicalRun logicalRun = ledger.createOrLoad(create);
        requireLogicalRun(logicalRun, graphCommand, command, binding);

        Attempt attempt = ledger.startNextAttempt(
                logicalRun.agentRunId(),
                new AttemptAllocation(command.attemptNo(), graphCommand, binding),
                startedAt);
        ExecuteAgentRunRequest request = new ExecuteAgentRunRequest(
                ExecuteAgentRunRequest.SCHEMA_VERSION,
                logicalRun.agentRunId(),
                command.attemptNo(),
                logicalRun.attemptLimit(),
                AgentRunProtocol.V3.wireValue(),
                attempt.logicalInputHash(),
                attempt.previousAttemptId(),
                attempt.resetRequired(),
                attempt.publicSequenceOffset(),
                graphCommand);
        requireAttempt(attempt, request);
        StartReceipt workflow;
        try {
            workflow = workflowLauncher.start(request);
            requireWorkflow(workflow, request);
        } catch (AgentRunV2WorkflowLaunchException failure) {
            recordPermanentAdmissionFailure(attempt, request, failure);
            throw failure;
        }
        return new StartOutcome(logicalRun, attempt, request, workflow);
    }

    /** Replays one already committed attempt allocation to the stable workflow. */
    public StartReceipt dispatchAllocatedAttempt(ExecuteAgentRunRequest request) {
        Objects.requireNonNull(request, "request");
        requireV2Runtime();
        Attempt attempt = ledger.requireAllocatedAttempt(request);
        requireAttempt(attempt, request);
        try {
            StartReceipt workflow = workflowLauncher.start(request);
            requireWorkflow(workflow, request);
            return workflow;
        } catch (AgentRunV2WorkflowLaunchException failure) {
            recordPermanentAdmissionFailure(attempt, request, failure);
            throw failure;
        }
    }

    private void recordPermanentAdmissionFailure(
            Attempt attempt,
            ExecuteAgentRunRequest request,
            AgentRunV2WorkflowLaunchException failure) {
        if (failure.retryable() || attempt.status() != AgentRunAttemptStatus.RUNNING) {
            return;
        }
        try {
            ledger.recordAttemptFailure(
                    request.agentRunId(),
                    request.attemptId(),
                    request.attemptNo(),
                    AgentRunAttemptStatus.FAILED,
                    failure.code(),
                    AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                    clock.instant());
        } catch (RuntimeException persistenceFailure) {
            AgentRunV2WorkflowLaunchException retryable =
                    AgentRunV2WorkflowLaunchException.retryable(
                            "AGENT_RUN_ADMISSION_FAILURE_UNPERSISTED", persistenceFailure);
            retryable.addSuppressed(failure);
            throw retryable;
        }
    }

    private void requireShadowSelection(Selection selection) {
        requireV2Runtime();
        if (selection != Selection.SHADOW) {
            throw new IllegalStateException("AgentRun V2 execution is restricted to SHADOW");
        }
    }

    private void requireV2Runtime() {
        if (!properties.enabled()) {
            throw new IllegalStateException("AgentRun V2 is OFF");
        }
        if (properties.schedulerMode() == SchedulerMode.EXECUTOR) {
            throw new IllegalStateException("legacy scheduler cannot execute AgentRun V2");
        }
    }

    private static void requireLogicalRun(
            LogicalRun logicalRun,
            RoomGraphCommand graphCommand,
            StartCommand command,
            Binding binding) {
        if (logicalRun == null
                || !graphCommand.logicalRunId().equals(logicalRun.agentRunId())
                || !graphCommand.caseId().equals(logicalRun.caseId())
                || !command.logicalIdempotencyKey().equals(logicalRun.logicalIdempotencyKey())
                || logicalRun.protocol() != AgentRunProtocol.V3
                || logicalRun.executorKind() != AgentRunExecutorKind.TEMPORAL_ACTIVITY
                || !command.roomEpochId().equals(logicalRun.roomEpochId())
                || graphCommand.roomEpoch() != logicalRun.roomEpoch()
                || graphCommand.processRevision() != logicalRun.processRevision()
                || command.fencingToken() != logicalRun.fencingToken()
                || !AgentRunLedger.LOGICAL_LINEAGE_SCHEMA_VERSION.equals(
                        logicalRun.lineageSchemaVersion())
                || !binding.logicalInputHash().equals(logicalRun.logicalInputHash())
                || command.attemptLimit() != logicalRun.attemptLimit()
                || !Objects.equals(graphCommand.deadlineAt(), logicalRun.deadlineAt())) {
            throw new IllegalStateException(
                    "persisted logical AgentRun conflicts with the V2 command");
        }
    }

    private static void requireAttempt(Attempt attempt, ExecuteAgentRunRequest request) {
        if (attempt == null
                || !request.agentRunId().equals(attempt.agentRunId())
                || !request.attemptId().equals(attempt.attemptId())
                || request.attemptNo() != attempt.attemptNo()
                || !request.logicalInputHash().equals(attempt.logicalInputHash())
                || !Objects.equals(
                        request.previousAttemptId(), attempt.previousAttemptId())
                || request.resetRequired() != attempt.resetRequired()
                || request.publicSequenceOffset() != attempt.publicSequenceOffset()
                || !request.command().commandId().equals(attempt.commandId())
                || !request.command().requestHash().equals(attempt.commandRequestHash())
                || (attempt.status() != AgentRunAttemptStatus.RUNNING
                        && attempt.status() != AgentRunAttemptStatus.RESULT_READY
                        && attempt.status() != AgentRunAttemptStatus.COMPLETED)) {
            throw new IllegalStateException("persisted attempt conflicts with the V2 request");
        }
    }

    private static void requireWorkflow(StartReceipt workflow, ExecuteAgentRunRequest request) {
        String expectedWorkflowId =
                TemporalAgentRunV2WorkflowLauncher.workflowId(request.logicalRunId());
        boolean validDisposition =
                request.attemptNo() == 1
                        ? workflow != null
                                && (workflow.disposition() == StartDisposition.STARTED
                                        || workflow.disposition()
                                                == StartDisposition.ALREADY_STARTED)
                        : workflow != null
                                && workflow.disposition() == StartDisposition.ATTEMPT_ACCEPTED;
        if (!validDisposition || !expectedWorkflowId.equals(workflow.workflowId())) {
            throw AgentRunV2WorkflowLaunchException.permanent(
                    "TEMPORAL_RECEIPT_CONFLICT",
                    new IllegalStateException(
                            "Temporal workflow receipt conflicts with the AgentRun attempt"));
        }
    }

    public enum Selection {
        OFF,
        SHADOW
    }

    public record StartCommand(
            Selection selection,
            String logicalIdempotencyKey,
            String roomId,
            String roomEpochId,
            String operation,
            long fencingToken,
            int attemptNo,
            int attemptLimit,
            RoomGraphCommand graphCommand) {
        public StartCommand {
            required(selection, "selection");
            required(logicalIdempotencyKey, "logicalIdempotencyKey");
            required(roomId, "roomId");
            required(roomEpochId, "roomEpochId");
            required(operation, "operation");
            required(graphCommand, "graphCommand");
            if (fencingToken < 1
                    || attemptNo < 1
                    || attemptLimit < attemptNo
                    || attemptLimit > AgentRunTemporalPolicy.MAXIMUM_LOGICAL_ATTEMPTS) {
                throw new IllegalArgumentException("fence and attempt bounds are invalid");
            }
            if (graphCommand.logicalRunId().isBlank()) {
                throw new IllegalArgumentException("graph logicalRunId is invalid");
            }
        }

        private static void required(Object value, String field) {
            if (value == null || (value instanceof String text && text.isBlank())) {
                throw new IllegalArgumentException(field + " is required");
            }
        }
    }

    public record StartOutcome(
            LogicalRun logicalRun,
            Attempt attempt,
            ExecuteAgentRunRequest request,
            StartReceipt workflow) {}
}
