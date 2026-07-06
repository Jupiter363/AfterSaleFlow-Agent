package com.example.dispute.hearing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.AppProperties;
import com.example.dispute.config.DisputeProperties;
import com.example.dispute.hearing.application.HearingWorkflowCoordinator;
import com.example.dispute.workflow.temporal.DisputeHearingWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HearingWorkflowCoordinatorTest {

    @Mock private WorkflowClient workflowClient;

    @Test
    void startFailuresDoNotPropagateAfterEvidenceHasBeenSealed() {
        HearingWorkflowCoordinator coordinator = coordinator();
        when(workflowClient.newWorkflowStub(
                        eq(DisputeHearingWorkflow.class),
                        any(WorkflowOptions.class)))
                .thenThrow(new IllegalStateException("temporal unavailable"));

        assertThatCode(() -> coordinator.startAfterCommit("CASE_1", 1))
                .doesNotThrowAnyException();
    }

    @Test
    void signalFailuresDoNotPropagateAfterRoundStateHasBeenSaved() {
        HearingWorkflowCoordinator coordinator = coordinator();
        when(workflowClient.newWorkflowStub(
                        DisputeHearingWorkflow.class,
                        "hearing-window-CASE_1"))
                .thenThrow(new IllegalStateException("temporal unavailable"));

        assertThatCode(() -> coordinator.roundCompletedAfterCommit("CASE_1", 1, false))
                .doesNotThrowAnyException();
    }

    private HearingWorkflowCoordinator coordinator() {
        return new HearingWorkflowCoordinator(
                workflowClient,
                appProperties(),
                disputeProperties(),
                new PostCommitSideEffectExecutor(Runnable::run));
    }

    private static AppProperties appProperties() {
        return new AppProperties(
                "test",
                new AppProperties.Security("secret"),
                null,
                null,
                new AppProperties.Temporal("localhost:7233", "default", "test-task-queue"),
                null,
                null,
                null,
                null);
    }

    private static DisputeProperties disputeProperties() {
        return new DisputeProperties(
                Duration.ofHours(2),
                Duration.ofHours(3),
                Duration.ofMinutes(5),
                3,
                Duration.ofSeconds(15),
                true);
    }
}
