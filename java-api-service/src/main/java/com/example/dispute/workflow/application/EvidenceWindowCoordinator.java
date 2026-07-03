package com.example.dispute.workflow.application;

import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.domain.EvidenceWindowCommand;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class EvidenceWindowCoordinator {

    private final WorkflowClient workflowClient;
    private final AppProperties properties;

    public EvidenceWindowCoordinator(
            WorkflowClient workflowClient, AppProperties properties) {
        this.workflowClient = workflowClient;
        this.properties = properties;
    }

    public void startAfterCommit(String caseId, Duration window) {
        afterCommit(
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
        afterCommit(
                () ->
                        workflowClient
                                .newWorkflowStub(
                                        EvidenceWindowWorkflow.class,
                                        workflowId(caseId))
                                .partyCompleted(role));
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }

    private static String workflowId(String caseId) {
        return "evidence-window-" + caseId;
    }
}
