package com.example.dispute.workflow.domain;

import java.util.List;

public record ExecutionResult(
        String status,
        boolean manualHandoff,
        List<String> completedActionIds) {

    public ExecutionResult {
        completedActionIds =
                completedActionIds == null
                        ? List.of()
                        : List.copyOf(completedActionIds);
    }
}
