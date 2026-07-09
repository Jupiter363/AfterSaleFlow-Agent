package com.example.dispute.tool.application;

import com.example.dispute.executor.domain.ExecutableAction;
import com.example.dispute.executor.domain.ToolExecutionResult;
import java.util.List;

public interface ToolAdapter {

    List<ToolDefinition> definitions();

    boolean supports(String actionType);

    ToolExecutionResult execute(ExecutableAction action);
}
