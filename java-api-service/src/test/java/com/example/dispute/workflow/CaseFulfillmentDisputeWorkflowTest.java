package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.domain.model.RouteType;
import com.example.dispute.workflow.domain.CaseWorkflowInput;
import com.example.dispute.workflow.domain.CaseWorkflowResult;
import com.example.dispute.workflow.domain.HearingAnalysisActivityCommand;
import com.example.dispute.workflow.domain.HearingAnalysisActivityResult;
import com.example.dispute.workflow.domain.PartyEvidenceSignal;
import com.example.dispute.workflow.domain.ReviewerWorkflowSignal;
import com.example.dispute.workflow.temporal.CaseFulfillmentDisputeActivities;
import com.example.dispute.workflow.temporal.CaseFulfillmentDisputeWorkflow;
import com.example.dispute.workflow.temporal.CaseFulfillmentDisputeWorkflowImpl;
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

class CaseFulfillmentDisputeWorkflowTest {

    private static final String TASK_QUEUE = "case-workflow-test";

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private RecordingActivities activities;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                CaseFulfillmentDisputeWorkflowImpl.class);
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
    void pausesForPartyEvidenceAndResumesFromSignalIntoRemedyPlanning() {
        CaseFulfillmentDisputeWorkflow workflow = newWorkflow("WORKFLOW_signal");
        WorkflowClient.start(
                workflow::run,
                input("CASE_signal", "WORKFLOW_signal", Duration.ofHours(24)));

        workflow.submitPartyEvidence(
                new PartyEvidenceSignal(
                        "USER",
                        "SUBMISSION_signal",
                        List.of("EVIDENCE_signal")));
        workflow.submitReviewerSignal(
                new ReviewerWorkflowSignal(
                        "reviewer-1", "APPROVE", "reviewed"));
        CaseWorkflowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(CaseWorkflowResult.class);

        assertThat(result.workflowStatus()).isEqualTo("COMPLETED");
        assertThat(result.nextStage()).isEqualTo("EVALUATION_COMPLETE");
        assertThat(result.remedyPlanId()).isEqualTo("REMEDY_test");
        assertThat(result.reviewTaskId()).isEqualTo("REVIEW_test");
        assertThat(result.evidenceTimedOut()).isFalse();
        assertThat(activities.analysisCalls()).isEqualTo(2);
        assertThat(activities.recordedSignals()).containsExactly("SUBMISSION_signal");
        assertThat(activities.executionCalls).isEqualTo(1);
        assertThat(activities.closureCalls).isEqualTo(1);
    }

    @Test
    void evidenceTimeoutUsesTemporalTimerAndStillProducesHumanReviewDraft() {
        activities.alwaysRequireEvidence = true;
        CaseFulfillmentDisputeWorkflow workflow = newWorkflow("WORKFLOW_timeout");
        long startedAt = environment.currentTimeMillis();

        CaseWorkflowResult result =
                workflow.run(
                        input(
                                "CASE_timeout",
                                "WORKFLOW_timeout",
                                Duration.ofHours(48)));

        assertThat(result.workflowStatus()).isEqualTo("COMPLETED");
        assertThat(result.nextStage()).isEqualTo("HUMAN_HANDOFF");
        assertThat(result.evidenceTimedOut()).isTrue();
        assertThat(result.manualRequired()).isTrue();
        assertThat(environment.currentTimeMillis())
                .isGreaterThanOrEqualTo(
                        startedAt + Duration.ofHours(48).toMillis());
    }

    @Test
    void reviewerCanResumeWithAvailableEvidenceAndForceManualReview() {
        CaseFulfillmentDisputeWorkflow workflow =
                newWorkflow("WORKFLOW_reviewer");
        WorkflowClient.start(
                workflow::run,
                input(
                        "CASE_reviewer",
                        "WORKFLOW_reviewer",
                        Duration.ofHours(24)));

        workflow.submitReviewerSignal(
                new ReviewerWorkflowSignal(
                        "reviewer-1",
                        "ESCALATE_MANUAL",
                        "conflicting delivery evidence"));
        CaseWorkflowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(CaseWorkflowResult.class);

        assertThat(result.workflowStatus()).isEqualTo("COMPLETED");
        assertThat(result.manualRequired()).isTrue();
        assertThat(activities.reviewerSignals).isEqualTo(1);
    }

    @Test
    void regularAndRuleRoutesGoDirectlyThroughRemedyPlanning() {
        for (RouteType route :
                List.of(
                        RouteType.TRANSFERRED,
                        RouteType.SIMPLE_HEARING)) {
            String suffix = route.name().toLowerCase();
            CaseFulfillmentDisputeWorkflow workflow =
                    newWorkflow("WORKFLOW_" + suffix);
            WorkflowClient.start(
                    workflow::run,
                    new CaseWorkflowInput(
                            "CASE_" + suffix,
                            "WORKFLOW_" + suffix,
                            route,
                            Duration.ofHours(24),
                            2));
            workflow.submitReviewerSignal(
                    new ReviewerWorkflowSignal(
                            "reviewer-1", "APPROVE", "reviewed"));
            CaseWorkflowResult result =
                    io.temporal.client.WorkflowStub.fromTyped(workflow)
                            .getResult(CaseWorkflowResult.class);

            assertThat(result.nextStage()).isEqualTo("EVALUATION_COMPLETE");
            assertThat(result.remedyPlanId()).isEqualTo("REMEDY_test");
        }
        assertThat(activities.remedyCalls).isEqualTo(2);
        assertThat(activities.analysisCalls()).isZero();
        assertThat(activities.executionCalls).isEqualTo(2);
        assertThat(activities.closureCalls).isEqualTo(2);
    }

    @Test
    void reviewerCanRequestEvidenceThenApproveTheResumedWorkflow() {
        CaseFulfillmentDisputeWorkflow workflow =
                newWorkflow("WORKFLOW_review_evidence");
        WorkflowClient.start(
                workflow::run,
                new CaseWorkflowInput(
                        "CASE_review_evidence",
                        "WORKFLOW_review_evidence",
                        RouteType.SIMPLE_HEARING,
                        Duration.ofHours(24),
                        2));
        workflow.submitReviewerSignal(
                new ReviewerWorkflowSignal(
                        "reviewer-1",
                        "REQUEST_MORE_EVIDENCE",
                        "need delivery photo"));
        workflow.submitPartyEvidence(
                new PartyEvidenceSignal(
                        "MERCHANT", "SUBMISSION_review", List.of("EVIDENCE_review")));
        workflow.submitReviewerSignal(
                new ReviewerWorkflowSignal(
                        "reviewer-1", "APPROVE", "evidence verified"));

        CaseWorkflowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(CaseWorkflowResult.class);

        assertThat(result.nextStage()).isEqualTo("EVALUATION_COMPLETE");
        assertThat(activities.reviewCalls).isEqualTo(2);
        assertThat(activities.recordedSignals()).contains("SUBMISSION_review");
        assertThat(activities.executionCalls).isEqualTo(1);
        assertThat(activities.closureCalls).isEqualTo(1);
    }

    private CaseFulfillmentDisputeWorkflow newWorkflow(String workflowId) {
        return client.newWorkflowStub(
                CaseFulfillmentDisputeWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
    }

    private static CaseWorkflowInput input(
            String caseId, String workflowId, Duration timeout) {
        return new CaseWorkflowInput(
                caseId,
                workflowId,
                RouteType.FULL_HEARING,
                timeout,
                2);
    }

    private static final class RecordingActivities
            implements CaseFulfillmentDisputeActivities {

        private final AtomicInteger calls = new AtomicInteger();
        private final java.util.concurrent.CopyOnWriteArrayList<String> signals =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile boolean alwaysRequireEvidence;
        private volatile int reviewerSignals;
        private volatile int remedyCalls;
        private volatile int reviewCalls;
        private volatile int executionCalls;
        private volatile int closureCalls;

        @Override
        public void initializeHearing(CaseWorkflowInput input) {}

        @Override
        public HearingAnalysisActivityResult analyzeHearing(
                HearingAnalysisActivityCommand command) {
            int call = calls.incrementAndGet();
            if (!command.evidenceTimedOut()
                    && (alwaysRequireEvidence || call == 1)) {
                return new HearingAnalysisActivityResult(
                        true, false, null, "WAITING_EVIDENCE");
            }
            return new HearingAnalysisActivityResult(
                    false,
                    command.evidenceTimedOut(),
                    "DRAFT_test",
                    "COMPLETED");
        }

        @Override
        public void recordPartyEvidence(PartyEvidenceSignal signal) {
            signals.add(signal.submissionId());
        }

        @Override
        public void recordReviewerSignal(
                ReviewerWorkflowSignal signal) {
            reviewerSignals++;
        }

        @Override
        public void completeHearing(
                String caseId,
                String workflowId,
                boolean manualRequired,
                boolean evidenceTimedOut) {}

        @Override
        public String planRemedy(String caseId, String workflowId) {
            remedyCalls++;
            return "REMEDY_test";
        }

        @Override
        public String createReviewTask(String caseId, String remedyPlanId) {
            reviewCalls++;
            return "REVIEW_test";
        }

        @Override
        public void executeApprovedPlan(String caseId) {
            executionCalls++;
        }

        @Override
        public void closeCaseAndEvaluate(String caseId) {
            closureCalls++;
        }

        int analysisCalls() {
            return calls.get();
        }

        List<String> recordedSignals() {
            return List.copyOf(signals);
        }
    }
}
