package com.example.dispute.workflow.application.intake;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.contract.v1.RoomGraphCommand;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Creates a proposal-only room-graph-command.v1 from immutable private references. */
public final class IntakeGraphCommandFactory {

    private static final String ZERO_HASH = "0".repeat(64);

    public RoomGraphCommand create(CommandRequest request) {
        Objects.requireNonNull(request, "request");
        IntakeGraphThreadBinding binding = request.threadBinding();
        IntakePrivateThreadRegistration registration = binding.registration();
        registration.requireCanonicalHash();
        requireSnapshotMatches(binding, request.initialSnapshot());
        if (request.event() != null) {
            requireEventMatches(binding, request.initialSnapshot(), request.event());
        }

        RoomGraphCommand unsigned = command(request, registration, ZERO_HASH);
        String requestHash = IntakeContractHashes.graphCommandHash(unsigned);
        return command(request, registration, requestHash);
    }

    private static RoomGraphCommand command(
            CommandRequest request,
            IntakePrivateThreadRegistration registration,
            String requestHash) {
        var actor = registration.actorScope();
        return new RoomGraphCommand(
                "room-graph-command.v1",
                request.commandId(),
                request.logicalRunId(),
                request.attemptId(),
                registration.tenantSurrogate(),
                registration.caseId(),
                RoomType.INTAKE,
                registration.roomEpoch(),
                registration.graphKey(),
                registration.graphVersion(),
                registration.checkpointSchemaVersion(),
                registration.threadId(),
                new RoomGraphCommand.ActorScope(
                        actor.actorId(),
                        actor.actorRole(),
                        actor.audience(),
                        actor.capabilities()),
                request.processRevision(),
                request.stageCode(),
                request.stageSequence(),
                request.initialSnapshot().payloadRef(),
                request.event() == null ? null : request.event().payloadRef(),
                new RoomGraphCommand.InvocationContext(
                        request.agentProfileId(),
                        registration.promptVersion(),
                        registration.modelProfileId(),
                        registration.outputSchemaVersion(),
                        registration.policyVersion(),
                        registration.guardrailVersion(),
                        List.of(),
                        request.envelopeKeyId(),
                        request.envelopeNonce()),
                new RoomGraphCommand.RetryBudget(
                        request.providerAttemptsRemaining(),
                        request.activityAttemptsRemaining(),
                        request.repairsRemaining()),
                request.deadlineAt(),
                request.traceparent(),
                requestHash);
    }

    private static void requireSnapshotMatches(
            IntakeGraphThreadBinding binding, IntakeSnapshotReference snapshot) {
        IntakePrivateThreadRegistration registration = binding.registration();
        if (!registration.registrationId().equals(snapshot.threadRegistrationId())
                || !registration.tenantSurrogate().equals(snapshot.tenantSurrogate())
                || !registration.caseId().equals(snapshot.caseId())
                || registration.roomEpoch() != snapshot.roomEpoch()
                || binding.fencingToken() != snapshot.fencingToken()
                || !registration.threadId().equals(snapshot.threadId())
                || !registration.actorScopeHash().equals(snapshot.actorScopeHash())
                || !registration.agentSessionId().equals(snapshot.agentSessionId())) {
            throw new IntakeGraphBindingConflictException(
                    "initial snapshot does not match the private thread binding");
        }
    }

    private static void requireEventMatches(
            IntakeGraphThreadBinding binding,
            IntakeSnapshotReference snapshot,
            IntakeEventReference event) {
        IntakePrivateThreadRegistration registration = binding.registration();
        if (!registration.registrationId().equals(event.threadRegistrationId())
                || !registration.tenantSurrogate().equals(event.tenantSurrogate())
                || !registration.caseId().equals(event.caseId())
                || registration.roomEpoch() != event.roomEpoch()
                || binding.fencingToken() != event.fencingToken()
                || !registration.threadId().equals(event.threadId())
                || !registration.actorScopeHash().equals(event.actorScopeHash())
                || !registration.agentSessionId().equals(event.agentSessionId())
                || registration.actorScope().audience() != event.audience()
                || event.domainRevision() < snapshot.domainRevision()) {
            throw new IntakeGraphBindingConflictException(
                    "event does not match the private thread and snapshot binding");
        }
    }

    public record CommandRequest(
            String commandId,
            String logicalRunId,
            String attemptId,
            IntakeGraphThreadBinding threadBinding,
            IntakeSnapshotReference initialSnapshot,
            IntakeEventReference event,
            long processRevision,
            String stageCode,
            long stageSequence,
            String agentProfileId,
            int providerAttemptsRemaining,
            int activityAttemptsRemaining,
            int repairsRemaining,
            Instant deadlineAt,
            String traceparent,
            String envelopeKeyId,
            String envelopeNonce) {

        public CommandRequest {
            commandId = IntakeContractSupport.identifier(commandId, "commandId");
            logicalRunId = IntakeContractSupport.identifier(logicalRunId, "logicalRunId");
            attemptId = IntakeContractSupport.identifier(attemptId, "attemptId");
            threadBinding = Objects.requireNonNull(threadBinding, "threadBinding");
            initialSnapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
            IntakeContractSupport.nonNegative(processRevision, "processRevision");
            stageCode = IntakeContractSupport.identifier(stageCode, "stageCode");
            IntakeContractSupport.nonNegative(stageSequence, "stageSequence");
            agentProfileId = IntakeContractSupport.identifier(agentProfileId, "agentProfileId");
            if (providerAttemptsRemaining < 0 || providerAttemptsRemaining > 2) {
                throw new IllegalArgumentException("providerAttemptsRemaining must be between 0 and 2");
            }
            if (activityAttemptsRemaining < 0 || activityAttemptsRemaining > 3) {
                throw new IllegalArgumentException("activityAttemptsRemaining must be between 0 and 3");
            }
            if (repairsRemaining < 0 || repairsRemaining > 1) {
                throw new IllegalArgumentException("repairsRemaining must be between 0 and 1");
            }
            deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
            traceparent = IntakeContractSupport.traceparent(traceparent);
            envelopeKeyId = IntakeContractSupport.identifier(envelopeKeyId, "envelopeKeyId");
            envelopeNonce = IntakeContractSupport.identifier(envelopeNonce, "envelopeNonce");
        }
    }
}
