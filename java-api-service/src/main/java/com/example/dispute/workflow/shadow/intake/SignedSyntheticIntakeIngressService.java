package com.example.dispute.workflow.shadow.intake;

import com.example.dispute.config.AuthenticatedActor;
import com.example.dispute.workflow.application.command.AcceptCaseCommand;
import com.example.dispute.workflow.application.command.CaseCommandAcceptance;
import com.example.dispute.workflow.application.command.CaseCommandService;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.shadow.intake.IntakeSignedSyntheticAdmissionPort.AdmissionAttempt;
import com.example.dispute.workflow.temporal.room.intake.IntakeCommandType;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowCommand;
import java.time.Instant;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signed synthetic ingress bridge into the canonical command ledger.
 *
 * <p>This service intentionally never signals an Intake room workflow. It durably admits the
 * signed synthetic tuple first, then reuses the existing command outbox path into CaseProcess.
 */
@Service
@ConditionalOnBean(SignedSyntheticIntakeDriver.class)
public class SignedSyntheticIntakeIngressService {

    private static final String PAYLOAD_SCHEMA = "intake-turn-event.v2";

    private final SignedSyntheticIntakeDriver driver;
    private final CaseCommandService commandService;

    public SignedSyntheticIntakeIngressService(
            SignedSyntheticIntakeDriver driver, CaseCommandService commandService) {
        this.driver = Objects.requireNonNull(driver, "driver must not be null");
        this.commandService = Objects.requireNonNull(commandService, "commandService must not be null");
    }

    @Transactional
    public CaseCommandAcceptance accept(
            AdmissionAttempt attempt,
            IntakeWorkflowCommand inertCommand,
            long expectedProcessRevision,
            long payloadSizeBytes,
            AuthenticatedActor actor,
            String traceId,
            String requestId,
            String traceparent) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(inertCommand, "inertCommand must not be null");
        if (inertCommand.commandType() != IntakeCommandType.INTAKE_MESSAGE) {
            throw new SecurityException("signed synthetic ingress accepts Intake messages only");
        }
        IntakeWorkflowCommand admitted = driver.admit(attempt, inertCommand);
        if (admitted.executionContext() != null) {
            throw new SecurityException("signed synthetic ingress must enqueue an inert command");
        }
        AcceptCaseCommand command = new AcceptCaseCommand(
                CommandType.INTAKE_MESSAGE,
                RoomType.INTAKE,
                admitted.roomEpoch(),
                new PayloadRef(
                        PAYLOAD_SCHEMA,
                        admitted.payloadRef(),
                        admitted.payloadHash(),
                        payloadSizeBytes),
                expectedProcessRevision,
                Instant.ofEpochMilli(attempt.deadlineEpochMillis()));
        CaseCommandAcceptance accepted = commandService.accept(
                admitted.caseId(),
                admitted.commandId(),
                command,
                actor,
                traceId,
                requestId,
                traceparent);
        requireAcceptedMatchesAdmission(admitted, expectedProcessRevision, accepted);
        return accepted;
    }

    private static void requireAcceptedMatchesAdmission(
            IntakeWorkflowCommand admitted,
            long expectedProcessRevision,
            CaseCommandAcceptance accepted) {
        var command = accepted.command();
        boolean exact =
                command.commandId().equals(admitted.commandId())
                        && command.tenantSurrogate().equals(admitted.tenantSurrogate())
                        && command.caseId().equals(admitted.caseId())
                        && command.caseCommandSequence() == admitted.sequence()
                        && command.roomType() == RoomType.INTAKE
                        && command.roomEpoch() == admitted.roomEpoch()
                        && command.expectedProcessRevision() == expectedProcessRevision
                        && command.payloadRef().uri().equals(admitted.payloadRef())
                        && command.payloadRef().sha256().equals(admitted.payloadHash())
                        && command.requestHash().equals(admitted.requestHash());
        if (!exact) {
            throw new SecurityException(
                    "accepted command does not match the verified synthetic admission");
        }
    }
}
