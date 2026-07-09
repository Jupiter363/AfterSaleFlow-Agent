package com.example.dispute.tool.application;

import com.example.dispute.executor.domain.ExecutableAction;
import com.example.dispute.executor.domain.ToolExecutionResult;

public interface ToolAdapter {

    boolean supports(String actionType);

    ToolExecutionResult execute(ExecutableAction action);
}
