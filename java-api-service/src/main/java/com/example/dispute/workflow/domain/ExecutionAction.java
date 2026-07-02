package com.example.dispute.workflow.domain;

import java.util.List;

public record ExecutionAction(
        String actionId,
        String actionType,
        String idempotencyKey,
        List<String> dependsOn) {

    public ExecutionAction {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
