package com.example.dispute.hearing.application;

import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.temporal.DisputeHearingWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class HearingWorkflowCoordinator {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HearingWorkflowCoordinator.class);
    private static final Duration HEARING_WINDOW = Duration.ofHours(3);

    private final WorkflowClient workflowClient;
    private final AppProperties properties;

    public HearingWorkflowCoordinator(
            WorkflowClient workflowClient, AppProperties properties) {
        this.workflowClient = workflowClient;
        this.properties = properties;
    }

    public void startAfterCommit(String caseId, int dossierVersion) {
        afterCommit(
                () -> {
                    DisputeHearingWorkflow workflow =
                            workflowClient.newWorkflowStub(
                                    DisputeHearingWorkflow.class,
                                    WorkflowOptions.newBuilder()
                                            .setWorkflowId(workflowId(caseId))
                                            .setTaskQueue(properties.temporal().taskQueue())
                                            .build());
                    WorkflowClient.start(
                            workflow::run,
                            new HearingWorkflowCommand(
                                    caseId,
                                    workflowId(caseId),
                                    dossierVersion,
                                    HEARING_WINDOW,
                                    2,
                                    HEARING_WINDOW,
                                    HearingRoundService.MAX_ROUNDS));
                });
    }

    public void roundCompletedAfterCommit(
            String caseId, int roundNo, boolean factsSufficient) {
        signalAfterCommit(
                caseId,
                workflow ->
                        workflow.hearingRoundCompleted(
                                roundNo, factsSufficient));
    }

    public void settlementConfirmedAfterCommit(String caseId, int version) {
        signalAfterCommit(
                caseId, workflow -> workflow.settlementConfirmed(version));
    }

    private void signalAfterCommit(
            String caseId,
            java.util.function.Consumer<DisputeHearingWorkflow> signal) {
        afterCommit(
                () -> {
                    try {
                        signal.accept(
                                workflowClient.newWorkflowStub(
                                        DisputeHearingWorkflow.class,
                                        workflowId(caseId)));
                    } catch (WorkflowNotFoundException missing) {
                        LOGGER.warn(
                                "Hearing workflow is not running: case_id={}",
                                caseId);
                    }
                });
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
        return "hearing-window-" + caseId;
    }
}
