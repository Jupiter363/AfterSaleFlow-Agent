package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RouteType;
import com.example.dispute.workflow.domain.DeliberationPanelCommand;
import com.example.dispute.workflow.domain.DeliberationPanelResult;
import com.example.dispute.workflow.domain.ExecutionCommand;
import com.example.dispute.workflow.domain.ExecutionResult;
import com.example.dispute.workflow.domain.FulfillmentDisputeCommand;
import com.example.dispute.workflow.domain.FulfillmentDisputeResult;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.domain.HearingWorkflowResult;
import com.example.dispute.workflow.domain.HumanReviewCommand;
import com.example.dispute.workflow.domain.HumanReviewResult;
import com.example.dispute.workflow.domain.ReviewGateSnapshot;
import com.example.dispute.workflow.temporal.DeliberationPanelWorkflow;
import com.example.dispute.workflow.temporal.ExecutionWorkflow;
import com.example.dispute.workflow.temporal.FulfillmentDisputeActivities;
import com.example.dispute.workflow.temporal.FulfillmentDisputeWorkflow;
import com.example.dispute.workflow.temporal.FulfillmentDisputeWorkflowImpl;
import com.example.dispute.workflow.temporal.HumanReviewWorkflow;
import com.example.dispute.workflow.temporal.DisputeHearingWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FinalWorkflowOrchestrationTest {

    private static final String TASK_QUEUE = "final-orchestration-test";

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private RecordingActivities activities;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                FulfillmentDisputeWorkflowImpl.class,
                StubHearingWorkflow.class,
                StubPanelWorkflow.class,
                StubReviewWorkflow.class,
                StubExecutionWorkflow.class);
        activities = new RecordingActivities();
        worker.registerActivitiesImplementations(activities);
        environment.start();
        client = environment.getWorkflowClient();
        StubHearingWorkflow.calls.set(0);
        StubPanelWorkflow.calls.set(0);
        StubReviewWorkflow.calls.set(0);
        StubExecutionWorkflow.calls.set(0);
    }

    @AfterEach
    void tearDown() {
        environment.close();
    }

    @Test
    void transferredRouteTerminatesWithoutHearingReviewOrExecution() {
        FulfillmentDisputeResult result =
                workflow("WORKFLOW_transferred")
                        .run(command("CASE_transferred", "WORKFLOW_transferred",
                                RouteType.TRANSFERRED, false));

        assertThat(result.workflowStatus()).isEqualTo("COMPLETED");
        assertThat(result.nextStage()).isEqualTo("TRANSFERRED");
        assertThat(result.humanReviewRequired()).isFalse();
        assertThat(activities.transferredCalls).isEqualTo(1);
        assertThat(activities.remedyCalls).isZero();
        assertThat(StubHearingWorkflow.calls).hasValue(0);
        assertThat(StubReviewWorkflow.calls).hasValue(0);
        assertThat(StubExecutionWorkflow.calls).hasValue(0);
    }

    @Test
    void simpleHearingPlansRemedyButStillRequiresHumanReview() {
        FulfillmentDisputeResult result =
                workflow("WORKFLOW_simple")
                        .run(command("CASE_simple", "WORKFLOW_simple",
                                RouteType.SIMPLE_HEARING, false));

        assertThat(result.nextStage()).isEqualTo("EVALUATION_COMPLETE");
        assertThat(result.humanReviewRequired()).isTrue();
        assertThat(StubHearingWorkflow.calls).hasValue(0);
        assertThat(StubPanelWorkflow.calls).hasValue(0);
        assertThat(StubReviewWorkflow.calls).hasValue(1);
        assertThat(StubExecutionWorkflow.calls).hasValue(1);
        assertThat(activities.closeCalls).isEqualTo(1);
    }

    @Test
    void fullHearingUsesHearingOptionalPanelReviewAndExecutionChildren() {
        FulfillmentDisputeResult result =
                workflow("WORKFLOW_full")
                        .run(command("CASE_full", "WORKFLOW_full",
                                RouteType.FULL_HEARING, true));

        assertThat(result.nextStage()).isEqualTo("EVALUATION_COMPLETE");
        assertThat(result.draftId()).isEqualTo("DRAFT_test");
        assertThat(result.deliberationId()).isEqualTo("DELIBERATION_test");
        assertThat(StubHearingWorkflow.calls).hasValue(1);
        assertThat(StubPanelWorkflow.calls).hasValue(1);
        assertThat(StubReviewWorkflow.calls).hasValue(1);
        assertThat(StubExecutionWorkflow.calls).hasValue(1);
    }

    private FulfillmentDisputeWorkflow workflow(String workflowId) {
        return client.newWorkflowStub(
                FulfillmentDisputeWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
    }

    private static FulfillmentDisputeCommand command(
            String caseId,
            String workflowId,
            RouteType routeType,
            boolean deliberationRequired) {
        return new FulfillmentDisputeCommand(
                caseId,
                workflowId,
                routeType,
                1,
                Duration.ofHours(24),
                Duration.ofDays(7),
                2,
                deliberationRequired);
    }

    public static final class StubHearingWorkflow
            implements DisputeHearingWorkflow {
        static final AtomicInteger calls = new AtomicInteger();

        @Override
        public HearingWorkflowResult run(HearingWorkflowCommand command) {
            calls.incrementAndGet();
            return new HearingWorkflowResult(
                    "DRAFT_test", false, false, 2, "COMPLETED");
        }

        @Override
        public void submitEvidence(
                com.example.dispute.workflow.domain.EvidenceSubmissionSignal signal) {}

        @Override
        public void hearingRoundCompleted(int roundNo, boolean factsSufficient) {}

        @Override
        public void settlementConfirmed(int settlementVersion) {}
    }

    public static final class StubPanelWorkflow
            implements DeliberationPanelWorkflow {
        static final AtomicInteger calls = new AtomicInteger();

        @Override
        public DeliberationPanelResult run(DeliberationPanelCommand command) {
            calls.incrementAndGet();
            return new DeliberationPanelResult(
                    "DELIBERATION_test",
                    "NO_MAJOR_OBJECTION",
                    false,
                    false,
                    List.of(),
                    List.of());
        }
    }

    public static final class StubReviewWorkflow
            implements HumanReviewWorkflow {
        static final AtomicInteger calls = new AtomicInteger();

        @Override
        public HumanReviewResult run(HumanReviewCommand command) {
            calls.incrementAndGet();
            return new HumanReviewResult(
                    "REVIEW_test", "APPROVED", true, false, null);
        }

        @Override
        public void submitDecision(
                com.example.dispute.workflow.domain.HumanReviewSignal signal) {}
    }

    public static final class StubExecutionWorkflow
            implements ExecutionWorkflow {
        static final AtomicInteger calls = new AtomicInteger();

        @Override
        public ExecutionResult run(ExecutionCommand command) {
            calls.incrementAndGet();
            return new ExecutionResult("SUCCEEDED", false, List.of());
        }
    }

    private static final class RecordingActivities
            implements FulfillmentDisputeActivities {
        private int transferredCalls;
        private int remedyCalls;
        private int closeCalls;

        @Override
        public void markTransferred(String caseId, String workflowId) {
            transferredCalls++;
        }

        @Override
        public String planRemedy(
                String caseId,
                String workflowId,
                String draftId,
                String deliberationId) {
            remedyCalls++;
            return "REMEDY_test";
        }

        @Override
        public ReviewGateSnapshot createReviewPacket(
                String caseId,
                String draftId,
                String deliberationId,
                String remedyPlanId) {
            return new ReviewGateSnapshot(
                    "REVIEW_test",
                    "PACKET_test",
                    1,
                    "ACTION_HASH_test",
                    System.currentTimeMillis() + Duration.ofDays(7).toMillis(),
                    "PLATFORM_REVIEWER");
        }

        @Override
        public void closeCaseAndEvaluate(String caseId) {
            closeCalls++;
        }
    }
}
