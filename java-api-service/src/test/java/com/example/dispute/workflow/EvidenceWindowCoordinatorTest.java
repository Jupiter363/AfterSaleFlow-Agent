package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

import com.example.dispute.common.transaction.PostCommitSideEffectExecutor;
import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.application.EvidenceWindowCoordinator;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflow;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceWindowCoordinatorTest {

    @Mock private WorkflowClient workflowClient;

    @Test
    void partyCompletedSignalFailuresDoNotPropagateToTheBusinessRequest() {
        EvidenceWindowCoordinator coordinator =
                new EvidenceWindowCoordinator(
                        workflowClient,
                        appProperties(),
                        new PostCommitSideEffectExecutor(Runnable::run));
        when(workflowClient.newWorkflowStub(
                        EvidenceWindowWorkflow.class,
                        "evidence-window-CASE_1"))
                .thenThrow(new IllegalStateException("temporal unavailable"));

        assertThatCode(
                        () ->
                                coordinator.signalPartyCompletedAfterCommit(
                                        "CASE_1", "USER"))
                .doesNotThrowAnyException();
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
}
