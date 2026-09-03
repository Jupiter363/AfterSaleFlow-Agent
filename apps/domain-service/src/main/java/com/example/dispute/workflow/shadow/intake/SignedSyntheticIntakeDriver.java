package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.workflow.application.epoch.RoomEpochSelectionContext.TrafficSource;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.AdmissionAttempt;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.VerifiedAdmission;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import java.util.Objects;

/** Authenticates and dispatches exactly one comparison-only synthetic Intake message command. */
public final class SignedSyntheticIntakeDriver {

    private final IntakeSignedSyntheticAdmissionPort admission;

    public SignedSyntheticIntakeDriver(IntakeSignedSyntheticAdmissionPort admission) {
        this.admission = Objects.requireNonNull(admission, "admission must not be null");
    }

    public IntakeWorkflowCommand admit(
            AdmissionAttempt attempt, IntakeWorkflowCommand inertCommand) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(inertCommand, "inertCommand must not be null");
        requireAdmissibleAttempt(attempt, inertCommand);
        VerifiedAdmission verified = Objects.requireNonNull(
                admission.admit(attempt, inertCommand), "verified admission must not be null");
        requireExactAdmission(attempt, inertCommand, verified);

        return inertCommand;
    }

    private static void requireAdmissibleAttempt(
            AdmissionAttempt attempt, IntakeWorkflowCommand command) {
        if (attempt.trafficSource() != TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC) {
            throw new SecurityException("real-case traffic cannot enter the synthetic Intake driver");
        }
        if (!attempt.hasSignatureEvidence()) {
            throw new SecurityException("signed synthetic admission evidence is required");
        }
        if (command.executionContext() != null) {
            throw new SecurityException("caller-supplied Intake execution context is forbidden");
        }
        if (command.commandType() != IntakeCommandType.INTAKE_MESSAGE) {
            throw new SecurityException("synthetic Intake driver admits message commands only");
        }
    }

    private static void requireExactAdmission(
            AdmissionAttempt attempt,
            IntakeWorkflowCommand command,
            VerifiedAdmission verified) {
        boolean exact =
                verified.trafficSource() == TrafficSource.AUTHENTICATED_SIGNED_SYNTHETIC
                        && verified.tenantSurrogate().equals(command.tenantSurrogate())
                        && verified.caseId().equals(command.caseId())
                        && verified.roomEpoch() == command.roomEpoch()
                        && verified.fencingToken() == command.fencingToken()
                        && verified.commandId().equals(command.commandId())
                        && verified.commandSequence() == command.sequence()
                        && verified.commandType() == IntakeCommandType.INTAKE_MESSAGE
                        && verified.party() == command.party()
                        && verified.commandPayloadRef().equals(command.payloadRef())
                        && verified.commandPayloadHash().equals(command.payloadHash())
                        && verified.commandOperationKey().equals(command.operationKey())
                        && verified.actorScopeHash().equals(command.actorScopeHash())
                        && verified.requestHash().equals(command.requestHash())
                        && verified.threadId().equals(attempt.threadId())
                        && verified.agentSessionId().equals(attempt.agentSessionId())
                        && verified.deadlineEpochMillis() == attempt.deadlineEpochMillis()
                        && verified.retryBudget().equals(attempt.retryBudget());
        if (!exact) {
            throw new SecurityException(
                    "verified synthetic admission does not match the exact command tuple");
        }
    }
}
