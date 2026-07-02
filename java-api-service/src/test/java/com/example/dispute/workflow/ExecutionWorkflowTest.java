package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.domain.ApprovalValidationResult;
import com.example.dispute.workflow.domain.ExecutionAction;
import com.example.dispute.workflow.domain.ExecutionActionActivityResult;
import com.example.dispute.workflow.domain.ExecutionCommand;
import com.example.dispute.workflow.domain.ExecutionResult;
import com.example.dispute.workflow.temporal.ExecutionActivities;
import com.example.dispute.workflow.temporal.ExecutionWorkflow;
import com.example.dispute.workflow.temporal.ExecutionWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExecutionWorkflowTest {

    private static final String TASK_QUEUE = "final-execution-test";

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private RecordingActivities activities;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                ExecutionWorkflowImpl.class);
        activities = new RecordingActivities();
        worker.registerActivitiesImplementations(activities);
        environment.start();
        client = environment.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        environment.close();
    }

    @Test
    void validatesApprovalAndExecutesDependencyOrderWithIdempotencyKeys() {
        ExecutionResult result =
                workflow("WORKFLOW_execution_order")
                        .run(
                                command(
                                        List.of(
                                                new ExecutionAction(
                                                        "ACTION_notify",
                                                        "NOTIFY",
                                                        "IDEMPOTENCY_notify",
                                                        List.of("ACTION_refund")),
                                                new ExecutionAction(
                                                        "ACTION_refund",
                                                        "REFUND",
                                                        "IDEMPOTENCY_refund",
                                                        List.of()))));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.manualHandoff()).isFalse();
        assertThat(activities.executedActions)
                .containsExactly("ACTION_refund", "ACTION_notify");
        assertThat(activities.idempotencyKeys)
                .containsExactly(
                        "IDEMPOTENCY_refund", "IDEMPOTENCY_notify");
    }

    @Test
    void unknownExternalResultIsLookedUpBeforeAnyRetryDecision() {
        activities.unknownAction = "ACTION_refund";

        ExecutionResult result =
                workflow("WORKFLOW_execution_lookup")
                        .run(
                                command(
                                        List.of(
                                                new ExecutionAction(
                                                        "ACTION_refund",
                                                        "REFUND",
                                                        "IDEMPOTENCY_refund",
                                                        List.of()))));

        assertThat(activities.lookupKeys)
                .containsExactly("IDEMPOTENCY_refund");
        assertThat(result.status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void invalidOrExpiredApprovalNeverCallsExternalAction() {
        activities.approvalValid = false;
        ExecutionResult invalid =
                workflow("WORKFLOW_execution_invalid")
                        .run(command(List.of(
                                new ExecutionAction(
                                        "ACTION_refund",
                                        "REFUND",
                                        "IDEMPOTENCY_refund",
                                        List.of()))));

        assertThat(invalid.status()).isEqualTo("MANUAL_HANDOFF");
        assertThat(activities.executedActions).isEmpty();

        activities.approvalValid = true;
        ExecutionCommand expired =
                new ExecutionCommand(
                        "CASE_execution",
                        "REVIEW_execution",
                        2,
                        "ACTION_HASH_1",
                        true,
                        environment.currentTimeMillis() - 1,
                        List.of(
                                new ExecutionAction(
                                        "ACTION_refund",
                                        "REFUND",
                                        "IDEMPOTENCY_refund",
                                        List.of())));
        ExecutionResult expiredResult =
                workflow("WORKFLOW_execution_expired").run(expired);
        assertThat(expiredResult.status()).isEqualTo("MANUAL_HANDOFF");
        assertThat(activities.executedActions).isEmpty();
    }

    private ExecutionWorkflow workflow(String workflowId) {
        return client.newWorkflowStub(
                ExecutionWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
    }

    private ExecutionCommand command(List<ExecutionAction> actions) {
        return new ExecutionCommand(
                "CASE_execution",
                "REVIEW_execution",
                2,
                "ACTION_HASH_1",
                true,
                environment.currentTimeMillis()
                        + Duration.ofHours(1).toMillis(),
                actions);
    }

    private static final class RecordingActivities
            implements ExecutionActivities {
        private final List<String> executedActions =
                new CopyOnWriteArrayList<>();
        private final List<String> idempotencyKeys =
                new CopyOnWriteArrayList<>();
        private final List<String> lookupKeys =
                new CopyOnWriteArrayList<>();
        private volatile boolean approvalValid = true;
        private volatile String unknownAction;

        @Override
        public ApprovalValidationResult validateApproval(
                ExecutionCommand command) {
            return new ApprovalValidationResult(
                    approvalValid,
                    approvalValid ? null : "ACTION_HASH_MISMATCH");
        }

        @Override
        public ExecutionActionActivityResult executeAction(
                String caseId,
                ExecutionAction action) {
            executedActions.add(action.actionId());
            idempotencyKeys.add(action.idempotencyKey());
            return new ExecutionActionActivityResult(
                    action.actionId(),
                    action.actionId().equals(unknownAction)
                            ? "UNKNOWN"
                            : "SUCCEEDED",
                    null);
        }

        @Override
        public ExecutionActionActivityResult lookupAction(
                String caseId,
                ExecutionAction action) {
            lookupKeys.add(action.idempotencyKey());
            return new ExecutionActionActivityResult(
                    action.actionId(), "SUCCEEDED", "EXTERNAL_1");
        }
    }
}
