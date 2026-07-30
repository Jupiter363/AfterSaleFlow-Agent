package com.example.dispute.workflow.caseprocess;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.example.dispute.workflow.targete2e.temporal.TargetTypedRoomCaseProcessDispatcher;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CaseProcessWorkflowReplayTest {

    @Test
    void capturedV1HistoryReplaysAgainstTheCurrentWorker() throws Exception {
        try (InputStream input =
                Objects.requireNonNull(
                        getClass()
                                .getClassLoader()
                                .getResourceAsStream(
                                        "temporal-history/case-process-v1.json"),
                        "captured Temporal history is missing")) {
            WorkflowExecutionHistory history =
                    WorkflowExecutionHistory.fromJson(
                            new String(input.readAllBytes(), StandardCharsets.UTF_8),
                            "case-process:tenant-case-process:CASE_ProcessWorkflow");
            WorkflowReplayer.replayWorkflowExecution(
                    history, CaseProcessWorkflowImpl.class);
        }
    }

    @Test
    void intakePartyScopeAuthorityVersionReplaysLegacyStartChildWithoutSchedulingActivity() throws Exception {
        WorkflowExecutionHistory legacyHistory;
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("intake-party-scope-legacy-replay");
            worker.registerWorkflowImplementationTypes(
                    LegacyPartyScopeProbeWorkflow.class, PartyScopeProbeChildWorkflow.class);
            environment.start();

            PartyScopeProbeWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    PartyScopeProbeWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId("intake-party-scope-legacy-history")
                            .setTaskQueue("intake-party-scope-legacy-replay")
                            .build());
            WorkflowClient.start(workflow::run);
            assertThat(WorkflowStub.fromTyped(workflow).getResult(String.class)).isEqualTo("child-complete");
            legacyHistory = environment.getWorkflowClient().fetchHistory(
                    "intake-party-scope-legacy-history");
        }

        assertThat(legacyHistory.getEvents())
                .noneMatch(event -> event.getEventType() == EVENT_TYPE_ACTIVITY_TASK_SCHEDULED)
                .anyMatch(event ->
                        event.getEventType() == EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED);
        WorkflowReplayer.replayWorkflowExecution(
                legacyHistory, VersionedPartyScopeProbeWorkflow.class);
    }

    @Test
    void intakePartyScopeAuthorityVersionSchedulesActivityForNewHistory() {
        AtomicInteger authorityCalls = new AtomicInteger();
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("intake-party-scope-new-history");
            worker.registerWorkflowImplementationTypes(
                    VersionedPartyScopeProbeWorkflow.class, PartyScopeProbeChildWorkflow.class);
            worker.registerActivitiesImplementations(
                    (PartyScopeProbeActivity) () -> {
                        authorityCalls.incrementAndGet();
                        return "authority-resolved";
                    });
            environment.start();

            PartyScopeProbeWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    PartyScopeProbeWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId("intake-party-scope-new-history")
                            .setTaskQueue("intake-party-scope-new-history")
                            .build());
            WorkflowClient.start(workflow::run);
            assertThat(WorkflowStub.fromTyped(workflow).getResult(String.class)).isEqualTo("child-complete");

            assertThat(authorityCalls).hasValue(1);
            assertThat(environment.getWorkflowClient()
                            .fetchHistory("intake-party-scope-new-history")
                            .getEvents())
                    .anyMatch(event -> event.getEventType() == EVENT_TYPE_ACTIVITY_TASK_SCHEDULED)
                    .anyMatch(event ->
                            event.getEventType() == EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED);
        }
    }

    @WorkflowInterface
    public interface PartyScopeProbeWorkflow {
        @WorkflowMethod
        String run();
    }

    @WorkflowInterface
    public interface PartyScopeProbeChild {
        @WorkflowMethod
        String run();
    }

    @ActivityInterface
    public interface PartyScopeProbeActivity {
        @ActivityMethod
        String resolve();
    }

    public static final class LegacyPartyScopeProbeWorkflow implements PartyScopeProbeWorkflow {
        @Override
        public String run() {
            return child().run();
        }
    }

    public static final class VersionedPartyScopeProbeWorkflow implements PartyScopeProbeWorkflow {
        private final PartyScopeProbeActivity authority = Workflow.newActivityStub(
                PartyScopeProbeActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(5))
                        .build());

        @Override
        public String run() {
            int version = Workflow.getVersion(
                    TargetTypedRoomCaseProcessDispatcher
                            .TARGET_INTAKE_PARTY_SCOPE_AUTHORITY_CHANGE_ID,
                    Workflow.DEFAULT_VERSION,
                    1);
            if (version == 1) {
                authority.resolve();
            }
            return child().run();
        }
    }

    public static final class PartyScopeProbeChildWorkflow implements PartyScopeProbeChild {
        @Override
        public String run() {
            return "child-complete";
        }
    }

    private static PartyScopeProbeChild child() {
        return Workflow.newChildWorkflowStub(
                PartyScopeProbeChild.class,
                ChildWorkflowOptions.newBuilder()
                        .setWorkflowId("intake-party-scope-probe-child")
                        .build());
    }
}
