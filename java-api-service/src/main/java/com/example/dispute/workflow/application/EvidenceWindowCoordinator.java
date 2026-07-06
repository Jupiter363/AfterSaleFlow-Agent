package com.example.dispute.workflow.application;

import com.example.dispute.config.AppProperties;
import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.workflow.domain.EvidenceWindowCommand;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EvidenceWindowCoordinator {

    private final WorkflowClient workflowClient;
    private final AppProperties properties;
    private final PostCommitSideEffectExecutor postCommit;

    public EvidenceWindowCoordinator(
            WorkflowClient workflowClient,
            AppProperties properties,
            PostCommitSideEffectExecutor postCommit) {
        this.workflowClient = workflowClient;
        this.properties = properties;
        this.postCommit = postCommit;
    }

    public void startAfterCommit(String caseId, Duration window) {
        postCommit.execute(
                "evidence-window-start",
                Map.of("case_id", caseId, "workflow_id", workflowId(caseId)),
                () -> {
                    EvidenceWindowWorkflow workflow =
                            workflowClient.newWorkflowStub(
                                    EvidenceWindowWorkflow.class,
                                    WorkflowOptions.newBuilder()
                                            .setWorkflowId(workflowId(caseId))
                                            .setTaskQueue(properties.temporal().taskQueue())
                                            .build());
                    WorkflowClient.start(
                            workflow::run, new EvidenceWindowCommand(caseId, window));
                });
    }

    public void signalPartyCompletedAfterCommit(String caseId, String role) {
        postCommit.execute(
                "evidence-window-party-completed",
                Map.of(
                        "case_id", caseId,
                        "workflow_id", workflowId(caseId),
                        "role", role),
                () ->
                        workflowClient
                                .newWorkflowStub(
                                        EvidenceWindowWorkflow.class,
                                        workflowId(caseId))
                                .partyCompleted(role));
    }

    private static String workflowId(String caseId) {
        return "evidence-window-" + caseId;
    }
}
