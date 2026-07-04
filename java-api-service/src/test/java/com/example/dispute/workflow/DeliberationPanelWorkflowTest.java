package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.domain.CriticActivityResult;
import com.example.dispute.workflow.domain.DeliberationPanelCommand;
import com.example.dispute.workflow.domain.DeliberationPanelResult;
import com.example.dispute.workflow.domain.FrozenDeliberationSnapshot;
import com.example.dispute.workflow.temporal.DeliberationPanelActivities;
import com.example.dispute.workflow.temporal.DeliberationPanelWorkflow;
import com.example.dispute.workflow.temporal.DeliberationPanelWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliberationPanelWorkflowTest {

    private static final String TASK_QUEUE = "final-panel-test";

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private RecordingActivities activities;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                DeliberationPanelWorkflowImpl.class);
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
    void runsOnlyRiskSelectedCriticsAgainstOneFrozenInput() {
        DeliberationPanelResult result =
                workflow("WORKFLOW_panel_selected")
                        .run(
                                command(
                                        List.of(
                                                "EVIDENCE_CRITIC",
                                                "RISK_CRITIC")));

        assertThat(activities.critics)
                .containsExactlyInAnyOrder(
                        "EVIDENCE_CRITIC", "RISK_CRITIC");
        assertThat(activities.fingerprints)
                .containsOnly("FROZEN_fingerprint");
        assertThat(result.panelResult()).isEqualTo("NO_MAJOR_OBJECTION");
        assertThat(result.manualRequired()).isFalse();
    }

    @Test
    void blockerAndMinorityMajorObjectionCannotBeAveragedAway() {
        activities.blockingCritic = "EVIDENCE_CRITIC";

        DeliberationPanelResult result =
                workflow("WORKFLOW_panel_blocker")
                        .run(command(
                                List.of(
                                        "EVIDENCE_CRITIC",
                                        "RULE_CRITIC",
                                        "RISK_CRITIC",
                                        "REMEDY_CRITIC",
                                        "FAIRNESS_CRITIC")));

        assertThat(result.panelResult()).isEqualTo("REVISION_REQUIRED");
        assertThat(result.revisionRequired()).isTrue();
        assertThat(result.majorObjections())
                .containsExactly("UNRESOLVED_EVIDENCE_CONFLICT");
    }

    @Test
    void criticScoreBelowThresholdRequiresRevision() {
        activities.lowScoreCritic = "RISK_CRITIC";

        DeliberationPanelResult result =
                workflow("WORKFLOW_panel_low_score")
                        .run(
                                new DeliberationPanelCommand(
                                        "CASE_panel",
                                        "WORKFLOW_panel",
                                        "DRAFT_panel",
                                        3,
                                        List.of("RISK_CRITIC"),
                                        List.of("HIGH_VALUE_CASE"),
                                        80,
                                        2));

        assertThat(result.panelResult()).isEqualTo("REVISION_REQUIRED");
        assertThat(result.revisionRequired()).isTrue();
        assertThat(result.majorObjections())
                .containsExactly("RISK_CRITIC_SCORE_BELOW_THRESHOLD_79");
    }

    @Test
    void failedOrTimedOutCriticRequiresManualReview() {
        activities.timedOutCritic = "RULE_CRITIC";

        DeliberationPanelResult result =
                workflow("WORKFLOW_panel_timeout")
                        .run(
                                command(
                                        List.of(
                                                "EVIDENCE_CRITIC",
                                                "RULE_CRITIC")));

        assertThat(result.panelResult())
                .isEqualTo("MANUAL_REVIEW_REQUIRED");
        assertThat(result.manualRequired()).isTrue();
        assertThat(result.unavailableCritics())
                .containsExactly("RULE_CRITIC");
    }

    private DeliberationPanelWorkflow workflow(String workflowId) {
        return client.newWorkflowStub(
                DeliberationPanelWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
    }

    private static DeliberationPanelCommand command(
            List<String> selectedCritics) {
        return new DeliberationPanelCommand(
                "CASE_panel",
                "WORKFLOW_panel",
                "DRAFT_panel",
                3,
                selectedCritics);
    }

    private static final class RecordingActivities
            implements DeliberationPanelActivities {
        private final List<String> critics = new CopyOnWriteArrayList<>();
        private final List<String> fingerprints =
                new CopyOnWriteArrayList<>();
        private volatile String blockingCritic;
        private volatile String timedOutCritic;
        private volatile String lowScoreCritic;

        @Override
        public FrozenDeliberationSnapshot freeze(
                DeliberationPanelCommand command) {
            return new FrozenDeliberationSnapshot(
                    command.caseId(),
                    7,
                    command.dossierVersion(),
                    2,
                    "RULE_2026_01",
                    1,
                    "FROZEN_fingerprint");
        }

        @Override
        public CriticActivityResult runCritic(
                FrozenDeliberationSnapshot snapshot,
                String critic) {
            critics.add(critic);
            fingerprints.add(snapshot.fingerprint());
            if (critic.equals(timedOutCritic)) {
                return new CriticActivityResult(
                        critic,
                        "TIMED_OUT",
                        "BLOCKER",
                        List.of("CRITIC_TIMEOUT"),
                        snapshot.fingerprint());
            }
            if (critic.equals(blockingCritic)) {
                return new CriticActivityResult(
                        critic,
                        "COMPLETED",
                        "BLOCKER",
                        List.of("UNRESOLVED_EVIDENCE_CONFLICT"),
                        snapshot.fingerprint());
            }
            if (critic.equals(lowScoreCritic)) {
                return new CriticActivityResult(
                        critic,
                        "COMPLETED",
                        "NONE",
                        List.of(),
                        snapshot.fingerprint(),
                        79);
            }
            return new CriticActivityResult(
                    critic,
                    "COMPLETED",
                    "NONE",
                    List.of(),
                    snapshot.fingerprint());
        }

        @Override
        public String persistReport(
                DeliberationPanelCommand command,
                FrozenDeliberationSnapshot snapshot,
                List<CriticActivityResult> reports,
                String panelResult) {
            return "DELIBERATION_test";
        }
    }
}
