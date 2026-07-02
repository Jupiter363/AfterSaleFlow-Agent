package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.domain.HumanReviewCommand;
import com.example.dispute.workflow.domain.HumanReviewResult;
import com.example.dispute.workflow.domain.HumanReviewSignal;
import com.example.dispute.workflow.temporal.HumanReviewActivities;
import com.example.dispute.workflow.temporal.HumanReviewWorkflow;
import com.example.dispute.workflow.temporal.HumanReviewWorkflowImpl;
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

class HumanReviewWorkflowTest {

    private static final String TASK_QUEUE = "final-review-test";

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private RecordingActivities activities;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                HumanReviewWorkflowImpl.class);
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
    void validatesReviewerRolePacketVersionAndActionHashBeforeApproval() {
        HumanReviewWorkflow workflow = workflow("WORKFLOW_review_validate");
        WorkflowClient.start(
                workflow::run,
                command(environment.currentTimeMillis() + Duration.ofDays(1).toMillis()));
        workflow.submitDecision(
                new HumanReviewSignal(
                        "reviewer-1",
                        "MERCHANT",
                        "APPROVE",
                        2,
                        "ACTION_HASH_1",
                        "HUMAN_REVIEW_1",
                        "unauthorized role"));
        workflow.submitDecision(
                new HumanReviewSignal(
                        "reviewer-1",
                        "PLATFORM_REVIEWER",
                        "APPROVE",
                        1,
                        "ACTION_HASH_1",
                        "HUMAN_REVIEW_1",
                        "stale packet"));
        workflow.submitDecision(
                new HumanReviewSignal(
                        "reviewer-1",
                        "PLATFORM_REVIEWER",
                        "APPROVE",
                        2,
                        "WRONG_HASH",
                        "HUMAN_REVIEW_1",
                        "wrong action hash"));
        workflow.submitDecision(
                new HumanReviewSignal(
                        "reviewer-1",
                        "PLATFORM_REVIEWER",
                        "APPROVE",
                        2,
                        "ACTION_HASH_1",
                        "HUMAN_REVIEW_1",
                        "evidence and policy verified"));

        HumanReviewResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(HumanReviewResult.class);

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.approved()).isTrue();
        assertThat(activities.invalidReasons)
                .containsExactly(
                        "UNAUTHORIZED_REVIEWER_ROLE",
                        "STALE_REVIEW_PACKET",
                        "ACTION_HASH_MISMATCH");
        assertThat(activities.acceptedDecisions).containsExactly("APPROVE");
    }

    @Test
    void modifyReturnRejectAndEscalateRemainDistinctDecisions() {
        assertDecision("MODIFY_AND_APPROVE", "MODIFIED_AND_APPROVED", true, true);
        assertDecision("RETURN_FOR_REVISION", "RETURNED_FOR_REVISION", false, false);
        assertDecision("REQUEST_MORE_EVIDENCE", "MORE_EVIDENCE_REQUESTED", false, false);
        assertDecision("REJECT", "REJECTED", false, false);
        assertDecision("ESCALATE", "ESCALATED", false, false);
    }

    @Test
    void expiredPacketCannotBeApproved() {
        HumanReviewResult result =
                workflow("WORKFLOW_review_expired")
                        .run(command(environment.currentTimeMillis() - 1));

        assertThat(result.status()).isEqualTo("EXPIRED");
        assertThat(result.approved()).isFalse();
        assertThat(result.failureReason()).isEqualTo("REVIEW_PACKET_EXPIRED");
    }

    private void assertDecision(
            String decision,
            String expectedStatus,
            boolean approved,
            boolean modified) {
        String workflowId = "WORKFLOW_review_" + decision.toLowerCase();
        HumanReviewWorkflow workflow = workflow(workflowId);
        WorkflowClient.start(
                workflow::run,
                command(environment.currentTimeMillis() + Duration.ofDays(1).toMillis()));
        workflow.submitDecision(
                new HumanReviewSignal(
                        "reviewer-1",
                        "PLATFORM_REVIEWER",
                        decision,
                        2,
                        "ACTION_HASH_1",
                        "HUMAN_REVIEW_1",
                        "review reason"));
        HumanReviewResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(HumanReviewResult.class);
        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.approved()).isEqualTo(approved);
        assertThat(result.modified()).isEqualTo(modified);
    }

    private HumanReviewWorkflow workflow(String workflowId) {
        return client.newWorkflowStub(
                HumanReviewWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
    }

    private static HumanReviewCommand command(long expiresAt) {
        return new HumanReviewCommand(
                "CASE_review",
                "PACKET_review",
                2,
                "ACTION_HASH_1",
                expiresAt,
                Duration.ofDays(7),
                "PLATFORM_REVIEWER");
    }

    private static final class RecordingActivities
            implements HumanReviewActivities {
        private final List<String> invalidReasons =
                new CopyOnWriteArrayList<>();
        private final List<String> acceptedDecisions =
                new CopyOnWriteArrayList<>();

        @Override
        public void recordInvalidDecision(
                HumanReviewCommand command,
                HumanReviewSignal signal,
                String reason) {
            invalidReasons.add(reason);
        }

        @Override
        public String persistDecision(
                HumanReviewCommand command,
                HumanReviewSignal signal,
                String status) {
            acceptedDecisions.add(signal.decision());
            return "REVIEW_test";
        }
    }
}
