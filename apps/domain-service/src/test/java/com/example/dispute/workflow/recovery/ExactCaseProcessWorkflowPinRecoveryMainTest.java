package com.example.dispute.workflow.recovery;

import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.DOMAIN_EVENT_SIGNAL;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.TARGET_INTAKE_TERMINAL_NO_COMMIT_SIGNAL;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.CASE_PROCESS_WORKFLOW_CLASS;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.UPDATE_IDENTITY;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.UPDATE_MASK_PATH;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.WORKTREE_MARKER;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.PendingTaskState.ABSENT;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.PendingTaskState.OTHER;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.PendingTaskState.SCHEDULED;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.WorkflowClassAuthority.IDENTICAL;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.WorkflowClassAuthority.TARGET_INTAKE_TERMINAL_V3_ACK_RECOVERY_V1;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.WorkflowStatus.RUNNING;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_UNSPECIFIED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED;
import static io.temporal.api.enums.v1.TaskQueueKind.TASK_QUEUE_KIND_NORMAL;
import static io.temporal.api.enums.v1.TaskQueueKind.TASK_QUEUE_KIND_STICKY;
import static io.temporal.api.enums.v1.VersioningBehavior.VERSIONING_BEHAVIOR_AUTO_UPGRADE;
import static io.temporal.api.enums.v1.VersioningBehavior.VERSIONING_BEHAVIOR_PINNED;
import static io.temporal.api.workflow.v1.VersioningOverride.PinnedOverrideBehavior.PINNED_OVERRIDE_BEHAVIOR_PINNED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.CurrentOverride;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.Disposition;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.ExecutionAuthority;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.Mode;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.OperationResult;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.PendingChildAuthority;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.PinCommand;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.PinOutcome;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.PinnedVersion;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.RecoveryPlan;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.RecoveryRequest;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowPinRecoveryMain.TemporalAuthority;
import io.temporal.api.common.v1.Callback;
import io.temporal.api.common.v1.Priority;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.deployment.v1.WorkerDeploymentVersion;
import io.temporal.api.enums.v1.VersioningBehavior;
import io.temporal.api.history.v1.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionTerminatedEventAttributes;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.MarkerRecordedEventAttributes;
import io.temporal.api.history.v1.StartChildWorkflowExecutionInitiatedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionSignaledEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionOptionsUpdatedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskCompletedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskScheduledEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskStartedEventAttributes;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflow.v1.VersioningOverride;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflow.v1.WorkflowExecutionOptions;
import io.temporal.api.workflow.v1.WorkflowExecutionVersioningInfo;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionOptionsRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExactCaseProcessWorkflowPinRecoveryMainTest {

    private static final String WORKTREE_BINDING = "a".repeat(64);
    private static final String LEGACY_BUILD_ID =
            "local-final-control.local-source-" + WORKTREE_BINDING + "-control";
    private static final String WORKFLOW_ID = "case-process:tenant-test:CASE_TEST";
    private static final String RUN_ID = "11111111-1111-4111-8111-111111111111";
    private static final String CHILD_WORKFLOW_ID = "room-workflow:CASE_TEST:INTAKE:0";
    private static final String CHILD_RUN_ID = "22222222-2222-4222-8222-222222222222";
    private static final String CHILD_WORKFLOW_TYPE = "IntakeRoomWorkflow";
    private static final long CHILD_INITIATED_EVENT_ID = 2;
    private static final long PENDING_WFT_SCHEDULED_EVENT_ID = 125;
    private static final long HISTORY_TAIL_EVENT_ID = 126;
    private static final long LAST_SIGNAL_EVENT_ID = 126;
    private static final long TERMINAL_SIGNAL_EVENT_ID = 123;
    private static final int HISTORY_MAX_EVENTS = 200;
    private static final String RECOVERY_DEPLOYMENT = "local-final-control";
    private static final String RECOVERY_BUILD_ID = "recovery-build-1";
    private static final String RECOVERY_PINNED_VERSION =
            new io.temporal.common.WorkerDeploymentVersion(
                            RECOVERY_DEPLOYMENT, RECOVERY_BUILD_ID)
                    .toCanonicalString();
    private static final byte[] WORKFLOW_BYTES =
            "exact-case-process-pin-workflow-bytecode".getBytes(StandardCharsets.UTF_8);

    @TempDir Path tempDir;

    @Test
    void prepareBindsExactExecutionAndPerformsNoMutation() throws Exception {
        Path retained = retainedClasses("prepare", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request = request(retained);
        FakeAuthority authority = new FakeAuthority("1.27.4", execution(), validHistory());
        RecordingPinExecutor pinExecutor = new RecordingPinExecutor(authority, true, false);

        OperationResult result =
                ExactCaseProcessWorkflowPinRecoveryMain.operate(
                        request, authority, () -> WORKFLOW_BYTES.clone(), pinExecutor);
        RecoveryPlan plan =
                ExactCaseProcessWorkflowPinRecoveryMain.prepare(
                        request, authority, () -> WORKFLOW_BYTES.clone());

        assertThat(result.disposition()).isEqualTo(Disposition.PREPARED);
        assertThat(result.workflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(result.runId()).isEqualTo(RUN_ID);
        assertThat(result.pinnedVersion()).isEqualTo(RECOVERY_PINNED_VERSION);
        assertThat(result.authoritySha256()).matches("[0-9a-f]{64}");
        assertThat(plan.history().lastEventId()).isEqualTo(HISTORY_TAIL_EVENT_ID);
        assertThat(plan.history().lastSignalEventId()).isEqualTo(LAST_SIGNAL_EVENT_ID);
        assertThat(plan.history().pendingWorkflowTaskScheduledEventId())
                .isEqualTo(PENDING_WFT_SCHEDULED_EVENT_ID);
        assertThat(plan.history().pendingChild()).isEqualTo(pendingChild());
        assertThat(pinExecutor.commands).isEmpty();
    }

    @Test
    void applyPinsExactRunOnceAndExactReplayAddsNoSecondRpcOrHistoryEvent() throws Exception {
        Path retained = retainedClasses("apply", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest prepareRequest = request(retained);
        FakeAuthority authority = new FakeAuthority("1.27.4", execution(), validHistory());
        RecoveryPlan prepared =
                ExactCaseProcessWorkflowPinRecoveryMain.prepare(
                        prepareRequest, authority, () -> WORKFLOW_BYTES.clone());
        RecoveryRequest applyRequest = prepareRequest.forApply(prepared.authoritySha256());
        RecordingPinExecutor pinExecutor = new RecordingPinExecutor(authority, true, false);
        List<HistoryEvent> before = authority.history;

        OperationResult first =
                ExactCaseProcessWorkflowPinRecoveryMain.operate(
                        applyRequest, authority, () -> WORKFLOW_BYTES.clone(), pinExecutor);
        List<HistoryEvent> afterFirst = authority.history;
        OperationResult replay =
                ExactCaseProcessWorkflowPinRecoveryMain.operate(
                        applyRequest, authority, () -> WORKFLOW_BYTES.clone(), pinExecutor);

        assertThat(first.disposition()).isEqualTo(Disposition.PINNED);
        assertThat(replay.disposition()).isEqualTo(Disposition.ALREADY_PINNED);
        assertThat(pinExecutor.commands).hasSize(1);
        PinCommand command = pinExecutor.commands.getFirst();
        assertThat(command.namespace()).isEqualTo("uat-namespace");
        assertThat(command.workflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(command.runId()).isEqualTo(RUN_ID);
        assertThat(command.updateMaskPath()).isEqualTo(UPDATE_MASK_PATH);
        assertThat(command.targetVersion()).isEqualTo(targetVersion());
        assertThat(afterFirst).hasSize(Math.toIntExact(HISTORY_TAIL_EVENT_ID + 1));
        assertThat(afterFirst.subList(0, before.size())).containsExactlyElementsOf(before);
        assertThat(afterFirst.getLast()).isEqualTo(exactOptionsUpdatedEvent(targetVersion()));
        assertThat(authority.history).isSameAs(afterFirst);
    }

    @Test
    void legacyAbsentWorkflowTaskV3TransitionPinsExactChildAndReplays() throws Exception {
        byte[] newWorkflowBytes =
                ("case-process-after:"
                                + ExactCaseProcessWorkflowPinRecoveryMain
                                        .TARGET_INTAKE_TERMINAL_V3_ACK_RECOVERY_CHANGE_ID)
                        .getBytes(StandardCharsets.UTF_8);
        Path retained =
                retainedClasses("legacy-absent-v3-transition", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest prepareRequest = transitionRequest(retained);
        FakeAuthority authority =
                new FakeAuthority("1.29.7", absentExecution(), absentWorkflowTaskHistory());
        RecoveryPlan prepared =
                ExactCaseProcessWorkflowPinRecoveryMain.prepare(
                        prepareRequest, authority, () -> newWorkflowBytes.clone());
        RecordingPinExecutor pinExecutor = new RecordingPinExecutor(authority, true, false);

        OperationResult first =
                ExactCaseProcessWorkflowPinRecoveryMain.operate(
                        prepareRequest.forApply(prepared.authoritySha256()),
                        authority,
                        () -> newWorkflowBytes.clone(),
                        pinExecutor);
        OperationResult replay =
                ExactCaseProcessWorkflowPinRecoveryMain.operate(
                        prepareRequest.forApply(prepared.authoritySha256()),
                        authority,
                        () -> newWorkflowBytes.clone(),
                        pinExecutor);

        assertThat(prepared.history().pendingWorkflowTaskScheduledEventId()).isZero();
        assertThat(first.disposition()).isEqualTo(Disposition.PINNED);
        assertThat(replay.disposition()).isEqualTo(Disposition.ALREADY_PINNED);
        assertThat(replay.authoritySha256()).isEqualTo(prepared.authoritySha256());
        assertThat(pinExecutor.commands).hasSize(1);

        Path invalidOld =
                retainedClasses(
                        "legacy-absent-v3-marker-already-old",
                        WORKTREE_BINDING,
                        newWorkflowBytes);
        assertRejected(
                transitionRequest(invalidOld),
                new FakeAuthority("1.29.7", absentExecution(), absentWorkflowTaskHistory()),
                newWorkflowBytes,
                new RecordingPinExecutor(null, false, false));
    }

    @Test
    void pinRequestUsesOnlyTopLevelPinnedVersioningOverride() {
        PinCommand command =
                new PinCommand(
                        "uat-namespace",
                        WORKFLOW_ID,
                        RUN_ID,
                        targetVersion(),
                        UPDATE_MASK_PATH,
                        UPDATE_IDENTITY);

        UpdateWorkflowExecutionOptionsRequest request =
                ExactCaseProcessWorkflowPinRecoveryMain.updateRequest(command);

        assertThat(request.getNamespace()).isEqualTo("uat-namespace");
        assertThat(request.getWorkflowExecution().getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(request.getWorkflowExecution().getRunId()).isEqualTo(RUN_ID);
        assertThat(request.getUpdateMask().getPathsList()).containsExactly(UPDATE_MASK_PATH);
        assertThat(request.getIdentity()).isEqualTo(UPDATE_IDENTITY);
        VersioningOverride override =
                request.getWorkflowExecutionOptions().getVersioningOverride();
        assertThat(override.getBehavior()).isEqualTo(VERSIONING_BEHAVIOR_PINNED);
        assertThat(override.getPinnedVersion()).isEqualTo(RECOVERY_PINNED_VERSION);
        assertThat(override.hasPinned()).isFalse();
    }

    @Test
    void serverPersistedNestedHistoryAndDualDescribeReplayWithoutAnotherRpc()
            throws Exception {
        VersioningOverride exact =
                topLevelOverride(VERSIONING_BEHAVIOR_PINNED, RECOVERY_PINNED_VERSION);
        VersioningOverride nestedOnly = nestedOverride();
        VersioningOverride exactDual =
                exact.toBuilder().setPinned(nestedOnly.getPinned()).build();

        for (VersioningOverride persisted : List.of(exact, nestedOnly, exactDual)) {
            assertThat(
                            ExactCaseProcessWorkflowPinRecoveryMain.requirePinnedVersion(
                                    options(persisted), "response"))
                    .isEqualTo(targetVersion());
            assertThat(ExactCaseProcessWorkflowPinRecoveryMain.currentOverride(info(persisted)))
                    .isEqualTo(
                            CurrentOverride.pinned(RECOVERY_DEPLOYMENT, RECOVERY_BUILD_ID));
        }

        Path retained = retainedClasses("server-persisted-replay", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request = request(retained);
        RecoveryPlan before =
                ExactCaseProcessWorkflowPinRecoveryMain.prepare(
                        request,
                        new FakeAuthority("1.29.7", execution(), validHistory()),
                        () -> WORKFLOW_BYTES.clone());
        ExecutionAuthority pinnedExecution =
                withOverride(
                        execution(),
                        ExactCaseProcessWorkflowPinRecoveryMain.currentOverride(info(exactDual)));
        FakeAuthority persisted =
                new FakeAuthority(
                        "1.29.7",
                        pinnedExecution,
                        historyWithSuffix(
                                optionsUpdatedEvent(
                                        nestedOnly, true, false, "", false, "", false)));
        RecordingPinExecutor executor = new RecordingPinExecutor(persisted, true, false);

        OperationResult replay =
                ExactCaseProcessWorkflowPinRecoveryMain.operate(
                        request.forApply(before.authoritySha256()),
                        persisted,
                        () -> WORKFLOW_BYTES.clone(),
                        executor);

        assertThat(replay.disposition()).isEqualTo(Disposition.ALREADY_PINNED);
        assertThat(replay.authoritySha256()).isEqualTo(before.authoritySha256());
        assertThat(executor.commands).isEmpty();

        VersioningOverride wrongBehavior =
                topLevelOverride(VERSIONING_BEHAVIOR_AUTO_UPGRADE, RECOVERY_PINNED_VERSION);
        VersioningOverride blankVersion =
                topLevelOverride(VERSIONING_BEHAVIOR_PINNED, "");
        VersioningOverride invalidVersion =
                topLevelOverride(VERSIONING_BEHAVIOR_PINNED, "not-canonical");
        VersioningOverride noncanonicalVersion =
                topLevelOverride(VERSIONING_BEHAVIOR_PINNED, " " + RECOVERY_PINNED_VERSION);
        VersioningOverride conflictingDual =
                exact.toBuilder()
                        .setPinned(
                                nestedOnly
                                        .getPinned()
                                        .toBuilder()
                                        .setVersion(
                                                WorkerDeploymentVersion.newBuilder()
                                                        .setDeploymentName("other")
                                                        .setBuildId("other-build")))
                        .build();

        for (VersioningOverride invalid :
                List.of(
                        wrongBehavior,
                        blankVersion,
                        invalidVersion,
                        noncanonicalVersion,
                        conflictingDual)) {
            assertThatThrownBy(
                            () ->
                                    ExactCaseProcessWorkflowPinRecoveryMain.requirePinnedVersion(
                                            options(invalid), "response"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(
                            () ->
                                    ExactCaseProcessWorkflowPinRecoveryMain.currentOverride(
                                            info(invalid)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void applyRejectsWrongHashAndPrepareToApplyDriftBeforeRpc() throws Exception {
        Path retained = retainedClasses("apply-drift", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest prepareRequest = request(retained);
        FakeAuthority authority = new FakeAuthority("1.27.4", execution(), validHistory());
        RecoveryPlan prepared =
                ExactCaseProcessWorkflowPinRecoveryMain.prepare(
                        prepareRequest, authority, () -> WORKFLOW_BYTES.clone());
        RecordingPinExecutor pinExecutor = new RecordingPinExecutor(authority, true, false);

        assertRejected(
                prepareRequest.forApply("b".repeat(64)),
                authority,
                WORKFLOW_BYTES,
                pinExecutor);

        authority.history =
                replace(
                        validHistory(),
                        Math.toIntExact(LAST_SIGNAL_EVENT_ID),
                        signaled(LAST_SIGNAL_EVENT_ID, "wrongSignal"));
        assertRejected(
                prepareRequest.forApply(prepared.authoritySha256()),
                authority,
                WORKFLOW_BYTES,
                pinExecutor);
        assertThat(pinExecutor.commands).isEmpty();
    }

    @Test
    void prepareRejectsServerAndExistingOverrideAuthorityDrift() throws Exception {
        Path retained = retainedClasses("server-override", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request = request(retained);

        assertRejected(
                request,
                new FakeAuthority("1.27.3", execution(), validHistory()),
                WORKFLOW_BYTES,
                new RecordingPinExecutor(null, false, false));
        assertRejected(
                request,
                new FakeAuthority("not-semver", execution(), validHistory()),
                WORKFLOW_BYTES,
                new RecordingPinExecutor(null, false, false));
        assertRejected(
                request,
                new FakeAuthority(
                        "1.27.4", withOverride(execution(), CurrentOverride.other()), validHistory()),
                WORKFLOW_BYTES,
                new RecordingPinExecutor(null, false, false));
        assertRejected(
                request,
                new FakeAuthority(
                        "1.27.4",
                        withOverride(
                                execution(),
                                ExactCaseProcessWorkflowPinRecoveryMain.currentOverride(
                                        info(
                                                topLevelOverride(
                                                        VERSIONING_BEHAVIOR_PINNED,
                                                        new io.temporal.common.WorkerDeploymentVersion(
                                                                        "other", "other-build")
                                                                .toCanonicalString())))),
                        validHistory()),
                WORKFLOW_BYTES,
                new RecordingPinExecutor(null, false, false));

        FakeAuthority exact =
                new FakeAuthority(
                        "1.27.4",
                        withOverride(
                                execution(),
                                CurrentOverride.pinned(
                                        RECOVERY_DEPLOYMENT, RECOVERY_BUILD_ID)),
                        pinnedHistory());
        RecoveryPlan alreadyPinned =
                ExactCaseProcessWorkflowPinRecoveryMain.prepare(
                        request, exact, () -> WORKFLOW_BYTES.clone());
        assertThat(alreadyPinned.alreadyPinned()).isTrue();
    }

    @Test
    void alreadyPinnedReplayRequiresExactSingleOptionsUpdatedSuffix() throws Exception {
        Path retained = retainedClasses("replay-suffix", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request = request(retained);
        ExecutionAuthority pinnedExecution =
                withOverride(
                        execution(),
                        CurrentOverride.pinned(RECOVERY_DEPLOYMENT, RECOVERY_BUILD_ID));
        RecoveryPlan beforePin =
                ExactCaseProcessWorkflowPinRecoveryMain.prepare(
                        request,
                        new FakeAuthority("1.27.4", execution(), validHistory()),
                        () -> WORKFLOW_BYTES.clone());
        RecoveryPlan replay =
                ExactCaseProcessWorkflowPinRecoveryMain.prepare(
                        request,
                        new FakeAuthority("1.27.4", pinnedExecution, pinnedHistory()),
                        () -> WORKFLOW_BYTES.clone());

        assertThat(replay.alreadyPinned()).isTrue();
        assertThat(replay.authoritySha256()).isEqualTo(beforePin.authoritySha256());
        assertThat(replay.history()).isEqualTo(beforePin.history());

        PinnedVersion wrongVersion =
                new PinnedVersion(
                        "other",
                        "other-build",
                        new io.temporal.common.WorkerDeploymentVersion("other", "other-build")
                                .toCanonicalString());
        List<List<HistoryEvent>> invalidHistories =
                List.of(
                        validHistory(),
                        historyWithSuffix(workflowTaskCompleted(HISTORY_TAIL_EVENT_ID + 1)),
                        historyWithSuffix(
                                exactOptionsUpdatedEvent(targetVersion()).toBuilder()
                                        .setEventType(EVENT_TYPE_WORKFLOW_TASK_COMPLETED)
                                        .build()),
                        historyWithSuffix(
                                exactOptionsUpdatedEvent(targetVersion()).toBuilder()
                                        .setEventType(EVENT_TYPE_UNSPECIFIED)
                                        .build()),
                        historyWithSuffix(
                                optionsUpdatedEvent(
                                        topLevelOverride(
                                                VERSIONING_BEHAVIOR_PINNED,
                                                targetVersion().canonicalVersion()),
                                        false,
                                        false,
                                        "",
                                        false,
                                        "",
                                        false)),
                        historyWithSuffix(
                                optionsUpdatedEvent(
                                        topLevelOverride(
                                                VERSIONING_BEHAVIOR_PINNED,
                                                wrongVersion.canonicalVersion()),
                                        true,
                                        false,
                                        "",
                                        false,
                                        "",
                                        false)),
                        historyWithSuffix(
                                optionsUpdatedEvent(
                                        nestedOverride(), true, false, "", false, "", false)),
                        historyWithSuffix(
                                optionsUpdatedEvent(
                                        topLevelOverride(
                                                VERSIONING_BEHAVIOR_PINNED,
                                                targetVersion().canonicalVersion()),
                                        true,
                                        true,
                                        "",
                                        false,
                                        "",
                                        false)),
                        historyWithSuffix(
                                optionsUpdatedEvent(
                                        topLevelOverride(
                                                VERSIONING_BEHAVIOR_PINNED,
                                                targetVersion().canonicalVersion()),
                                        true,
                                        false,
                                        "attached-request",
                                        false,
                                        "",
                                        false)),
                        historyWithSuffix(
                                optionsUpdatedEvent(
                                        topLevelOverride(
                                                VERSIONING_BEHAVIOR_PINNED,
                                                targetVersion().canonicalVersion()),
                                        true,
                                        false,
                                        "",
                                        true,
                                        "",
                                        false)),
                        historyWithSuffix(
                                optionsUpdatedEvent(
                                        topLevelOverride(
                                                VERSIONING_BEHAVIOR_PINNED,
                                                targetVersion().canonicalVersion()),
                                        true,
                                        false,
                                        "",
                                        false,
                                        "unexpected-identity",
                                        false)),
                        historyWithSuffix(
                                optionsUpdatedEvent(
                                        topLevelOverride(
                                                VERSIONING_BEHAVIOR_PINNED,
                                                targetVersion().canonicalVersion()),
                                        true,
                                        false,
                                        "",
                                        false,
                                        "",
                                        true)),
                        appendEvent(
                                pinnedHistory(),
                                workflowTaskCompleted(HISTORY_TAIL_EVENT_ID + 2)));

        for (List<HistoryEvent> invalidHistory : invalidHistories) {
            assertRejected(
                    request,
                    new FakeAuthority("1.27.4", pinnedExecution, invalidHistory),
                    WORKFLOW_BYTES,
                    new RecordingPinExecutor(null, false, false));
        }
    }

    @Test
    void prepareRejectsExecutionTaskAndPendingActivityDrift() throws Exception {
        Path retained = retainedClasses("execution-drift", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request = request(retained);
        ExecutionAuthority exact = execution();
        List<ExecutionAuthority> invalid =
                List.of(
                        copy(exact, "other-workflow", exact.runId(), exact.workflowType(), exact.status(), exact.taskQueue(), "", exact.mostRecentBuildId(), true, SCHEDULED, 1, 0, exact.pendingChildren()),
                        copy(exact, exact.workflowId(), "33333333-3333-4333-8333-333333333333", exact.workflowType(), exact.status(), exact.taskQueue(), "", exact.mostRecentBuildId(), true, SCHEDULED, 1, 0, exact.pendingChildren()),
                        copy(exact, exact.workflowId(), exact.runId(), "OtherWorkflow", exact.status(), exact.taskQueue(), "", exact.mostRecentBuildId(), true, SCHEDULED, 1, 0, exact.pendingChildren()),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), ExactCaseProcessWorkflowPinRecoveryMain.WorkflowStatus.OTHER, exact.taskQueue(), "", exact.mostRecentBuildId(), true, SCHEDULED, 1, 0, exact.pendingChildren()),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), exact.status(), "room-control", "", exact.mostRecentBuildId(), true, SCHEDULED, 1, 0, exact.pendingChildren()),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), exact.status(), exact.taskQueue(), "assigned", exact.mostRecentBuildId(), true, SCHEDULED, 1, 0, exact.pendingChildren()),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), exact.status(), exact.taskQueue(), "", "wrong-build", true, SCHEDULED, 1, 0, exact.pendingChildren()),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), exact.status(), exact.taskQueue(), "", exact.mostRecentBuildId(), false, SCHEDULED, 1, 0, exact.pendingChildren()),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), exact.status(), exact.taskQueue(), "", exact.mostRecentBuildId(), true, OTHER, 1, 0, exact.pendingChildren()),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), exact.status(), exact.taskQueue(), "", exact.mostRecentBuildId(), true, SCHEDULED, 2, 0, exact.pendingChildren()),
                        copy(exact, exact.workflowId(), exact.runId(), exact.workflowType(), exact.status(), exact.taskQueue(), "", exact.mostRecentBuildId(), true, SCHEDULED, 1, 1, exact.pendingChildren()));

        for (ExecutionAuthority execution : invalid) {
            assertRejected(
                    request,
                    new FakeAuthority("1.27.4", execution, validHistory()),
                    WORKFLOW_BYTES,
                    new RecordingPinExecutor(null, false, false));
        }
    }

    @Test
    void prepareRejectsWorkflowTaskAndHistoryAuthorityDrift() throws Exception {
        Path retained = retainedClasses("history-drift", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request = request(retained);

        assertRejectedHistory(
                request,
                replace(
                        validHistory(),
                        Math.toIntExact(PENDING_WFT_SCHEDULED_EVENT_ID),
                        workflowTaskScheduled(
                                PENDING_WFT_SCHEDULED_EVENT_ID,
                                1,
                                TASK_QUEUE_KIND_STICKY)));
        assertRejectedHistory(
                request,
                replace(
                        validHistory(),
                        Math.toIntExact(PENDING_WFT_SCHEDULED_EVENT_ID),
                        workflowTaskScheduled(
                                PENDING_WFT_SCHEDULED_EVENT_ID,
                                2,
                                TASK_QUEUE_KIND_NORMAL)));
        assertRejectedHistory(
                request,
                replace(
                        validHistory(),
                        Math.toIntExact(LAST_SIGNAL_EVENT_ID),
                        signaled(LAST_SIGNAL_EVENT_ID, "wrongSignal")));
        List<HistoryEvent> nonContiguous = new ArrayList<>(validHistory());
        nonContiguous.remove(2);
        assertRejectedHistory(request, nonContiguous);
        assertRejectedHistory(
                withPendingScheduleEvent(request, PENDING_WFT_SCHEDULED_EVENT_ID - 1),
                validHistory());
        assertRejectedHistory(withLastSignalEvent(request, LAST_SIGNAL_EVENT_ID - 1), validHistory());
        RecoveryRequest wrongLast = withLastEvent(request, HISTORY_TAIL_EVENT_ID + 1);
        assertRejectedHistory(wrongLast, validHistory());
    }

    @Test
    void prepareRejectsPendingChildDescribeOrLifecycleDrift() throws Exception {
        Path retained = retainedClasses("child-drift", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request = request(retained);

        assertRejected(
                request,
                new FakeAuthority(
                        "1.27.4", withChildren(execution(), List.of()), validHistory()),
                WORKFLOW_BYTES,
                new RecordingPinExecutor(null, false, false));
        assertRejected(
                request,
                new FakeAuthority(
                        "1.27.4",
                        withChildren(execution(), List.of(pendingChild(), pendingChild())),
                        validHistory()),
                WORKFLOW_BYTES,
                new RecordingPinExecutor(null, false, false));
        assertRejected(
                request,
                new FakeAuthority(
                        "1.27.4",
                        withChildren(
                                execution(),
                                List.of(
                                        new PendingChildAuthority(
                                                CHILD_WORKFLOW_ID,
                                                "44444444-4444-4444-8444-444444444444",
                                                CHILD_WORKFLOW_TYPE,
                                                CHILD_INITIATED_EVENT_ID))),
                        validHistory()),
                WORKFLOW_BYTES,
                new RecordingPinExecutor(null, false, false));
        assertRejectedHistory(
                request,
                appendChildTerminationBeforeTail(validHistory()));
    }

    @Test
    void prepareRejectsRetainedMarkerAndClassMismatch() throws Exception {
        Path wrongMarker = retainedClasses("wrong-marker", "b".repeat(64), WORKFLOW_BYTES);
        assertRejected(
                request(wrongMarker),
                new FakeAuthority("1.27.4", execution(), validHistory()),
                WORKFLOW_BYTES,
                new RecordingPinExecutor(null, false, false));

        Path wrongClass =
                retainedClasses(
                        "wrong-class",
                        WORKTREE_BINDING,
                        "different-bytecode".getBytes(StandardCharsets.UTF_8));
        assertRejected(
                request(wrongClass),
                new FakeAuthority("1.27.4", execution(), validHistory()),
                WORKFLOW_BYTES,
                new RecordingPinExecutor(null, false, false));
    }

    @Test
    void applyRejectsWrongRpcOutcomeAndMissingPostPinOverride() throws Exception {
        Path retained = retainedClasses("post-pin", WORKTREE_BINDING, WORKFLOW_BYTES);
        RecoveryRequest prepareRequest = request(retained);
        FakeAuthority authority = new FakeAuthority("1.27.4", execution(), validHistory());
        RecoveryPlan prepared =
                ExactCaseProcessWorkflowPinRecoveryMain.prepare(
                        prepareRequest, authority, () -> WORKFLOW_BYTES.clone());
        RecoveryRequest applyRequest = prepareRequest.forApply(prepared.authoritySha256());

        RecordingPinExecutor wrongOutcome = new RecordingPinExecutor(authority, false, true);
        assertThatThrownBy(
                        () ->
                                ExactCaseProcessWorkflowPinRecoveryMain.operate(
                                        applyRequest,
                                        authority,
                                        () -> WORKFLOW_BYTES.clone(),
                                        wrongOutcome))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pin response");
        assertThat(wrongOutcome.commands).hasSize(1);

        authority.execution = execution();
        RecordingPinExecutor missingPostState = new RecordingPinExecutor(authority, false, false);
        assertThatThrownBy(
                        () ->
                                ExactCaseProcessWorkflowPinRecoveryMain.operate(
                                        applyRequest,
                                        authority,
                                        () -> WORKFLOW_BYTES.clone(),
                                        missingPostState))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not retain");
        assertThat(missingPostState.commands).hasSize(1);
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

    private static RecoveryRequest request(Path retained) {
        return new RecoveryRequest(
                "127.0.0.1:7233",
                "uat-namespace",
                WORKFLOW_ID,
                RUN_ID,
                LEGACY_BUILD_ID,
                retained,
                IDENTICAL,
                RECOVERY_DEPLOYMENT,
                RECOVERY_BUILD_ID,
                RECOVERY_PINNED_VERSION,
                SCHEDULED,
                PENDING_WFT_SCHEDULED_EVENT_ID,
                1,
                CHILD_WORKFLOW_ID,
                CHILD_RUN_ID,
                CHILD_WORKFLOW_TYPE,
                CHILD_INITIATED_EVENT_ID,
                HISTORY_TAIL_EVENT_ID,
                LAST_SIGNAL_EVENT_ID,
                DOMAIN_EVENT_SIGNAL,
                HISTORY_MAX_EVENTS,
                Mode.PREPARE,
                null);
    }

    private static RecoveryRequest transitionRequest(Path retained) {
        RecoveryRequest source = request(retained);
        return new RecoveryRequest(
                source.address(),
                source.namespace(),
                source.workflowId(),
                source.runId(),
                source.legacyBuildId(),
                source.retainedClasses(),
                TARGET_INTAKE_TERMINAL_V3_ACK_RECOVERY_V1,
                source.recoveryDeploymentName(),
                source.recoveryBuildId(),
                source.recoveryPinnedVersion(),
                ABSENT,
                0,
                0,
                source.pendingChildWorkflowId(),
                source.pendingChildRunId(),
                source.pendingChildWorkflowType(),
                source.pendingChildInitiatedEventId(),
                source.expectedLastEventId(),
                TERMINAL_SIGNAL_EVENT_ID,
                TARGET_INTAKE_TERMINAL_NO_COMMIT_SIGNAL,
                source.historyMaxEvents(),
                source.mode(),
                source.expectedAuthoritySha256());
    }

    private static RecoveryRequest withLastEvent(
            RecoveryRequest source, long lastEventId) {
        return withHistoryCoordinates(
                source,
                source.pendingWorkflowTaskScheduledEventId(),
                lastEventId,
                source.expectedLastSignalEventId());
    }

    private static RecoveryRequest withPendingScheduleEvent(
            RecoveryRequest source, long pendingWorkflowTaskScheduledEventId) {
        return withHistoryCoordinates(
                source,
                pendingWorkflowTaskScheduledEventId,
                source.expectedLastEventId(),
                source.expectedLastSignalEventId());
    }

    private static RecoveryRequest withLastSignalEvent(
            RecoveryRequest source, long lastSignalEventId) {
        return withHistoryCoordinates(
                source,
                source.pendingWorkflowTaskScheduledEventId(),
                source.expectedLastEventId(),
                lastSignalEventId);
    }

    private static RecoveryRequest withHistoryCoordinates(
            RecoveryRequest source,
            long pendingWorkflowTaskScheduledEventId,
            long lastEventId,
            long lastSignalEventId) {
        return new RecoveryRequest(
                source.address(),
                source.namespace(),
                source.workflowId(),
                source.runId(),
                source.legacyBuildId(),
                source.retainedClasses(),
                source.workflowClassAuthority(),
                source.recoveryDeploymentName(),
                source.recoveryBuildId(),
                source.recoveryPinnedVersion(),
                source.pendingWorkflowTaskState(),
                pendingWorkflowTaskScheduledEventId,
                source.pendingWorkflowTaskAttempt(),
                source.pendingChildWorkflowId(),
                source.pendingChildRunId(),
                source.pendingChildWorkflowType(),
                source.pendingChildInitiatedEventId(),
                lastEventId,
                lastSignalEventId,
                source.expectedSignalName(),
                source.historyMaxEvents(),
                source.mode(),
                source.expectedAuthoritySha256());
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
                List.of(pendingChild()),
                CurrentOverride.absent(),
                false,
                false);
    }

    private static ExecutionAuthority absentExecution() {
        ExecutionAuthority source = execution();
        return copy(
                source,
                source.workflowId(),
                source.runId(),
                source.workflowType(),
                source.status(),
                source.taskQueue(),
                source.assignedBuildId(),
                source.mostRecentBuildId(),
                source.versioned(),
                ABSENT,
                0,
                source.pendingActivities(),
                source.pendingChildren());
    }

    private static ExecutionAuthority copy(
            ExecutionAuthority source,
            String workflowId,
            String runId,
            String workflowType,
            ExactCaseProcessWorkflowPinRecoveryMain.WorkflowStatus status,
            String taskQueue,
            String assignedBuildId,
            String mostRecentBuildId,
            boolean versioned,
            ExactCaseProcessWorkflowPinRecoveryMain.PendingTaskState pendingState,
            int pendingAttempt,
            int pendingActivities,
            List<PendingChildAuthority> children) {
        return new ExecutionAuthority(
                workflowId,
                runId,
                workflowType,
                status,
                taskQueue,
                assignedBuildId,
                mostRecentBuildId,
                versioned,
                pendingState,
                pendingAttempt,
                pendingActivities,
                children,
                source.currentOverride(),
                source.deploymentTransition(),
                source.versionTransition());
    }

    private static ExecutionAuthority withChildren(
            ExecutionAuthority source, List<PendingChildAuthority> children) {
        return copy(
                source,
                source.workflowId(),
                source.runId(),
                source.workflowType(),
                source.status(),
                source.taskQueue(),
                source.assignedBuildId(),
                source.mostRecentBuildId(),
                source.versioned(),
                source.pendingState(),
                source.pendingAttempt(),
                source.pendingActivities(),
                children);
    }

    private static ExecutionAuthority withOverride(
            ExecutionAuthority source, CurrentOverride currentOverride) {
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
                source.pendingAttempt(),
                source.pendingActivities(),
                source.pendingChildren(),
                currentOverride,
                source.deploymentTransition(),
                source.versionTransition());
    }

    private static PendingChildAuthority pendingChild() {
        return new PendingChildAuthority(
                CHILD_WORKFLOW_ID,
                CHILD_RUN_ID,
                CHILD_WORKFLOW_TYPE,
                CHILD_INITIATED_EVENT_ID);
    }

    private static PinnedVersion targetVersion() {
        return new PinnedVersion(
                RECOVERY_DEPLOYMENT, RECOVERY_BUILD_ID, RECOVERY_PINNED_VERSION);
    }

    private static VersioningOverride topLevelOverride(
            VersioningBehavior behavior, String pinnedVersion) {
        return VersioningOverride.newBuilder()
                .setBehavior(behavior)
                .setPinnedVersion(pinnedVersion)
                .build();
    }

    private static VersioningOverride nestedOverride() {
        return VersioningOverride.newBuilder()
                .setPinned(
                        VersioningOverride.PinnedOverride.newBuilder()
                                .setBehavior(PINNED_OVERRIDE_BEHAVIOR_PINNED)
                                .setVersion(
                                        WorkerDeploymentVersion.newBuilder()
                                                .setDeploymentName(RECOVERY_DEPLOYMENT)
                                                .setBuildId(RECOVERY_BUILD_ID)))
                .build();
    }

    private static WorkflowExecutionOptions options(VersioningOverride override) {
        return WorkflowExecutionOptions.newBuilder().setVersioningOverride(override).build();
    }

    private static WorkflowExecutionInfo info(VersioningOverride override) {
        return WorkflowExecutionInfo.newBuilder()
                .setVersioningInfo(
                        WorkflowExecutionVersioningInfo.newBuilder()
                                .setVersioningOverride(override))
                .build();
    }

    private static List<HistoryEvent> pinnedHistory() {
        return historyWithSuffix(exactOptionsUpdatedEvent(targetVersion()));
    }

    private static List<HistoryEvent> historyWithSuffix(HistoryEvent suffix) {
        return appendEvent(validHistory(), suffix);
    }

    private static List<HistoryEvent> appendEvent(
            List<HistoryEvent> source, HistoryEvent event) {
        List<HistoryEvent> events = new ArrayList<>(source);
        events.add(event);
        return List.copyOf(events);
    }

    private static HistoryEvent exactOptionsUpdatedEvent(PinnedVersion version) {
        return optionsUpdatedEvent(
                topLevelOverride(VERSIONING_BEHAVIOR_PINNED, version.canonicalVersion()),
                true,
                false,
                "",
                false,
                "",
                false);
    }

    private static HistoryEvent optionsUpdatedEvent(
            VersioningOverride override,
            boolean workerMayIgnore,
            boolean unsetVersioningOverride,
            String attachedRequestId,
            boolean attachedCallback,
            String identity,
            boolean priority) {
        WorkflowExecutionOptionsUpdatedEventAttributes.Builder attributes =
                WorkflowExecutionOptionsUpdatedEventAttributes.newBuilder()
                        .setVersioningOverride(override)
                        .setUnsetVersioningOverride(unsetVersioningOverride)
                        .setAttachedRequestId(attachedRequestId)
                        .setIdentity(identity);
        if (attachedCallback) {
            attributes.addAttachedCompletionCallbacks(Callback.getDefaultInstance());
        }
        if (priority) {
            attributes.setPriority(Priority.getDefaultInstance());
        }
        return HistoryEvent.newBuilder()
                .setEventId(HISTORY_TAIL_EVENT_ID + 1)
                .setEventType(EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED)
                .setWorkerMayIgnore(workerMayIgnore)
                .setWorkflowExecutionOptionsUpdatedEventAttributes(attributes)
                .build();
    }

    private static List<HistoryEvent> validHistory() {
        List<HistoryEvent> events = new ArrayList<>();
        events.add(workflowStarted(1));
        events.add(childInitiated(2));
        events.add(childStarted(3));
        for (long eventId = 4; eventId < PENDING_WFT_SCHEDULED_EVENT_ID; eventId++) {
            events.add(filler(eventId));
        }
        events.add(
                workflowTaskScheduled(
                        PENDING_WFT_SCHEDULED_EVENT_ID, 1, TASK_QUEUE_KIND_NORMAL));
        events.add(signaled(LAST_SIGNAL_EVENT_ID, DOMAIN_EVENT_SIGNAL));
        return List.copyOf(events);
    }

    private static List<HistoryEvent> absentWorkflowTaskHistory() {
        List<HistoryEvent> events = new ArrayList<>();
        events.add(workflowStarted(1));
        events.add(childInitiated(2));
        events.add(childStarted(3));
        for (long eventId = 4; eventId < TERMINAL_SIGNAL_EVENT_ID; eventId++) {
            events.add(filler(eventId));
        }
        events.add(signaled(TERMINAL_SIGNAL_EVENT_ID, TARGET_INTAKE_TERMINAL_NO_COMMIT_SIGNAL));
        events.add(workflowTaskScheduled(124, 1, TASK_QUEUE_KIND_NORMAL));
        events.add(workflowTaskStarted(125, 124));
        events.add(workflowTaskCompleted(126, 124, 125));
        return List.copyOf(events);
    }

    private static List<HistoryEvent> appendChildTerminationBeforeTail(
            List<HistoryEvent> source) {
        long terminalEventId = PENDING_WFT_SCHEDULED_EVENT_ID - 1;
        return replace(
                source,
                Math.toIntExact(terminalEventId),
                childTerminated(terminalEventId));
    }

    private static HistoryEvent workflowStarted(long eventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setWorkflowExecutionStartedEventAttributes(
                        WorkflowExecutionStartedEventAttributes.getDefaultInstance())
                .build();
    }

    private static HistoryEvent filler(long eventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setMarkerRecordedEventAttributes(MarkerRecordedEventAttributes.getDefaultInstance())
                .build();
    }

    private static HistoryEvent childInitiated(long eventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setStartChildWorkflowExecutionInitiatedEventAttributes(
                        StartChildWorkflowExecutionInitiatedEventAttributes.newBuilder()
                                .setWorkflowId(CHILD_WORKFLOW_ID)
                                .setWorkflowType(
                                        WorkflowType.newBuilder().setName(CHILD_WORKFLOW_TYPE)))
                .build();
    }

    private static HistoryEvent childStarted(long eventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setChildWorkflowExecutionStartedEventAttributes(
                        ChildWorkflowExecutionStartedEventAttributes.newBuilder()
                                .setInitiatedEventId(CHILD_INITIATED_EVENT_ID)
                                .setWorkflowExecution(
                                        WorkflowExecution.newBuilder()
                                                .setWorkflowId(CHILD_WORKFLOW_ID)
                                                .setRunId(CHILD_RUN_ID))
                                .setWorkflowType(
                                        WorkflowType.newBuilder().setName(CHILD_WORKFLOW_TYPE)))
                .build();
    }

    private static HistoryEvent childTerminated(long eventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setChildWorkflowExecutionTerminatedEventAttributes(
                        ChildWorkflowExecutionTerminatedEventAttributes.newBuilder()
                                .setInitiatedEventId(CHILD_INITIATED_EVENT_ID)
                                .setStartedEventId(3))
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

    private static HistoryEvent workflowTaskCompleted(long eventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setWorkflowTaskCompletedEventAttributes(
                        WorkflowTaskCompletedEventAttributes.getDefaultInstance())
                .build();
    }

    private static HistoryEvent workflowTaskCompleted(
            long eventId, long scheduledEventId, long startedEventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setWorkflowTaskCompletedEventAttributes(
                        WorkflowTaskCompletedEventAttributes.newBuilder()
                                .setScheduledEventId(scheduledEventId)
                                .setStartedEventId(startedEventId))
                .build();
    }

    private static HistoryEvent workflowTaskStarted(long eventId, long scheduledEventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setWorkflowTaskStartedEventAttributes(
                        WorkflowTaskStartedEventAttributes.newBuilder()
                                .setScheduledEventId(scheduledEventId))
                .build();
    }

    private static HistoryEvent workflowTaskScheduled(
            long eventId,
            int attempt,
            io.temporal.api.enums.v1.TaskQueueKind kind) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setWorkflowTaskScheduledEventAttributes(
                        WorkflowTaskScheduledEventAttributes.newBuilder()
                                .setTaskQueue(
                                        TaskQueue.newBuilder()
                                                .setName(CASE_CONTROL_TASK_QUEUE)
                                                .setKind(kind))
                                .setAttempt(attempt))
                .build();
    }

    private static List<HistoryEvent> replace(
            List<HistoryEvent> source, int eventId, HistoryEvent replacement) {
        List<HistoryEvent> copy = new ArrayList<>(source);
        copy.set(eventId - 1, replacement);
        return List.copyOf(copy);
    }

    private static void assertRejectedHistory(
            RecoveryRequest request, List<HistoryEvent> history) {
        assertRejected(
                request,
                new FakeAuthority("1.27.4", execution(), history),
                WORKFLOW_BYTES,
                new RecordingPinExecutor(null, false, false));
    }

    private static void assertRejected(
            RecoveryRequest request,
            TemporalAuthority authority,
            byte[] currentWorkflowBytes,
            ExactCaseProcessWorkflowPinRecoveryMain.PinExecutor pinExecutor) {
        assertThatThrownBy(
                        () ->
                                ExactCaseProcessWorkflowPinRecoveryMain.operate(
                                        request,
                                        authority,
                                        () -> currentWorkflowBytes.clone(),
                                        pinExecutor))
                .isInstanceOf(IllegalStateException.class);
        if (pinExecutor instanceof RecordingPinExecutor recording) {
            assertThat(recording.commands).isEmpty();
        }
    }

    private static final class FakeAuthority implements TemporalAuthority {
        private String serverVersion;
        private ExecutionAuthority execution;
        private List<HistoryEvent> history;

        private FakeAuthority(
                String serverVersion,
                ExecutionAuthority execution,
                List<HistoryEvent> history) {
            this.serverVersion = serverVersion;
            this.execution = execution;
            this.history = List.copyOf(history);
        }

        @Override
        public String serverVersion() {
            return serverVersion;
        }

        @Override
        public ExecutionAuthority describe(RecoveryRequest request) {
            return execution;
        }

        @Override
        public List<HistoryEvent> loadCompleteHistory(RecoveryRequest request) {
            return history;
        }
    }

    private static final class RecordingPinExecutor
            implements ExactCaseProcessWorkflowPinRecoveryMain.PinExecutor {
        private final FakeAuthority authority;
        private final boolean publishExactOverride;
        private final boolean returnWrongVersion;
        private final List<PinCommand> commands = new ArrayList<>();

        private RecordingPinExecutor(
                FakeAuthority authority,
                boolean publishExactOverride,
                boolean returnWrongVersion) {
            this.authority = authority;
            this.publishExactOverride = publishExactOverride;
            this.returnWrongVersion = returnWrongVersion;
        }

        @Override
        public PinOutcome pin(PinCommand command) {
            commands.add(command);
            if (publishExactOverride) {
                authority.execution =
                        withOverride(
                                authority.execution,
                                CurrentOverride.pinned(
                                        command.targetVersion().deploymentName(),
                                        command.targetVersion().buildId()));
                authority.history =
                        appendEvent(
                                authority.history,
                                exactOptionsUpdatedEvent(command.targetVersion()));
            }
            if (returnWrongVersion) {
                return new PinOutcome(new PinnedVersion("other", "other", "other.other"));
            }
            return new PinOutcome(command.targetVersion());
        }
    }
}
