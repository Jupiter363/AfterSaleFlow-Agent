package com.example.dispute.workflow.activity.domain;

import com.example.dispute.workflow.application.projection.DomainOperationConflictException;
import com.example.dispute.workflow.application.projection.DomainOperationInProgressException;
import com.example.dispute.workflow.application.projection.FencedProcessProjectionService;
import com.example.dispute.workflow.application.projection.ProjectionWriteRejectedException;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionResult;
import io.temporal.failure.ApplicationFailure;
import org.springframework.stereotype.Component;

@Component
public class ProcessProjectionActivitiesImpl implements ProcessProjectionActivities {

    private final FencedProcessProjectionService projectionService;

    public ProcessProjectionActivitiesImpl(
            FencedProcessProjectionService projectionService) {
        this.projectionService = projectionService;
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
}
