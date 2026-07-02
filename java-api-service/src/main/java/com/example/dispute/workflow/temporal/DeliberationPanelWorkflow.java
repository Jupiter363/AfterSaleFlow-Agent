package com.example.dispute.workflow.temporal;

import com.example.dispute.workflow.domain.DeliberationPanelCommand;
import com.example.dispute.workflow.domain.DeliberationPanelResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface DeliberationPanelWorkflow {

    @WorkflowMethod
    DeliberationPanelResult run(DeliberationPanelCommand command);
}
