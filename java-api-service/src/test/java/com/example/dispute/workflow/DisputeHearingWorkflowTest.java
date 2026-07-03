package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.domain.EvidenceSubmissionSignal;
import com.example.dispute.workflow.domain.HearingStageActivityResult;
import com.example.dispute.workflow.domain.HearingWorkflowCommand;
import com.example.dispute.workflow.domain.HearingWorkflowResult;
import com.example.dispute.workflow.temporal.DisputeHearingActivities;
import com.example.dispute.workflow.temporal.DisputeHearingWorkflow;
import com.example.dispute.workflow.temporal.DisputeHearingWorkflowImpl;
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

class DisputeHearingWorkflowTest {

    private static final String TASK_QUEUE = "final-hearing-test";

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private RecordingActivities activities;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                DisputeHearingWorkflowImpl.class);
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
    void runsC1ToC6InOrderAndResumesOnEvidenceSignal() {
        DisputeHearingWorkflow workflow = workflow("WORKFLOW_hearing_signal");
        WorkflowClient.start(
                workflow::run,
                command(
                        "CASE_hearing_signal",
                        "WORKFLOW_hearing_signal",
                        Duration.ofHours(24),
                        2));
        workflow.submitEvidence(
                new EvidenceSubmissionSignal(
                        "SUBMISSION_2",
                        "USER",
                        List.of("EVIDENCE_2")));

        HearingWorkflowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(HearingWorkflowResult.class);

        assertThat(activities.stages)
                .containsExactly(
                        "C1_ISSUE_FRAMING",
                        "C2_EVIDENCE_GAP",
                        "C3_EVIDENCE_REQUEST",
                        "C1_ISSUE_FRAMING",
                        "C2_EVIDENCE_GAP",
                        "C4_EVIDENCE_CROSS_CHECK",
                        "C5_RULE_APPLICATION",
                        "C6_DRAFT_GENERATION");
        assertThat(activities.traces).containsExactlyElementsOf(activities.stages);
        assertThat(activities.recordedEvidence).containsExactly("SUBMISSION_2");
        assertThat(result.dossierVersion()).isEqualTo(2);
        assertThat(result.draftId()).isEqualTo("DRAFT_final");
        assertThat(result.evidenceTimedOut()).isFalse();
    }

    @Test
    void evidenceTimerAndBoundedRoundsEndInManualReview() {
        activities.alwaysRequireEvidence = true;
        long startedAt = environment.currentTimeMillis();

        HearingWorkflowResult result =
                workflow("WORKFLOW_hearing_timeout")
                        .run(
                                command(
                                        "CASE_hearing_timeout",
                                        "WORKFLOW_hearing_timeout",
                                        Duration.ofHours(48),
                                        1));

        assertThat(result.evidenceTimedOut()).isTrue();
        assertThat(result.manualRequired()).isTrue();
        assertThat(environment.currentTimeMillis())
                .isGreaterThanOrEqualTo(
                        startedAt + Duration.ofHours(48).toMillis());
        assertThat(activities.stages)
                .endsWith(
                        "C4_EVIDENCE_CROSS_CHECK",
                        "C5_RULE_APPLICATION",
                        "C6_DRAFT_GENERATION");
    }

    @Test
    void invalidStructuredStageInterruptsBeforeLaterCognition() {
        activities.invalidStage = "C4_EVIDENCE_CROSS_CHECK";

        HearingWorkflowResult result =
                workflow("WORKFLOW_hearing_invalid")
                        .run(
                                command(
                                        "CASE_hearing_invalid",
                                        "WORKFLOW_hearing_invalid",
                                        Duration.ofHours(1),
                                        1));

        assertThat(result.status()).isEqualTo("VALIDATION_INTERRUPTED");
        assertThat(result.manualRequired()).isTrue();
        assertThat(activities.stages)
                .doesNotContain("C5_RULE_APPLICATION", "C6_DRAFT_GENERATION");
    }

    @Test
    void threeHourDeadlineAlwaysConvergesThroughC6() {
        long startedAt = environment.currentTimeMillis();

        HearingWorkflowResult result =
                workflow("WORKFLOW_shared_hearing_timeout")
                        .run(
                                new HearingWorkflowCommand(
                                        "CASE_shared_hearing_timeout",
                                        "WORKFLOW_shared_hearing_timeout",
                                        1,
                                        Duration.ofHours(24),
                                        2,
                                        Duration.ofHours(3),
                                        3));

        assertThat(result.stopReason()).isEqualTo("DEADLINE_EXPIRED");
        assertThat(result.draftId()).isEqualTo("DRAFT_final");
        assertThat(result.manualRequired()).isTrue();
        assertThat(environment.currentTimeMillis())
                .isGreaterThanOrEqualTo(
                        startedAt + Duration.ofHours(3).toMillis());
        assertThat(activities.stages).contains("C6_DRAFT_GENERATION");
    }

    @Test
    void threeCompletedRoundsForceConvergenceBeforeDeadline() {
        long startedAt = environment.currentTimeMillis();
        DisputeHearingWorkflow workflow =
                workflow("WORKFLOW_shared_hearing_rounds");
        WorkflowClient.start(
                workflow::run,
                new HearingWorkflowCommand(
                        "CASE_shared_hearing_rounds",
                        "WORKFLOW_shared_hearing_rounds",
                        1,
                        Duration.ofHours(24),
                        2,
                        Duration.ofHours(3),
                        3));
        workflow.hearingRoundCompleted(1, false);
        workflow.hearingRoundCompleted(2, false);
        workflow.hearingRoundCompleted(3, false);

        HearingWorkflowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(HearingWorkflowResult.class);

        assertThat(result.stopReason()).isEqualTo("MAX_ROUNDS");
        assertThat(result.draftId()).isEqualTo("DRAFT_final");
        assertThat(environment.currentTimeMillis())
                .isLessThan(startedAt + Duration.ofHours(3).toMillis());
    }

    private DisputeHearingWorkflow workflow(String workflowId) {
        return client.newWorkflowStub(
                DisputeHearingWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
    }

    private static HearingWorkflowCommand command(
            String caseId,
            String workflowId,
            Duration wait,
            int maxRounds) {
        return new HearingWorkflowCommand(
                caseId, workflowId, 1, wait, maxRounds);
    }

    private static final class RecordingActivities
            implements DisputeHearingActivities {
        private final List<String> stages = new CopyOnWriteArrayList<>();
        private final List<String> traces = new CopyOnWriteArrayList<>();
        private final List<String> recordedEvidence =
                new CopyOnWriteArrayList<>();
        private volatile boolean alwaysRequireEvidence;
        private volatile String invalidStage;
        private int c2Calls;

        @Override
        public void initialize(HearingWorkflowCommand command) {}

        @Override
        public HearingStageActivityResult runStage(
                String caseId,
                String workflowId,
                String stage,
                int round,
                long dossierVersion,
                boolean evidenceTimedOut) {
            stages.add(stage);
            boolean valid = !stage.equals(invalidStage);
            boolean requiresEvidence = false;
            if ("C2_EVIDENCE_GAP".equals(stage)) {
                c2Calls++;
                requiresEvidence =
                        !evidenceTimedOut
                                && (alwaysRequireEvidence || c2Calls == 1);
            }
            return new HearingStageActivityResult(
                    stage,
                    valid,
                    requiresEvidence,
                    !valid,
                    "C6_DRAFT_GENERATION".equals(stage)
                            ? "DRAFT_final"
                            : null,
                    "OUTPUT_" + stages.size());
        }

        @Override
        public long recordEvidence(EvidenceSubmissionSignal signal) {
            recordedEvidence.add(signal.submissionId());
            return 2;
        }

        @Override
        public void persistStageTrace(
                String caseId,
                String workflowId,
                String stage,
                int round,
                long dossierVersion,
                String outputVersion) {
            traces.add(stage);
        }

        @Override
        public void complete(
                String caseId,
                String workflowId,
                String status,
                boolean manualRequired,
                boolean evidenceTimedOut,
                long dossierVersion,
                String stopReason) {}
    }
}
