package com.example.dispute.workflow.caseprocess;

import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.WorkflowReplayer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class CaseProcessWorkflowReplayTest {

    @Test
    void capturedV1HistoryReplaysAgainstTheCurrentWorker() throws Exception {
        try (InputStream input =
                Objects.requireNonNull(
                        getClass()
                                .getClassLoader()
                                .getResourceAsStream(
                                        "temporal-history/case-process-v1.json"),
                        "captured Temporal history is missing")) {
            WorkflowExecutionHistory history =
                    WorkflowExecutionHistory.fromJson(
                            new String(input.readAllBytes(), StandardCharsets.UTF_8),
                            "case-process:tenant-case-process:CASE_ProcessWorkflow");
            WorkflowReplayer.replayWorkflowExecution(
                    history, CaseProcessWorkflowImpl.class);
        }
    }
}
