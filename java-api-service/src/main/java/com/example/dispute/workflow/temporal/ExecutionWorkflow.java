package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.ExecutionCommand;
import com.example.dispute.workflow.domain.ExecutionResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ExecutionWorkflow {

    @WorkflowMethod
    ExecutionResult run(ExecutionCommand command);
}
