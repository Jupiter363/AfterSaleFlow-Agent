package com.example.dispute.workflow.domain;

import java.util.List;

public record ExecutionCommand(
        String caseId,
        String reviewId,
        int reviewPacketVersion,
        String actionHash,
        boolean approved,
        long approvalExpiresAtEpochMillis,
        List<ExecutionAction> actions) {

    public ExecutionCommand {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
