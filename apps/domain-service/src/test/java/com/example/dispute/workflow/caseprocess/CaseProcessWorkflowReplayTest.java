package com.example.dispute.workflow.caseprocess;

import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_MARKER_RECORDED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.example.dispute.workflow.runtime.temporal.TargetTypedRoomCaseProcessDispatcher;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            String historyJson = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            WorkflowExecutionHistory history =
                    WorkflowExecutionHistory.fromJson(
                            historyJson,
                            "case-process:tenant-case-process:CASE_ProcessWorkflow");
            assertThat(history.getEvents())
                    .noneMatch(event -> isVersionMarker(
                            event,
                            "case-process-expired-target-evidence-terminal-recovery-v1"));
            WorkflowReplayer.replayWorkflowExecution(
                    history, CaseProcessWorkflowImpl.class);
        }
    }

    @Test
    void returnedEvidenceTerminalOldMarkerHistoryReplaysThroughCachedExpiredRecovery()
            throws Exception {
        ReturnedEvidenceReplayActivityImpl activities =
                new ReturnedEvidenceReplayActivityImpl();
        WorkflowExecutionHistory history;
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            String taskQueue = "returned-evidence-terminal-history-replay";
            String workflowId = "returned-evidence-terminal-old-history";
            var worker = environment.newWorker(taskQueue);
            worker.registerWorkflowImplementationTypes(
                    HistoricalReturnedEvidenceHistoryWorkflow.class,
                    ReturnedEvidenceTerminalChildWorkflow.class);
            worker.registerActivitiesImplementations(activities);
            environment.start();

            ReturnedEvidenceHistoryWorkflow workflow =
                    environment.getWorkflowClient().newWorkflowStub(
                            ReturnedEvidenceHistoryWorkflow.class,
                            WorkflowOptions.newBuilder()
                                    .setWorkflowId(workflowId)
                                    .setTaskQueue(taskQueue)
                                    .build());
            WorkflowClient.start(workflow::run);

            assertThatThrownBy(
                            () ->
                                    WorkflowStub.fromTyped(workflow)
                                            .startUpdate(
                                                    UpdateOptions.newBuilder(String.class)
                                                            .setUpdateName(
                                                                    "dispatchReturnedEvidenceTerminal")
                                                            .setUpdateId("dispatch-returned-terminal-old")
                                                            .setWaitForStage(
                                                                    WorkflowUpdateStage.COMPLETED)
                                                            .build())
                                            .getResult())
                    .isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("TARGET_TYPED_ROOM_COMMAND_DISPATCH_FAILED");

            String firstRecovery =
                    WorkflowStub.fromTyped(workflow)
                            .startUpdate(
                                    UpdateOptions.newBuilder(String.class)
                                            .setUpdateName("recoverExpiredTargetEvidence")
                                            .setUpdateId("recover-expired-evidence-1")
                                            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                                            .build(),
                                    "recovery-expired-evidence-9")
                            .getResult();
            String cachedRecovery =
                    WorkflowStub.fromTyped(workflow)
                            .startUpdate(
                                    UpdateOptions.newBuilder(String.class)
                                            .setUpdateName("recoverExpiredTargetEvidence")
                                            .setUpdateId("recover-expired-evidence-2")
                                            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                                            .build(),
                                    "recovery-expired-evidence-9")
                            .getResult();
            assertThat(firstRecovery)
                    .isEqualTo("recovered:recovery-expired-evidence-9")
                    .isEqualTo(cachedRecovery);
            assertThat(activities.recoveryCalls).hasValue(1);
            assertThat(activities.convergenceCalls).hasValue(0);

            workflow.finish();
            WorkflowStub.fromTyped(workflow).getResult(Void.class);
            history = environment.getWorkflowClient().fetchHistory(workflowId);
        }

        assertThat(
                        history.getEvents().stream()
                                .filter(event -> isVersionMarker(
                                        event,
                                        TargetTypedRoomCaseProcessDispatcher
                                                .TARGET_EVIDENCE_AGENT_RUN_TERMINAL_NO_COMMIT_CHANGE_ID)))
                .hasSize(1);
        assertThat(history.getEvents())
                .noneMatch(event -> isVersionMarker(
                        event,
                        TargetTypedRoomCaseProcessDispatcher
                                .TARGET_EVIDENCE_RETURNED_AGENT_RUN_TERMINAL_NO_COMMIT_CHANGE_ID));

        long oldMarkerEventId =
                history.getEvents().stream()
                        .filter(event -> isVersionMarker(
                                event,
                                TargetTypedRoomCaseProcessDispatcher
                                        .TARGET_EVIDENCE_AGENT_RUN_TERMINAL_NO_COMMIT_CHANGE_ID))
                        .findFirst()
                        .orElseThrow()
                        .getEventId();
        long childStartedEventId =
                history.getEvents().stream()
                        .filter(event -> event.getEventType()
                                == EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED)
                        .findFirst()
                        .orElseThrow()
                        .getEventId();
        long childCompletedEventId =
                history.getEvents().stream()
                        .filter(event -> event.getEventType()
                                == EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED)
                        .findFirst()
                        .orElseThrow()
                        .getEventId();
        List<HistoryEvent> recoverySchedules =
                history.getEvents().stream()
                        .filter(event -> event.getEventType() == EVENT_TYPE_ACTIVITY_TASK_SCHEDULED)
                        .filter(event -> event.getActivityTaskScheduledEventAttributes()
                                .getActivityType()
                                .getName()
                                .equals("RecoverExpiredTargetEvidenceTerminalNoCommit"))
                        .toList();
        List<Long> updateCompletedEventIds =
                history.getEvents().stream()
                        .filter(event -> event.getEventType()
                                == EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED)
                        .map(HistoryEvent::getEventId)
                        .toList();
        assertThat(recoverySchedules).hasSize(1);
        long recoveryScheduledEventId = recoverySchedules.getFirst().getEventId();
        long recoveryCompletedEventId =
                history.getEvents().stream()
                        .filter(event -> event.getEventType() == EVENT_TYPE_ACTIVITY_TASK_COMPLETED)
                        .filter(event -> event.getActivityTaskCompletedEventAttributes()
                                .getScheduledEventId() == recoveryScheduledEventId)
                        .findFirst()
                        .orElseThrow()
                        .getEventId();
        assertThat(history.getEvents().stream()
                        .filter(event -> event.getEventType() == EVENT_TYPE_ACTIVITY_TASK_SCHEDULED)
                        .map(event -> event.getActivityTaskScheduledEventAttributes()
                                .getActivityType()
                                .getName()))
                .doesNotContain("ConvergeTargetEvidenceTerminalNoCommit");
        assertThat(updateCompletedEventIds).hasSize(3);
        assertThat(oldMarkerEventId).isLessThan(childStartedEventId);
        assertThat(childStartedEventId).isLessThan(childCompletedEventId);
        assertThat(childCompletedEventId).isLessThan(updateCompletedEventIds.get(0));
        assertThat(updateCompletedEventIds.get(0)).isLessThan(recoveryScheduledEventId);
        assertThat(recoveryScheduledEventId).isLessThan(recoveryCompletedEventId);
        assertThat(recoveryCompletedEventId).isLessThan(updateCompletedEventIds.get(1));
        assertThat(updateCompletedEventIds.get(1)).isLessThan(updateCompletedEventIds.get(2));

        WorkflowReplayer.replayWorkflowExecution(
                history, VersionedReturnedEvidenceHistoryWorkflow.class);
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

    private static boolean isVersionMarker(HistoryEvent event, String changeId) {
        if (event.getEventType() != EVENT_TYPE_MARKER_RECORDED) {
            return false;
        }
        var marker = event.getMarkerRecordedEventAttributes();
        var details = marker.getDetailsMap().get("changeId");
        return marker.getMarkerName().equals("Version")
                && details != null
                && details.getPayloadsCount() == 1
                && details.getPayloads(0).getData().toStringUtf8()
                        .equals("\"" + changeId + "\"");
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

    @WorkflowInterface
    public interface ReturnedEvidenceHistoryWorkflow {
        @WorkflowMethod
        void run();

        @UpdateMethod(name = "dispatchReturnedEvidenceTerminal")
        String dispatchReturnedEvidenceTerminal();

        @UpdateMethod(name = "recoverExpiredTargetEvidence")
        String recoverExpiredTargetEvidence(String recoveryId);

        @SignalMethod
        void finish();
    }

    @WorkflowInterface
    public interface ReturnedEvidenceTerminalChild {
        @WorkflowMethod
        ExecuteAgentRunResult run();
    }

    @ActivityInterface
    public interface ReturnedEvidenceReplayActivity {
        @ActivityMethod(name = "ConvergeTargetEvidenceTerminalNoCommit")
        String converge(String authority);

        @ActivityMethod(name = "RecoverExpiredTargetEvidenceTerminalNoCommit")
        String recover(String recoveryId);
    }

    public static final class HistoricalReturnedEvidenceHistoryWorkflow
            extends ReturnedEvidenceHistoryWorkflowBase {
        @Override
        protected boolean queryReturnedTerminalMarker() {
            return false;
        }
    }

    public static final class VersionedReturnedEvidenceHistoryWorkflow
            extends ReturnedEvidenceHistoryWorkflowBase {
        @Override
        protected boolean queryReturnedTerminalMarker() {
            return true;
        }
    }

    public abstract static class ReturnedEvidenceHistoryWorkflowBase
            implements ReturnedEvidenceHistoryWorkflow {
        private final ReturnedEvidenceReplayActivity activities =
                Workflow.newActivityStub(
                        ReturnedEvidenceReplayActivity.class,
                        ActivityOptions.newBuilder()
                                .setStartToCloseTimeout(Duration.ofSeconds(5))
                                .build());
        private final Map<String, String> recoveryCommitments = new HashMap<>();
        private boolean finished;

        @Override
        public void run() {
            Workflow.await(() -> finished);
        }

        @Override
        public String dispatchReturnedEvidenceTerminal() {
            int oldVersion =
                    Workflow.getVersion(
                            TargetTypedRoomCaseProcessDispatcher
                                    .TARGET_EVIDENCE_AGENT_RUN_TERMINAL_NO_COMMIT_CHANGE_ID,
                            Workflow.DEFAULT_VERSION,
                            1);
            ReturnedEvidenceTerminalChild child =
                    Workflow.newChildWorkflowStub(
                            ReturnedEvidenceTerminalChild.class,
                            ChildWorkflowOptions.newBuilder()
                                    .setWorkflowId("returned-evidence-terminal-child")
                                    .build());
            ExecuteAgentRunResult result = child.run();
            if (oldVersion == 1
                    && isTerminalNoCommit(result)
                    && queryReturnedTerminalMarker()) {
                int returnedVersion =
                        Workflow.getVersion(
                                TargetTypedRoomCaseProcessDispatcher
                                        .TARGET_EVIDENCE_RETURNED_AGENT_RUN_TERMINAL_NO_COMMIT_CHANGE_ID,
                                Workflow.DEFAULT_VERSION,
                                1);
                if (returnedVersion == 1) {
                    activities.converge(result.errorCode());
                }
            }
            throw ApplicationFailure.newNonRetryableFailure(
                    "target typed room command dispatch failed",
                    "TARGET_TYPED_ROOM_COMMAND_DISPATCH_FAILED");
        }

        @Override
        public String recoverExpiredTargetEvidence(String recoveryId) {
            String committed = recoveryCommitments.get(recoveryId);
            if (committed != null) {
                return committed;
            }
            String recovered = activities.recover(recoveryId);
            recoveryCommitments.put(recoveryId, recovered);
            return recovered;
        }

        @Override
        public void finish() {
            finished = true;
        }

        protected abstract boolean queryReturnedTerminalMarker();

        private static boolean isTerminalNoCommit(ExecuteAgentRunResult result) {
            return result.outcome() == ExecuteAgentRunResult.Outcome.FAILED
                    && !result.retryable()
                    && result.recoveryAction() == AgentRunRecoveryAction.FAIL_LOGICAL_RUN;
        }
    }

    public static final class ReturnedEvidenceTerminalChildWorkflow
            implements ReturnedEvidenceTerminalChild {
        @Override
        public ExecuteAgentRunResult run() {
            return new ExecuteAgentRunResult(
                    ExecuteAgentRunResult.SCHEMA_VERSION,
                    "returned-evidence-agent-run",
                    "returned-evidence-agent-run",
                    "returned-evidence-agent-run:1",
                    1,
                    ExecuteAgentRunResult.Outcome.FAILED,
                    null,
                    null,
                    2,
                    false,
                    "GRAPH_CONTRACT_REJECTED",
                    false,
                    AgentRunRecoveryAction.FAIL_LOGICAL_RUN,
                    Instant.ofEpochMilli(Workflow.currentTimeMillis()));
        }
    }

    public static final class ReturnedEvidenceReplayActivityImpl
            implements ReturnedEvidenceReplayActivity {
        private final AtomicInteger convergenceCalls = new AtomicInteger();
        private final AtomicInteger recoveryCalls = new AtomicInteger();

        @Override
        public String converge(String authority) {
            convergenceCalls.incrementAndGet();
            return "converged:" + authority;
        }

        @Override
        public String recover(String recoveryId) {
            recoveryCalls.incrementAndGet();
            return "recovered:" + recoveryId;
        }
    }
}
