package com.example.dispute.workflow.activity.domain;

import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.CommandSource;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.DomainEventSource;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.ReadUnavailableException;
import com.example.dispute.workflow.activity.domain.IntakeChildBridgeReadPort.StartSource;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.ContractTypes.WriterMode;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpoch;
import com.example.dispute.workflow.temporal.caseprocess.CaseDomainEventRef;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.ActiveChildBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.CommandRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.DomainEventBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.DomainEventRequest;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.StartBinding;
import com.example.dispute.workflow.temporal.caseprocess.IntakeChildBridgeActivities.StartRequest;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventRef;
import com.example.dispute.workflow.temporal.room.intake.IntakeDomainEventType;
import com.example.dispute.workflow.temporal.room.intake.IntakeParty;
import com.example.dispute.workflow.temporal.room.intake.IntakeRoomStart;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import io.temporal.failure.ApplicationFailure;
import java.util.Objects;
import java.util.function.Supplier;

/** Validates every read-port value before exposing a typed Intake child binding. */
public final class IntakeChildBridgeActivitiesAdapter implements IntakeChildBridgeActivities {

    public static final String INVARIANT_FAILURE = "INTAKE_CHILD_BRIDGE_INVARIANT";
    public static final String READ_UNAVAILABLE = "INTAKE_CHILD_BRIDGE_READ_UNAVAILABLE";
    public static final String UNCLASSIFIED_READ_FAILURE =
            "INTAKE_CHILD_BRIDGE_READ_UNCLASSIFIED";

    private static final String SELECTION_V2 = "room-epoch-selection.v2";
    private static final String ROOM_WORKFLOW_TYPE = "IntakeRoomWorkflow";
    private static final String GRAPH_KEY = "intake.v2";

    private final IntakeChildBridgeReadPort readPort;

    public IntakeChildBridgeActivitiesAdapter(IntakeChildBridgeReadPort readPort) {
        this.readPort = Objects.requireNonNull(readPort, "readPort");
    }

    @Override
    public StartBinding bindStart(StartRequest request) {
        StartRequest expected = requireRequest(request);
        StartSource source = read(() -> readPort.readStart(expected));
        return validate(() -> startBinding(expected, source));
    }

    @Override
    public CommandBinding bindCommand(CommandRequest request) {
        CommandRequest expected = requireRequest(request);
        CommandSource source = read(() -> readPort.readCommand(expected));
        return validate(() -> commandBinding(expected, source));
    }

    @Override
    public DomainEventBinding bindDomainEvent(DomainEventRequest request) {
        DomainEventRequest expected = requireRequest(request);
        DomainEventSource source = read(() -> readPort.readDomainEvent(expected));
        return validate(() -> eventBinding(expected, source));
    }

    private static StartBinding startBinding(StartRequest request, StartSource source) {
        Objects.requireNonNull(source, "start source");
        ProvisionRoomEpoch provision = request.provisioning();
        ActiveChildBinding active = request.activeBinding();
        requireActiveBinding(active, source.persistedBinding());
        requireProvisioningScope(provision, active);
        requireTypedSelection(active);
        requireEqual(source.provisioningRequestHash(), provision.payloadSha256(),
                "provisioning request hash");
        requireIdentifier(source.promptVersion(), "prompt version");
        requireIdentifier(source.modelProfileId(), "model profile");
        requireIdentifier(source.outputSchemaVersion(), "output schema");
        requireIdentifier(source.policyVersion(), "policy version");
        requireIdentifier(source.guardrailVersion(), "guardrail version");
        requireIdentifier(source.toolPolicyVersion(), "tool policy version");
        requireHash(source.initiatorActorScopeHash(), "initiator actor scope");
        requireHash(source.respondentActorScopeHash(), "respondent actor scope");
        if (source.initiatorActorScopeHash().equals(source.respondentActorScopeHash())) {
            throw new IllegalArgumentException("party actor scopes must be distinct");
        }

        IntakeRoomStart start =
                new IntakeRoomStart(
                        "intake-room-start.v1",
                        provision.tenantSurrogate(),
                        provision.caseId(),
                        provision.roomEpoch(),
                        provision.fencingToken(),
                        provision.initialProcessRevision(),
                        provision.initialRoomRevision(),
                        provision.firstCommandSequence(),
                        provision.firstCaseEventSequence(),
                        active.roomWorkflowBuildId(),
                        provision.graphVersion(),
                        provision.checkpointSchemaVersion(),
                        source.promptVersion(),
                        source.modelProfileId(),
                        source.outputSchemaVersion(),
                        source.policyVersion(),
                        source.guardrailVersion(),
                        source.toolPolicyVersion(),
                        source.initiatorActorScopeHash(),
                        source.respondentActorScopeHash());
        return new StartBinding(
                "intake-child-start-binding.v1",
                active,
                source.provisioningRequestHash(),
                start);
    }

    private static CommandBinding commandBinding(CommandRequest request, CommandSource source) {
        Objects.requireNonNull(source, "command source");
        CaseCommandRef command = request.command();
        ActiveChildBinding active = request.activeBinding();
        requireActiveBinding(active, source.persistedBinding());
        requireTypedSelection(active);
        requireEqual(source.commandId(), command.commandId(), "command id");
        requireEqual(source.tenantSurrogate(), command.tenantSurrogate(), "tenant");
        requireEqual(source.caseId(), command.caseId(), "case");
        requireEqual(source.roomEpoch(), command.roomEpoch(), "room epoch");
        requireEqual(source.fencingToken(), active.fencingToken(), "fencing token");
        requireEqual(source.sequence(), command.caseCommandSequence(), "command sequence");
        requireSame(source.commandType(), command.commandType(), "command type");
        requireEqual(source.sourcePayloadHash(), command.payloadRef().sha256(), "payload hash");
        requireEqual(source.sourceRequestHash(), command.requestHash(), "request hash");
        requireEqual(source.processRevision(), command.expectedProcessRevision(), "process revision");
        requireNonNegative(source.roomRevision(), "room revision");
        requireGenericScope(
                command.tenantSurrogate(), command.caseId(), command.roomType(), command.roomEpoch(),
                active);
        requireHash(source.actorScopeHash(), "actor scope");
        requireOperationKey(source.operationKey());
        requireEqual(
                source.operationKey(),
                "intake.operation:" + command.caseId() + ":" + command.commandId(),
                "command operation key");
        requireParticipantRole(command.actorRef().actorRole());
        Objects.requireNonNull(source.party(), "authoritative Intake party");
        IntakeCommandType type = commandType(command.commandType());
        if (source.executionContext() != null) {
            throw new IllegalArgumentException(
                    "current Intake authority gate permits only inert external events");
        }

        IntakeWorkflowCommand typed =
                new IntakeWorkflowCommand(
                        "intake-workflow-command.v1",
                        command.commandId(),
                        command.tenantSurrogate(),
                        command.caseId(),
                        command.roomEpoch(),
                        active.fencingToken(),
                        command.caseCommandSequence(),
                        type,
                        source.party(),
                        source.actorScopeHash(),
                        command.payloadRef().uri(),
                        command.payloadRef().sha256(),
                        source.operationKey(),
                        command.requestHash(),
                        source.executionContext());
        return new CommandBinding(
                "intake-child-command-binding.v1",
                active,
                source.sourcePayloadHash(),
                source.sourceRequestHash(),
                source.processRevision(),
                source.roomRevision(),
                typed);
    }

    private static DomainEventBinding eventBinding(
            DomainEventRequest request, DomainEventSource source) {
        Objects.requireNonNull(source, "domain event source");
        CaseDomainEventRef event = request.event();
        ActiveChildBinding active = request.activeBinding();
        requireActiveBinding(active, source.persistedBinding());
        requireTypedSelection(active);
        requireEqual(source.eventId(), event.eventId(), "event id");
        requireEqual(source.sourceEventType(), event.eventType(), "source event type");
        requireEqual(source.tenantSurrogate(), event.tenantSurrogate(), "tenant");
        requireEqual(source.caseId(), event.caseId(), "case");
        requireEqual(source.roomEpoch(), event.roomEpoch(), "room epoch");
        requireEqual(source.fencingToken(), active.fencingToken(), "fencing token");
        requireEqual(source.sequence(), event.caseEventSequence(), "event sequence");
        requireEqual(source.sourcePayloadHash(), event.payloadRef().sha256(), "payload hash");
        requireGenericScope(
                event.tenantSurrogate(), event.caseId(), event.roomType(), event.roomEpoch(), active);
        IntakeDomainEventType mappedType = eventType(event.eventType());
        requireSame(source.eventType(), mappedType, "typed event type");
        requireEventParty(mappedType, source.party());
        requireIdentifier(source.commandId(), "command id");
        requireHash(source.actorScopeHash(), "actor scope");
        requireOperationKey(source.operationKey());
        requireHash(source.requestHash(), "request hash");
        requireHash(source.resultHash(), "result hash");
        requireNonNegative(source.processRevision(), "process revision");
        requireNonNegative(source.roomRevision(), "room revision");
        boolean turnEvent = mappedType == IntakeDomainEventType.TURN_NEEDS_INPUT
                || mappedType == IntakeDomainEventType.TURN_READY_TO_CONFIRM;
        if (turnEvent && (source.agentRunRef() == null || source.graphExecutionRef() == null)) {
            throw new IllegalArgumentException("turn event requires Agent and Graph execution references");
        }
        if (!turnEvent && (source.agentRunRef() != null || source.graphExecutionRef() != null)) {
            throw new IllegalArgumentException("branch event forbids Agent and Graph execution references");
        }

        IntakeDomainEventRef typed =
                new IntakeDomainEventRef(
                        "intake-domain-event-ref.v1",
                        event.eventId(),
                        source.eventRef(),
                        source.eventHash(),
                        event.caseEventSequence(),
                        mappedType,
                        source.party(),
                        source.commandId(),
                        event.tenantSurrogate(),
                        event.caseId(),
                        event.roomEpoch(),
                        active.fencingToken(),
                        source.actorScopeHash(),
                        source.operationKey(),
                        source.requestHash(),
                        source.resultHash(),
                        source.processRevision(),
                        source.roomRevision(),
                        source.agentRunRef(),
                        source.graphExecutionRef());
        return new DomainEventBinding(
                "intake-child-domain-event-binding.v1",
                active,
                source.sourcePayloadHash(),
                source.requestHash(),
                source.processRevision(),
                source.roomRevision(),
                typed);
    }

    private static void requireProvisioningScope(
            ProvisionRoomEpoch provision, ActiveChildBinding active) {
        requireGenericScope(
                provision.tenantSurrogate(), provision.caseId(), provision.roomType(),
                provision.roomEpoch(), active);
        requireEqual(provision.fencingToken(), active.fencingToken(), "fencing token");
        requireEqual(provision.selectionSchemaVersion(), active.selectionSchemaVersion(),
                "selection schema version");
        requireEqual(provision.workflowType(), active.caseWorkflowType(), "case workflow type");
        requireEqual(provision.temporalBuildId(), active.caseWorkflowBuildId(),
                "case workflow build id");
        requireEqual(provision.roomWorkflowType(), active.roomWorkflowType(),
                "room workflow type");
        requireEqual(provision.roomWorkflowBuildId(), active.roomWorkflowBuildId(),
                "room workflow build id");
        if (provision.writerMode() != WriterMode.SHADOW) {
            throw new IllegalArgumentException("current typed Intake bridge gate requires SHADOW");
        }
        if (!GRAPH_KEY.equals(provision.graphKey())) {
            throw new IllegalArgumentException("persisted typed Intake selection is invalid");
        }
    }

    private static void requireTypedSelection(ActiveChildBinding active) {
        if (!SELECTION_V2.equals(active.selectionSchemaVersion())
                || !CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE.equals(active.caseWorkflowType())
                || !ROOM_WORKFLOW_TYPE.equals(active.roomWorkflowType())) {
            throw new IllegalArgumentException("persisted typed Intake selection is invalid");
        }
    }

    private static void requireGenericScope(
            String tenant,
            String caseId,
            RoomType roomType,
            long roomEpoch,
            ActiveChildBinding active) {
        requireEqual(tenant, active.tenantSurrogate(), "tenant");
        requireEqual(caseId, active.caseId(), "case");
        requireEqual(roomEpoch, active.roomEpoch(), "room epoch");
        if (roomType != RoomType.INTAKE) {
            throw new IllegalArgumentException("source does not belong to Intake");
        }
    }

    private static void requireActiveBinding(
            ActiveChildBinding expected, ActiveChildBinding persisted) {
        if (!expected.equals(persisted)) {
            throw new IllegalArgumentException("persisted active child binding mismatch");
        }
    }

    private static IntakeCommandType commandType(CommandType source) {
        return switch (source) {
            case INTAKE_MESSAGE -> IntakeCommandType.INTAKE_MESSAGE;
            case INTAKE_CONFIRM -> IntakeCommandType.INTAKE_CONFIRM;
            case INTAKE_CANCEL -> IntakeCommandType.INTAKE_CANCEL;
            default -> throw new IllegalArgumentException("unknown Intake command type");
        };
    }

    private static void requireParticipantRole(ActorRole role) {
        if (role != ActorRole.USER && role != ActorRole.MERCHANT) {
            throw new IllegalArgumentException("unknown Intake actor role");
        }
    }

    private static IntakeDomainEventType eventType(String source) {
        return switch (source) {
            case "TURN_NEEDS_INPUT", "INTAKE_TURN_NEEDS_INPUT" ->
                    IntakeDomainEventType.TURN_NEEDS_INPUT;
            case "TURN_READY_TO_CONFIRM", "INTAKE_TURN_READY_TO_CONFIRM" ->
                    IntakeDomainEventType.TURN_READY_TO_CONFIRM;
            case "INITIATOR_ACCEPTED", "INITIATOR_INTAKE_COMPLETED" ->
                    IntakeDomainEventType.INITIATOR_ACCEPTED;
            case "NOT_ADMISSIBLE", "INTAKE_REJECTED" -> IntakeDomainEventType.NOT_ADMISSIBLE;
            case "CANCELLED", "INTAKE_CANCELLED" -> IntakeDomainEventType.CANCELLED;
            case "RESPONDENT_CONFIRMED", "RESPONDENT_INTAKE_COMPLETED" ->
                    IntakeDomainEventType.RESPONDENT_CONFIRMED;
            default -> throw new IllegalArgumentException("unknown Intake domain event type");
        };
    }

    private static void requireEventParty(IntakeDomainEventType type, IntakeParty party) {
        boolean valid =
                switch (type) {
                    case RESPONDENT_CONFIRMED -> party == IntakeParty.RESPONDENT;
                    case INITIATOR_ACCEPTED, NOT_ADMISSIBLE, CANCELLED ->
                            party == IntakeParty.INITIATOR;
                    case TURN_NEEDS_INPUT, TURN_READY_TO_CONFIRM -> true;
                };
        if (!valid) {
            throw new IllegalArgumentException("event type does not match the Intake party");
        }
    }

    private static <T> T requireRequest(T request) {
        if (request == null) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Intake child bridge request is missing", INVARIANT_FAILURE);
        }
        return request;
    }

    private static <T> T read(Supplier<T> read) {
        try {
            return read.get();
        } catch (ReadUnavailableException failure) {
            throw ApplicationFailure.newFailureWithCause(
                    "Intake child bridge source is temporarily unavailable",
                    READ_UNAVAILABLE,
                    failure,
                    failure.getClass().getSimpleName());
        } catch (RuntimeException failure) {
            throw ApplicationFailure.newNonRetryableFailureWithCause(
                    "Intake child bridge read failed without an explicit retry classification",
                    UNCLASSIFIED_READ_FAILURE,
                    failure,
                    failure.getClass().getName());
        }
    }

    private static <T> T validate(Supplier<T> binding) {
        try {
            return binding.get();
        } catch (RuntimeException failure) {
            throw ApplicationFailure.newNonRetryableFailureWithCause(
                    "Intake child bridge binding violates its authority contract",
                    INVARIANT_FAILURE,
                    failure,
                    failure.getClass().getSimpleName());
        }
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded identifier");
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 value");
        }
    }

    private static void requireOperationKey(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 512
                || !value.chars().allMatch(character -> character >= 0x20 && character <= 0x7e)) {
            throw new IllegalArgumentException("operation key must be bounded ASCII");
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    private static void requireEqual(Object actual, Object expected, String field) {
        if (!Objects.equals(actual, expected)) {
            throw new IllegalArgumentException(field + " mismatch");
        }
    }

    private static void requireSame(Object actual, Object expected, String field) {
        if (actual != expected) {
            throw new IllegalArgumentException(field + " mismatch");
        }
    }

    private static void requireEqual(long actual, long expected, String field) {
        if (actual != expected) {
            throw new IllegalArgumentException(field + " mismatch");
        }
    }
}
