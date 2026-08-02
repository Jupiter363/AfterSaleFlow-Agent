package com.example.dispute.workflow.activity.domain;

import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.ApplyProjectionResult;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionCommand;
import com.example.dispute.workflow.contract.v1.ProcessProjectionContract.CompleteConsumedIntakeProjectionResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface ProcessProjectionActivities {

    @ActivityMethod(name = "ApplyFencedProcessProjection")
    ApplyProjectionResult apply(ApplyProjectionCommand command);

    @ActivityMethod(name = "CompleteConsumedIntakeProjection")
    CompleteConsumedIntakeProjectionResult completeConsumedIntakeProjection(
            CompleteConsumedIntakeProjectionCommand command);
}
