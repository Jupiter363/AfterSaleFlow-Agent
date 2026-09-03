package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.shadow.intake.SignedSyntheticIntakeCommandAdmissionLookup.PersistedCommandAdmission;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.DomainEventRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.StartRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandExecutionContext;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import java.time.Clock;
import java.util.Objects;

/**
 * Synthetic-only read-port decorator that upgrades an inert authoritative command from durable
 * admission state. Missing, stale, or mismatched admission fails closed.
 */
public final class SignedSyntheticIntakeBridgeReadPortDecorator
        implements IntakeChildBridgeReadPort {

    private final IntakeChildBridgeReadPort delegate;
    private final SignedSyntheticIntakeCommandAdmissionLookup admissions;
    private final Clock clock;

    public SignedSyntheticIntakeBridgeReadPortDecorator(
            IntakeChildBridgeReadPort delegate,
            SignedSyntheticIntakeCommandAdmissionLookup admissions,
            Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.admissions = Objects.requireNonNull(admissions, "admissions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public StartSource readStart(StartRequest request) {
        return delegate.readStart(request);
    }

    @Override
    public CommandSource readCommand(CommandRequest request) {
        CommandSource source = delegate.readCommand(request);
        if (source.executionContext() != null) {
            throw new SecurityException("caller-supplied Intake execution context is forbidden");
        }
        if (request.command().commandType() != CommandType.INTAKE_MESSAGE) {
            return source;
        }
        PersistedCommandAdmission admission = Objects.requireNonNull(
                admissions.require(request, source),
                "signed synthetic admission lookup must fail closed, not return null");
        requireExact(request, source, admission);
        if (admission.deadlineEpochMillis() <= clock.millis()) {
            throw new SecurityException("signed synthetic command deadline has expired");
        }
        return new CommandSource(
                source.persistedBinding(),
                source.commandId(),
                source.tenantSurrogate(),
                source.caseId(),
                source.roomEpoch(),
                source.fencingToken(),
                source.sequence(),
                source.commandType(),
                source.sourcePayloadHash(),
                source.sourceRequestHash(),
                source.processRevision(),
                source.roomRevision(),
                source.party(),
                source.actorScopeHash(),
                source.operationKey(),
                new IntakeCommandExecutionContext(
                        "intake-command-execution-context.v1",
                        admission.threadId(),
                        admission.agentSessionId(),
                        admission.deadlineEpochMillis(),
                        admission.retryBudget(),
                        null));
    }

    @Override
    public DomainEventSource readDomainEvent(DomainEventRequest request) {
        return delegate.readDomainEvent(request);
    }

    private static void requireExact(
            CommandRequest request, CommandSource source, PersistedCommandAdmission admission) {
        var command = request.command();
        boolean exact =
                admission.tenantSurrogate().equals(command.tenantSurrogate())
                        && admission.tenantSurrogate().equals(source.tenantSurrogate())
                        && admission.caseId().equals(command.caseId())
                        && admission.caseId().equals(source.caseId())
                        && admission.roomEpoch() == command.roomEpoch()
                        && admission.roomEpoch() == source.roomEpoch()
                        && admission.fencingToken() == source.fencingToken()
                        && admission.commandId().equals(command.commandId())
                        && admission.commandId().equals(source.commandId())
                        && admission.commandSequence() == command.caseCommandSequence()
                        && admission.commandSequence() == source.sequence()
                        && admission.commandType() == IntakeCommandType.INTAKE_MESSAGE
                        && source.commandType() == CommandType.INTAKE_MESSAGE
                        && admission.party() == source.party()
                        && admission.payloadRef().equals(command.payloadRef().uri())
                        && admission.payloadHash().equals(command.payloadRef().sha256())
                        && admission.payloadHash().equals(source.sourcePayloadHash())
                        && admission.operationKey().equals(source.operationKey())
                        && admission.operationKey().equals(
                                "intake.operation:" + command.caseId() + ":" + command.commandId())
                        && admission.actorScopeHash().equals(source.actorScopeHash())
                        && admission.requestHash().equals(command.requestHash())
                        && admission.requestHash().equals(source.sourceRequestHash())
                        && admission.processRevision() == command.expectedProcessRevision()
                        && admission.processRevision() == source.processRevision()
                        && admission.roomRevision() == source.roomRevision()
                        && admission.deadlineEpochMillis() == command.deadlineAt().toEpochMilli();
        if (!exact) {
            throw new SecurityException(
                    "signed synthetic admission does not match the authoritative command");
        }
    }
}
