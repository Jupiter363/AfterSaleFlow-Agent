package com.example.dispute.hearing.application;

import com.example.dispute.config.AppProperties;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.temporal.DisputeHearingWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HearingWorkflowCoordinator {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(HearingWorkflowCoordinator.class);
    private final WorkflowClient workflowClient;
    private final AppProperties properties;
    private final DisputeProperties disputeProperties;
    private final PostCommitSideEffectExecutor postCommit;

    public HearingWorkflowCoordinator(
            WorkflowClient workflowClient,
            AppProperties properties,
            DisputeProperties disputeProperties,
            PostCommitSideEffectExecutor postCommit) {
        this.workflowClient = workflowClient;
        this.properties = properties;
        this.disputeProperties = disputeProperties;
        this.postCommit = postCommit;
    }

    public void startAfterCommit(String caseId, int dossierVersion) {
        postCommit.execute(
                "hearing-workflow-start",
                Map.of(
                        "case_id", caseId,
                        "workflow_id", workflowId(caseId),
                        "dossier_version", dossierVersion),
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
                                    disputeProperties.hearingWindow(),
                                    2,
                                    disputeProperties.hearingWindow(),
                                    disputeProperties.maxHearingRounds()));
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
        postCommit.execute(
                "hearing-workflow-signal",
                Map.of("case_id", caseId, "workflow_id", workflowId(caseId)),
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

    private static String workflowId(String caseId) {
        return "hearing-window-" + caseId;
    }
}
