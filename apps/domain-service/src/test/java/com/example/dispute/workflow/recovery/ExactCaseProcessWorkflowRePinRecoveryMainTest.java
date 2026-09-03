package com.example.dispute.workflow.recovery;

import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.CASE_PROCESS_WORKFLOW_CLASS;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.TARGET_TYPED_ROOM_DISPATCHER_CLASS;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.UPDATE_IDENTITY;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.UPDATE_MASK_PATH;
import static com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.WORKTREE_MARKER;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_UNSPECIFIED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_TIMER_STARTED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.DeploymentAuthority;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.Disposition;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.ExecutionAuthority;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.ExecutionVersionAuthority;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.Mode;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.OldVersionAuthority;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.OperationResult;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.PendingWorkflowTaskState;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.PinnedVersion;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.QueueMembership;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.RePinCommand;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.RePinOutcome;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.RecoveryPlan;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.RecoveryRequest;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.TemporalAuthority;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.VersionAuthority;
import com.example.dispute.workflow.recovery.ExactCaseProcessWorkflowRePinRecoveryMain.WorkflowClassAuthority;
import com.google.protobuf.Duration;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.deployment.v1.WorkerDeploymentVersion;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.TimerStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionOptionsUpdatedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionSignaledEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskCompletedEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskScheduledEventAttributes;
import io.temporal.api.history.v1.WorkflowTaskStartedEventAttributes;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflow.v1.VersioningOverride;
import io.temporal.api.workflow.v1.WorkflowExecutionVersioningInfo;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionOptionsRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExactCaseProcessWorkflowRePinRecoveryMainTest {

    private static final String OLD_BINDING = "a".repeat(64);
    private static final String NEW_BINDING = "b".repeat(64);
    private static final String DEPLOYMENT = "aflow-case-process-recovery";
    private static final String OLD_BUILD_ID = "current-old-" + OLD_BINDING + "-control";
    private static final String NEW_BUILD_ID = "inactive-new-" + NEW_BINDING + "-control";
    private static final String FOREIGN_BUILD_ID = "foreign-" + "c".repeat(64) + "-control";
    private static final String OLD_PINNED_VERSION = canonical(DEPLOYMENT, OLD_BUILD_ID);
    private static final String NEW_PINNED_VERSION = canonical(DEPLOYMENT, NEW_BUILD_ID);
    private static final String FOREIGN_PINNED_VERSION = canonical(DEPLOYMENT, FOREIGN_BUILD_ID);
    private static final String WORKFLOW_ID = "case-process:tenant-test:CASE_TEST";
    private static final String RUN_ID = "11111111-1111-4111-8111-111111111111";
    private static final long TIMER_EVENT_ID = 5;
    private static final String TIMER_ID = "case-process-wakeup";
    private static final String LIVE_TIMER_ID = "50a8824c-6350-3dce-bfac-2b2e73cc70ce";
    private static final long TIMER_SECONDS = 86_400;
    private static final long PENDING_WFT_EVENT_ID = 6;
    private static final int PENDING_WFT_ATTEMPT = 1;
    private static final long HISTORY_TAIL = 6;
    private static final byte[] WORKFLOW_BYTES =
            "exact-case-process-repin-workflow-bytecode".getBytes(StandardCharsets.UTF_8);
    private static final Set<QueueMembership> EXACT_MEMBERSHIP =
            Set.of(
                    new QueueMembership(CASE_CONTROL_TASK_QUEUE, TASK_QUEUE_TYPE_WORKFLOW),
                    new QueueMembership(CASE_CONTROL_TASK_QUEUE, TASK_QUEUE_TYPE_ACTIVITY));
    private static final Set<QueueMembership> INTAKE_CONTINUATION_MEMBERSHIP =
            Set.of(
                    new QueueMembership(CASE_CONTROL_TASK_QUEUE, TASK_QUEUE_TYPE_WORKFLOW),
                    new QueueMembership(CASE_CONTROL_TASK_QUEUE, TASK_QUEUE_TYPE_ACTIVITY),
                    new QueueMembership("room-control", TASK_QUEUE_TYPE_WORKFLOW));

    @TempDir Path tempDir;

    @Test
    void prepareBindsInactiveExactMembershipAndPerformsNoMutation() throws Exception {
        Fixture fixture = fixture("prepare");
        RecordingExecutor executor = new RecordingExecutor(fixture.authority(), true);

        OperationResult result =
                ExactCaseProcessWorkflowRePinRecoveryMain.operate(
                        fixture.request(),
                        fixture.authority(),
                        () -> WORKFLOW_BYTES.clone(),
                        executor);
        RecoveryPlan plan =
                ExactCaseProcessWorkflowRePinRecoveryMain.prepare(
                        fixture.request(), fixture.authority(), () -> WORKFLOW_BYTES.clone());

        assertThat(result.disposition()).isEqualTo(Disposition.PREPARED);
        assertThat(result.authoritySha256()).matches("[0-9a-f]{64}");
        assertThat(plan.oldVersion().canonicalVersion()).isEqualTo(OLD_PINNED_VERSION);
        assertThat(plan.newVersionAuthority().status())
                .isEqualTo(WORKER_DEPLOYMENT_VERSION_STATUS_INACTIVE);
        assertThat(plan.newVersionAuthority().membership()).isEqualTo(EXACT_MEMBERSHIP);
        assertThat(plan.oldDeployment().currentVersion()).isEqualTo(OLD_PINNED_VERSION);
        assertThat(plan.newDeployment().rampingVersion()).isEmpty();
        assertThat(plan.history().pendingWorkflowTask().scheduledEventId())
                .isEqualTo(PENDING_WFT_EVENT_ID);
        assertThat(plan.history().pendingTimer().startedEventId()).isEqualTo(TIMER_EVENT_ID);
        assertThat(executor.commands).isEmpty();
    }

    @Test
    void applyRepinsExactExecutionOnceAndExactReplayDoesNotWriteAgain() throws Exception {
        Fixture fixture = fixture("apply");
        RecoveryPlan prepared =
                ExactCaseProcessWorkflowRePinRecoveryMain.prepare(
                        fixture.request(), fixture.authority(), () -> WORKFLOW_BYTES.clone());
        RecoveryRequest apply = fixture.request().forApply(prepared.authoritySha256());
        RecordingExecutor executor = new RecordingExecutor(fixture.authority(), true);
        List<HistoryEvent> original = fixture.authority().history;

        OperationResult first =
                ExactCaseProcessWorkflowRePinRecoveryMain.operate(
                        apply, fixture.authority(), () -> WORKFLOW_BYTES.clone(), executor);
        OperationResult replay =
                ExactCaseProcessWorkflowRePinRecoveryMain.operate(
                        apply, fixture.authority(), () -> WORKFLOW_BYTES.clone(), executor);

        assertThat(first.disposition()).isEqualTo(Disposition.REPINNED);
        assertThat(replay.disposition()).isEqualTo(Disposition.ALREADY_REPINNED);
        assertThat(first.authoritySha256()).isEqualTo(prepared.authoritySha256());
        assertThat(replay.authoritySha256()).isEqualTo(prepared.authoritySha256());
        assertThat(executor.commands).hasSize(1);
        assertThat(fixture.authority().history.subList(0, original.size()))
                .containsExactlyElementsOf(original);
        assertThat(fixture.authority().history.getLast())
                .isEqualTo(exactOptionsUpdatedEvent(newVersion()));
        assertThat(fixture.authority().deployment.currentVersion())
                .isEqualTo(OLD_PINNED_VERSION);
        assertThat(fixture.authority().deployment.rampingVersion()).isEmpty();
    }

    @Test
    void inactivePinnedPredecessorCanRepinWithoutChangingDeploymentRouting() throws Exception {
        Fixture fixture = fixture("inactive-predecessor");
        fixture.authority().deployment =
                deployment(
                        FOREIGN_PINNED_VERSION,
                        "",
                        List.of(FOREIGN_PINNED_VERSION, OLD_PINNED_VERSION, NEW_PINNED_VERSION));
        RecoveryPlan prepared =
                ExactCaseProcessWorkflowRePinRecoveryMain.prepare(
                        fixture.request(), fixture.authority(), () -> WORKFLOW_BYTES.clone());
        RecordingExecutor executor = new RecordingExecutor(fixture.authority(), true);

        OperationResult first =
                ExactCaseProcessWorkflowRePinRecoveryMain.operate(
                        fixture.request().forApply(prepared.authoritySha256()),
                        fixture.authority(),
                        () -> WORKFLOW_BYTES.clone(),
                        executor);
        OperationResult replay =
                ExactCaseProcessWorkflowRePinRecoveryMain.operate(
                        fixture.request().forApply(prepared.authoritySha256()),
                        fixture.authority(),
                        () -> WORKFLOW_BYTES.clone(),
                        executor);

        assertThat(first.disposition()).isEqualTo(Disposition.REPINNED);
        assertThat(replay.disposition()).isEqualTo(Disposition.ALREADY_REPINNED);
        assertThat(executor.commands).hasSize(1);
        assertThat(fixture.authority().deployment.currentVersion())
                .isEqualTo(FOREIGN_PINNED_VERSION);
        assertThat(fixture.authority().deployment.rampingVersion()).isEmpty();
    }

    @Test
    void pinnedAssignmentAppliesAcrossPersistedDualDescribeAndNestedHistory() throws Exception {
        Fixture scheduledFixture = fixture("live-shape");
        RecoveryRequest liveRequest = liveRequest(scheduledFixture.request());
        FakeAuthority authority = scheduledFixture.authority();
        authority.execution =
                execution(
                        OldVersionAuthority.PINNED_ASSIGNMENT,
                        oldVersion(),
                        PendingWorkflowTaskState.ABSENT,
                        0);
        authority.history = liveHistoryWithoutPendingWorkflowTask();
        RecoveryPlan prepared =
                ExactCaseProcessWorkflowRePinRecoveryMain.prepare(
                        liveRequest, authority, () -> WORKFLOW_BYTES.clone());
        RecordingExecutor executor =
                new RecordingExecutor(authority, true, oldVersion(), true);

        RecoveryRequest apply = liveRequest.forApply(prepared.authoritySha256());
        OperationResult result =
                ExactCaseProcessWorkflowRePinRecoveryMain.operate(
                        apply, authority, () -> WORKFLOW_BYTES.clone(), executor);
        OperationResult replay =
                ExactCaseProcessWorkflowRePinRecoveryMain.operate(
                        apply, authority, () -> WORKFLOW_BYTES.clone(), executor);

        assertThat(result.disposition()).isEqualTo(Disposition.REPINNED);
        assertThat(replay.disposition()).isEqualTo(Disposition.ALREADY_REPINNED);
        assertThat(prepared.execution().versionAuthority().kind())
                .isEqualTo(OldVersionAuthority.PINNED_ASSIGNMENT);
        assertThat(prepared.history().pendingWorkflowTask().state())
                .isEqualTo(PendingWorkflowTaskState.ABSENT);
        assertThat(prepared.history().pendingWorkflowTask().scheduledEventId()).isZero();
        assertThat(prepared.history().pendingWorkflowTask().attempt()).isZero();
        assertThat(prepared.history().pendingTimer().startedEventId()).isEqualTo(15);
        assertThat(prepared.history().pendingTimer().timerId()).isEqualTo(LIVE_TIMER_ID);
        assertThat(prepared.history().pendingTimer().timeoutSeconds()).isEqualTo(86_400);
        assertThat(authority.history).hasSize(16);
        assertThat(executor.commands).hasSize(1);
    }

    @Test
    void livePostApplyStateAdoptsNestedEventAndOldEffectiveAssignmentWithoutAnotherRpc()
            throws Exception {
        Fixture scheduledFixture = fixture("live-post-apply");
        RecoveryRequest liveRequest = liveRequest(scheduledFixture.request());
        FakeAuthority authority = scheduledFixture.authority();
        authority.execution =
                execution(
                        OldVersionAuthority.PINNED_ASSIGNMENT,
                        oldVersion(),
                        PendingWorkflowTaskState.ABSENT,
                        0);
        authority.history = liveHistoryWithoutPendingWorkflowTask();
        RecoveryPlan prepared =
                ExactCaseProcessWorkflowRePinRecoveryMain.prepare(
                        liveRequest, authority, () -> WORKFLOW_BYTES.clone());
        ExecutionVersionAuthority persistedPostApply =
                ExactCaseProcessWorkflowRePinRecoveryMain.executionVersionAuthority(
                        assignmentInfo(oldVersion()).toBuilder()
                                .setVersioningOverride(dualOverride(newVersion()))
                                .build());
        authority.execution =
                execution(
                        persistedPostApply, PendingWorkflowTaskState.ABSENT, 0);
        authority.history =
                append(
                        authority.history,
                        exactNestedOptionsUpdatedEvent(newVersion(), 16));
        RecordingExecutor executor = new RecordingExecutor(authority, true);

        OperationResult replay =
                ExactCaseProcessWorkflowRePinRecoveryMain.operate(
                        liveRequest.forApply(prepared.authoritySha256()),
                        authority,
                        () -> WORKFLOW_BYTES.clone(),
                        executor);

        assertThat(replay.disposition()).isEqualTo(Disposition.ALREADY_REPINNED);
        assertThat(replay.authoritySha256()).isEqualTo(prepared.authoritySha256());
        assertThat(authority.execution.versionAuthority().kind())
                .isEqualTo(OldVersionAuthority.EXPLICIT_OVERRIDE);
        assertThat(authority.execution.versionAuthority().version()).isEqualTo(newVersion());
        assertThat(authority.execution.versionAuthority().effectiveAssignment())
                .isEqualTo(oldVersion());
        assertThat(executor.commands).isEmpty();
    }

    @Test
    void updateRequestIsExactExecutionScopedTopLevelOnlyAndNeverChangesRouting() {
        RePinCommand command =
                new RePinCommand(
                        "uat-namespace",
                        WORKFLOW_ID,
                        RUN_ID,
                        newVersion(),
                        UPDATE_MASK_PATH,
                        UPDATE_IDENTITY);

        UpdateWorkflowExecutionOptionsRequest request =
                ExactCaseProcessWorkflowRePinRecoveryMain.updateRequest(command);

        assertThat(request.getNamespace()).isEqualTo("uat-namespace");
        assertThat(request.getWorkflowExecution().getWorkflowId()).isEqualTo(WORKFLOW_ID);
        assertThat(request.getWorkflowExecution().getRunId()).isEqualTo(RUN_ID);
        assertThat(request.getUpdateMask().getPathsList()).containsExactly(UPDATE_MASK_PATH);
        assertThat(request.getIdentity()).isEqualTo(UPDATE_IDENTITY);
        VersioningOverride override =
                request.getWorkflowExecutionOptions().getVersioningOverride();
        assertThat(override.getBehavior()).isEqualTo(VERSIONING_BEHAVIOR_PINNED);
        assertThat(override.getPinnedVersion()).isEqualTo(NEW_PINNED_VERSION);
        assertThat(override.hasPinned()).isFalse();
        assertThat(
                        ExactCaseProcessWorkflowRePinRecoveryMain.parseClientPinnedOverride(
                                override, "test client request"))
                .isEqualTo(newVersion());
        assertThatThrownBy(
                        () ->
                                ExactCaseProcessWorkflowRePinRecoveryMain
                                        .parseClientPinnedOverride(
                                                nestedOverride(newVersion()),
                                                "test client request"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void versioningAuthorityDiscriminatorAcceptsStrictServerPersistedShapesOnly() {
        WorkflowExecutionVersioningInfo assignment = assignmentInfo(oldVersion());
        WorkflowExecutionVersioningInfo explicit =
                WorkflowExecutionVersioningInfo.newBuilder()
                        .setVersioningOverride(topLevelOverride(oldVersion()))
                        .build();
        WorkflowExecutionVersioningInfo nested =
                WorkflowExecutionVersioningInfo.newBuilder()
                        .setVersioningOverride(nestedOverride(oldVersion()))
                        .build();
        WorkflowExecutionVersioningInfo dual =
                WorkflowExecutionVersioningInfo.newBuilder()
                        .setVersioningOverride(dualOverride(oldVersion()))
                        .build();
        WorkflowExecutionVersioningInfo pendingApplication =
                assignmentInfo(oldVersion()).toBuilder()
                        .setVersioningOverride(dualOverride(newVersion()))
                        .build();
        WorkflowExecutionVersioningInfo applied =
                assignmentInfo(newVersion()).toBuilder()
                        .setVersioningOverride(dualOverride(newVersion()))
                        .build();

        assertThat(
                        ExactCaseProcessWorkflowRePinRecoveryMain.executionVersionAuthority(
                                assignment))
                .isEqualTo(
                        new ExecutionVersionAuthority(
                                OldVersionAuthority.PINNED_ASSIGNMENT,
                                oldVersion(),
                                oldVersion()));
        assertThat(
                        ExactCaseProcessWorkflowRePinRecoveryMain.executionVersionAuthority(
                                explicit))
                .isEqualTo(
                        new ExecutionVersionAuthority(
                                OldVersionAuthority.EXPLICIT_OVERRIDE, oldVersion(), null));
        assertThat(
                        ExactCaseProcessWorkflowRePinRecoveryMain.executionVersionAuthority(
                                nested))
                .isEqualTo(
                        new ExecutionVersionAuthority(
                                OldVersionAuthority.EXPLICIT_OVERRIDE, oldVersion(), null));
        assertThat(
                        ExactCaseProcessWorkflowRePinRecoveryMain.executionVersionAuthority(dual))
                .isEqualTo(
                        new ExecutionVersionAuthority(
                                OldVersionAuthority.EXPLICIT_OVERRIDE, oldVersion(), null));
        assertThat(
                        ExactCaseProcessWorkflowRePinRecoveryMain.executionVersionAuthority(
                                pendingApplication))
                .isEqualTo(
                        new ExecutionVersionAuthority(
                                OldVersionAuthority.EXPLICIT_OVERRIDE,
                                newVersion(),
                                oldVersion()));
        assertThat(
                        ExactCaseProcessWorkflowRePinRecoveryMain.executionVersionAuthority(applied))
                .isEqualTo(
                        new ExecutionVersionAuthority(
                                OldVersionAuthority.EXPLICIT_OVERRIDE,
                                newVersion(),
                                newVersion()));

        List<WorkflowExecutionVersioningInfo> invalid =
                List.of(
                        WorkflowExecutionVersioningInfo.getDefaultInstance(),
                        WorkflowExecutionVersioningInfo.newBuilder()
                                .setVersioningOverride(
                                        VersioningOverride.newBuilder()
                                                .setBehavior(VERSIONING_BEHAVIOR_PINNED))
                                .build(),
                        WorkflowExecutionVersioningInfo.newBuilder()
                                .setBehavior(VERSIONING_BEHAVIOR_PINNED)
                                .setVersion(OLD_PINNED_VERSION)
                                .build(),
                        WorkflowExecutionVersioningInfo.newBuilder()
                                .setVersioningOverride(
                                        VersioningOverride.newBuilder()
                                                .setPinned(
                                                        VersioningOverride.PinnedOverride.newBuilder()
                                                                .setVersion(
                                                                        WorkerDeploymentVersion
                                                                                .newBuilder()
                                                                                .setDeploymentName(
                                                                                        DEPLOYMENT)
                                                                                .setBuildId(
                                                                                        OLD_BUILD_ID))))
                                .build(),
                        WorkflowExecutionVersioningInfo.newBuilder()
                                .setVersioningOverride(
                                        VersioningOverride.newBuilder()
                                                .setPinned(
                                                        VersioningOverride.PinnedOverride.newBuilder()
                                                                .setBehavior(
                                                                        VersioningOverride
                                                                                .PinnedOverrideBehavior
                                                                                .PINNED_OVERRIDE_BEHAVIOR_PINNED)))
                                .build(),
                        WorkflowExecutionVersioningInfo.newBuilder()
                                .setBehavior(VERSIONING_BEHAVIOR_AUTO_UPGRADE)
                                .setVersion(OLD_PINNED_VERSION)
                                .setDeploymentVersion(
                                        WorkerDeploymentVersion.newBuilder()
                                                .setDeploymentName(DEPLOYMENT)
                                                .setBuildId(OLD_BUILD_ID))
                                .build(),
                        assignment.toBuilder()
                                .setDeploymentVersion(
                                        WorkerDeploymentVersion.newBuilder()
                                                .setDeploymentName(DEPLOYMENT)
                                                .setBuildId(NEW_BUILD_ID))
                                .build(),
                        WorkflowExecutionVersioningInfo.newBuilder()
                                .setVersioningOverride(
                                        topLevelOverride(oldVersion()).toBuilder()
                                                .setPinned(
                                                        nestedOverride(newVersion()).getPinned()))
                                .build());
        for (WorkflowExecutionVersioningInfo candidate : invalid) {
            assertThatThrownBy(
                            () ->
                                    ExactCaseProcessWorkflowRePinRecoveryMain
                                            .executionVersionAuthority(candidate))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void deploymentAuthorityRejectsCurrentRampingNoninactiveAndForeignMembership() throws Exception {
        Fixture fixture = fixture("deployment-negative");
        List<Runnable> mutations =
                List.of(
                        () ->
                                fixture.authority().deployment =
                                        deployment(NEW_PINNED_VERSION, "", List.of(OLD_PINNED_VERSION, NEW_PINNED_VERSION)),
                        () ->
                                fixture.authority().deployment =
                                        deployment(OLD_PINNED_VERSION, NEW_PINNED_VERSION, List.of(OLD_PINNED_VERSION, NEW_PINNED_VERSION)),
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
                                                                CASE_CONTROL_TASK_QUEUE,
                                                                TASK_QUEUE_TYPE_WORKFLOW),
                                                        new QueueMembership(
                                                                "room-control",
                                                                TASK_QUEUE_TYPE_ACTIVITY))));

        for (Runnable mutation : mutations) {
            fixture.authority().reset();
            mutation.run();
            assertPrepareRejected(fixture);
        }
    }

    @Test
    void deploymentAuthorityRejectsDuplicateRegistrationAndRoutingTransition() throws Exception {
        Fixture fixture = fixture("deployment-transition");
        fixture.authority().deployment =
                new DeploymentAuthority(
                        DEPLOYMENT,
                        OLD_PINNED_VERSION,
                        "",
                        0,
                        9,
                        false,
                        List.of(OLD_PINNED_VERSION, NEW_PINNED_VERSION, NEW_PINNED_VERSION));
        assertPrepareRejected(fixture);

        fixture.authority().reset();
        fixture.authority().deployment =
                new DeploymentAuthority(
                        DEPLOYMENT,
                        OLD_PINNED_VERSION,
                        "",
                        0,
                        9,
                        true,
                        List.of(OLD_PINNED_VERSION, NEW_PINNED_VERSION));
        assertPrepareRejected(fixture);
    }

    @Test
    void executionAuthorityRejectsWrongIdentityQueueStateOrCoTenantWork() throws Exception {
        Fixture fixture = fixture("execution-negative");
        List<ExecutionAuthority> invalid =
                List.of(
                        execution(newVersion(), WORKFLOW_ID + "-other", CASE_WORKFLOW_TYPE, true, CASE_CONTROL_TASK_QUEUE, PendingWorkflowTaskState.SCHEDULED, 1, 0, 0),
                        execution(
                                OldVersionAuthority.PINNED_ASSIGNMENT,
                                oldVersion(),
                                PendingWorkflowTaskState.SCHEDULED,
                                1),
                        execution(
                                OldVersionAuthority.EXPLICIT_OVERRIDE,
                                newVersion(),
                                foreignVersion(),
                                PendingWorkflowTaskState.SCHEDULED,
                                1),
                        execution(oldVersion(), WORKFLOW_ID, "OutcomeRoomWorkflow", true, CASE_CONTROL_TASK_QUEUE, PendingWorkflowTaskState.SCHEDULED, 1, 0, 0),
                        execution(oldVersion(), WORKFLOW_ID, CASE_WORKFLOW_TYPE, false, CASE_CONTROL_TASK_QUEUE, PendingWorkflowTaskState.SCHEDULED, 1, 0, 0),
                        execution(oldVersion(), WORKFLOW_ID, CASE_WORKFLOW_TYPE, true, "room-control", PendingWorkflowTaskState.SCHEDULED, 1, 0, 0),
                        execution(oldVersion(), WORKFLOW_ID, CASE_WORKFLOW_TYPE, true, CASE_CONTROL_TASK_QUEUE, PendingWorkflowTaskState.ABSENT, 1, 0, 0),
                        execution(oldVersion(), WORKFLOW_ID, CASE_WORKFLOW_TYPE, true, CASE_CONTROL_TASK_QUEUE, PendingWorkflowTaskState.SCHEDULED, 2, 0, 0),
                        execution(oldVersion(), WORKFLOW_ID, CASE_WORKFLOW_TYPE, true, CASE_CONTROL_TASK_QUEUE, PendingWorkflowTaskState.SCHEDULED, 1, 1, 0),
                        execution(oldVersion(), WORKFLOW_ID, CASE_WORKFLOW_TYPE, true, CASE_CONTROL_TASK_QUEUE, PendingWorkflowTaskState.SCHEDULED, 1, 0, 1));

        for (ExecutionAuthority execution : invalid) {
            fixture.authority().reset();
            fixture.authority().execution = execution;
            assertPrepareRejected(fixture);
        }
    }

    @Test
    void completeHistoryBindsPendingNormalWftAndExactTwentyFourHourTimer() throws Exception {
        Fixture fixture = fixture("history-negative");
        List<List<HistoryEvent>> invalid =
                List.of(
                        replace(validHistory(), 5, timerStarted(5, TIMER_ID, TIMER_SECONDS - 1)),
                        replace(validHistory(), 6, workflowTaskScheduled(6, "room-control", 1)),
                        replace(validHistory(), 6, workflowTaskScheduled(6, CASE_CONTROL_TASK_QUEUE, 2)),
                        replace(validHistory(), 6, workflowTaskStarted(6, 6)),
                        replace(validHistory(), 6, workflowTaskScheduled(7, CASE_CONTROL_TASK_QUEUE, 1)));

        for (List<HistoryEvent> history : invalid) {
            fixture.authority().reset();
            fixture.authority().history = history;
            assertPrepareRejected(fixture);
        }
    }

    @Test
    void exactReplayRejectsWrongTypeShapeOrExtraHistoryBeforeAnyRpc() throws Exception {
        Fixture fixture = fixture("replay-negative");
        fixture.authority().execution = execution(newVersion());
        List<HistoryEvent> exact = append(validHistory(), exactOptionsUpdatedEvent(newVersion()));
        List<List<HistoryEvent>> invalid =
                List.of(
                        append(
                                validHistory(),
                                exactOptionsUpdatedEvent(newVersion())
                                        .toBuilder()
                                        .setEventType(EVENT_TYPE_UNSPECIFIED)
                                        .build()),
                        append(
                                validHistory(),
                                exactOptionsUpdatedEvent(newVersion())
                                        .toBuilder()
                                        .setWorkerMayIgnore(false)
                                        .build()),
                        append(
                                validHistory(),
                                optionsUpdatedEvent(
                                        oldVersion(),
                                        HISTORY_TAIL + 1,
                                        true,
                                        "unexpected-request",
                                        false)),
                        append(exact, workflowTaskScheduled(8, CASE_CONTROL_TASK_QUEUE, 1)));

        RecordingExecutor executor = new RecordingExecutor(fixture.authority(), true);
        for (List<HistoryEvent> history : invalid) {
            fixture.authority().history = history;
            assertThatThrownBy(
                            () ->
                                    ExactCaseProcessWorkflowRePinRecoveryMain.operate(
                                            fixture.request().forApply("c".repeat(64)),
                                            fixture.authority(),
                                            () -> WORKFLOW_BYTES.clone(),
                                            executor))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(executor.commands).isEmpty();
    }

    @Test
    void applyRejectsWrongAuthorityAndPrepareToApplyDriftBeforeRpc() throws Exception {
        Fixture fixture = fixture("apply-drift");
        RecoveryPlan prepared =
                ExactCaseProcessWorkflowRePinRecoveryMain.prepare(
                        fixture.request(), fixture.authority(), () -> WORKFLOW_BYTES.clone());
        RecordingExecutor executor = new RecordingExecutor(fixture.authority(), true);

        assertThatThrownBy(
                        () ->
                                ExactCaseProcessWorkflowRePinRecoveryMain.operate(
                                        fixture.request().forApply("d".repeat(64)),
                                        fixture.authority(),
                                        () -> WORKFLOW_BYTES.clone(),
                                        executor))
                .isInstanceOf(IllegalStateException.class);

        fixture.authority().driftHistoryOnDescribeCall = fixture.authority().describeCalls + 2;
        assertThatThrownBy(
                        () ->
                                ExactCaseProcessWorkflowRePinRecoveryMain.operate(
                                        fixture.request().forApply(prepared.authoritySha256()),
                                        fixture.authority(),
                                        () -> WORKFLOW_BYTES.clone(),
                                        executor))
                .isInstanceOf(IllegalStateException.class);
        assertThat(executor.commands).isEmpty();
    }

    @Test
    void artifactMarkersAndThreeWayWorkflowBytesAreFailClosed() throws Exception {
        Fixture wrongMarker = fixture("wrong-marker");
        Files.writeString(
                wrongMarker.request().newClasses().resolve(WORKTREE_MARKER),
                "e".repeat(64),
                StandardCharsets.US_ASCII);
        assertPrepareRejected(wrongMarker);

        Fixture wrongClass = fixture("wrong-class");
        Files.write(
                wrongClass.request().newClasses().resolve(CASE_PROCESS_WORKFLOW_CLASS),
                "different-workflow".getBytes(StandardCharsets.UTF_8));
        assertPrepareRejected(wrongClass);
    }

    @Test
    void versionedTargetTypedDispatchTransitionBindsDistinctWorkflowClasses() throws Exception {
        Fixture fixture = fixture("versioned-target-dispatch");
        byte[] oldBytes = "case-process-before-target-dispatch-recovery".getBytes(StandardCharsets.UTF_8);
        byte[] newBytes =
                ("case-process-after:"
                                + ExactCaseProcessWorkflowRePinRecoveryMain
                                        .TARGET_TYPED_DISPATCH_RECOVERY_CHANGE_ID)
                        .getBytes(StandardCharsets.UTF_8);
        Files.write(
                fixture.request().oldRetainedClasses().resolve(CASE_PROCESS_WORKFLOW_CLASS),
                oldBytes);
        Files.write(fixture.request().newClasses().resolve(CASE_PROCESS_WORKFLOW_CLASS), newBytes);
        RecoveryRequest transition =
                withWorkflowClassAuthority(
                        fixture.request(),
                        WorkflowClassAuthority.TARGET_TYPED_DISPATCH_RECOVERY_V1);

        RecoveryPlan prepared =
                ExactCaseProcessWorkflowRePinRecoveryMain.prepare(
                        transition, fixture.authority(), () -> newBytes.clone());

        assertThat(prepared.artifacts().workflowClassAuthority())
                .isEqualTo(WorkflowClassAuthority.TARGET_TYPED_DISPATCH_RECOVERY_V1);
        assertThat(prepared.artifacts().oldWorkflowClassSha256())
                .isNotEqualTo(prepared.artifacts().newWorkflowClassSha256());
        assertThat(prepared.authoritySha256()).matches("[0-9a-f]{64}");

        Files.write(
                fixture.request().oldRetainedClasses().resolve(CASE_PROCESS_WORKFLOW_CLASS),
                newBytes);
        assertThatThrownBy(
                        () ->
                                ExactCaseProcessWorkflowRePinRecoveryMain.prepare(
                                        transition,
                                        fixture.authority(),
                                        () -> newBytes.clone()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void versionedIntakeCurrentRunRecoveryBindsWorkflowAndDispatcherClasses() throws Exception {
        Fixture fixture = fixture("versioned-intake-current-run-dispatch");
        byte[] oldWorkflowBytes =
                "case-process-before-intake-current-run-dispatch".getBytes(StandardCharsets.UTF_8);
        byte[] newWorkflowBytes =
                ("case-process-after:"
                                + ExactCaseProcessWorkflowRePinRecoveryMain
                                        .TARGET_INTAKE_CURRENT_RUN_DISPATCH_RECOVERY_SIGNAL)
                        .getBytes(StandardCharsets.UTF_8);
        byte[] oldDispatcherBytes =
                "target-dispatcher-before-intake-current-run-dispatch"
                        .getBytes(StandardCharsets.UTF_8);
        byte[] newDispatcherBytes =
                ("target-dispatcher-after:"
                                + ExactCaseProcessWorkflowRePinRecoveryMain
                                        .TARGET_INTAKE_CURRENT_RUN_DISPATCH_RECOVERY_HOOK)
                        .getBytes(StandardCharsets.UTF_8);
        Files.write(
                fixture.request().oldRetainedClasses().resolve(CASE_PROCESS_WORKFLOW_CLASS),
                oldWorkflowBytes);
        Files.write(
                fixture.request().newClasses().resolve(CASE_PROCESS_WORKFLOW_CLASS),
                newWorkflowBytes);
        Path oldDispatcher =
                fixture.request().oldRetainedClasses().resolve(TARGET_TYPED_ROOM_DISPATCHER_CLASS);
        Path newDispatcher =
                fixture.request().newClasses().resolve(TARGET_TYPED_ROOM_DISPATCHER_CLASS);
        Files.createDirectories(oldDispatcher.getParent());
        Files.createDirectories(newDispatcher.getParent());
        Files.write(oldDispatcher, oldDispatcherBytes);
        Files.write(newDispatcher, newDispatcherBytes);
        RecoveryRequest transition =
                withWorkflowClassAuthority(
                        fixture.request(),
                        WorkflowClassAuthority.TARGET_INTAKE_CURRENT_RUN_DISPATCH_RECOVERY_V1);
        fixture.authority().version =
                new VersionAuthority(
                        newVersion(),
                        WORKER_DEPLOYMENT_VERSION_STATUS_INACTIVE,
                        INTAKE_CONTINUATION_MEMBERSHIP);

        RecoveryPlan prepared =
                ExactCaseProcessWorkflowRePinRecoveryMain.prepare(
                        transition, fixture.authority(), () -> newWorkflowBytes.clone());

        assertThat(prepared.artifacts().workflowClassAuthority())
                .isEqualTo(
                        WorkflowClassAuthority.TARGET_INTAKE_CURRENT_RUN_DISPATCH_RECOVERY_V1);
        assertThat(prepared.artifacts().oldDispatcherClassSha256())
                .isNotEqualTo(prepared.artifacts().newDispatcherClassSha256());
        assertThat(prepared.newVersionAuthority().membership())
                .isEqualTo(INTAKE_CONTINUATION_MEMBERSHIP);
        assertThat(prepared.authoritySha256()).matches("[0-9a-f]{64}");

        fixture.authority().version =
                new VersionAuthority(
                        newVersion(),
                        WORKER_DEPLOYMENT_VERSION_STATUS_INACTIVE,
                        EXACT_MEMBERSHIP);
        assertThatThrownBy(
                        () ->
                                ExactCaseProcessWorkflowRePinRecoveryMain.prepare(
                                        transition,
                                        fixture.authority(),
                                        () -> newWorkflowBytes.clone()))
                .isInstanceOf(IllegalStateException.class);

        fixture.authority().version =
                new VersionAuthority(
                        newVersion(),
                        WORKER_DEPLOYMENT_VERSION_STATUS_INACTIVE,
                        INTAKE_CONTINUATION_MEMBERSHIP);
        Files.write(oldDispatcher, newDispatcherBytes);
        assertThatThrownBy(
                        () ->
                                ExactCaseProcessWorkflowRePinRecoveryMain.prepare(
                                        transition,
                                        fixture.authority(),
                                        () -> newWorkflowBytes.clone()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void versionedIntakeTerminalV3AckTransitionBindsOnlyTheExactNewMarker()
            throws Exception {
        Fixture fixture = fixture("versioned-intake-terminal-v3-ack");
        byte[] oldBytes = "case-process-before-intake-terminal-v3-ack".getBytes(StandardCharsets.UTF_8);
        byte[] newBytes =
                ("case-process-after:"
                                + ExactCaseProcessWorkflowRePinRecoveryMain
                                        .TARGET_INTAKE_TERMINAL_V3_ACK_RECOVERY_CHANGE_ID)
                        .getBytes(StandardCharsets.UTF_8);
        Files.write(
                fixture.request().oldRetainedClasses().resolve(CASE_PROCESS_WORKFLOW_CLASS),
                oldBytes);
        Files.write(fixture.request().newClasses().resolve(CASE_PROCESS_WORKFLOW_CLASS), newBytes);
        RecoveryRequest transition =
                withWorkflowClassAuthority(
                        fixture.request(),
                        WorkflowClassAuthority.TARGET_INTAKE_TERMINAL_V3_ACK_RECOVERY_V1);

        RecoveryPlan prepared =
                ExactCaseProcessWorkflowRePinRecoveryMain.prepare(
                        transition, fixture.authority(), () -> newBytes.clone());

        assertThat(prepared.artifacts().workflowClassAuthority())
                .isEqualTo(
                        WorkflowClassAuthority.TARGET_INTAKE_TERMINAL_V3_ACK_RECOVERY_V1);
        assertThat(prepared.artifacts().oldWorkflowClassSha256())
                .isNotEqualTo(prepared.artifacts().newWorkflowClassSha256());
        assertThat(prepared.authoritySha256()).matches("[0-9a-f]{64}");

        Files.write(
                fixture.request().oldRetainedClasses().resolve(CASE_PROCESS_WORKFLOW_CLASS),
                newBytes);
        assertThatThrownBy(
                        () ->
                                ExactCaseProcessWorkflowRePinRecoveryMain.prepare(
                                        transition,
                                        fixture.authority(),
                                        () -> newBytes.clone()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static RecoveryRequest withWorkflowClassAuthority(
            RecoveryRequest request, WorkflowClassAuthority authority) {
        return new RecoveryRequest(
                request.address(),
                request.namespace(),
                request.workflowId(),
                request.runId(),
                request.oldVersionAuthority(),
                authority,
                request.oldPinnedVersion(),
                request.oldBuildId(),
                request.oldRetainedClasses(),
                request.newDeploymentName(),
                request.newBuildId(),
                request.newPinnedVersion(),
                request.newClasses(),
                request.pendingWorkflowTaskState(),
                request.pendingWorkflowTaskScheduledEventId(),
                request.pendingWorkflowTaskAttempt(),
                request.pendingTimerStartedEventId(),
                request.pendingTimerId(),
                request.pendingTimerTimeoutSeconds(),
                request.expectedLastEventId(),
                request.historyMaxEvents(),
                request.mode(),
                request.expectedAuthoritySha256());
    }

    private static RecoveryRequest liveRequest(RecoveryRequest scheduled) {
        return new RecoveryRequest(
                scheduled.address(),
                scheduled.namespace(),
                scheduled.workflowId(),
                scheduled.runId(),
                OldVersionAuthority.PINNED_ASSIGNMENT,
                scheduled.workflowClassAuthority(),
                scheduled.oldPinnedVersion(),
                scheduled.oldBuildId(),
                scheduled.oldRetainedClasses(),
                scheduled.newDeploymentName(),
                scheduled.newBuildId(),
                scheduled.newPinnedVersion(),
                scheduled.newClasses(),
                PendingWorkflowTaskState.ABSENT,
                0,
                0,
                15,
                LIVE_TIMER_ID,
                TIMER_SECONDS,
                15,
                100,
                Mode.PREPARE,
                null);
    }

    private Fixture fixture(String name) throws Exception {
        Path oldClasses = classes(name + "-old", OLD_BINDING, WORKFLOW_BYTES);
        Path newClasses = classes(name + "-new", NEW_BINDING, WORKFLOW_BYTES);
        RecoveryRequest request =
                new RecoveryRequest(
                        "127.0.0.1:7233",
                        "uat-namespace",
                        WORKFLOW_ID,
                        RUN_ID,
                        OldVersionAuthority.EXPLICIT_OVERRIDE,
                        WorkflowClassAuthority.IDENTICAL,
                        OLD_PINNED_VERSION,
                        OLD_BUILD_ID,
                        oldClasses,
                        DEPLOYMENT,
                        NEW_BUILD_ID,
                        NEW_PINNED_VERSION,
                        newClasses,
                        PendingWorkflowTaskState.SCHEDULED,
                        PENDING_WFT_EVENT_ID,
                        PENDING_WFT_ATTEMPT,
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
        Files.createDirectories(root.resolve(CASE_PROCESS_WORKFLOW_CLASS).getParent());
        Files.writeString(root.resolve(WORKTREE_MARKER), binding, StandardCharsets.US_ASCII);
        Files.write(root.resolve(CASE_PROCESS_WORKFLOW_CLASS), workflowBytes);
        return root;
    }

    private static void assertPrepareRejected(Fixture fixture) {
        assertThatThrownBy(
                        () ->
                                ExactCaseProcessWorkflowRePinRecoveryMain.prepare(
                                        fixture.request(),
                                        fixture.authority(),
                                        () -> WORKFLOW_BYTES.clone()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String canonical(String deployment, String buildId) {
        return new io.temporal.common.WorkerDeploymentVersion(deployment, buildId)
                .toCanonicalString();
    }

    private static PinnedVersion oldVersion() {
        return new PinnedVersion(DEPLOYMENT, OLD_BUILD_ID, OLD_PINNED_VERSION);
    }

    private static PinnedVersion newVersion() {
        return new PinnedVersion(DEPLOYMENT, NEW_BUILD_ID, NEW_PINNED_VERSION);
    }

    private static PinnedVersion foreignVersion() {
        return new PinnedVersion(DEPLOYMENT, FOREIGN_BUILD_ID, FOREIGN_PINNED_VERSION);
    }

    private static DeploymentAuthority deployment(
            String current, String ramping, List<String> versions) {
        return new DeploymentAuthority(DEPLOYMENT, current, ramping, 0, 9, false, versions);
    }

    private static VersionAuthority version() {
        return new VersionAuthority(
                newVersion(), WORKER_DEPLOYMENT_VERSION_STATUS_INACTIVE, EXACT_MEMBERSHIP);
    }

    private static ExecutionAuthority execution(PinnedVersion version) {
        return execution(
                OldVersionAuthority.EXPLICIT_OVERRIDE,
                version,
                null,
                WORKFLOW_ID,
                CASE_WORKFLOW_TYPE,
                true,
                CASE_CONTROL_TASK_QUEUE,
                PendingWorkflowTaskState.SCHEDULED,
                PENDING_WFT_ATTEMPT,
                0,
                0);
    }

    private static ExecutionAuthority execution(
            PinnedVersion version, PendingWorkflowTaskState state, int attempt) {
        return execution(
                OldVersionAuthority.EXPLICIT_OVERRIDE,
                version,
                null,
                WORKFLOW_ID,
                CASE_WORKFLOW_TYPE,
                true,
                CASE_CONTROL_TASK_QUEUE,
                state,
                attempt,
                0,
                0);
    }

    private static ExecutionAuthority execution(
            OldVersionAuthority kind,
            PinnedVersion version,
            PendingWorkflowTaskState state,
            int attempt) {
        PinnedVersion effectiveAssignment =
                kind == OldVersionAuthority.PINNED_ASSIGNMENT ? version : null;
        return execution(kind, version, effectiveAssignment, state, attempt);
    }

    private static ExecutionAuthority execution(
            ExecutionVersionAuthority versionAuthority,
            PendingWorkflowTaskState state,
            int attempt) {
        return new ExecutionAuthority(
                WORKFLOW_ID,
                RUN_ID,
                CASE_WORKFLOW_TYPE,
                true,
                CASE_CONTROL_TASK_QUEUE,
                state,
                attempt,
                0,
                0,
                versionAuthority,
                false,
                false);
    }

    private static ExecutionAuthority execution(
            OldVersionAuthority kind,
            PinnedVersion version,
            PinnedVersion effectiveAssignment,
            PendingWorkflowTaskState state,
            int attempt) {
        return execution(
                kind,
                version,
                effectiveAssignment,
                WORKFLOW_ID,
                CASE_WORKFLOW_TYPE,
                true,
                CASE_CONTROL_TASK_QUEUE,
                state,
                attempt,
                0,
                0);
    }

    private static ExecutionAuthority execution(
            PinnedVersion version,
            String workflowId,
            String workflowType,
            boolean running,
            String queue,
            PendingWorkflowTaskState pendingState,
            int pendingAttempt,
            int pendingActivities,
            int pendingChildren) {
        return execution(
                OldVersionAuthority.EXPLICIT_OVERRIDE,
                version,
                null,
                workflowId,
                workflowType,
                running,
                queue,
                pendingState,
                pendingAttempt,
                pendingActivities,
                pendingChildren);
    }

    private static ExecutionAuthority execution(
            OldVersionAuthority kind,
            PinnedVersion version,
            PinnedVersion effectiveAssignment,
            String workflowId,
            String workflowType,
            boolean running,
            String queue,
            PendingWorkflowTaskState pendingState,
            int pendingAttempt,
            int pendingActivities,
            int pendingChildren) {
        return new ExecutionAuthority(
                workflowId,
                RUN_ID,
                workflowType,
                running,
                queue,
                pendingState,
                pendingAttempt,
                pendingActivities,
                pendingChildren,
                new ExecutionVersionAuthority(kind, version, effectiveAssignment),
                false,
                false);
    }

    private static List<HistoryEvent> validHistory() {
        return List.of(
                HistoryEvent.newBuilder()
                        .setEventId(1)
                        .setEventType(EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
                        .setWorkflowExecutionStartedEventAttributes(
                                WorkflowExecutionStartedEventAttributes.newBuilder()
                                        .setWorkflowType(
                                                WorkflowType.newBuilder().setName(CASE_WORKFLOW_TYPE)))
                        .build(),
                workflowTaskScheduled(2, CASE_CONTROL_TASK_QUEUE, 1),
                workflowTaskStarted(3, 2),
                HistoryEvent.newBuilder()
                        .setEventId(4)
                        .setEventType(EVENT_TYPE_WORKFLOW_TASK_COMPLETED)
                        .setWorkflowTaskCompletedEventAttributes(
                                WorkflowTaskCompletedEventAttributes.newBuilder()
                                        .setScheduledEventId(2)
                                        .setStartedEventId(3))
                        .build(),
                timerStarted(TIMER_EVENT_ID, TIMER_ID, TIMER_SECONDS),
                workflowTaskScheduled(
                        PENDING_WFT_EVENT_ID, CASE_CONTROL_TASK_QUEUE, PENDING_WFT_ATTEMPT));
    }

    private static List<HistoryEvent> liveHistoryWithoutPendingWorkflowTask() {
        return List.of(
                HistoryEvent.newBuilder()
                        .setEventId(1)
                        .setEventType(EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
                        .setWorkflowExecutionStartedEventAttributes(
                                WorkflowExecutionStartedEventAttributes.newBuilder()
                                        .setWorkflowType(
                                                WorkflowType.newBuilder().setName(CASE_WORKFLOW_TYPE)))
                        .build(),
                workflowTaskScheduled(2, CASE_CONTROL_TASK_QUEUE, 1),
                workflowTaskStarted(3, 2),
                workflowTaskCompleted(4, 2, 3),
                workflowTaskScheduled(5, CASE_CONTROL_TASK_QUEUE, 1),
                workflowTaskStarted(6, 5),
                workflowTaskCompleted(7, 5, 6),
                workflowTaskScheduled(8, CASE_CONTROL_TASK_QUEUE, 1),
                workflowTaskStarted(9, 8),
                workflowTaskCompleted(10, 8, 9),
                workflowTaskScheduled(11, CASE_CONTROL_TASK_QUEUE, 1),
                workflowTaskStarted(12, 11),
                workflowTaskCompleted(13, 11, 12),
                HistoryEvent.newBuilder()
                        .setEventId(14)
                        .setEventType(EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED)
                        .setWorkflowExecutionSignaledEventAttributes(
                                WorkflowExecutionSignaledEventAttributes.newBuilder()
                                        .setSignalName("domainEventCommitted"))
                        .build(),
                timerStarted(15, LIVE_TIMER_ID, TIMER_SECONDS));
    }

    private static HistoryEvent workflowTaskCompleted(
            long eventId, long scheduledEventId, long startedEventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setEventType(EVENT_TYPE_WORKFLOW_TASK_COMPLETED)
                .setWorkflowTaskCompletedEventAttributes(
                        WorkflowTaskCompletedEventAttributes.newBuilder()
                                .setScheduledEventId(scheduledEventId)
                                .setStartedEventId(startedEventId))
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

    private static HistoryEvent workflowTaskStarted(long eventId, long scheduledEventId) {
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setEventType(EVENT_TYPE_WORKFLOW_TASK_STARTED)
                .setWorkflowTaskStartedEventAttributes(
                        WorkflowTaskStartedEventAttributes.newBuilder()
                                .setScheduledEventId(scheduledEventId))
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
                                .setBehavior(
                                        VersioningOverride.PinnedOverrideBehavior
                                                .PINNED_OVERRIDE_BEHAVIOR_PINNED)
                                .setVersion(
                                        WorkerDeploymentVersion.newBuilder()
                                                .setDeploymentName(version.deploymentName())
                                                .setBuildId(version.buildId())))
                .build();
    }

    private static VersioningOverride dualOverride(PinnedVersion version) {
        return topLevelOverride(version).toBuilder()
                .setPinned(nestedOverride(version).getPinned())
                .build();
    }

    private static WorkflowExecutionVersioningInfo assignmentInfo(PinnedVersion version) {
        return WorkflowExecutionVersioningInfo.newBuilder()
                .setBehavior(VERSIONING_BEHAVIOR_PINNED)
                .setVersion(version.canonicalVersion())
                .setDeploymentVersion(
                        WorkerDeploymentVersion.newBuilder()
                                .setDeploymentName(version.deploymentName())
                                .setBuildId(version.buildId()))
                .build();
    }

    private static HistoryEvent exactOptionsUpdatedEvent(PinnedVersion version) {
        return exactOptionsUpdatedEvent(version, HISTORY_TAIL + 1);
    }

    private static HistoryEvent exactOptionsUpdatedEvent(PinnedVersion version, long eventId) {
        return optionsUpdatedEvent(version, eventId, true, "", false);
    }

    private static HistoryEvent exactNestedOptionsUpdatedEvent(
            PinnedVersion version, long eventId) {
        return optionsUpdatedEvent(nestedOverride(version), eventId, true, "", false);
    }

    private static HistoryEvent optionsUpdatedEvent(
            PinnedVersion version,
            long eventId,
            boolean workerMayIgnore,
            String attachedRequestId,
            boolean priority) {
        return optionsUpdatedEvent(
                topLevelOverride(version),
                eventId,
                workerMayIgnore,
                attachedRequestId,
                priority);
    }

    private static HistoryEvent optionsUpdatedEvent(
            VersioningOverride override,
            long eventId,
            boolean workerMayIgnore,
            String attachedRequestId,
            boolean priority) {
        WorkflowExecutionOptionsUpdatedEventAttributes.Builder attributes =
                WorkflowExecutionOptionsUpdatedEventAttributes.newBuilder()
                        .setVersioningOverride(override)
                        .setUnsetVersioningOverride(false)
                        .setAttachedRequestId(attachedRequestId);
        if (priority) {
            attributes.setPriority(io.temporal.api.common.v1.Priority.getDefaultInstance());
        }
        return HistoryEvent.newBuilder()
                .setEventId(eventId)
                .setEventType(EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED)
                .setWorkerMayIgnore(workerMayIgnore)
                .setWorkflowExecutionOptionsUpdatedEventAttributes(attributes)
                .build();
    }

    private static List<HistoryEvent> append(
            List<HistoryEvent> source, HistoryEvent... suffix) {
        List<HistoryEvent> result = new ArrayList<>(source);
        result.addAll(List.of(suffix));
        return List.copyOf(result);
    }

    private static List<HistoryEvent> replace(
            List<HistoryEvent> source, int oneBasedIndex, HistoryEvent replacement) {
        List<HistoryEvent> result = new ArrayList<>(source);
        result.set(oneBasedIndex - 1, replacement);
        return List.copyOf(result);
    }

    private record Fixture(RecoveryRequest request, FakeAuthority authority) {}

    private static final class FakeAuthority implements TemporalAuthority {
        private ExecutionAuthority execution;
        private List<HistoryEvent> history;
        private DeploymentAuthority deployment;
        private VersionAuthority version;
        private int describeCalls;
        private int driftHistoryOnDescribeCall = -1;

        private FakeAuthority() {
            reset();
        }

        void reset() {
            execution = execution(oldVersion());
            history = validHistory();
            deployment =
                    deployment(
                            OLD_PINNED_VERSION,
                            "",
                            List.of(OLD_PINNED_VERSION, NEW_PINNED_VERSION));
            version = version();
        }

        @Override
        public String serverVersion() {
            return "1.29.7";
        }

        @Override
        public ExecutionAuthority describeExecution(RecoveryRequest request) {
            describeCalls++;
            if (describeCalls == driftHistoryOnDescribeCall) {
                history =
                        replace(
                                history,
                                5,
                                timerStarted(5, TIMER_ID, TIMER_SECONDS - 1));
            }
            return execution;
        }

        @Override
        public List<HistoryEvent> loadCompleteHistory(RecoveryRequest request) {
            return history;
        }

        @Override
        public DeploymentAuthority describeDeployment(String namespace, String deploymentName) {
            return deployment;
        }

        @Override
        public VersionAuthority describeVersion(String namespace, PinnedVersion requested) {
            return version;
        }
    }

    private static final class RecordingExecutor
            implements ExactCaseProcessWorkflowRePinRecoveryMain.RePinExecutor {
        private final FakeAuthority authority;
        private final boolean appendSuffix;
        private final PinnedVersion postEffectiveAssignment;
        private final boolean nestedSuffix;
        private final List<RePinCommand> commands = new ArrayList<>();

        private RecordingExecutor(FakeAuthority authority, boolean appendSuffix) {
            this(authority, appendSuffix, null, false);
        }

        private RecordingExecutor(
                FakeAuthority authority,
                boolean appendSuffix,
                PinnedVersion postEffectiveAssignment,
                boolean nestedSuffix) {
            this.authority = authority;
            this.appendSuffix = appendSuffix;
            this.postEffectiveAssignment = postEffectiveAssignment;
            this.nestedSuffix = nestedSuffix;
        }

        @Override
        public RePinOutcome repin(RePinCommand command) {
            commands.add(command);
            ExecutionAuthority before = authority.execution;
            authority.execution =
                    new ExecutionAuthority(
                            before.workflowId(),
                            before.runId(),
                            before.workflowType(),
                            before.running(),
                            before.taskQueue(),
                            before.pendingWorkflowTaskState(),
                            before.pendingWorkflowTaskAttempt(),
                            before.pendingActivities(),
                            before.pendingChildren(),
                            new ExecutionVersionAuthority(
                                    OldVersionAuthority.EXPLICIT_OVERRIDE,
                                    command.targetVersion(),
                                    postEffectiveAssignment),
                            before.deploymentTransition(),
                            before.versionTransition());
            if (appendSuffix) {
                long suffixEventId = authority.history.getLast().getEventId() + 1;
                authority.history =
                        append(
                                authority.history,
                                nestedSuffix
                                        ? exactNestedOptionsUpdatedEvent(
                                                command.targetVersion(), suffixEventId)
                                        : exactOptionsUpdatedEvent(
                                                command.targetVersion(), suffixEventId));
            }
            return new RePinOutcome(command.targetVersion());
        }
    }
}
