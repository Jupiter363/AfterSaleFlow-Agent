package com.example.dispute.workflow.activity.domain;

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
import org.springframework.stereotype.Component;

@Component
public class ProcessProjectionActivitiesImpl implements ProcessProjectionActivities {

    private final FencedProcessProjectionService projectionService;
    private final IntakeProcessProjectionCompletionService intakeCompletionService;

    public ProcessProjectionActivitiesImpl(
            FencedProcessProjectionService projectionService,
            IntakeProcessProjectionCompletionService intakeCompletionService) {
        this.projectionService = projectionService;
        this.intakeCompletionService = intakeCompletionService;
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
}
