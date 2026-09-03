package com.example.dispute.workflow.recovery;

import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE;
import static com.example.dispute.workflow.recovery.LegacyCaseProcessWorkflowRecoveryMain.CASE_PROCESS_WORKFLOW_CLASS;
import static com.example.dispute.workflow.recovery.LegacyCaseProcessWorkflowRecoveryMain.WORKTREE_MARKER;
import static com.example.dispute.workflow.recovery.LegacyCaseProcessWorkflowRecoveryMain.PendingTaskKind.NORMAL;
import static com.example.dispute.workflow.recovery.LegacyCaseProcessWorkflowRecoveryMain.PendingTaskState.SCHEDULED;
import static com.example.dispute.workflow.recovery.LegacyCaseProcessWorkflowRecoveryMain.WorkflowStatus.OTHER;
import static com.example.dispute.workflow.recovery.LegacyCaseProcessWorkflowRecoveryMain.WorkflowStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.recovery.LegacyCaseProcessWorkflowRecoveryMain.ExecutionAuthority;
import com.example.dispute.workflow.recovery.LegacyCaseProcessWorkflowRecoveryMain.OpenExecutionAuthority;
import com.example.dispute.workflow.recovery.LegacyCaseProcessWorkflowRecoveryMain.RecoveryPlan;
import com.example.dispute.workflow.recovery.LegacyCaseProcessWorkflowRecoveryMain.RecoveryRequest;
import com.example.dispute.workflow.recovery.LegacyCaseProcessWorkflowRecoveryMain.TemporalAuthority;
import com.example.dispute.workflow.recovery.LegacyCaseProcessWorkflowRecoveryMain.WorkflowOnlyRuntime;
import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyCaseProcessWorkflowRecoveryMainTest {

    private static final String WORKTREE_BINDING = "a".repeat(64);
    private static final String LEGACY_BUILD_ID =
            "local-final-control.local-source-"
                    + WORKTREE_BINDING
                    + "-control";
    private static final String WORKFLOW_ID = "case-process:tenant-test:CASE_TEST";
    private static final String RUN_ID = "11111111-1111-4111-8111-111111111111";
    private static final byte[] WORKFLOW_BYTES =
            "replay-compatible-case-process-workflow".getBytes(StandardCharsets.UTF_8);

    @TempDir Path tempDir;

    @Test
    void workflowOnlyBridgeRequiresExactAuthorityAndRegistersNoActivities() throws Exception {
        Path retained = retainedClasses(WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request = request(retained, LEGACY_BUILD_ID);
        ExecutionAuthority execution = execution();
        OpenExecutionAuthority openExecution = openExecution();
        FakeAuthority authority = new FakeAuthority(execution, List.of(openExecution));

        RecoveryPlan first =
                LegacyCaseProcessWorkflowRecoveryMain.prepare(
                        request, authority, () -> WORKFLOW_BYTES.clone());
        RecoveryPlan replay =
                LegacyCaseProcessWorkflowRecoveryMain.prepare(
                        request, authority, () -> WORKFLOW_BYTES.clone());

        assertThat(replay).isEqualTo(first);
        assertThat(first.retainedWorktreeBinding()).isEqualTo(WORKTREE_BINDING);
        assertThat(first.legacyBuildId()).isEqualTo(LEGACY_BUILD_ID);
        assertThat(first.maxRuntime()).isEqualTo(Duration.ofMinutes(30));
        assertThat(authority.describeCalls).isEqualTo(2);
        assertThat(authority.listCalls).isEqualTo(2);

        RecordingRuntime runtime = new RecordingRuntime();
        LegacyCaseProcessWorkflowRecoveryMain.runBridge(first, runtime);
        assertThat(runtime.workflowTypes).containsExactly(CaseProcessWorkflowImpl.class);
        assertThat(runtime.activityRegistrationCount).isZero();
        assertThat(runtime.startCount).isEqualTo(1);
        assertThat(runtime.awaited).containsExactly(Duration.ofMinutes(30));

        WorkflowExecutionInfo legacyVisibilityRow =
                WorkflowExecutionInfo.newBuilder()
                        .setExecution(
                                WorkflowExecution.newBuilder()
                                        .setWorkflowId(WORKFLOW_ID)
                                        .setRunId(RUN_ID))
                        .setType(WorkflowType.newBuilder().setName(CASE_WORKFLOW_TYPE))
                        .setStatus(
                                io.temporal.api.enums.v1.WorkflowExecutionStatus
                                        .WORKFLOW_EXECUTION_STATUS_RUNNING)
                        .setTaskQueue(CASE_CONTROL_TASK_QUEUE)
                        .build();
        assertThat(
                        LegacyCaseProcessWorkflowRecoveryMain.mergeExactBuildVisibilityRows(
                                List.of(legacyVisibilityRow),
                                List.of(legacyVisibilityRow),
                                LEGACY_BUILD_ID))
                .containsExactly(openExecution);

        assertFailure(
                request,
                new FakeAuthority(withWorkflowType(execution, "OtherWorkflow"), List.of(openExecution)),
                WORKFLOW_BYTES);
        assertFailure(
                request,
                new FakeAuthority(withStatus(execution, OTHER), List.of(openExecution)),
                WORKFLOW_BYTES);
        assertFailure(
                request,
                new FakeAuthority(withTaskQueue(execution, "room-control"), List.of(openExecution)),
                WORKFLOW_BYTES);
        assertFailure(
                request,
                new FakeAuthority(withRunId(execution, "22222222-2222-4222-8222-222222222222"), List.of(openExecution)),
                WORKFLOW_BYTES);
        assertFailure(
                request,
                new FakeAuthority(withAssignedBuild(execution, "other-build"), List.of(openExecution)),
                WORKFLOW_BYTES);
        assertFailure(
                request,
                new FakeAuthority(withPendingState(execution), List.of(openExecution)),
                WORKFLOW_BYTES);
        assertFailure(
                request,
                new FakeAuthority(withPendingKind(execution), List.of(openExecution)),
                WORKFLOW_BYTES);
        assertFailure(request, new FakeAuthority(execution, List.of()), WORKFLOW_BYTES);
        assertFailure(
                request,
                new FakeAuthority(execution, List.of(openExecution, openExecution)),
                WORKFLOW_BYTES);
        assertFailure(request, authority, "different-workflow-bytecode".getBytes(StandardCharsets.UTF_8));

        Files.writeString(
                retained.resolve(WORKTREE_MARKER), "b".repeat(64), StandardCharsets.US_ASCII);
        assertFailure(request, authority, WORKFLOW_BYTES);
        Files.writeString(
                retained.resolve(WORKTREE_MARKER), WORKTREE_BINDING, StandardCharsets.US_ASCII);
        assertFailure(request(retained, buildIdFor("b".repeat(64))), authority, WORKFLOW_BYTES);

        OpenExecutionAuthority wrongOpenRun =
                new OpenExecutionAuthority(
                        WORKFLOW_ID,
                        "33333333-3333-4333-8333-333333333333",
                        CASE_WORKFLOW_TYPE,
                        RUNNING,
                        CASE_CONTROL_TASK_QUEUE,
                        LEGACY_BUILD_ID,
                        LEGACY_BUILD_ID,
                        true);
        assertFailure(
                request, new FakeAuthority(execution, List.of(wrongOpenRun)), WORKFLOW_BYTES);
    }

    private Path retainedClasses(String binding, byte[] workflowBytes) throws Exception {
        Path retained = tempDir.resolve("retained-classes");
        Files.createDirectories(retained.resolve(WORKTREE_MARKER).getParent());
        Files.createDirectories(retained.resolve(CASE_PROCESS_WORKFLOW_CLASS).getParent());
        Files.writeString(retained.resolve(WORKTREE_MARKER), binding, StandardCharsets.US_ASCII);
        Files.write(retained.resolve(CASE_PROCESS_WORKFLOW_CLASS), workflowBytes);
        return retained;
    }

    private static RecoveryRequest request(Path retained, String buildId) {
        return new RecoveryRequest(
                "127.0.0.1:7233",
                "uat-namespace",
                WORKFLOW_ID,
                RUN_ID,
                buildId,
                retained,
                Duration.ofMinutes(30));
    }

    private static String buildIdFor(String binding) {
        return "local-final-control.local-source-" + binding + "-control";
    }

    private static ExecutionAuthority execution() {
        return new ExecutionAuthority(
                WORKFLOW_ID,
                RUN_ID,
                CASE_WORKFLOW_TYPE,
                RUNNING,
                CASE_CONTROL_TASK_QUEUE,
                LEGACY_BUILD_ID,
                LEGACY_BUILD_ID,
                true,
                SCHEDULED,
                NORMAL,
                CASE_CONTROL_TASK_QUEUE,
                1);
    }

    private static OpenExecutionAuthority openExecution() {
        return new OpenExecutionAuthority(
                WORKFLOW_ID,
                RUN_ID,
                CASE_WORKFLOW_TYPE,
                RUNNING,
                CASE_CONTROL_TASK_QUEUE,
                LEGACY_BUILD_ID,
                LEGACY_BUILD_ID,
                true);
    }

    private static ExecutionAuthority withWorkflowType(
            ExecutionAuthority source, String workflowType) {
        return new ExecutionAuthority(
                source.workflowId(),
                source.runId(),
                workflowType,
                source.status(),
                source.taskQueue(),
                source.assignedBuildId(),
                source.mostRecentBuildId(),
                source.versioned(),
                source.pendingState(),
                source.pendingTaskKind(),
                source.pendingTaskQueue(),
                source.pendingAttempt());
    }

    private static ExecutionAuthority withStatus(
            ExecutionAuthority source,
            LegacyCaseProcessWorkflowRecoveryMain.WorkflowStatus status) {
        return new ExecutionAuthority(
                source.workflowId(),
                source.runId(),
                source.workflowType(),
                status,
                source.taskQueue(),
                source.assignedBuildId(),
                source.mostRecentBuildId(),
                source.versioned(),
                source.pendingState(),
                source.pendingTaskKind(),
                source.pendingTaskQueue(),
                source.pendingAttempt());
    }

    private static ExecutionAuthority withTaskQueue(
            ExecutionAuthority source, String taskQueue) {
        return new ExecutionAuthority(
                source.workflowId(),
                source.runId(),
                source.workflowType(),
                source.status(),
                taskQueue,
                source.assignedBuildId(),
                source.mostRecentBuildId(),
                source.versioned(),
                source.pendingState(),
                source.pendingTaskKind(),
                taskQueue,
                source.pendingAttempt());
    }

    private static ExecutionAuthority withRunId(ExecutionAuthority source, String runId) {
        return new ExecutionAuthority(
                source.workflowId(),
                runId,
                source.workflowType(),
                source.status(),
                source.taskQueue(),
                source.assignedBuildId(),
                source.mostRecentBuildId(),
                source.versioned(),
                source.pendingState(),
                source.pendingTaskKind(),
                source.pendingTaskQueue(),
                source.pendingAttempt());
    }

    private static ExecutionAuthority withAssignedBuild(
            ExecutionAuthority source, String assignedBuildId) {
        return new ExecutionAuthority(
                source.workflowId(),
                source.runId(),
                source.workflowType(),
                source.status(),
                source.taskQueue(),
                assignedBuildId,
                source.mostRecentBuildId(),
                source.versioned(),
                source.pendingState(),
                source.pendingTaskKind(),
                source.pendingTaskQueue(),
                source.pendingAttempt());
    }

    private static ExecutionAuthority withPendingState(ExecutionAuthority source) {
        return new ExecutionAuthority(
                source.workflowId(),
                source.runId(),
                source.workflowType(),
                source.status(),
                source.taskQueue(),
                source.assignedBuildId(),
                source.mostRecentBuildId(),
                source.versioned(),
                LegacyCaseProcessWorkflowRecoveryMain.PendingTaskState.OTHER,
                source.pendingTaskKind(),
                source.pendingTaskQueue(),
                source.pendingAttempt());
    }

    private static ExecutionAuthority withPendingKind(ExecutionAuthority source) {
        return new ExecutionAuthority(
                source.workflowId(),
                source.runId(),
                source.workflowType(),
                source.status(),
                source.taskQueue(),
                source.assignedBuildId(),
                source.mostRecentBuildId(),
                source.versioned(),
                source.pendingState(),
                LegacyCaseProcessWorkflowRecoveryMain.PendingTaskKind.OTHER,
                source.pendingTaskQueue(),
                source.pendingAttempt());
    }

    private static void assertFailure(
            RecoveryRequest request, FakeAuthority authority, byte[] currentWorkflowBytes) {
        assertThatThrownBy(
                        () ->
                                LegacyCaseProcessWorkflowRecoveryMain.prepare(
                                        request,
                                        authority,
                                        () -> currentWorkflowBytes.clone()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static final class FakeAuthority implements TemporalAuthority {
        private final ExecutionAuthority execution;
        private final List<OpenExecutionAuthority> openExecutions;
        private int describeCalls;
        private int listCalls;

        private FakeAuthority(
                ExecutionAuthority execution, List<OpenExecutionAuthority> openExecutions) {
            this.execution = execution;
            this.openExecutions = List.copyOf(openExecutions);
        }

        @Override
        public ExecutionAuthority describe(RecoveryRequest request) {
            describeCalls++;
            return execution;
        }

        @Override
        public List<OpenExecutionAuthority> openExecutionsBoundTo(
                String namespace, String legacyBuildId) {
            listCalls++;
            return openExecutions;
        }
    }

    private static final class RecordingRuntime implements WorkflowOnlyRuntime {
        private final List<Class<?>> workflowTypes = new ArrayList<>();
        private final List<Duration> awaited = new ArrayList<>();
        private int activityRegistrationCount;
        private int startCount;

        @Override
        public void registerWorkflowImplementationTypes(
                Class<?>... workflowImplementationTypes) {
            workflowTypes.addAll(List.of(workflowImplementationTypes));
        }

        @Override
        public void registerActivitiesImplementations(Object... activityImplementations) {
            activityRegistrationCount += activityImplementations.length;
        }

        @Override
        public void start() {
            startCount++;
        }

        @Override
        public void await(Duration duration) {
            awaited.add(duration);
        }

        @Override
        public void close() {}
    }
}
