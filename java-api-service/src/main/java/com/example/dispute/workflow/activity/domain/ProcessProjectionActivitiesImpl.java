package com.example.dispute.workflow.activity.domain;

import com.example.dispute.room.application.CaseEventService;
import com.example.dispute.room.infrastructure.delivery.CaseEventWakeup;
import com.example.dispute.room.infrastructure.delivery.CaseEventWakeupPublisher;
import com.example.dispute.workflow.application.projection.DomainOperationConflictException;
import com.example.dispute.workflow.application.projection.DomainOperationInProgressException;
import com.example.dispute.workflow.application.projection.FencedProcessProjectionService;
import com.example.dispute.workflow.application.projection.IntakeProcessProjectionCompletionService;
import com.example.dispute.workflow.application.projection.ProjectionWriteRejectedException;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionResult;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionOutcome;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionResult;
import io.temporal.failure.ApplicationFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ProcessProjectionActivitiesImpl implements ProcessProjectionActivities {

    private static final String INTAKE_PROJECTION_READY = "INTAKE_PROJECTION_READY";
    private static final String INTAKE_PROJECTION_READY_EVENT_KEY_PREFIX =
            "intake-projection-ready:";
    private static final String CONTROL_ACTOR_ID = "intake-projection-control";

    private final FencedProcessProjectionService projectionService;
    private final IntakeProcessProjectionCompletionService intakeCompletionService;
    private final CaseEventService caseEventService;
    private final CaseEventWakeupPublisher caseEventWakeupPublisher;

    public ProcessProjectionActivitiesImpl(
            FencedProcessProjectionService projectionService,
            IntakeProcessProjectionCompletionService intakeCompletionService,
            CaseEventService caseEventService,
            CaseEventWakeupPublisher caseEventWakeupPublisher) {
        this.projectionService = projectionService;
        this.intakeCompletionService = intakeCompletionService;
        this.caseEventService = caseEventService;
        this.caseEventWakeupPublisher = caseEventWakeupPublisher;
    }

    @Override
    public ApplyProjectionResult apply(ApplyProjectionCommand command) {
        try {
            return projectionService.apply(command);
        } catch (ProjectionWriteRejectedException failure) {
            throw ApplicationFailure.newNonRetryableFailure(
                    failure.getMessage(), failure.reasonCode());
        } catch (DomainOperationConflictException failure) {
            throw ApplicationFailure.newNonRetryableFailure(
                    failure.getMessage(), failure.reasonCode());
        } catch (DomainOperationInProgressException failure) {
            throw ApplicationFailure.newFailure(
                    failure.getMessage(), "DOMAIN_OPERATION_IN_PROGRESS");
        }
    }

    @Override
    public CompleteConsumedIntakeProjectionResult completeConsumedIntakeProjection(
            CompleteConsumedIntakeProjectionCommand command) {
        try {
            IntakeProcessProjectionCompletionService.CompletionResult completed =
                    intakeCompletionService.completeConsumedEvent(command);
            // The REQUIRES_NEW projection transaction has committed before this independent,
            // replayable notification is recorded. A notification failure therefore retries the
            // activity against an idempotent projection and the same deterministic event key.
            var event =
                    caseEventService.recordLifecycleEvent(
                            command.caseId(),
                            null,
                            INTAKE_PROJECTION_READY,
                            Map.ofEntries(
                                    Map.entry(
                                            "schema_version",
                                            "intake-projection-ready.v1"),
                                    Map.entry("logical_run_id", completed.logicalRunId()),
                                    Map.entry("attempt_id", completed.attemptId()),
                                    Map.entry("process_revision", completed.processRevision()),
                                    Map.entry("room_revision", completed.roomRevision()),
                                    Map.entry("room_epoch", command.roomEpoch()),
                                    Map.entry("fencing_token", command.fencingToken()),
                                    Map.entry(
                                            "command_sequence",
                                            command.lastCommandSequence()),
                                    Map.entry("event_id", command.eventId()),
                                    Map.entry("command_admission_state", "READY")),
                            projectionReadyEventKey(command, completed),
                            CONTROL_ACTOR_ID);
            publishWakeupBestEffort(command.caseId(), event.getSequenceNo());
            return new CompleteConsumedIntakeProjectionResult(
                    "complete-consumed-intake-projection-result.v1",
                    command.eventId(),
                    completed.lastCaseEventSequence(),
                    CompleteConsumedIntakeProjectionOutcome.valueOf(
                            completed.outcome().name()),
                    command.lastCommandSequence(),
                    completed.processRevision(),
                    completed.roomRevision(),
                    command.roomEpoch(),
                    command.fencingToken(),
                    command.temporalWorkflowId(),
                    command.firstExecutionRunId(),
                    command.activeChildRunId(),
                    completed.resultRef(),
                    completed.resultSha256(),
                    completed.completedAt());
        } catch (ProjectionWriteRejectedException failure) {
            throw ApplicationFailure.newNonRetryableFailure(
                    failure.getMessage(), failure.reasonCode());
        } catch (DomainOperationConflictException failure) {
            throw ApplicationFailure.newNonRetryableFailure(
                    failure.getMessage(), failure.reasonCode());
        } catch (DomainOperationInProgressException failure) {
            throw ApplicationFailure.newFailure(
                    failure.getMessage(), "DOMAIN_OPERATION_IN_PROGRESS");
        }
    }

    private static String projectionReadyEventKey(
            CompleteConsumedIntakeProjectionCommand command,
            IntakeProcessProjectionCompletionService.CompletionResult completed) {
        String identity =
                String.join(
                        "|",
                        command.tenantSurrogate(),
                        command.caseId(),
                        command.eventId(),
                        completed.logicalRunId(),
                        completed.attemptId(),
                        Long.toString(completed.processRevision()),
                        Long.toString(completed.roomRevision()),
                        Long.toString(command.roomEpoch()),
                        Long.toString(command.fencingToken()),
                        Long.toString(command.lastCommandSequence()));
        return INTAKE_PROJECTION_READY_EVENT_KEY_PREFIX + sha256(identity);
    }

    private void publishWakeupBestEffort(String caseId, long durableSequence) {
        try {
            caseEventWakeupPublisher.publish(
                    new CaseEventWakeup(
                            CaseEventWakeup.SCHEMA_VERSION, caseId, durableSequence));
        } catch (RuntimeException ignored) {
            // PostgreSQL is authoritative and API subscribers can catch up from this cursor.
            // Publisher implementations own rate-limited diagnostics for this best-effort hint.
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
