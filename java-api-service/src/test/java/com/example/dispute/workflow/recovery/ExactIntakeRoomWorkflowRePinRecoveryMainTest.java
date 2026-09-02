package com.example.dispute.workflow.recovery;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.ROOM_CONTROL;
import static com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.INTAKE_ROOM_WORKFLOW_CLASS;
import static com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.INTAKE_WORKFLOW_TYPE;
import static com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.UPDATE_IDENTITY;
import static com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.UPDATE_MASK_PATH;
import static com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.WORKTREE_MARKER;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_STARTED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_UNSPECIFIED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED;
import static io.temporal.api.enums.v1.TaskQueueKind.TASK_QUEUE_KIND_NORMAL;
import static io.temporal.api.enums.v1.TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY;
import static io.temporal.api.enums.v1.TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW;
import static io.temporal.api.enums.v1.VersioningBehavior.VERSIONING_BEHAVIOR_AUTO_UPGRADE;
import static io.temporal.api.enums.v1.VersioningBehavior.VERSIONING_BEHAVIOR_PINNED;
import static io.temporal.api.enums.v1.WorkerDeploymentVersionStatus.WORKER_DEPLOYMENT_VERSION_STATUS_CURRENT;
import static io.temporal.api.enums.v1.WorkerDeploymentVersionStatus.WORKER_DEPLOYMENT_VERSION_STATUS_INACTIVE;
import static io.temporal.api.workflow.v1.VersioningOverride.PinnedOverrideBehavior.PINNED_OVERRIDE_BEHAVIOR_PINNED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.DeploymentAuthority;
import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.Disposition;
import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.ExecutionAuthority;
import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.Mode;
import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.OldVersionAuthority;
import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.OperationResult;
import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.PendingWorkflowTaskState;
import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.PinnedVersion;
import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.QueueMembership;
import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.RePinCommand;
import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.RePinOutcome;
import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.RecoveryPlan;
import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.RecoveryRequest;
import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.TemporalAuthority;
import com.example.dispute.workflow.recovery.ExactIntakeRoomWorkflowRePinRecoveryMain.VersionAuthority;
import com.google.protobuf.Duration;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkerVersionStamp;
import io.temporal.api.deployment.v1.WorkerDeploymentVersion;
import io.temporal.api.history.v1.ActivityTaskScheduledEventAttributes;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.TimerStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionOptionsUpdatedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskCompletedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskScheduledEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskStartedEventAttributes;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflow.v1.VersioningOverride;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionOptionsRequest;
import io.temporal.common.converter.DefaultDataConverter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExactIntakeRoomWorkflowRePinRecoveryMainTest {

    private static final String OLD_BINDING = "4".repeat(64);
    private static final String NEW_BINDING = "9".repeat(64);
    private static final String CURRENT_BINDING = "c".repeat(64);
    private static final String FOREIGN_BINDING = "f".repeat(64);
    private static final String OLD_BUILD_ID =
            "local-final-control.local-" + OLD_BINDING + "-control";
    private static final String DEPLOYMENT = "aflow-case-process-recovery-p9";
    private static final String NEW_BUILD_ID = "local-" + NEW_BINDING + "-control";
    private static final String CURRENT_BUILD_ID = "local-" + CURRENT_BINDING + "-control";
    private static final String FOREIGN_BUILD_ID = "local-" + FOREIGN_BINDING + "-control";
    private static final String NEW_PINNED_VERSION = canonical(DEPLOYMENT, NEW_BUILD_ID);
    private static final String NEW_PINNED_SEARCH_BUILD_ID =
            "pinned:" + DEPLOYMENT + ":" + NEW_BUILD_ID;
    private static final String CURRENT_PINNED_VERSION = canonical(DEPLOYMENT, CURRENT_BUILD_ID);
    private static final String FOREIGN_PINNED_VERSION = canonical(DEPLOYMENT, FOREIGN_BUILD_ID);
    private static final String WORKFLOW_ID = "room-workflow:CASE_TEST:INTAKE:0";
    private static final String RUN_ID = "cf43b73e-dad8-4346-af27-c78c8a9fa0f3";
    private static final long TIMER_EVENT_ID = 8;
    private static final String TIMER_ID = "184b8592-0c72-34bf-9945-78a33c12cfcc";
    private static final long TIMER_SECONDS = 86_400;
    private static final long HISTORY_TAIL = 8;
    private static final byte[] WORKFLOW_BYTES =
            "exact-intake-room-repin-workflow-bytecode".getBytes(StandardCharsets.UTF_8);
    private static final Set<QueueMembership> EXACT_MEMBERSHIP =
            Set.of(
                    new QueueMembership(CASE_CONTROL, TASK_QUEUE_TYPE_WORKFLOW),
                    new QueueMembership(CASE_CONTROL, TASK_QUEUE_TYPE_ACTIVITY),
                    new QueueMembership(ROOM_CONTROL, TASK_QUEUE_TYPE_WORKFLOW));

    @TempDir Path tempDir;

    @Test
    void prepareBindsLegacyStampInactiveMembershipTimerAndPerformsNoMutation() throws Exception {
        Fixture fixture = fixture("prepare");
        RecordingExecutor executor = new RecordingExecutor(fixture.authority());

        OperationResult result =
                ExactIntakeRoomWorkflowRePinRecoveryMain.operate(
                        fixture.request(),
                        fixture.authority(),
                        () -> WORKFLOW_BYTES.clone(),
                        executor);
        RecoveryPlan plan =
                ExactIntakeRoomWorkflowRePinRecoveryMain.prepare(
                        fixture.request(), fixture.authority(), () -> WORKFLOW_BYTES.clone());

        assertThat(result.disposition()).isEqualTo(Disposition.PREPARED);
        assertThat(plan.authoritySha256()).matches("[0-9a-f]{64}");
        assertThat(plan.execution().oldVersionAuthority())
                .isEqualTo(OldVersionAuthority.LEGACY_BUILD_ID_STAMP);
        assertThat(plan.execution().mostRecentBuildId()).isEmpty();
        assertThat(plan.execution().searchBuildIds())
                .containsExactly("versioned:" + OLD_BUILD_ID);
        assertThat(plan.history().legacyWorkerStamp().completedEventId()).isEqualTo(7);
        assertThat(plan.history().legacyWorkerStamp().buildId()).isEqualTo(OLD_BUILD_ID);
        assertThat(plan.execution().explicitOverride()).isNull();
        assertThat(plan.history().pendingWorkflowTask().state())
                .isEqualTo(PendingWorkflowTaskState.ABSENT);
        assertThat(plan.history().pendingTimer().startedEventId()).isEqualTo(TIMER_EVENT_ID);
        assertThat(plan.history().pendingTimer().timerId()).isEqualTo(TIMER_ID);
        assertThat(plan.history().pendingTimer().timeoutSeconds()).isEqualTo(TIMER_SECONDS);
        assertThat(plan.newVersionAuthority().status())
                .isEqualTo(WORKER_DEPLOYMENT_VERSION_STATUS_INACTIVE);
        assertThat(plan.newVersionAuthority().membership()).isEqualTo(EXACT_MEMBERSHIP);
        assertThat(executor.commands).isEmpty();

        fixture.authority().execution =
                withSearchBuildIds(
                        execution(
                                OLD_BUILD_ID,
                                true,
                                "",
                                null,
                                null,
                                false,
                                false,
                                0,
                                0),
                        Set.of());
        assertThat(
                        ExactIntakeRoomWorkflowRePinRecoveryMain.prepare(
                                fixture.request(),
                                fixture.authority(),
                                () -> WORKFLOW_BYTES.clone()))
                .isNotNull();

        fixture.authority().execution =
                withSearchBuildIds(
                        execution(
                                "",
                                false,
                                OLD_BUILD_ID,
                                null,
                                null,
                                false,
                                false,
                                0,
                                0),
                        Set.of("assigned:" + OLD_BUILD_ID));
        assertThat(
                        ExactIntakeRoomWorkflowRePinRecoveryMain.prepare(
                                fixture.request(),
                                fixture.authority(),
                                () -> WORKFLOW_BYTES.clone()))
                .isNotNull();
    }

    @Test
    void applyRepinsExactExecutionOnceAndReplayAcceptsOnlySoleEventNine() throws Exception {
        Fixture fixture = fixture("apply");
        RecoveryPlan prepared =
                ExactIntakeRoomWorkflowRePinRecoveryMain.prepare(
                        fixture.request(), fixture.authority(), () -> WORKFLOW_BYTES.clone());
        RecoveryRequest apply = fixture.request().forApply(prepared.authoritySha256());
        RecordingExecutor executor = new RecordingExecutor(fixture.authority());

        OperationResult first =
                ExactIntakeRoomWorkflowRePinRecoveryMain.operate(
                        apply, fixture.authority(), () -> WORKFLOW_BYTES.clone(), executor);
        OperationResult replay =
                ExactIntakeRoomWorkflowRePinRecoveryMain.operate(
                        apply, fixture.authority(), () -> WORKFLOW_BYTES.clone(), executor);

        assertThat(first.disposition()).isEqualTo(Disposition.REPINNED);
        assertThat(replay.disposition()).isEqualTo(Disposition.ALREADY_REPINNED);
        assertThat(first.authoritySha256()).isEqualTo(prepared.authoritySha256());
        assertThat(replay.authoritySha256()).isEqualTo(prepared.authoritySha256());
        assertThat(executor.commands).hasSize(1);
        assertThat(fixture.authority().history).hasSize(9);
        assertThat(fixture.authority().history.getLast().getEventType())
                .isEqualTo(EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED);
        assertThat(fixture.authority().execution.searchBuildIds())
                .containsExactlyInAnyOrder(
                        "versioned:" + OLD_BUILD_ID, NEW_PINNED_SEARCH_BUILD_ID);

        ExecutionAuthority exactPostUpdateExecution = fixture.authority().execution;
        fixture.authority().execution =
                withSearchBuildIds(
                        exactPostUpdateExecution,
                        Set.of(
                                "versioned:" + OLD_BUILD_ID,
                                NEW_PINNED_SEARCH_BUILD_ID,
                                "pinned:" + DEPLOYMENT + ":" + FOREIGN_BUILD_ID));
        assertThatThrownBy(
                        () ->
                                ExactIntakeRoomWorkflowRePinRecoveryMain.operate(
                                        apply,
                                        fixture.authority(),
                                        () -> WORKFLOW_BYTES.clone(),
                                        executor))
                .isInstanceOf(IllegalStateException.class);
        assertThat(executor.commands).hasSize(1);
        fixture.authority().execution = exactPostUpdateExecution;

        fixture.authority().history =
                append(fixture.authority().history, workflowTaskScheduled(10, ROOM_CONTROL, 1));
        assertThatThrownBy(
                        () ->
                                ExactIntakeRoomWorkflowRePinRecoveryMain.operate(
                                        apply,
                                        fixture.authority(),
                                        () -> WORKFLOW_BYTES.clone(),
                                        executor))
                .isInstanceOf(IllegalStateException.class);
        assertThat(executor.commands).hasSize(1);
    }

    @Test
    void updateRequestIsExactRunTopLevelPinnedAndChangesOnlyVersioningOverride() {
        RePinCommand command =
                new RePinCommand(
                        "default",
                        WORKFLOW_ID,
                        RUN_ID,
                        newVersion(),
                        UPDATE_MASK_PATH,
                        UPDATE_IDENTITY);

        UpdateWorkflowExecutionOptionsRequest request =
                ExactIntakeRoomWorkflowRePinRecoveryMain.updateRequest(command);

        assertThat(request.getNamespace()).isEqualTo("default");
        assertThat(request.getWorkflowExecution().getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(request.getWorkflowExecution().getRunId()).isEqualTo(RUN_ID);
        assertThat(request.getUpdateMask().getPathsList()).containsExactly(UPDATE_MASK_PATH);
        assertThat(request.getIdentity()).isEqualTo(UPDATE_IDENTITY);
        VersioningOverride override =
                request.getWorkflowExecutionOptions().getVersioningOverride();
        assertThat(override.getBehavior()).isEqualTo(VERSIONING_BEHAVIOR_PINNED);
        assertThat(override.getPinnedVersion()).isEqualTo(NEW_PINNED_VERSION);
        assertThat(override.hasPinned()).isFalse();
    }

    @Test
    void legacyBuildStampAuthorityRejectsMissingForeignOverrideAndRepresentationConflicts()
            throws Exception {
        Fixture fixture = fixture("legacy-negative");
        List<ExecutionAuthority> invalid =
                List.of(
                        withSearchBuildIds(
                                execution("", false, "", null, null, false, false, 0, 0),
                                Set.of()),
                        execution(FOREIGN_BUILD_ID, true, "", null, null, false, false, 0, 0),
                        withSearchBuildIds(
                                execution("", false, "", null, null, false, false, 0, 0),
                                Set.of("versioned:" + FOREIGN_BUILD_ID)),
                        execution(OLD_BUILD_ID, true, FOREIGN_BUILD_ID, null, null, false, false, 0, 0),
                        execution(
                                OLD_BUILD_ID,
                                true,
                                "",
                                foreignVersion(),
                                null,
                                false,
                                false,
                                0,
                                0),
                        execution(
                                OLD_BUILD_ID,
                                true,
                                "",
                                null,
                                newVersion(),
                                false,
                                false,
                                0,
                                0),
                        execution(
                                OLD_BUILD_ID,
                                true,
                                "",
                                null,
                                null,
                                true,
                                false,
                                0,
                                0),
                        execution(
                                OLD_BUILD_ID,
                                true,
                                "",
                                null,
                                null,
                                false,
                                true,
                                0,
                                0));

        for (ExecutionAuthority candidate : invalid) {
            fixture.authority().reset();
            fixture.authority().execution = candidate;
            assertPrepareRejected(fixture);
        }
    }

    @Test
    void identityQueueStateAndZeroCoTenantWorkAreFailClosedBeforeRpc() throws Exception {
        Fixture fixture = fixture("execution-negative");
        List<ExecutionAuthority> invalid =
                List.of(
                        withIdentity(oldExecution(), WORKFLOW_ID + "-other", RUN_ID, INTAKE_WORKFLOW_TYPE, true, ROOM_CONTROL),
                        withIdentity(oldExecution(), WORKFLOW_ID, "22222222-2222-4222-8222-222222222222", INTAKE_WORKFLOW_TYPE, true, ROOM_CONTROL),
                        withIdentity(oldExecution(), WORKFLOW_ID, RUN_ID, "OutcomeRoomWorkflow", true, ROOM_CONTROL),
                        withIdentity(oldExecution(), WORKFLOW_ID, RUN_ID, INTAKE_WORKFLOW_TYPE, false, ROOM_CONTROL),
                        withIdentity(oldExecution(), WORKFLOW_ID, RUN_ID, INTAKE_WORKFLOW_TYPE, true, CASE_CONTROL),
                        execution(OLD_BUILD_ID, true, "", null, null, false, false, 1, 0),
                        execution(OLD_BUILD_ID, true, "", null, null, false, false, 0, 1));

        RecordingExecutor executor = new RecordingExecutor(fixture.authority());
        for (ExecutionAuthority candidate : invalid) {
            fixture.authority().reset();
            fixture.authority().execution = candidate;
            assertThatThrownBy(
                            () ->
                                    ExactIntakeRoomWorkflowRePinRecoveryMain.operate(
                                            fixture.request(),
                                            fixture.authority(),
                                            () -> WORKFLOW_BYTES.clone(),
                                            executor))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(executor.commands).isEmpty();
    }

    @Test
    void deploymentRoutingStatusAndCompleteMembershipAreExact() throws Exception {
        Fixture fixture = fixture("deployment-negative");
        List<Runnable> mutations =
                List.of(
                        () ->
                                fixture.authority().deployment =
                                        deployment(NEW_PINNED_VERSION, "", false),
                        () ->
                                fixture.authority().deployment =
                                        deployment(CURRENT_PINNED_VERSION, NEW_PINNED_VERSION, false),
                        () ->
                                fixture.authority().deployment =
                                        deployment(CURRENT_PINNED_VERSION, "", true),
                        () ->
                                fixture.authority().version =
                                        new VersionAuthority(
                                                newVersion(),
                                                WORKER_DEPLOYMENT_VERSION_STATUS_CURRENT,
                                                EXACT_MEMBERSHIP),
                        () ->
                                fixture.authority().version =
                                        new VersionAuthority(
                                                newVersion(),
                                                WORKER_DEPLOYMENT_VERSION_STATUS_INACTIVE,
                                                Set.of(
                                                        new QueueMembership(
                                                                ROOM_CONTROL,
                                                                TASK_QUEUE_TYPE_WORKFLOW),
                                                        new QueueMembership(
                                                                CASE_CONTROL,
                                                                TASK_QUEUE_TYPE_ACTIVITY))),
                        () ->
                                fixture.authority().version =
                                        new VersionAuthority(
                                                newVersion(),
                                                WORKER_DEPLOYMENT_VERSION_STATUS_INACTIVE,
                                                Set.of(
                                                        new QueueMembership(
                                                                ROOM_CONTROL,
                                                                TASK_QUEUE_TYPE_WORKFLOW),
                                                        new QueueMembership(
                                                                ROOM_CONTROL,
                                                                TASK_QUEUE_TYPE_ACTIVITY),
                                                        new QueueMembership(
                                                                CASE_CONTROL,
                                                                TASK_QUEUE_TYPE_WORKFLOW),
                                                        new QueueMembership(
                                                                CASE_CONTROL,
                                                                TASK_QUEUE_TYPE_ACTIVITY))));

        for (Runnable mutation : mutations) {
            fixture.authority().reset();
            mutation.run();
            assertPrepareRejected(fixture);
        }
    }

    @Test
    void historyRequiresContiguousPrefixSoleUuidTimerAndNoPendingWork() throws Exception {
        Fixture fixture = fixture("history-negative");
        List<List<HistoryEvent>> invalid =
                List.of(
                        replace(validHistory(), 8, timerStarted(8, TIMER_ID, TIMER_SECONDS - 1)),
                        replace(validHistory(), 8, timerStarted(8, "not-a-live-timer", TIMER_SECONDS)),
                        replace(
                                validHistory(),
                                7,
                                workflowTaskCompleted(
                                        7, 5, 6, FOREIGN_BUILD_ID, true, false)),
                        replace(
                                validHistory(),
                                7,
                                workflowTaskCompleted(7, 5, 6, "", false, false)),
                        replace(
                                validHistory(),
                                7,
                                workflowTaskCompleted(
                                        7, 5, 6, OLD_BUILD_ID, true, true)),
                        replace(validHistory(), 7, workflowTaskScheduled(7, ROOM_CONTROL, 1)),
                        replace(validHistory(), 8, activityScheduled(8, CASE_CONTROL)),
                        replace(validHistory(), 4, workflowTaskCompleted(9, 2, 3)),
                        append(validHistory(), workflowTaskScheduled(9, ROOM_CONTROL, 1)));

        for (List<HistoryEvent> history : invalid) {
            fixture.authority().reset();
            fixture.authority().history = history;
            assertPrepareRejected(fixture);
        }
    }

    @Test
    void serverPersistedOverrideShapesAcceptExactCanonicalOnly() {
        VersioningOverride top = topLevelOverride(newVersion());
        VersioningOverride nested = nestedOverride(newVersion());
        VersioningOverride dual =
                top.toBuilder().setPinned(nested.getPinned()).build();

        assertThat(
                        ExactIntakeRoomWorkflowRePinRecoveryMain
                                .parseServerPersistedPinnedOverride(top, "top"))
                .isEqualTo(newVersion());
        assertThat(
                        ExactIntakeRoomWorkflowRePinRecoveryMain
                                .parseServerPersistedPinnedOverride(nested, "nested"))
                .isEqualTo(newVersion());
        assertThat(
                        ExactIntakeRoomWorkflowRePinRecoveryMain
                                .parseServerPersistedPinnedOverride(dual, "dual"))
                .isEqualTo(newVersion());

        List<VersioningOverride> invalid =
                List.of(
                        VersioningOverride.getDefaultInstance(),
                        top.toBuilder().setBehavior(VERSIONING_BEHAVIOR_AUTO_UPGRADE).build(),
                        top.toBuilder().setPinned(nestedOverride(foreignVersion()).getPinned()).build(),
                        nested.toBuilder()
                                .setPinned(
                                        nested.getPinned().toBuilder()
                                                .setBehavior(
                                                        VersioningOverride.PinnedOverrideBehavior
                                                                .PINNED_OVERRIDE_BEHAVIOR_UNSPECIFIED))
                                .build());
        for (VersioningOverride candidate : invalid) {
            assertThatThrownBy(
                            () ->
                                    ExactIntakeRoomWorkflowRePinRecoveryMain
                                            .parseServerPersistedPinnedOverride(
                                                    candidate, "invalid"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void searchBuildIdsDecodeExactLegacyAuthorityAndRejectMalformedRepresentations() {
        WorkflowExecutionInfo exact =
                workflowInfoWithBuildIds(List.of("versioned:" + OLD_BUILD_ID));
        assertThat(ExactIntakeRoomWorkflowRePinRecoveryMain.searchBuildIds(exact))
                .containsExactly("versioned:" + OLD_BUILD_ID);
        assertThat(
                        ExactIntakeRoomWorkflowRePinRecoveryMain.searchBuildIds(
                                WorkflowExecutionInfo.getDefaultInstance()))
                .isEmpty();

        assertThatThrownBy(
                        () ->
                                ExactIntakeRoomWorkflowRePinRecoveryMain.searchBuildIds(
                                        workflowInfoWithBuildIds(
                                                List.of(
                                                        "versioned:" + OLD_BUILD_ID,
                                                        "versioned:" + OLD_BUILD_ID))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                ExactIntakeRoomWorkflowRePinRecoveryMain.searchBuildIds(
                                        workflowInfoWithBuildIds(List.of(1))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replaySuffixAndPrepareToApplyAuthorityDriftRejectBeforeRpc() throws Exception {
        Fixture fixture = fixture("apply-negative");
        RecoveryPlan prepared =
                ExactIntakeRoomWorkflowRePinRecoveryMain.prepare(
                        fixture.request(), fixture.authority(), () -> WORKFLOW_BYTES.clone());
        RecordingExecutor executor = new RecordingExecutor(fixture.authority());

        assertThatThrownBy(
                        () ->
                                ExactIntakeRoomWorkflowRePinRecoveryMain.operate(
                                        fixture.request().forApply("d".repeat(64)),
                                        fixture.authority(),
                                        () -> WORKFLOW_BYTES.clone(),
                                        executor))
                .isInstanceOf(IllegalStateException.class);

        fixture.authority().driftOnDescribeCall = fixture.authority().describeCalls + 2;
        assertThatThrownBy(
                        () ->
                                ExactIntakeRoomWorkflowRePinRecoveryMain.operate(
                                        fixture.request().forApply(prepared.authoritySha256()),
                                        fixture.authority(),
                                        () -> WORKFLOW_BYTES.clone(),
                                        executor))
                .isInstanceOf(IllegalStateException.class);
        assertThat(executor.commands).isEmpty();

        fixture.authority().reset();
        fixture.authority().execution = newExecution();
        List<HistoryEvent> exact = append(validHistory(), optionsUpdatedEvent(newVersion()));
        List<List<HistoryEvent>> invalid =
                List.of(
                        replace(
                                exact,
                                9,
                                optionsUpdatedEvent(newVersion())
                                        .toBuilder()
                                        .setEventType(EVENT_TYPE_UNSPECIFIED)
                                        .build()),
                        replace(
                                exact,
                                9,
                                optionsUpdatedEvent(newVersion())
                                        .toBuilder()
                                        .setWorkerMayIgnore(false)
                                        .build()),
                        replace(exact, 9, optionsUpdatedEvent(foreignVersion())),
                        append(exact, workflowTaskScheduled(10, ROOM_CONTROL, 1)));
        for (List<HistoryEvent> history : invalid) {
            fixture.authority().history = history;
            assertThatThrownBy(
                            () ->
                                    ExactIntakeRoomWorkflowRePinRecoveryMain.operate(
                                            fixture.request()
                                                    .forApply(prepared.authoritySha256()),
                                            fixture.authority(),
                                            () -> WORKFLOW_BYTES.clone(),
                                            executor))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(executor.commands).isEmpty();
    }

    @Test
    void artifactMarkersAndThreeWayIntakeWorkflowBytesAreFailClosed() throws Exception {
        Fixture wrongMarker = fixture("wrong-marker");
        Files.writeString(
                wrongMarker.request().newClasses().resolve(WORKTREE_MARKER),
                "e".repeat(64),
                StandardCharsets.US_ASCII);
        assertPrepareRejected(wrongMarker);

        Fixture wrongClass = fixture("wrong-class");
        Files.write(
                wrongClass.request().newClasses().resolve(INTAKE_ROOM_WORKFLOW_CLASS),
                "different-intake-workflow".getBytes(StandardCharsets.UTF_8));
        assertPrepareRejected(wrongClass);
    }

    private Fixture fixture(String name) throws Exception {
        Path oldClasses = classes(name + "-old", OLD_BINDING, WORKFLOW_BYTES);
        Path newClasses = classes(name + "-new", NEW_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request =
                new RecoveryRequest(
                        "127.0.0.1:7233",
                        "default",
                        WORKFLOW_ID,
                        RUN_ID,
                        OldVersionAuthority.LEGACY_BUILD_ID_STAMP,
                        OLD_BUILD_ID,
                        oldClasses,
                        DEPLOYMENT,
                        NEW_BUILD_ID,
                        NEW_PINNED_VERSION,
                        newClasses,
                        PendingWorkflowTaskState.ABSENT,
                        0,
                        0,
                        TIMER_EVENT_ID,
                        TIMER_ID,
                        TIMER_SECONDS,
                        HISTORY_TAIL,
                        100,
                        Mode.PREPARE,
                        null);
        return new Fixture(request, new FakeAuthority());
    }

    private Path classes(String name, String binding, byte[] workflowBytes) throws Exception {
        Path root = tempDir.resolve(name);
        Files.createDirectories(root.resolve(WORKTREE_MARKER).getParent());
        Files.createDirectories(root.resolve(INTAKE_ROOM_WORKFLOW_CLASS).getParent());
        Files.writeString(root.resolve(WORKTREE_MARKER), binding, StandardCharsets.US_ASCII);
        Files.write(root.resolve(INTAKE_ROOM_WORKFLOW_CLASS), workflowBytes);
        return root;
    }

    private static void assertPrepareRejected(Fixture fixture) {
        assertThatThrownBy(
                        () ->
                                ExactIntakeRoomWorkflowRePinRecoveryMain.prepare(
                                        fixture.request(),
                                        fixture.authority(),
                                        () -> WORKFLOW_BYTES.clone()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String canonical(String deployment, String buildId) {
        return new io.temporal.common.WorkerDeploymentVersion(deployment, buildId)
                .toCanonicalString();
    }

    private static PinnedVersion newVersion() {
        return new PinnedVersion(DEPLOYMENT, NEW_BUILD_ID, NEW_PINNED_VERSION);
    }

    private static PinnedVersion currentVersion() {
        return new PinnedVersion(DEPLOYMENT, CURRENT_BUILD_ID, CURRENT_PINNED_VERSION);
    }

    private static PinnedVersion foreignVersion() {
        return new PinnedVersion(DEPLOYMENT, FOREIGN_BUILD_ID, FOREIGN_PINNED_VERSION);
    }

    private static DeploymentAuthority deployment(
            String current, String ramping, boolean transition) {
        return new DeploymentAuthority(
                DEPLOYMENT,
                current,
                ramping,
                0,
                11,
                transition,
                List.of(CURRENT_PINNED_VERSION, NEW_PINNED_VERSION));
    }

    private static VersionAuthority version() {
        return new VersionAuthority(
                newVersion(), WORKER_DEPLOYMENT_VERSION_STATUS_INACTIVE, EXACT_MEMBERSHIP);
    }

    private static ExecutionAuthority oldExecution() {
        return execution("", false, "", null, null, false, false, 0, 0);
    }

    private static ExecutionAuthority newExecution() {
        return withOverride(oldExecution(), newVersion());
    }

    private static ExecutionAuthority execution(
            String mostRecentBuildId,
            boolean versioned,
            String assignedBuildId,
            PinnedVersion explicitOverride,
            PinnedVersion effectiveAssignment,
            boolean deploymentTransition,
            boolean versionTransition,
            int pendingActivities,
            int pendingChildren) {
        return new ExecutionAuthority(
                WORKFLOW_ID,
                RUN_ID,
                INTAKE_WORKFLOW_TYPE,
                true,
                ROOM_CONTROL,
                PendingWorkflowTaskState.ABSENT,
                0,
                pendingActivities,
                pendingChildren,
                assignedBuildId,
                mostRecentBuildId,
                versioned,
                Set.of("versioned:" + OLD_BUILD_ID),
                OldVersionAuthority.LEGACY_BUILD_ID_STAMP,
                explicitOverride,
                effectiveAssignment,
                deploymentTransition,
                versionTransition);
    }

    private static ExecutionAuthority withIdentity(
            ExecutionAuthority source,
            String workflowId,
            String runId,
            String workflowType,
            boolean running,
            String taskQueue) {
        return new ExecutionAuthority(
                workflowId,
                runId,
                workflowType,
                running,
                taskQueue,
                source.pendingWorkflowTaskState(),
                source.pendingWorkflowTaskAttempt(),
                source.pendingActivities(),
                source.pendingChildren(),
                source.assignedBuildId(),
                source.mostRecentBuildId(),
                source.versioned(),
                source.searchBuildIds(),
                source.oldVersionAuthority(),
                source.explicitOverride(),
                source.effectiveAssignment(),
                source.deploymentTransition(),
                source.versionTransition());
    }

    private static ExecutionAuthority withSearchBuildIds(
            ExecutionAuthority source, Set<String> searchBuildIds) {
        return new ExecutionAuthority(
                source.workflowId(),
                source.runId(),
                source.workflowType(),
                source.running(),
                source.taskQueue(),
                source.pendingWorkflowTaskState(),
                source.pendingWorkflowTaskAttempt(),
                source.pendingActivities(),
                source.pendingChildren(),
                source.assignedBuildId(),
                source.mostRecentBuildId(),
                source.versioned(),
                searchBuildIds,
                source.oldVersionAuthority(),
                source.explicitOverride(),
                source.effectiveAssignment(),
                source.deploymentTransition(),
                source.versionTransition());
    }

    private static ExecutionAuthority withOverride(
            ExecutionAuthority source, PinnedVersion explicitOverride) {
        return new ExecutionAuthority(
                source.workflowId(),
                source.runId(),
                source.workflowType(),
                source.running(),
                source.taskQueue(),
                source.pendingWorkflowTaskState(),
                source.pendingWorkflowTaskAttempt(),
                source.pendingActivities(),
                source.pendingChildren(),
                source.assignedBuildId(),
                source.mostRecentBuildId(),
                source.versioned(),
                source.searchBuildIds(),
                source.oldVersionAuthority(),
                explicitOverride,
                source.effectiveAssignment(),
                source.deploymentTransition(),
                source.versionTransition());
    }

    private static List<HistoryEvent> validHistory() {
        return List.of(
                workflowStarted(1),
                workflowTaskScheduled(2, ROOM_CONTROL, 1),
                workflowTaskStarted(3, 2),
                workflowTaskCompleted(4, 2, 3),
                workflowTaskScheduled(5, ROOM_CONTROL, 1),
                workflowTaskStarted(6, 5),
                workflowTaskCompleted(7, 5, 6),
                timerStarted(8, TIMER_ID, TIMER_SECONDS));
    }

    private static HistoryEvent workflowStarted(long eventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setEventType(EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
                .setWorkflowExecutionStartedEventAttributes(
                        WorkflowExecutionStartedEventAttributes.newBuilder())
                .build();
    }

    private static HistoryEvent workflowTaskScheduled(
            long eventId, String taskQueue, int attempt) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setEventType(EVENT_TYPE_WORKFLOW_TASK_SCHEDULED)
                .setWorkflowTaskScheduledEventAttributes(
                        WorkflowTaskScheduledEventAttributes.newBuilder()
                                .setTaskQueue(
                                        TaskQueue.newBuilder()
                                                .setName(taskQueue)
                                                .setKind(TASK_QUEUE_KIND_NORMAL))
                                .setAttempt(attempt))
                .build();
    }

    private static HistoryEvent workflowTaskStarted(long eventId, long scheduledId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setEventType(EVENT_TYPE_WORKFLOW_TASK_STARTED)
                .setWorkflowTaskStartedEventAttributes(
                        WorkflowTaskStartedEventAttributes.newBuilder()
                                .setScheduledEventId(scheduledId))
                .build();
    }

    private static HistoryEvent workflowTaskCompleted(
            long eventId, long scheduledId, long startedId) {
        return workflowTaskCompleted(
                eventId, scheduledId, startedId, OLD_BUILD_ID, true, false);
    }

    private static HistoryEvent workflowTaskCompleted(
            long eventId,
            long scheduledId,
            long startedId,
            String buildId,
            boolean useVersioning,
            boolean conflictingDeploymentRepresentation) {
        WorkflowTaskCompletedEventAttributes.Builder attributes =
                WorkflowTaskCompletedEventAttributes.newBuilder()
                        .setScheduledEventId(scheduledId)
                        .setStartedEventId(startedId);
        if (!buildId.isBlank() || useVersioning) {
            attributes.setWorkerVersion(
                    WorkerVersionStamp.newBuilder()
                            .setBuildId(buildId)
                            .setUseVersioning(useVersioning));
        }
        if (conflictingDeploymentRepresentation) {
            attributes.setWorkerDeploymentVersion(NEW_PINNED_VERSION);
        }
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setEventType(EVENT_TYPE_WORKFLOW_TASK_COMPLETED)
                .setWorkflowTaskCompletedEventAttributes(attributes)
                .build();
    }

    private static HistoryEvent timerStarted(long eventId, String timerId, long seconds) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setEventType(EVENT_TYPE_TIMER_STARTED)
                .setTimerStartedEventAttributes(
                        TimerStartedEventAttributes.newBuilder()
                                .setTimerId(timerId)
                                .setStartToFireTimeout(
                                        Duration.newBuilder().setSeconds(seconds)))
                .build();
    }

    private static HistoryEvent activityScheduled(long eventId, String taskQueue) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setEventType(EVENT_TYPE_ACTIVITY_TASK_SCHEDULED)
                .setActivityTaskScheduledEventAttributes(
                        ActivityTaskScheduledEventAttributes.newBuilder()
                                .setActivityId("activity-" + eventId)
                                .setActivityType(
                                        ActivityType.newBuilder().setName("UnexpectedActivity"))
                                .setTaskQueue(TaskQueue.newBuilder().setName(taskQueue)))
                .build();
    }

    private static HistoryEvent optionsUpdatedEvent(PinnedVersion version) {
        return HistoryEvent.newBuilder()
                .setEventId(HISTORY_TAIL + 1)
                .setEventType(EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED)
                .setWorkerMayIgnore(true)
                .setWorkflowExecutionOptionsUpdatedEventAttributes(
                        WorkflowExecutionOptionsUpdatedEventAttributes.newBuilder()
                                .setVersioningOverride(nestedOverride(version)))
                .build();
    }

    private static VersioningOverride topLevelOverride(PinnedVersion version) {
        return VersioningOverride.newBuilder()
                .setBehavior(VERSIONING_BEHAVIOR_PINNED)
                .setPinnedVersion(version.canonicalVersion())
                .build();
    }

    private static VersioningOverride nestedOverride(PinnedVersion version) {
        return VersioningOverride.newBuilder()
                .setPinned(
                        VersioningOverride.PinnedOverride.newBuilder()
                                .setBehavior(PINNED_OVERRIDE_BEHAVIOR_PINNED)
                                .setVersion(
                                        WorkerDeploymentVersion.newBuilder()
                                                .setDeploymentName(version.deploymentName())
                                                .setBuildId(version.buildId())))
                .build();
    }

    private static WorkflowExecutionInfo workflowInfoWithBuildIds(Object values) {
        return WorkflowExecutionInfo.newBuilder()
                .setSearchAttributes(
                        SearchAttributes.newBuilder()
                                .putIndexedFields(
                                        "BuildIds",
                                        DefaultDataConverter.STANDARD_INSTANCE
                                                .toPayload(values)
                                                .orElseThrow()))
                .build();
    }

    private static List<HistoryEvent> append(
            List<HistoryEvent> source, HistoryEvent event) {
        List<HistoryEvent> copy = new ArrayList<>(source);
        copy.add(event);
        return List.copyOf(copy);
    }

    private static List<HistoryEvent> replace(
            List<HistoryEvent> source, int oneBasedPosition, HistoryEvent event) {
        List<HistoryEvent> copy = new ArrayList<>(source);
        copy.set(oneBasedPosition - 1, event);
        return List.copyOf(copy);
    }

    private record Fixture(RecoveryRequest request, FakeAuthority authority) {}

    private static final class FakeAuthority implements TemporalAuthority {
        private ExecutionAuthority execution;
        private List<HistoryEvent> history;
        private DeploymentAuthority deployment;
        private VersionAuthority version;
        private int describeCalls;
        private int driftOnDescribeCall = Integer.MAX_VALUE;

        private FakeAuthority() {
            reset();
        }

        private void reset() {
            execution = oldExecution();
            history = validHistory();
            deployment = deployment(CURRENT_PINNED_VERSION, "", false);
            version = version();
            describeCalls = 0;
            driftOnDescribeCall = Integer.MAX_VALUE;
        }

        @Override
        public String serverVersion() {
            return "1.29.7";
        }

        @Override
        public ExecutionAuthority describeExecution(RecoveryRequest request) {
            describeCalls++;
            if (describeCalls == driftOnDescribeCall) {
                return execution(
                        OLD_BUILD_ID, true, "", null, null, false, false, 1, 0);
            }
            return execution;
        }

        @Override
        public List<HistoryEvent> loadCompleteHistory(RecoveryRequest request) {
            return List.copyOf(history);
        }

        @Override
        public DeploymentAuthority describeDeployment(
                String namespace, String deploymentName) {
            return deployment;
        }

        @Override
        public VersionAuthority describeVersion(String namespace, PinnedVersion version) {
            return this.version;
        }
    }

    private static final class RecordingExecutor
            implements ExactIntakeRoomWorkflowRePinRecoveryMain.RePinExecutor {
        private final FakeAuthority authority;
        private final List<RePinCommand> commands = new ArrayList<>();

        private RecordingExecutor(FakeAuthority authority) {
            this.authority = authority;
        }

        @Override
        public RePinOutcome repin(RePinCommand command) {
            commands.add(command);
            assertThat(command.namespace()).isEqualTo("default");
            assertThat(command.workflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(command.runId()).isEqualTo(RUN_ID);
            assertThat(command.targetVersion()).isEqualTo(newVersion());
            assertThat(command.updateMaskPath()).isEqualTo(UPDATE_MASK_PATH);
            assertThat(command.identity()).isEqualTo(UPDATE_IDENTITY);
            authority.execution =
                    withSearchBuildIds(
                            withOverride(authority.execution, newVersion()),
                            Set.of(
                                    "versioned:" + OLD_BUILD_ID,
                                    NEW_PINNED_SEARCH_BUILD_ID));
            authority.history = append(validHistory(), optionsUpdatedEvent(newVersion()));
            return new RePinOutcome(newVersion());
        }
    }
}
