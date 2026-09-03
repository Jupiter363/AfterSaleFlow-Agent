package com.example.dispute.workflow.recovery;

import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.DOMAIN_EVENT_SIGNAL;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowResetRecoveryMain.CASE_PROCESS_WORKFLOW_CLASS;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowResetRecoveryMain.NO_TIMER;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowResetRecoveryMain.WORKTREE_MARKER;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowResetRecoveryMain.PendingTaskState.OTHER;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowResetRecoveryMain.PendingTaskState.SCHEDULED;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowResetRecoveryMain.WorkflowStatus.RUNNING;
import static io.temporal.api.enums.v1.ResetReapplyType.RESET_REAPPLY_TYPE_SIGNAL;
import static io.temporal.api.enums.v1.TaskQueueKind.TASK_QUEUE_KIND_NORMAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowResetRecoveryMain.ExecutionAuthority;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowResetRecoveryMain.Mode;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowResetRecoveryMain.OperationResult;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowResetRecoveryMain.RecoveryPlan;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowResetRecoveryMain.RecoveryRequest;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowResetRecoveryMain.ResetCommand;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowResetRecoveryMain.ResetOutcome;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowResetRecoveryMain.TemporalAuthority;
import io.temporal.api.history.v1.ActivityTaskScheduledEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionTerminatedEventAttributes;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.StartChildWorkflowExecutionInitiatedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionSignaledEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskCompletedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskScheduledEventAttributes;
import io.temporal.api.taskqueue.v1.TaskQueue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExactCaseProcessWorkflowResetRecoveryMainTest {

    private static final String WORKTREE_BINDING = "a".repeat(64);
    private static final String LEGACY_BUILD_ID =
            "local-final-control.local-source-" + WORKTREE_BINDING + "-control";
    private static final String WORKFLOW_ID = "case-process:tenant-test:CASE_TEST";
    private static final String RUN_ID = "11111111-1111-4111-8111-111111111111";
    private static final String REQUEST_ID = "22222222-2222-4222-8222-222222222222";
    private static final String NEW_RUN_ID = "33333333-3333-4333-8333-333333333333";
    private static final byte[] WORKFLOW_BYTES =
            "exact-case-process-workflow-bytecode".getBytes(StandardCharsets.UTF_8);

    @TempDir Path tempDir;

    @Test
    void prepareBindsExactTargetAndPerformsNoResetMutation() throws Exception {
        Path retained = retainedClasses("prepared", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request = request(retained, 10, 100);
        FakeAuthority authority = new FakeAuthority(execution(), validHistory());
        RecordingResetExecutor resetExecutor = new RecordingResetExecutor();

        OperationResult result =
                ExactCaseProcessWorkflowResetRecoveryMain.operate(
                        request, authority, () -> WORKFLOW_BYTES.clone(), resetExecutor);

        assertThat(result.mode()).isEqualTo(Mode.PREPARE);
        assertThat(result.workflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(result.sourceRunId()).isEqualTo(RUN_ID);
        assertThat(result.resetWorkflowTaskFinishEventId()).isEqualTo(10);
        assertThat(result.resetBaseSha256()).matches("[0-9a-f]{64}");
        assertThat(result.historySuffixSha256()).matches("[0-9a-f]{64}");
        assertThat(result.authoritySha256()).matches("[0-9a-f]{64}");
        assertThat(authority.describeCalls).isEqualTo(1);
        assertThat(authority.historyCalls).isEqualTo(1);
        assertThat(resetExecutor.commands).isEmpty();
    }

    @Test
    void applyRevalidatesThenSendsOneStableIdempotentResetRequest() throws Exception {
        Path retained = retainedClasses("apply", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest prepareRequest = request(retained, 10, 100);
        FakeAuthority authority = new FakeAuthority(execution(), validHistory());
        RecoveryPlan prepared =
                ExactCaseProcessWorkflowResetRecoveryMain.prepare(
                        prepareRequest, authority, () -> WORKFLOW_BYTES.clone());
        RecoveryRequest applyRequest =
                prepareRequest.forApply(
                        prepared.historySuffixSha256(), prepared.authoritySha256());
        RecordingResetExecutor resetExecutor = new RecordingResetExecutor();

        OperationResult first =
                ExactCaseProcessWorkflowResetRecoveryMain.operate(
                        applyRequest, authority, () -> WORKFLOW_BYTES.clone(), resetExecutor);
        OperationResult replay =
                ExactCaseProcessWorkflowResetRecoveryMain.operate(
                        applyRequest, authority, () -> WORKFLOW_BYTES.clone(), resetExecutor);

        assertThat(first.mode()).isEqualTo(Mode.APPLY);
        assertThat(first.newRunId()).isEqualTo(NEW_RUN_ID);
        assertThat(replay).isEqualTo(first);
        assertThat(resetExecutor.commands).hasSize(2);
        assertThat(resetExecutor.commands.get(0)).isEqualTo(resetExecutor.commands.get(1));
        ResetCommand command = resetExecutor.commands.getFirst();
        assertThat(command.namespace()).isEqualTo("uat-namespace");
        assertThat(command.workflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(command.runId()).isEqualTo(RUN_ID);
        assertThat(command.workflowTaskFinishEventId()).isEqualTo(10);
        assertThat(command.requestId()).isEqualTo(REQUEST_ID);
        assertThat(command.resetReapplyType()).isEqualTo(RESET_REAPPLY_TYPE_SIGNAL);
        assertThat(authority.describeCalls).isEqualTo(5);
        assertThat(authority.historyCalls).isEqualTo(5);
    }

    @Test
    void prepareRejectsEveryExecutionAuthorityDriftBeforeReset() throws Exception {
        Path retained = retainedClasses("execution-drift", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request = request(retained, 10, 100);
        ExecutionAuthority exact = execution();
        List<ExecutionAuthority> invalid =
                List.of(
                        copy(exact, "other-workflow", exact.runId(), exact.workflowType(), exact.taskQueue(), "", exact.mostRecentBuildId(), true, SCHEDULED, 1, 0, 0),
                        copy(exact, exact.workflowId(), "44444444-4444-4444-8444-444444444444", exact.workflowType(), exact.taskQueue(), "", exact.mostRecentBuildId(), true, SCHEDULED, 1, 0, 0),
                        copy(exact, exact.workflowId(), exact.runId(), "OtherWorkflow", exact.taskQueue(), "", exact.mostRecentBuildId(), true, SCHEDULED, 1, 0, 0),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), "room-control", "", exact.mostRecentBuildId(), true, SCHEDULED, 1, 0, 0),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), exact.taskQueue(), "assigned-build", exact.mostRecentBuildId(), true, SCHEDULED, 1, 0, 0),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), exact.taskQueue(), "", "other-build", true, SCHEDULED, 1, 0, 0),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), exact.taskQueue(), "", exact.mostRecentBuildId(), false, SCHEDULED, 1, 0, 0),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), exact.taskQueue(), "", exact.mostRecentBuildId(), true, OTHER, 1, 0, 0),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), exact.taskQueue(), "", exact.mostRecentBuildId(), true, SCHEDULED, 0, 0, 0),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), exact.taskQueue(), "", exact.mostRecentBuildId(), true, SCHEDULED, 1, 1, 0),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), exact.taskQueue(), "", exact.mostRecentBuildId(), true, SCHEDULED, 1, 0, 1));

        for (ExecutionAuthority execution : invalid) {
            assertRejected(request, execution, validHistory(), WORKFLOW_BYTES);
        }
    }

    @Test
    void prepareRejectsHistoryDriftWrongSignalAndUnsupportedSuffixEvent() throws Exception {
        Path retained = retainedClasses("history-drift", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request = request(retained, 10, 100);

        assertRejected(
                request,
                execution(),
                replace(validHistory(), 11, signaled(11, "unexpectedSignal")),
                WORKFLOW_BYTES);
        assertRejected(
                request,
                execution(),
                replace(validHistory(), 11, activityScheduled(11)),
                WORKFLOW_BYTES);
        assertRejected(
                request,
                execution(),
                replace(validHistory(), 10, event(10)),
                WORKFLOW_BYTES);
        List<HistoryEvent> nonContiguous = new ArrayList<>(validHistory());
        nonContiguous.remove(4);
        assertRejected(request, execution(), nonContiguous, WORKFLOW_BYTES);
    }

    @Test
    void prepareRejectsRetainedMarkerAndWorkflowClassDrift() throws Exception {
        Path wrongMarker = retainedClasses("wrong-marker", "b".repeat(64), WORKFLOW_BYTES);
        assertRejected(request(wrongMarker, 10, 100), execution(), validHistory(), WORKFLOW_BYTES);

        Path wrongClass =
                retainedClasses(
                        "wrong-class",
                        WORKTREE_BINDING,
                        "different-bytecode".getBytes(StandardCharsets.UTF_8));
        assertRejected(request(wrongClass, 10, 100), execution(), validHistory(), WORKFLOW_BYTES);
    }

    @Test
    void prepareRejectsTargetHistoryWhoseResetBaseStillContainsStartedChild() throws Exception {
        Path retained = retainedClasses("pending-child", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request = request(retained, 121, 200);
        List<HistoryEvent> history = targetLikeHistory(false);
        RecordingResetExecutor resetExecutor = new RecordingResetExecutor();

        assertThatThrownBy(
                        () ->
                                ExactCaseProcessWorkflowResetRecoveryMain.operate(
                                        request,
                                        new FakeAuthority(withPendingChildren(execution(), 1), history),
                                        () -> WORKFLOW_BYTES.clone(),
                                        resetExecutor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TARGET_INELIGIBLE_PENDING_CHILD");

        assertThat(history.get(23).getEventId()).isEqualTo(24);
        assertThat(history.get(23).hasStartChildWorkflowExecutionInitiatedEventAttributes())
                .isTrue();
        assertThat(history.get(24).getEventId()).isEqualTo(25);
        assertThat(history.get(24).hasChildWorkflowExecutionStartedEventAttributes()).isTrue();
        assertThat(resetExecutor.commands).isEmpty();
    }

    @Test
    void prepareAcceptsHistoryOnlyWhenChildTerminatesBeforeResetBaseBoundary() throws Exception {
        Path retained = retainedClasses("closed-child", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request = request(retained, 121, 200);

        RecoveryPlan plan =
                ExactCaseProcessWorkflowResetRecoveryMain.prepare(
                        request,
                        new FakeAuthority(execution(), targetLikeHistory(true)),
                        () -> WORKFLOW_BYTES.clone());

        assertThat(plan.historyEventCount()).isEqualTo(123);
        assertThat(plan.historyLastEventId()).isEqualTo(123);
        assertThat(plan.historySuffixEventCount()).isEqualTo(3);
        assertThat(plan.resetBaseSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void applyRefusesAuthorityOrHistoryDriftBeforeResetMutation() throws Exception {
        Path retained = retainedClasses("apply-drift", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest prepareRequest = request(retained, 10, 100);
        FakeAuthority authority = new FakeAuthority(execution(), validHistory());
        RecoveryPlan prepared =
                ExactCaseProcessWorkflowResetRecoveryMain.prepare(
                        prepareRequest, authority, () -> WORKFLOW_BYTES.clone());
        RecoveryRequest applyRequest =
                prepareRequest.forApply(
                        prepared.historySuffixSha256(), prepared.authoritySha256());
        authority.history = replace(validHistory(), 11, signaled(11, "unexpectedSignal"));
        RecordingResetExecutor resetExecutor = new RecordingResetExecutor();

        assertThatThrownBy(
                        () ->
                                ExactCaseProcessWorkflowResetRecoveryMain.operate(
                                        applyRequest,
                                        authority,
                                        () -> WORKFLOW_BYTES.clone(),
                                        resetExecutor))
                .isInstanceOf(IllegalStateException.class);
        assertThat(resetExecutor.commands).isEmpty();
    }

    private Path retainedClasses(String name, String binding, byte[] workflowBytes)
            throws Exception {
        Path retained = tempDir.resolve(name);
        Files.createDirectories(retained.resolve(WORKTREE_MARKER).getParent());
        Files.createDirectories(retained.resolve(CASE_PROCESS_WORKFLOW_CLASS).getParent());
        Files.writeString(retained.resolve(WORKTREE_MARKER), binding, StandardCharsets.US_ASCII);
        Files.write(retained.resolve(CASE_PROCESS_WORKFLOW_CLASS), workflowBytes);
        return retained;
    }

    private static RecoveryRequest request(Path retained, long resetEventId, int historyMaxEvents) {
        return new RecoveryRequest(
                "127.0.0.1:7233",
                "uat-namespace",
                WORKFLOW_ID,
                RUN_ID,
                LEGACY_BUILD_ID,
                retained,
                resetEventId,
                DOMAIN_EVENT_SIGNAL,
                NO_TIMER,
                REQUEST_ID,
                "exact target-only recovery",
                historyMaxEvents,
                Mode.PREPARE,
                null,
                null);
    }

    private static ExecutionAuthority execution() {
        return new ExecutionAuthority(
                WORKFLOW_ID,
                RUN_ID,
                CASE_WORKFLOW_TYPE,
                RUNNING,
                CASE_CONTROL_TASK_QUEUE,
                "",
                LEGACY_BUILD_ID,
                true,
                SCHEDULED,
                1,
                0,
                0);
    }

    private static ExecutionAuthority copy(
            ExecutionAuthority source,
            String workflowId,
            String runId,
            String workflowType,
            String taskQueue,
            String assignedBuildId,
            String mostRecentBuildId,
            boolean versioned,
            ExactCaseProcessWorkflowResetRecoveryMain.PendingTaskState pendingState,
            int pendingAttempt,
            int pendingActivities,
            int pendingChildren) {
        return new ExecutionAuthority(
                workflowId,
                runId,
                workflowType,
                source.status(),
                taskQueue,
                assignedBuildId,
                mostRecentBuildId,
                versioned,
                pendingState,
                pendingAttempt,
                pendingActivities,
                pendingChildren);
    }

    private static ExecutionAuthority withPendingChildren(
            ExecutionAuthority source, int pendingChildren) {
        return copy(
                source,
                source.workflowId(),
                source.runId(),
                source.workflowType(),
                source.taskQueue(),
                source.assignedBuildId(),
                source.mostRecentBuildId(),
                source.versioned(),
                source.pendingState(),
                source.pendingAttempt(),
                source.pendingActivities(),
                pendingChildren);
    }

    private static List<HistoryEvent> validHistory() {
        List<HistoryEvent> history = fillerHistory(12);
        history.set(0, workflowStarted(1));
        history.set(9, workflowTaskCompleted(10));
        history.set(10, signaled(11, DOMAIN_EVENT_SIGNAL));
        history.set(11, normalWorkflowTaskScheduled(12, 1));
        return List.copyOf(history);
    }

    private static List<HistoryEvent> targetLikeHistory(boolean childTerminates) {
        List<HistoryEvent> history = fillerHistory(123);
        history.set(0, workflowStarted(1));
        history.set(23, childInitiated(24));
        history.set(24, childStarted(25, 24));
        if (childTerminates) {
            history.set(25, childTerminated(26, 24, 25));
        }
        history.set(120, workflowTaskCompleted(121));
        history.set(121, signaled(122, DOMAIN_EVENT_SIGNAL));
        history.set(122, normalWorkflowTaskScheduled(123, 1));
        return List.copyOf(history);
    }

    private static List<HistoryEvent> fillerHistory(int count) {
        List<HistoryEvent> history = new ArrayList<>(count);
        for (int eventId = 1; eventId <= count; eventId++) {
            history.add(event(eventId));
        }
        return history;
    }

    private static HistoryEvent event(long eventId) {
        return HistoryEvent.newBuilder().setEventId(eventId).build();
    }

    private static HistoryEvent workflowStarted(long eventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setWorkflowExecutionStartedEventAttributes(
                        WorkflowExecutionStartedEventAttributes.getDefaultInstance())
                .build();
    }

    private static HistoryEvent workflowTaskCompleted(long eventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setWorkflowTaskCompletedEventAttributes(
                        WorkflowTaskCompletedEventAttributes.getDefaultInstance())
                .build();
    }

    private static HistoryEvent signaled(long eventId, String signalName) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setWorkflowExecutionSignaledEventAttributes(
                        WorkflowExecutionSignaledEventAttributes.newBuilder()
                                .setSignalName(signalName))
                .build();
    }

    private static HistoryEvent normalWorkflowTaskScheduled(long eventId, int attempt) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setWorkflowTaskScheduledEventAttributes(
                        WorkflowTaskScheduledEventAttributes.newBuilder()
                                .setTaskQueue(
                                        TaskQueue.newBuilder()
                                                .setName(CASE_CONTROL_TASK_QUEUE)
                                                .setKind(TASK_QUEUE_KIND_NORMAL))
                                .setAttempt(attempt))
                .build();
    }

    private static HistoryEvent activityScheduled(long eventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setActivityTaskScheduledEventAttributes(
                        ActivityTaskScheduledEventAttributes.getDefaultInstance())
                .build();
    }

    private static HistoryEvent childInitiated(long eventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setStartChildWorkflowExecutionInitiatedEventAttributes(
                        StartChildWorkflowExecutionInitiatedEventAttributes.getDefaultInstance())
                .build();
    }

    private static HistoryEvent childStarted(
            long eventId, long initiatedEventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setChildWorkflowExecutionStartedEventAttributes(
                        ChildWorkflowExecutionStartedEventAttributes.newBuilder()
                                .setInitiatedEventId(initiatedEventId))
                .build();
    }

    private static HistoryEvent childTerminated(
            long eventId, long initiatedEventId, long startedEventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setChildWorkflowExecutionTerminatedEventAttributes(
                        ChildWorkflowExecutionTerminatedEventAttributes.newBuilder()
                                .setInitiatedEventId(initiatedEventId)
                                .setStartedEventId(startedEventId))
                .build();
    }

    private static List<HistoryEvent> replace(
            List<HistoryEvent> source, int eventId, HistoryEvent replacement) {
        List<HistoryEvent> copy = new ArrayList<>(source);
        copy.set(eventId - 1, replacement);
        return List.copyOf(copy);
    }

    private static void assertRejected(
            RecoveryRequest request,
            ExecutionAuthority execution,
            List<HistoryEvent> history,
            byte[] currentWorkflowBytes) {
        RecordingResetExecutor resetExecutor = new RecordingResetExecutor();
        assertThatThrownBy(
                        () ->
                                ExactCaseProcessWorkflowResetRecoveryMain.operate(
                                        request,
                                        new FakeAuthority(execution, history),
                                        () -> currentWorkflowBytes.clone(),
                                        resetExecutor))
                .isInstanceOf(IllegalStateException.class);
        assertThat(resetExecutor.commands).isEmpty();
    }

    private static final class FakeAuthority implements TemporalAuthority {
        private ExecutionAuthority execution;
        private List<HistoryEvent> history;
        private int describeCalls;
        private int historyCalls;

        private FakeAuthority(ExecutionAuthority execution, List<HistoryEvent> history) {
            this.execution = execution;
            this.history = List.copyOf(history);
        }

        @Override
        public ExecutionAuthority describe(RecoveryRequest request) {
            describeCalls++;
            return execution;
        }

        @Override
        public List<HistoryEvent> loadCompleteHistory(RecoveryRequest request) {
            historyCalls++;
            return history;
        }
    }

    private static final class RecordingResetExecutor
            implements ExactCaseProcessWorkflowResetRecoveryMain.ResetExecutor {
        private final List<ResetCommand> commands = new ArrayList<>();
        private final Map<String, String> outcomesByRequestId = new HashMap<>();

        @Override
        public ResetOutcome reset(ResetCommand command) {
            commands.add(command);
            return new ResetOutcome(
                    outcomesByRequestId.computeIfAbsent(command.requestId(), ignored -> NEW_RUN_ID));
        }
    }
}
