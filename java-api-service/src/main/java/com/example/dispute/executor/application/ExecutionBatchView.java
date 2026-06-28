package com.example.dispute.executor.application;

import java.util.List;

public record ExecutionBatchView(
        String caseId,
        String planId,
        String approvalRecordId,
        boolean allSucceeded,
        List<ActionRecordView> actions) {

    public ExecutionBatchView {
        actions = List.copyOf(actions);
    }
}
