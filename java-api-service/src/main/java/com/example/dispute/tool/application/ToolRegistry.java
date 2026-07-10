package com.example.dispute.tool.application;

import com.example.dispute.common.api.ErrorCode;
import com.example.dispute.common.exception.ToolExecutionException;
import com.example.dispute.executor.domain.ExecutableAction;
import com.example.dispute.executor.domain.ToolExecutionResult;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {

    private final List<ToolAdapter> adapters;

    public ToolRegistry(List<ToolAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    public List<ToolDefinition> definitions() {
        return adapters.stream()
                .flatMap(adapter -> adapter.definitions().stream())
                .toList();
    }

    public ToolExecutionResult execute(ExecutableAction action) {
        return adapters.stream()
                .filter(adapter -> adapter.supports(action.actionType()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new ToolExecutionException(
                                        ErrorCode.TOOL_EXECUTION_DENIED,
                                        "no registered tool adapter for approved action",
                                        Map.of("action_type", action.actionType())))
                .execute(action);
    }
}
