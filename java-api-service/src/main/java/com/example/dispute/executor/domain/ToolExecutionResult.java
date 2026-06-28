package com.example.dispute.executor.domain;

import java.util.Map;

public record ToolExecutionResult(
        String toolName,
        String operation,
        String referenceId,
        boolean simulated,
        Map<String, Object> response) {

    public ToolExecutionResult {
        response = Map.copyOf(response);
    }
}
