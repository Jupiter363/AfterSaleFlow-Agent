package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.ApprovalValidationResult;
import com.example.dispute.workflow.domain.ExecutionAction;
import com.example.dispute.workflow.domain.ExecutionActionActivityResult;
import com.example.dispute.workflow.domain.ExecutionCommand;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface ExecutionActivities {

    @ActivityMethod
    ApprovalValidationResult validateApproval(ExecutionCommand command);

    @ActivityMethod
    ExecutionActionActivityResult executeAction(
            String caseId,
            ExecutionAction action);

    @ActivityMethod
    ExecutionActionActivityResult lookupAction(
            String caseId,
            ExecutionAction action);
}
