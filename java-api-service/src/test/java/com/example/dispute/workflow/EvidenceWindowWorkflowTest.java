package com.example.dispute.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.domain.EvidenceWindowCommand;
import com.example.dispute.workflow.domain.EvidenceWindowResult;
import com.example.dispute.workflow.temporal.EvidenceWindowActivities;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflow;
import com.example.dispute.workflow.temporal.EvidenceWindowWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EvidenceWindowWorkflowTest {

    private static final String TASK_QUEUE = "evidence-window-test";

    private TestWorkflowEnvironment environment;
    private WorkflowClient client;
    private RecordingActivities activities;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(EvidenceWindowWorkflowImpl.class);
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
    void oneAbsentPartyCausesExpiryAfterTwoVirtualHours() {
        long startedAt = environment.currentTimeMillis();
        EvidenceWindowWorkflow workflow =
                client.newWorkflowStub(
                        EvidenceWindowWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId("evidence-window-CASE_TIMEOUT")
                                .setTaskQueue(TASK_QUEUE)
                                .build());
        WorkflowClient.start(
                workflow::run,
                new EvidenceWindowCommand("CASE_TIMEOUT", Duration.ofHours(2)));
        workflow.partyCompleted("USER");

        EvidenceWindowResult result =
                io.temporal.client.WorkflowStub.fromTyped(workflow)
                        .getResult(EvidenceWindowResult.class);

        assertThat(result.stopReason()).isEqualTo("DEADLINE_EXPIRED");
        assertThat(result.completedRoles()).containsExactly("USER");
        assertThat(activities.warnedCases).containsExactly("CASE_TIMEOUT");
        assertThat(activities.expiredCases).containsExactly("CASE_TIMEOUT");
        assertThat(environment.currentTimeMillis())
                .isGreaterThanOrEqualTo(
                        startedAt + Duration.ofHours(2).toMillis());
    }

    static final class RecordingActivities implements EvidenceWindowActivities {
        final List<String> expiredCases = new ArrayList<>();
        final List<String> warnedCases = new ArrayList<>();

        @Override
        public void warn(String caseId) {
            warnedCases.add(caseId);
        }

        @Override
        public void expire(String caseId) {
            expiredCases.add(caseId);
        }
    }
}
