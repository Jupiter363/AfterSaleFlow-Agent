package com.example.dispute.workflow.recovery;

import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.CASE_CONTROL;
import static com.example.dispute.workflow.contract.v1.TemporalTaskQueues.ROOM_CONTROL;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED;
import static io.temporal.api.enums.v1.PendingWorkflowTaskState.PENDING_WORKFLOW_TASK_STATE_SCHEDULED;
import static io.temporal.api.enums.v1.RoutingConfigUpdateState.ROUTING_CONFIG_UPDATE_STATE_IN_PROGRESS;
import static io.temporal.api.enums.v1.TaskQueueKind.TASK_QUEUE_KIND_NORMAL;
import static io.temporal.api.enums.v1.TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY;
import static io.temporal.api.enums.v1.TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW;
import static io.temporal.api.enums.v1.VersioningBehavior.VERSIONING_BEHAVIOR_PINNED;
import static io.temporal.api.enums.v1.VersioningBehavior.VERSIONING_BEHAVIOR_UNSPECIFIED;
import static io.temporal.api.enums.v1.WorkerDeploymentVersionStatus.WORKER_DEPLOYMENT_VERSION_STATUS_INACTIVE;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;
import static io.temporal.api.workflow.v1.VersioningOverride.PinnedOverrideBehavior.PINNED_OVERRIDE_BEHAVIOR_PINNED;

import com.example.dispute.workflow.temporal.room.intake.IntakeRoomWorkflowImpl;
import com.example.dispute.workflow.temporal.room.intake.IntakeWorkflowProtocol;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.FieldMask;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.deployment.v1.RoutingConfig;
import io.temporal.api.deployment.v1.WorkerDeploymentInfo;
import io.temporal.api.deployment.v1.WorkerDeploymentVersionInfo;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.enums.v1.WorkerDeploymentVersionStatus;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionOptionsUpdatedEventAttributes;
import io.temporal.api.workflow.v1.PendingChildExecutionInfo;
import io.temporal.api.workflow.v1.VersioningOverride;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflow.v1.WorkflowExecutionOptions;
import io.temporal.api.workflow.v1.WorkflowExecutionVersioningInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentResponse;
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentVersionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentVersionResponse;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionOptionsRequest;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionOptionsResponse;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Re-pins one exact running IntakeRoom execution from one legacy Build-ID assignment to one
 * already-registered inactive Worker Deployment Version.
 *
 * <p>{@code PREPARE} is read-only and binds the execution, complete history prefix, both artifacts,
 * deployment routing, and exact task-queue membership. {@code APPLY} repeats that proof before one
 * execution-scoped {@code versioning_override} update. This operator never changes Current or
 * Ramping deployment routing and never starts a worker.
 */
public final class ExactIntakeRoomWorkflowRePinRecoveryMain {

    static final Path WORKTREE_MARKER =
            Path.of("META-INF", "after-sale-flow", "compiled-worktree.sha256");
    static final Path INTAKE_ROOM_WORKFLOW_CLASS =
            Path.of(
                    "com",
                    "example",
                    "dispute",
                    "workflow",
                    "temporal",
                    "room",
                    "intake",
                    "IntakeRoomWorkflowImpl.class");
    static final String UPDATE_MASK_PATH = "versioning_override";
    static final String UPDATE_IDENTITY = "exact-intake-room-repin-recovery.v1";
    static final String INTAKE_WORKFLOW_TYPE = IntakeWorkflowProtocol.WORKFLOW_TYPE;
    static final String ROOM_CONTROL_TASK_QUEUE = ROOM_CONTROL;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final ServerVersion MINIMUM_SERVER_VERSION = new ServerVersion(1, 27, 4);
    private static final int MAXIMUM_HISTORY_EVENTS = 5000;
    private static final int MAXIMUM_HISTORY_PAGES = 100;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CONTROL_BUILD_ID =
            Pattern.compile("[A-Za-z0-9._-]+-([0-9a-f]{64})-control");
    private static final Pattern SERVER_VERSION =
            Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$");
    /**
     * The isolated recovery version owns the parent CaseProcess Workflow and its Activity lane,
     * plus the Intake child Workflow lane. A ROOM_CONTROL Activity poller or any other queue is a
     * conflicting co-tenant capability and is rejected rather than ignored.
     */
    private static final Set<QueueMembership> REQUIRED_MEMBERSHIP =
            Set.of(
                    new QueueMembership(CASE_CONTROL, TASK_QUEUE_TYPE_WORKFLOW),
                    new QueueMembership(CASE_CONTROL, TASK_QUEUE_TYPE_ACTIVITY),
                    new QueueMembership(ROOM_CONTROL, TASK_QUEUE_TYPE_WORKFLOW));
    private static final Set<String> COMMON_ARGUMENTS =
            Set.of(
                    "address",
                    "namespace",
                    "workflow-id",
                    "run-id",
                    "old-version-authority",
                    "old-build-id",
                    "old-retained-classes",
                    "new-deployment-name",
                    "new-build-id",
                    "new-pinned-version",
                    "new-classes",
                    "pending-workflow-task-state",
                    "pending-workflow-task-scheduled-event-id",
                    "pending-workflow-task-attempt",
                    "pending-timer-started-event-id",
                    "pending-timer-id",
                    "pending-timer-timeout-seconds",
                    "expected-last-event-id",
                    "history-max-events",
                    "mode");
    private static final Set<String> APPLY_ARGUMENTS = Set.of("expected-authority-sha256");
    private static final Set<String> ALL_ARGUMENTS = allArguments();

    private ExactIntakeRoomWorkflowRePinRecoveryMain() {}

    public static void main(String[] args) {
        int exitCode = execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int execute(String[] args) {
        try {
            RecoveryRequest request = RecoveryRequest.parse(args);
            try (SdkSession session = SdkSession.open(request)) {
                OperationResult result =
                        operate(
                                request,
                                session,
                                ExactIntakeRoomWorkflowRePinRecoveryMain::loadedWorkflowClassBytes,
                                session);
                printSafe(result);
            }
            return 0;
        } catch (RuntimeException | IOException failure) {
            System.err.println("Exact IntakeRoom re-pin recovery failed closed.");
            return 2;
        }
    }

    static OperationResult operate(
            RecoveryRequest request,
            TemporalAuthority authority,
            ClassBytesSource currentWorkflowClass,
            RePinExecutor executor)
            throws IOException {
        Objects.requireNonNull(executor, "executor");
        RecoveryPlan prepared = prepare(request, authority, currentWorkflowClass);
        if (request.mode() == Mode.PREPARE) {
            return OperationResult.prepared(prepared);
        }
        require(
                prepared.authoritySha256().equals(request.expectedAuthoritySha256()),
                "re-pin authority changed after PREPARE");
        if (prepared.alreadyRepinned()) {
            return OperationResult.alreadyRepinned(prepared);
        }

        RecoveryPlan revalidated = prepare(request, authority, currentWorkflowClass);
        require(
                revalidated.authoritySha256().equals(request.expectedAuthoritySha256()),
                "re-pin authority drifted during APPLY revalidation");
        require(prepared.equals(revalidated), "re-pin plan drifted during APPLY revalidation");

        RePinOutcome outcome = executor.repin(revalidated.command());
        require(outcome != null, "re-pin response is missing");
        require(
                revalidated.newVersion().equals(outcome.version()),
                "re-pin response does not contain the exact target version");

        RecoveryPlan after = prepare(request, authority, currentWorkflowClass);
        require(after.alreadyRepinned(), "workflow did not retain the exact override after re-pin");
        require(
                after.authoritySha256().equals(request.expectedAuthoritySha256()),
                "re-pin authority changed after the update");
        return OperationResult.repinned(after);
    }

    static RecoveryPlan prepare(
            RecoveryRequest request,
            TemporalAuthority authority,
            ClassBytesSource currentWorkflowClass)
            throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(currentWorkflowClass, "currentWorkflowClass");
        validateRequest(request);

        ArtifactAuthority artifacts = validateArtifacts(request, currentWorkflowClass);
        String serverVersion = authority.serverVersion();
        requireServerVersion(serverVersion);
        PinnedVersion newVersion = requestedNewVersion(request);
        DeploymentAuthority newDeployment =
                authority.describeDeployment(request.namespace(), newVersion.deploymentName());
        VersionAuthority newVersionAuthority =
                authority.describeVersion(request.namespace(), newVersion);
        validateDeploymentAuthorities(newVersion, newDeployment, newVersionAuthority);

        ExecutionAuthority execution = authority.describeExecution(request);
        boolean alreadyRepinned = validateExecution(request, execution, newVersion);
        List<HistoryEvent> completeHistory = List.copyOf(authority.loadCompleteHistory(request));
        HistoryAuthority history =
                validateHistory(request, execution, completeHistory, alreadyRepinned, newVersion);

        RePinCommand command =
                new RePinCommand(
                        request.namespace(),
                        request.workflowId(),
                        request.runId(),
                        newVersion,
                        UPDATE_MASK_PATH,
                        UPDATE_IDENTITY);
        String authoritySha256 =
                authoritySha256(
                        request,
                        artifacts,
                        serverVersion,
                        execution,
                        history,
                        newVersion,
                        newDeployment,
                        newVersionAuthority);
        return new RecoveryPlan(
                request,
                artifacts,
                serverVersion,
                execution,
                history,
                newVersion,
                newDeployment,
                newVersionAuthority,
                alreadyRepinned,
                authoritySha256,
                command);
    }

    private static void validateRequest(RecoveryRequest request) {
        requireText(request.address(), "address", 512);
        requireText(request.namespace(), "namespace", 255);
        requireText(request.workflowId(), "workflowId", 255);
        requireCanonicalUuid(request.runId(), "runId");
        require(
                request.oldVersionAuthority() == OldVersionAuthority.LEGACY_BUILD_ID_STAMP,
                "old version authority must be exact legacy Build-ID stamp");
        requireText(request.oldBuildId(), "oldBuildId", 255);
        require(request.oldRetainedClasses() != null, "oldRetainedClasses is required");
        requireText(request.newDeploymentName(), "newDeploymentName", 255);
        requireText(request.newBuildId(), "newBuildId", 255);
        requireText(request.newPinnedVersion(), "newPinnedVersion", 512);
        require(request.newClasses() != null, "newClasses is required");
        require(
                request.pendingWorkflowTaskState() == PendingWorkflowTaskState.ABSENT
                        || request.pendingWorkflowTaskState()
                                == PendingWorkflowTaskState.SCHEDULED,
                "pending workflow-task state must be explicit ABSENT or SCHEDULED");
        if (request.pendingWorkflowTaskState() == PendingWorkflowTaskState.ABSENT) {
            require(
                    request.pendingWorkflowTaskScheduledEventId() == 0
                            && request.pendingWorkflowTaskAttempt() == 0,
                    "absent pending workflow task must bind zero event ID and attempt");
        } else {
            require(
                    request.pendingWorkflowTaskScheduledEventId() > 0,
                    "pending workflow-task scheduled event ID must be positive");
            require(
                    request.pendingWorkflowTaskAttempt() > 0,
                    "pending workflow-task attempt must be positive");
        }
        require(
                request.pendingTimerStartedEventId() > 0,
                "pending timer started event ID must be positive");
        requireText(request.pendingTimerId(), "pendingTimerId", 255);
        require(
                request.pendingTimerTimeoutSeconds() > 0,
                "pending timer timeout must be positive");
        require(request.expectedLastEventId() > 0, "expected last event ID must be positive");
        require(
                (request.pendingWorkflowTaskScheduledEventId() == 0
                                || request.pendingWorkflowTaskScheduledEventId()
                                        <= request.expectedLastEventId())
                        && request.pendingTimerStartedEventId() <= request.expectedLastEventId(),
                "pending authority is after the complete history tail");
        require(
                request.expectedLastEventId() < MAXIMUM_HISTORY_EVENTS
                        && request.historyMaxEvents() >= request.expectedLastEventId() + 1
                        && request.historyMaxEvents() <= MAXIMUM_HISTORY_EVENTS,
                "history maximum is outside the bounded range");
        Objects.requireNonNull(request.mode(), "mode");
        buildWorktreeBinding(request.oldBuildId(), "oldBuildId");
        buildWorktreeBinding(request.newBuildId(), "newBuildId");
        requestedNewVersion(request);
        if (request.mode() == Mode.PREPARE) {
            require(
                    request.expectedAuthoritySha256() == null,
                    "PREPARE must not carry an APPLY authority hash");
        } else {
            requireSha256(request.expectedAuthoritySha256(), "expected authority hash");
        }
    }

    private static ArtifactAuthority validateArtifacts(
            RecoveryRequest request, ClassBytesSource currentWorkflowClass) throws IOException {
        Path oldRoot = request.oldRetainedClasses().toRealPath();
        Path newRoot = request.newClasses().toRealPath();
        require(Files.isDirectory(oldRoot), "old retained classes root is not a directory");
        require(Files.isDirectory(newRoot), "new classes root is not a directory");
        require(!oldRoot.equals(newRoot), "old and new classes roots must be distinct");

        String oldBinding = readMarker(oldRoot, "old retained");
        String newBinding = readMarker(newRoot, "new");
        require(
                oldBinding.equals(buildWorktreeBinding(request.oldBuildId(), "oldBuildId")),
                "old build ID does not bind the old retained marker");
        require(
                newBinding.equals(buildWorktreeBinding(request.newBuildId(), "newBuildId")),
                "new build ID does not bind the new marker");

        byte[] oldBytes = Files.readAllBytes(oldRoot.resolve(INTAKE_ROOM_WORKFLOW_CLASS));
        byte[] newBytes = Files.readAllBytes(newRoot.resolve(INTAKE_ROOM_WORKFLOW_CLASS));
        byte[] currentBytes = currentWorkflowClass.read();
        require(
                oldBytes.length > 0 && newBytes.length > 0 && currentBytes.length > 0,
                "IntakeRoom workflow class bytes are empty");
        String workflowClassSha256 = sha256(oldBytes);
        require(
                workflowClassSha256.equals(sha256(newBytes))
                        && workflowClassSha256.equals(sha256(currentBytes)),
                "old, new, and loaded IntakeRoom workflow classes differ");
        return new ArtifactAuthority(
                oldRoot, newRoot, oldBinding, newBinding, workflowClassSha256);
    }

    private static String readMarker(Path root, String source) throws IOException {
        String marker =
                Files.readString(root.resolve(WORKTREE_MARKER), StandardCharsets.US_ASCII).strip();
        require(SHA256.matcher(marker).matches(), source + " worktree marker is invalid");
        return marker;
    }

    private static void validateDeploymentAuthorities(
            PinnedVersion newVersion,
            DeploymentAuthority newDeployment,
            VersionAuthority newVersionAuthority) {
        require(newDeployment != null, "new deployment authority is missing");
        require(newVersionAuthority != null, "new version authority is missing");
        require(
                newVersion.deploymentName().equals(newDeployment.deploymentName()),
                "new deployment identity drifted");
        require(
                !newVersion.canonicalVersion().equals(newDeployment.currentVersion())
                        && !newVersion.canonicalVersion().equals(newDeployment.rampingVersion()),
                "new recovery version is Current or Ramping");
        require(
                !newDeployment.routingUpdateInProgress(),
                "deployment routing update is in progress");
        require(
                newDeployment.versionSummaries().stream()
                                .filter(newVersion.canonicalVersion()::equals)
                                .count()
                        == 1,
                "new version is not uniquely registered in its deployment");
        require(
                newVersion.equals(newVersionAuthority.version()),
                "new version description identity drifted");
        require(
                newVersionAuthority.status() == WORKER_DEPLOYMENT_VERSION_STATUS_INACTIVE,
                "new version is not inactive");
        require(
                newVersionAuthority.membership().equals(REQUIRED_MEMBERSHIP),
                "new version task-queue membership is not exact case-control Workflow+Activity plus room-control Workflow");
    }

    private static boolean validateExecution(
            RecoveryRequest request,
            ExecutionAuthority execution,
            PinnedVersion newVersion) {
        require(execution != null, "workflow execution authority is missing");
        require(
                request.workflowId().equals(execution.workflowId())
                        && request.runId().equals(execution.runId()),
                "workflow execution identity drifted");
        require(
                INTAKE_WORKFLOW_TYPE.equals(execution.workflowType()),
                "workflow type is not IntakeRoomWorkflow");
        require(execution.running(), "workflow execution is not running");
        require(
                ROOM_CONTROL_TASK_QUEUE.equals(execution.taskQueue()),
                "workflow task queue is not room-control");
        require(
                execution.pendingWorkflowTaskState() == request.pendingWorkflowTaskState()
                        && execution.pendingWorkflowTaskAttempt()
                                == request.pendingWorkflowTaskAttempt(),
                "workflow pending task state or attempt drifted");
        require(execution.pendingActivities() == 0, "workflow has pending activities");
        require(execution.pendingChildren() == 0, "workflow has pending children");
        require(
                !execution.deploymentTransition() && !execution.versionTransition(),
                "workflow has an active deployment version transition");
        require(
                execution.assignedBuildId().isBlank()
                        || request.oldBuildId().equals(execution.assignedBuildId()),
                "workflow assigned Build-ID representation conflicts with legacy authority");
        require(
                execution.mostRecentBuildId().isBlank()
                        ? !execution.versioned()
                        : execution.versioned()
                                && request.oldBuildId().equals(execution.mostRecentBuildId()),
                "workflow Describe legacy Build-ID stamp authority drifted");
        Set<String> legacyBuildIds = legacySearchBuildIds(request);
        require(
                !execution.mostRecentBuildId().isBlank()
                        || !execution.assignedBuildId().isBlank()
                        || !execution.searchBuildIds().isEmpty(),
                "workflow has no Describe or BuildIds cross-check for the legacy stamp");
        if (execution.explicitOverride() == null) {
            require(
                    execution.searchBuildIds().isEmpty()
                            || legacyBuildIds.containsAll(execution.searchBuildIds()),
                    "workflow legacy BuildIds search authority drifted");
            require(
                    execution.oldVersionAuthority() == request.oldVersionAuthority(),
                    "workflow old version authority discriminator drifted");
            require(
                    execution.effectiveAssignment() == null,
                    "legacy workflow unexpectedly has a deployment assignment");
            return false;
        }
        require(
                execution.explicitOverride().equals(newVersion),
                "workflow has a conflicting versioning override");
        String pinnedTargetBuildId =
                "pinned:" + newVersion.deploymentName() + ":" + newVersion.buildId();
        Set<String> postUpdateBuildIds = new HashSet<>(legacyBuildIds);
        postUpdateBuildIds.add(pinnedTargetBuildId);
        require(
                execution.searchBuildIds().isEmpty()
                        || (postUpdateBuildIds.containsAll(execution.searchBuildIds())
                                && execution.searchBuildIds().contains(pinnedTargetBuildId)),
                "workflow post-update BuildIds search authority drifted");
        require(
                execution.effectiveAssignment() == null
                        || execution.effectiveAssignment().equals(newVersion),
                "workflow post-update effective assignment conflicts with target version");
        return true;
    }

    private static Set<String> legacySearchBuildIds(RecoveryRequest request) {
        return Set.of(
                "versioned:" + request.oldBuildId(),
                "assigned:" + request.oldBuildId());
    }

    private static HistoryAuthority validateHistory(
            RecoveryRequest request,
            ExecutionAuthority execution,
            List<HistoryEvent> completeHistory,
            boolean alreadyRepinned,
            PinnedVersion newVersion) {
        require(completeHistory != null, "complete history is missing");
        int prefixCount = Math.toIntExact(request.expectedLastEventId());
        int expectedCount = prefixCount + (alreadyRepinned ? 1 : 0);
        require(
                completeHistory.size() == expectedCount
                        && !completeHistory.isEmpty()
                        && completeHistory.size() <= request.historyMaxEvents(),
                "complete history size is invalid");
        require(
                completeHistory.getFirst().getEventId() == 1
                        && completeHistory
                                .getFirst()
                                .hasWorkflowExecutionStartedEventAttributes(),
                "complete history does not start at workflow execution event 1");
        long nextEventId = 1;
        for (HistoryEvent event : completeHistory) {
            require(event.getEventId() == nextEventId, "complete history is not contiguous");
            nextEventId++;
        }
        if (alreadyRepinned) {
            validateReplaySuffix(completeHistory.getLast(), request, newVersion);
        }

        List<HistoryEvent> prefix = completeHistory.subList(0, prefixCount);
        Map<Long, WorkflowTaskState> pendingWorkflowTasks = new LinkedHashMap<>();
        Map<Long, ActivityState> pendingActivities = new LinkedHashMap<>();
        Map<Long, ChildState> pendingChildren = new LinkedHashMap<>();
        Map<Long, TimerAuthority> pendingTimers = new LinkedHashMap<>();
        for (HistoryEvent event : prefix) {
            applyWorkflowTaskEvent(event, pendingWorkflowTasks);
            applyActivityEvent(event, pendingActivities);
            applyChildEvent(event, pendingChildren);
            applyTimerEvent(event, pendingTimers);
        }

        WorkflowTaskState pendingWorkflowTask;
        if (request.pendingWorkflowTaskState() == PendingWorkflowTaskState.ABSENT) {
            require(pendingWorkflowTasks.isEmpty(), "history has a pending workflow task");
            pendingWorkflowTask = WorkflowTaskState.absent();
        } else {
            require(
                    pendingWorkflowTasks.size() == 1,
                    "history does not have exactly one pending workflow task");
            pendingWorkflowTask = pendingWorkflowTasks.values().iterator().next();
            require(
                    pendingWorkflowTask.state() == PendingWorkflowTaskState.SCHEDULED
                            && pendingWorkflowTask.scheduledEventId()
                                    == request.pendingWorkflowTaskScheduledEventId()
                            && !pendingWorkflowTask.started()
                            && pendingWorkflowTask.attempt()
                                    == request.pendingWorkflowTaskAttempt()
                            && pendingWorkflowTask.normal()
                            && ROOM_CONTROL_TASK_QUEUE.equals(pendingWorkflowTask.taskQueue()),
                    "pending workflow-task history authority drifted");
            require(
                    execution.pendingWorkflowTaskAttempt() == pendingWorkflowTask.attempt(),
                    "describe and history pending workflow-task attempts differ");
        }
        require(pendingActivities.isEmpty(), "history has pending activities");
        require(pendingChildren.isEmpty(), "history has pending children");
        require(pendingTimers.size() == 1, "history does not have exactly one pending timer");
        TimerAuthority timer = pendingTimers.values().iterator().next();
        require(
                timer.startedEventId() == request.pendingTimerStartedEventId()
                        && timer.timerId().equals(request.pendingTimerId())
                        && timer.timeoutSeconds() == request.pendingTimerTimeoutSeconds(),
                "pending timer history authority drifted");

        HistoryEvent lastCompletedWorkflowTask = null;
        for (HistoryEvent event : prefix) {
            if (event.hasWorkflowTaskCompletedEventAttributes()) {
                lastCompletedWorkflowTask = event;
            }
        }
        require(
                lastCompletedWorkflowTask != null,
                "history has no completed workflow-task worker stamp");
        var completedAttributes =
                lastCompletedWorkflowTask.getWorkflowTaskCompletedEventAttributes();
        require(
                completedAttributes.hasWorkerVersion()
                        && completedAttributes.getWorkerVersion().getUseVersioning()
                        && request.oldBuildId()
                                .equals(completedAttributes.getWorkerVersion().getBuildId())
                        && completedAttributes.getVersioningBehavior()
                                == VERSIONING_BEHAVIOR_UNSPECIFIED
                        && completedAttributes.getWorkerDeploymentVersion().isBlank()
                        && !completedAttributes.hasDeploymentVersion(),
                "last completed workflow-task legacy worker stamp authority drifted");
        LegacyWorkerStampAuthority workerStamp =
                new LegacyWorkerStampAuthority(
                        lastCompletedWorkflowTask.getEventId(),
                        completedAttributes.getWorkerVersion().getBuildId());

        return new HistoryAuthority(
                prefix.size(),
                request.expectedLastEventId(),
                historySha256(prefix),
                pendingWorkflowTask,
                timer,
                workerStamp,
                pendingActivities.size(),
                pendingChildren.size());
    }

    private static void validateReplaySuffix(
            HistoryEvent event, RecoveryRequest request, PinnedVersion newVersion) {
        require(
                event.getEventId() == request.expectedLastEventId() + 1,
                "re-pin history suffix event ID is invalid");
        require(
                event.getEventType() == EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED,
                "re-pin history suffix event type is invalid");
        require(event.getWorkerMayIgnore(), "re-pin history suffix is not worker-may-ignore");
        require(
                event.hasWorkflowExecutionOptionsUpdatedEventAttributes(),
                "re-pin history suffix has no options-updated attributes");
        WorkflowExecutionOptionsUpdatedEventAttributes attributes =
                event.getWorkflowExecutionOptionsUpdatedEventAttributes();
        require(
                attributes.hasVersioningOverride()
                        && !attributes.getUnsetVersioningOverride()
                        && attributes.getAttachedRequestId().isBlank()
                        && attributes.getAttachedCompletionCallbacksCount() == 0
                        && attributes.getIdentity().isBlank()
                        && !attributes.hasPriority(),
                "re-pin history suffix contains unsupported option changes");
        require(
                newVersion.equals(
                        parseServerPersistedPinnedOverride(
                                attributes.getVersioningOverride(),
                                "re-pin history versioning override")),
                "re-pin history suffix contains a different override");
    }

    private static void applyWorkflowTaskEvent(
            HistoryEvent event, Map<Long, WorkflowTaskState> pending) {
        if (event.hasWorkflowTaskScheduledEventAttributes()) {
            var attributes = event.getWorkflowTaskScheduledEventAttributes();
            require(
                    attributes.hasTaskQueue() && attributes.getAttempt() > 0,
                    "workflow-task schedule authority is incomplete");
            WorkflowTaskState state =
                    new WorkflowTaskState(
                            PendingWorkflowTaskState.SCHEDULED,
                            event.getEventId(),
                            attributes.getTaskQueue().getName(),
                            attributes.getTaskQueue().getKind() == TASK_QUEUE_KIND_NORMAL,
                            attributes.getAttempt(),
                            false);
            require(
                    pending.putIfAbsent(event.getEventId(), state) == null,
                    "workflow-task schedule is duplicated");
            return;
        }
        if (event.hasWorkflowTaskStartedEventAttributes()) {
            long scheduledId = event.getWorkflowTaskStartedEventAttributes().getScheduledEventId();
            WorkflowTaskState state = pending.get(scheduledId);
            require(state != null && !state.started(), "workflow-task start has no schedule");
            pending.put(
                    scheduledId,
                    new WorkflowTaskState(
                            state.state(),
                            state.scheduledEventId(),
                            state.taskQueue(),
                            state.normal(),
                            state.attempt(),
                            true));
            return;
        }
        Long terminalScheduledId = workflowTaskTerminalScheduledId(event);
        if (terminalScheduledId != null) {
            require(
                    pending.remove(terminalScheduledId) != null,
                    "workflow-task terminal event has no schedule");
        }
    }

    private static Long workflowTaskTerminalScheduledId(HistoryEvent event) {
        if (event.hasWorkflowTaskCompletedEventAttributes()) {
            return event.getWorkflowTaskCompletedEventAttributes().getScheduledEventId();
        }
        if (event.hasWorkflowTaskFailedEventAttributes()) {
            return event.getWorkflowTaskFailedEventAttributes().getScheduledEventId();
        }
        if (event.hasWorkflowTaskTimedOutEventAttributes()) {
            return event.getWorkflowTaskTimedOutEventAttributes().getScheduledEventId();
        }
        return null;
    }

    private static void applyActivityEvent(
            HistoryEvent event, Map<Long, ActivityState> pending) {
        if (event.hasActivityTaskScheduledEventAttributes()) {
            var attributes = event.getActivityTaskScheduledEventAttributes();
            require(
                    attributes.hasActivityType()
                            && attributes.hasTaskQueue()
                            && !attributes.getActivityId().isBlank(),
                    "activity schedule authority is incomplete");
            require(
                    pending.putIfAbsent(
                                    event.getEventId(),
                                    new ActivityState(
                                            event.getEventId(),
                                            attributes.getActivityId(),
                                            attributes.getActivityType().getName(),
                                            attributes.getTaskQueue().getName()))
                            == null,
                    "activity schedule is duplicated");
            return;
        }
        Long terminalScheduledId = activityTerminalScheduledId(event);
        if (terminalScheduledId != null) {
            require(
                    pending.remove(terminalScheduledId) != null,
                    "activity terminal event has no schedule");
        }
    }

    private static Long activityTerminalScheduledId(HistoryEvent event) {
        if (event.hasActivityTaskCompletedEventAttributes()) {
            return event.getActivityTaskCompletedEventAttributes().getScheduledEventId();
        }
        if (event.hasActivityTaskFailedEventAttributes()) {
            return event.getActivityTaskFailedEventAttributes().getScheduledEventId();
        }
        if (event.hasActivityTaskTimedOutEventAttributes()) {
            return event.getActivityTaskTimedOutEventAttributes().getScheduledEventId();
        }
        if (event.hasActivityTaskCanceledEventAttributes()) {
            return event.getActivityTaskCanceledEventAttributes().getScheduledEventId();
        }
        return null;
    }

    private static void applyChildEvent(HistoryEvent event, Map<Long, ChildState> pending) {
        if (event.hasStartChildWorkflowExecutionInitiatedEventAttributes()) {
            var attributes = event.getStartChildWorkflowExecutionInitiatedEventAttributes();
            require(
                    attributes.hasWorkflowType()
                            && !attributes.getWorkflowId().isBlank()
                            && !attributes.getWorkflowType().getName().isBlank(),
                    "child initiation authority is incomplete");
            require(
                    pending.putIfAbsent(
                                    event.getEventId(),
                                    new ChildState(
                                            event.getEventId(),
                                            0,
                                            attributes.getWorkflowId(),
                                            "",
                                            attributes.getWorkflowType().getName()))
                            == null,
                    "child initiation is duplicated");
            return;
        }
        if (event.hasStartChildWorkflowExecutionFailedEventAttributes()) {
            long initiatedId =
                    event.getStartChildWorkflowExecutionFailedEventAttributes()
                            .getInitiatedEventId();
            require(pending.remove(initiatedId) != null, "child start failure has no initiation");
            return;
        }
        if (event.hasChildWorkflowExecutionStartedEventAttributes()) {
            var attributes = event.getChildWorkflowExecutionStartedEventAttributes();
            ChildState state = pending.get(attributes.getInitiatedEventId());
            require(
                    state != null
                            && state.startedEventId() == 0
                            && attributes.hasWorkflowExecution()
                            && attributes.hasWorkflowType()
                            && state.workflowId()
                                    .equals(attributes.getWorkflowExecution().getWorkflowId())
                            && state.workflowType().equals(attributes.getWorkflowType().getName()),
                    "child start has no exact initiation authority");
            requireCanonicalUuid(
                    attributes.getWorkflowExecution().getRunId(), "child history runId");
            pending.put(
                    attributes.getInitiatedEventId(),
                    new ChildState(
                            state.initiatedEventId(),
                            event.getEventId(),
                            state.workflowId(),
                            attributes.getWorkflowExecution().getRunId(),
                            state.workflowType()));
            return;
        }
        ChildTerminal terminal = childTerminal(event);
        if (terminal != null) {
            ChildState state = pending.remove(terminal.initiatedEventId());
            require(
                    state != null
                            && state.startedEventId() > 0
                            && state.startedEventId() == terminal.startedEventId(),
                    "child terminal event has no exact start authority");
        }
    }

    private static ChildTerminal childTerminal(HistoryEvent event) {
        if (event.hasChildWorkflowExecutionCompletedEventAttributes()) {
            var a = event.getChildWorkflowExecutionCompletedEventAttributes();
            return new ChildTerminal(a.getInitiatedEventId(), a.getStartedEventId());
        }
        if (event.hasChildWorkflowExecutionFailedEventAttributes()) {
            var a = event.getChildWorkflowExecutionFailedEventAttributes();
            return new ChildTerminal(a.getInitiatedEventId(), a.getStartedEventId());
        }
        if (event.hasChildWorkflowExecutionCanceledEventAttributes()) {
            var a = event.getChildWorkflowExecutionCanceledEventAttributes();
            return new ChildTerminal(a.getInitiatedEventId(), a.getStartedEventId());
        }
        if (event.hasChildWorkflowExecutionTimedOutEventAttributes()) {
            var a = event.getChildWorkflowExecutionTimedOutEventAttributes();
            return new ChildTerminal(a.getInitiatedEventId(), a.getStartedEventId());
        }
        if (event.hasChildWorkflowExecutionTerminatedEventAttributes()) {
            var a = event.getChildWorkflowExecutionTerminatedEventAttributes();
            return new ChildTerminal(a.getInitiatedEventId(), a.getStartedEventId());
        }
        return null;
    }

    private static void applyTimerEvent(
            HistoryEvent event, Map<Long, TimerAuthority> pending) {
        if (event.hasTimerStartedEventAttributes()) {
            var attributes = event.getTimerStartedEventAttributes();
            require(
                    !attributes.getTimerId().isBlank()
                            && attributes.hasStartToFireTimeout()
                            && attributes.getStartToFireTimeout().getSeconds() > 0
                            && attributes.getStartToFireTimeout().getNanos() == 0,
                    "timer start authority is incomplete");
            require(
                    pending.putIfAbsent(
                                    event.getEventId(),
                                    new TimerAuthority(
                                            event.getEventId(),
                                            attributes.getTimerId(),
                                            attributes.getStartToFireTimeout().getSeconds()))
                            == null,
                    "timer start is duplicated");
            return;
        }
        Long terminalStartedId = timerTerminalStartedId(event);
        if (terminalStartedId != null) {
            require(
                    pending.remove(terminalStartedId) != null,
                    "timer terminal event has no start authority");
        }
    }

    private static Long timerTerminalStartedId(HistoryEvent event) {
        if (event.hasTimerFiredEventAttributes()) {
            return event.getTimerFiredEventAttributes().getStartedEventId();
        }
        if (event.hasTimerCanceledEventAttributes()) {
            return event.getTimerCanceledEventAttributes().getStartedEventId();
        }
        return null;
    }

    private static String authoritySha256(
            RecoveryRequest request,
            ArtifactAuthority artifacts,
            String serverVersion,
            ExecutionAuthority execution,
            HistoryAuthority history,
            PinnedVersion newVersion,
            DeploymentAuthority newDeployment,
            VersionAuthority newVersionAuthority) {
        List<String> values = new ArrayList<>();
        values.add("exact-intake-room-repin-authority.v1");
        values.add(request.address());
        values.add(request.namespace());
        values.add(request.workflowId());
        values.add(request.runId());
        values.add(artifacts.oldRoot().toString());
        values.add(artifacts.newRoot().toString());
        values.add(artifacts.oldBinding());
        values.add(artifacts.newBinding());
        values.add(artifacts.workflowClassSha256());
        values.add(serverVersion);
        values.add(request.oldVersionAuthority().name());
        values.add(request.oldBuildId());
        values.add(newVersion.canonicalVersion());
        values.add(execution.workflowType());
        values.add(Boolean.toString(execution.running()));
        values.add(execution.taskQueue());
        values.add(execution.pendingWorkflowTaskState().name());
        values.add(Integer.toString(execution.pendingWorkflowTaskAttempt()));
        values.add(Integer.toString(execution.pendingActivities()));
        values.add(Integer.toString(execution.pendingChildren()));
        values.add(execution.assignedBuildId());
        values.add(execution.mostRecentBuildId());
        values.add(Boolean.toString(execution.versioned()));
        execution.searchBuildIds().stream()
                .filter(legacySearchBuildIds(request)::contains)
                .sorted()
                .forEach(values::add);
        values.add(Boolean.toString(execution.deploymentTransition()));
        values.add(Boolean.toString(execution.versionTransition()));
        values.add(Integer.toString(history.eventCount()));
        values.add(Long.toString(history.lastEventId()));
        values.add(history.historySha256());
        values.add(history.pendingWorkflowTask().state().name());
        values.add(Long.toString(history.pendingWorkflowTask().scheduledEventId()));
        values.add(Integer.toString(history.pendingWorkflowTask().attempt()));
        values.add(Long.toString(history.pendingTimer().startedEventId()));
        values.add(history.pendingTimer().timerId());
        values.add(Long.toString(history.pendingTimer().timeoutSeconds()));
        values.add(Long.toString(history.legacyWorkerStamp().completedEventId()));
        values.add(history.legacyWorkerStamp().buildId());
        addDeploymentAuthority(values, "new", newDeployment);
        values.add(newVersionAuthority.status().name());
        newVersionAuthority.membership().stream()
                .sorted(
                        Comparator.comparing(QueueMembership::taskQueue)
                                .thenComparing(value -> value.type().name()))
                .forEach(
                        membership -> {
                            values.add(membership.taskQueue());
                            values.add(membership.type().name());
                        });
        values.add(UPDATE_MASK_PATH);
        values.add(UPDATE_IDENTITY);
        return canonicalSha256(values);
    }

    private static void addDeploymentAuthority(
            List<String> values, String label, DeploymentAuthority deployment) {
        values.add(label);
        values.add(deployment.deploymentName());
        values.add(deployment.currentVersion());
        values.add(deployment.rampingVersion());
        values.add(Float.toString(deployment.rampingPercentage()));
        values.add(Long.toString(deployment.revisionNumber()));
        values.add(Boolean.toString(deployment.routingUpdateInProgress()));
        deployment.versionSummaries().stream().sorted().forEach(values::add);
    }

    private static PinnedVersion requestedNewVersion(RecoveryRequest request) {
        PinnedVersion parsed = parsePinnedVersion(request.newPinnedVersion(), "newPinnedVersion");
        require(
                parsed.deploymentName().equals(request.newDeploymentName())
                        && parsed.buildId().equals(request.newBuildId()),
                "new pinned version does not match deployment name and build ID");
        return parsed;
    }

    static PinnedVersion parseClientPinnedOverride(VersioningOverride override, String source) {
        require(override != null, source + " is missing");
        require(
                override.getBehavior() == VERSIONING_BEHAVIOR_PINNED,
                source + " does not have top-level PINNED behavior");
        require(!override.hasPinned(), source + " contains a legacy nested pinned override");
        return parsePinnedVersion(override.getPinnedVersion(), source + " pinnedVersion");
    }

    static PinnedVersion parseServerPersistedPinnedOverride(
            VersioningOverride override, String source) {
        require(override != null, source + " is missing");
        boolean hasTopLevel =
                override.getBehavior() != VERSIONING_BEHAVIOR_UNSPECIFIED
                        || !override.getPinnedVersion().isBlank();
        boolean hasNested = override.hasPinned();
        require(hasTopLevel || hasNested, source + " has no pinned version authority");

        PinnedVersion topLevel = null;
        if (hasTopLevel) {
            require(
                    override.getBehavior() == VERSIONING_BEHAVIOR_PINNED,
                    source + " top-level behavior is not PINNED");
            topLevel =
                    parsePinnedVersion(
                            override.getPinnedVersion(), source + " top-level pinnedVersion");
        }

        PinnedVersion nested = null;
        if (hasNested) {
            VersioningOverride.PinnedOverride persisted = override.getPinned();
            require(
                    persisted.getBehavior() == PINNED_OVERRIDE_BEHAVIOR_PINNED,
                    source + " nested behavior is not PINNED");
            require(persisted.hasVersion(), source + " nested version is missing");
            nested =
                    parsePinnedVersion(
                            canonicalVersion(persisted.getVersion(), source + " nested version"),
                            source + " nested version");
        }

        require(
                topLevel == null || nested == null || topLevel.equals(nested),
                source + " top-level and nested versions conflict");
        return topLevel != null ? topLevel : nested;
    }

    static Set<String> searchBuildIds(WorkflowExecutionInfo info) {
        require(info != null, "workflow execution info is missing");
        if (!info.hasSearchAttributes()
                || !info.getSearchAttributes().containsIndexedFields("BuildIds")) {
            return Set.of();
        }
        Object decoded =
                DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
                        info.getSearchAttributes().getIndexedFieldsOrThrow("BuildIds"),
                        List.class,
                        List.class);
        require(decoded instanceof List<?>, "workflow BuildIds search attribute is not a list");
        List<?> values = (List<?>) decoded;
        Set<String> result = new HashSet<>();
        for (Object value : values) {
            require(value instanceof String, "workflow BuildIds value is not text");
            String text = (String) value;
            requireText(text, "workflow BuildIds value", 512);
            require(result.add(text), "workflow BuildIds values are duplicated");
        }
        require(!result.isEmpty(), "workflow BuildIds search attribute is empty");
        return Set.copyOf(result);
    }

    static PinnedVersion parsePinnedVersion(String canonical, String source) {
        requireText(canonical, source, 512);
        require(canonical.equals(canonical.strip()), source + " is noncanonical");
        io.temporal.common.WorkerDeploymentVersion parsed;
        try {
            parsed = io.temporal.common.WorkerDeploymentVersion.fromCanonicalString(canonical);
        } catch (IllegalArgumentException invalidVersion) {
            throw new IllegalStateException(source + " is invalid", invalidVersion);
        }
        requireText(parsed.getDeploymentName(), source + " deploymentName", 255);
        requireText(parsed.getBuildId(), source + " buildId", 255);
        require(canonical.equals(parsed.toCanonicalString()), source + " is noncanonical");
        return new PinnedVersion(parsed.getDeploymentName(), parsed.getBuildId(), canonical);
    }

    private static PinnedVersion pinnedAssignmentVersion(WorkflowExecutionVersioningInfo info) {
        require(
                info.getBehavior() == VERSIONING_BEHAVIOR_PINNED,
                "workflow assignment behavior is not PINNED");
        require(
                info.hasDeploymentVersion(),
                "workflow pinned assignment has no structured deployment version");
        PinnedVersion structured =
                parsePinnedVersion(
                        canonicalVersion(
                                info.getDeploymentVersion(),
                                "workflow pinned assignment deployment version"),
                        "workflow pinned assignment deployment version");
        PinnedVersion compatible =
                parsePinnedVersion(info.getVersion(), "workflow pinned assignment version");
        require(
                structured.equals(compatible),
                "workflow pinned assignment representations conflict");
        return structured;
    }

    private static String canonicalVersion(
            io.temporal.api.deployment.v1.WorkerDeploymentVersion version, String source) {
        require(
                version != null
                        && !version.getDeploymentName().isBlank()
                        && !version.getBuildId().isBlank(),
                source + " is incomplete");
        try {
            return new io.temporal.common.WorkerDeploymentVersion(
                            version.getDeploymentName(), version.getBuildId())
                    .toCanonicalString();
        } catch (RuntimeException invalidVersion) {
            throw new IllegalStateException(source + " is invalid", invalidVersion);
        }
    }

    private static VersioningOverride versioningOverride(PinnedVersion version) {
        return VersioningOverride.newBuilder()
                .setBehavior(VERSIONING_BEHAVIOR_PINNED)
                .setPinnedVersion(version.canonicalVersion())
                .build();
    }

    static UpdateWorkflowExecutionOptionsRequest updateRequest(RePinCommand command) {
        Objects.requireNonNull(command, "command");
        VersioningOverride requestedOverride = versioningOverride(command.targetVersion());
        require(
                command.targetVersion()
                        .equals(
                                parseClientPinnedOverride(
                                        requestedOverride, "client re-pin request override")),
                "client re-pin request override drifted");
        return UpdateWorkflowExecutionOptionsRequest.newBuilder()
                .setNamespace(command.namespace())
                .setWorkflowExecution(
                        WorkflowExecution.newBuilder()
                                .setWorkflowId(command.workflowId())
                                .setRunId(command.runId()))
                .setWorkflowExecutionOptions(
                        WorkflowExecutionOptions.newBuilder()
                                .setVersioningOverride(requestedOverride))
                .setUpdateMask(FieldMask.newBuilder().addPaths(command.updateMaskPath()))
                .setIdentity(command.identity())
                .build();
    }

    private static void requireServerVersion(String version) {
        requireText(version, "serverVersion", 128);
        Matcher matcher = SERVER_VERSION.matcher(version);
        require(matcher.matches(), "Temporal server version is not strict semantic versioning");
        ServerVersion parsed;
        try {
            parsed =
                    new ServerVersion(
                            Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2)),
                            Integer.parseInt(matcher.group(3)));
        } catch (NumberFormatException invalidVersion) {
            throw new IllegalStateException("Temporal server version is invalid", invalidVersion);
        }
        require(
                parsed.compareTo(MINIMUM_SERVER_VERSION) >= 0,
                "Temporal server does not support execution versioning override recovery");
    }

    private static String historySha256(List<HistoryEvent> history) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (HistoryEvent event : history) {
                byte[] deterministic = deterministicBytes(event);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(deterministic.length).array());
                digest.update(deterministic);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } catch (IOException serializationFailure) {
            throw new IllegalStateException(
                    "history event could not be deterministically serialized", serializationFailure);
        }
    }

    private static byte[] deterministicBytes(HistoryEvent event) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(event.getSerializedSize());
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.useDeterministicSerialization();
        event.writeTo(output);
        output.flush();
        return bytes.toByteArray();
    }

    private static String canonicalSha256(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes =
                        Objects.requireNonNull(value, "canonical authority value")
                                .getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static byte[] loadedWorkflowClassBytes() throws IOException {
        try (InputStream input =
                IntakeRoomWorkflowImpl.class.getResourceAsStream("IntakeRoomWorkflowImpl.class")) {
            if (input == null) {
                throw new IOException("loaded IntakeRoom workflow class resource is missing");
            }
            return input.readAllBytes();
        }
    }

    private static String buildWorktreeBinding(String buildId, String source) {
        Matcher matcher = CONTROL_BUILD_ID.matcher(buildId);
        require(matcher.matches(), source + " control build ID shape is invalid");
        return matcher.group(1);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireCanonicalUuid(String value, String field) {
        requireText(value, field, 64);
        try {
            require(
                    UUID.fromString(value).toString().equals(value),
                    field + " must be a canonical UUID");
        } catch (IllegalArgumentException invalidUuid) {
            throw new IllegalStateException(field + " must be a canonical UUID", invalidUuid);
        }
    }

    private static void requireSha256(String value, String field) {
        require(value != null && SHA256.matcher(value).matches(), field + " is invalid");
    }

    private static void requireText(String value, String field, int maximumLength) {
        require(
                value != null && !value.isBlank() && value.length() <= maximumLength,
                field + " is required or too long");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static Set<String> allArguments() {
        Set<String> all = new HashSet<>(COMMON_ARGUMENTS);
        all.addAll(APPLY_ARGUMENTS);
        return Set.copyOf(all);
    }

    private static void printSafe(OperationResult result) {
        System.out.println(
                "Exact IntakeRoom re-pin recovery"
                        + " disposition="
                        + result.disposition()
                        + " workflowId="
                        + result.workflowId()
                        + " runId="
                        + result.runId()
                        + " pinnedVersion="
                        + result.pinnedVersion()
                        + " authoritySha256="
                        + result.authoritySha256());
    }

    enum Mode {
        PREPARE,
        APPLY
    }

    enum Disposition {
        PREPARED,
        REPINNED,
        ALREADY_REPINNED
    }

    enum PendingWorkflowTaskState {
        ABSENT,
        SCHEDULED,
        OTHER
    }

    enum OldVersionAuthority {
        LEGACY_BUILD_ID_STAMP
    }

    record RecoveryRequest(
            String address,
            String namespace,
            String workflowId,
            String runId,
            OldVersionAuthority oldVersionAuthority,
            String oldBuildId,
            Path oldRetainedClasses,
            String newDeploymentName,
            String newBuildId,
            String newPinnedVersion,
            Path newClasses,
            PendingWorkflowTaskState pendingWorkflowTaskState,
            long pendingWorkflowTaskScheduledEventId,
            int pendingWorkflowTaskAttempt,
            long pendingTimerStartedEventId,
            String pendingTimerId,
            long pendingTimerTimeoutSeconds,
            long expectedLastEventId,
            int historyMaxEvents,
            Mode mode,
            String expectedAuthoritySha256) {

        static RecoveryRequest parse(String[] args) {
            Objects.requireNonNull(args, "args");
            Map<String, String> values = new HashMap<>();
            for (String argument : args) {
                require(argument != null && argument.startsWith("--"), "argument shape is invalid");
                int separator = argument.indexOf('=');
                require(
                        separator > 2 && separator < argument.length() - 1,
                        "argument shape is invalid");
                String key = argument.substring(2, separator);
                require(ALL_ARGUMENTS.contains(key), "unknown recovery argument");
                require(
                        values.putIfAbsent(key, argument.substring(separator + 1)) == null,
                        "duplicate recovery argument");
            }
            require(
                    values.keySet().containsAll(COMMON_ARGUMENTS),
                    "required recovery arguments are missing");
            try {
                Mode mode = Mode.valueOf(values.get("mode"));
                Set<String> expected = new HashSet<>(COMMON_ARGUMENTS);
                if (mode == Mode.APPLY) {
                    expected.addAll(APPLY_ARGUMENTS);
                }
                require(values.keySet().equals(expected), "recovery arguments do not match mode");
                return new RecoveryRequest(
                        values.get("address"),
                        values.get("namespace"),
                        values.get("workflow-id"),
                        values.get("run-id"),
                        OldVersionAuthority.valueOf(values.get("old-version-authority")),
                        values.get("old-build-id"),
                        Path.of(values.get("old-retained-classes")),
                        values.get("new-deployment-name"),
                        values.get("new-build-id"),
                        values.get("new-pinned-version"),
                        Path.of(values.get("new-classes")),
                        PendingWorkflowTaskState.valueOf(
                                values.get("pending-workflow-task-state")),
                        Long.parseLong(
                                values.get("pending-workflow-task-scheduled-event-id")),
                        Integer.parseInt(values.get("pending-workflow-task-attempt")),
                        Long.parseLong(values.get("pending-timer-started-event-id")),
                        values.get("pending-timer-id"),
                        Long.parseLong(values.get("pending-timer-timeout-seconds")),
                        Long.parseLong(values.get("expected-last-event-id")),
                        Integer.parseInt(values.get("history-max-events")),
                        mode,
                        values.get("expected-authority-sha256"));
            } catch (RuntimeException invalidArgument) {
                throw new IllegalStateException("recovery arguments are invalid", invalidArgument);
            }
        }

        RecoveryRequest forApply(String authoritySha256) {
            return new RecoveryRequest(
                    address,
                    namespace,
                    workflowId,
                    runId,
                    oldVersionAuthority,
                    oldBuildId,
                    oldRetainedClasses,
                    newDeploymentName,
                    newBuildId,
                    newPinnedVersion,
                    newClasses,
                    pendingWorkflowTaskState,
                    pendingWorkflowTaskScheduledEventId,
                    pendingWorkflowTaskAttempt,
                    pendingTimerStartedEventId,
                    pendingTimerId,
                    pendingTimerTimeoutSeconds,
                    expectedLastEventId,
                    historyMaxEvents,
                    Mode.APPLY,
                    authoritySha256);
        }
    }

    record ServerVersion(int major, int minor, int patch) implements Comparable<ServerVersion> {
        @Override
        public int compareTo(ServerVersion other) {
            int majorComparison = Integer.compare(major, other.major);
            if (majorComparison != 0) {
                return majorComparison;
            }
            int minorComparison = Integer.compare(minor, other.minor);
            return minorComparison != 0 ? minorComparison : Integer.compare(patch, other.patch);
        }
    }

    record PinnedVersion(String deploymentName, String buildId, String canonicalVersion) {}

    record ArtifactAuthority(
            Path oldRoot,
            Path newRoot,
            String oldBinding,
            String newBinding,
            String workflowClassSha256) {}

    record ExecutionAuthority(
            String workflowId,
            String runId,
            String workflowType,
            boolean running,
            String taskQueue,
            PendingWorkflowTaskState pendingWorkflowTaskState,
            int pendingWorkflowTaskAttempt,
            int pendingActivities,
            int pendingChildren,
            String assignedBuildId,
            String mostRecentBuildId,
            boolean versioned,
            Set<String> searchBuildIds,
            OldVersionAuthority oldVersionAuthority,
            PinnedVersion explicitOverride,
            PinnedVersion effectiveAssignment,
            boolean deploymentTransition,
            boolean versionTransition) {
        ExecutionAuthority {
            searchBuildIds = Set.copyOf(searchBuildIds);
        }
    }

    record WorkflowTaskState(
            PendingWorkflowTaskState state,
            long scheduledEventId,
            String taskQueue,
            boolean normal,
            int attempt,
            boolean started) {
        static WorkflowTaskState absent() {
            return new WorkflowTaskState(PendingWorkflowTaskState.ABSENT, 0, "", false, 0, false);
        }
    }

    record ActivityState(
            long scheduledEventId, String activityId, String activityType, String taskQueue) {}

    record ChildState(
            long initiatedEventId,
            long startedEventId,
            String workflowId,
            String runId,
            String workflowType) {}

    record ChildTerminal(long initiatedEventId, long startedEventId) {}

    record TimerAuthority(long startedEventId, String timerId, long timeoutSeconds) {}

    record LegacyWorkerStampAuthority(long completedEventId, String buildId) {}

    record HistoryAuthority(
            int eventCount,
            long lastEventId,
            String historySha256,
            WorkflowTaskState pendingWorkflowTask,
            TimerAuthority pendingTimer,
            LegacyWorkerStampAuthority legacyWorkerStamp,
            int pendingActivities,
            int pendingChildren) {}

    record QueueMembership(String taskQueue, TaskQueueType type) {}

    record DeploymentAuthority(
            String deploymentName,
            String currentVersion,
            String rampingVersion,
            float rampingPercentage,
            long revisionNumber,
            boolean routingUpdateInProgress,
            List<String> versionSummaries) {
        DeploymentAuthority {
            versionSummaries = List.copyOf(versionSummaries);
        }
    }

    record VersionAuthority(
            PinnedVersion version,
            WorkerDeploymentVersionStatus status,
            Set<QueueMembership> membership) {
        VersionAuthority {
            membership = Set.copyOf(membership);
        }
    }

    record RecoveryPlan(
            RecoveryRequest request,
            ArtifactAuthority artifacts,
            String serverVersion,
            ExecutionAuthority execution,
            HistoryAuthority history,
            PinnedVersion newVersion,
            DeploymentAuthority newDeployment,
            VersionAuthority newVersionAuthority,
            boolean alreadyRepinned,
            String authoritySha256,
            RePinCommand command) {}

    record RePinCommand(
            String namespace,
            String workflowId,
            String runId,
            PinnedVersion targetVersion,
            String updateMaskPath,
            String identity) {}

    record RePinOutcome(PinnedVersion version) {}

    record OperationResult(
            Disposition disposition,
            String workflowId,
            String runId,
            String pinnedVersion,
            String authoritySha256) {
        static OperationResult prepared(RecoveryPlan plan) {
            return from(plan, Disposition.PREPARED);
        }

        static OperationResult repinned(RecoveryPlan plan) {
            return from(plan, Disposition.REPINNED);
        }

        static OperationResult alreadyRepinned(RecoveryPlan plan) {
            return from(plan, Disposition.ALREADY_REPINNED);
        }

        private static OperationResult from(RecoveryPlan plan, Disposition disposition) {
            return new OperationResult(
                    disposition,
                    plan.request().workflowId(),
                    plan.request().runId(),
                    plan.newVersion().canonicalVersion(),
                    plan.authoritySha256());
        }
    }

    @FunctionalInterface
    interface ClassBytesSource {
        byte[] read() throws IOException;
    }

    interface TemporalAuthority {
        String serverVersion();

        ExecutionAuthority describeExecution(RecoveryRequest request);

        List<HistoryEvent> loadCompleteHistory(RecoveryRequest request);

        DeploymentAuthority describeDeployment(String namespace, String deploymentName);

        VersionAuthority describeVersion(String namespace, PinnedVersion version);
    }

    @FunctionalInterface
    interface RePinExecutor {
        RePinOutcome repin(RePinCommand command);
    }

    private static final class SdkSession
            implements AutoCloseable, TemporalAuthority, RePinExecutor {
        private final WorkflowServiceStubs serviceStubs;
        private final WorkflowServiceBlockingStub service;

        private SdkSession(
                WorkflowServiceStubs serviceStubs, WorkflowServiceBlockingStub service) {
            this.serviceStubs = serviceStubs;
            this.service = service;
        }

        static SdkSession open(RecoveryRequest request) {
            WorkflowServiceStubsOptions options =
                    WorkflowServiceStubsOptions.newBuilder()
                            .setTarget(request.address())
                            .setHealthCheckTimeout(CONNECT_TIMEOUT)
                            .setSystemInfoTimeout(CONNECT_TIMEOUT)
                            .build();
            WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(options);
            try {
                stubs.connect(CONNECT_TIMEOUT);
                return new SdkSession(stubs, stubs.blockingStub());
            } catch (RuntimeException failure) {
                stubs.shutdownNow();
                throw failure;
            }
        }

        @Override
        public String serverVersion() {
            return service.getSystemInfo(GetSystemInfoRequest.getDefaultInstance()).getServerVersion();
        }

        @Override
        public ExecutionAuthority describeExecution(RecoveryRequest request) {
            DescribeWorkflowExecutionResponse response =
                    service.describeWorkflowExecution(
                            DescribeWorkflowExecutionRequest.newBuilder()
                                    .setNamespace(request.namespace())
                                    .setExecution(workflowExecution(request))
                                    .build());
            require(
                    response.hasExecutionConfig()
                            && response.hasWorkflowExecutionInfo(),
                    "workflow description authority is incomplete");
            WorkflowExecutionInfo info = response.getWorkflowExecutionInfo();
            boolean versioned =
                    info.hasMostRecentWorkerVersionStamp()
                            && info.getMostRecentWorkerVersionStamp().getUseVersioning();
            String mostRecentBuildId =
                    versioned ? info.getMostRecentWorkerVersionStamp().getBuildId() : "";
            Set<String> searchBuildIds = searchBuildIds(info);
            WorkflowExecutionVersioningInfo versioningInfo =
                    info.hasVersioningInfo()
                            ? info.getVersioningInfo()
                            : WorkflowExecutionVersioningInfo.getDefaultInstance();
            boolean hasAssignmentRepresentation =
                    versioningInfo.getBehavior() != VERSIONING_BEHAVIOR_UNSPECIFIED
                            || versioningInfo.hasDeploymentVersion()
                            || !versioningInfo.getVersion().isBlank();
            PinnedVersion effectiveAssignment =
                    hasAssignmentRepresentation
                            ? pinnedAssignmentVersion(versioningInfo)
                            : null;
            PinnedVersion explicitOverride =
                    versioningInfo.hasVersioningOverride()
                            ? parseServerPersistedPinnedOverride(
                                    versioningInfo.getVersioningOverride(),
                                    "workflow description override")
                            : null;
            for (PendingChildExecutionInfo child : response.getPendingChildrenList()) {
                require(
                        !child.getWorkflowId().isBlank()
                                && !child.getRunId().isBlank()
                                && !child.getWorkflowTypeName().isBlank()
                                && child.getInitiatedId() > 0,
                        "pending child description authority is incomplete");
            }
            PendingWorkflowTaskState pendingState = PendingWorkflowTaskState.ABSENT;
            int pendingAttempt = 0;
            if (response.hasPendingWorkflowTask()) {
                pendingState =
                        response.getPendingWorkflowTask().getState()
                                        == PENDING_WORKFLOW_TASK_STATE_SCHEDULED
                                ? PendingWorkflowTaskState.SCHEDULED
                                : PendingWorkflowTaskState.OTHER;
                pendingAttempt = response.getPendingWorkflowTask().getAttempt();
            }
            return new ExecutionAuthority(
                    info.getExecution().getWorkflowId(),
                    info.getExecution().getRunId(),
                    info.getType().getName(),
                    info.getStatus() == WORKFLOW_EXECUTION_STATUS_RUNNING,
                    response.getExecutionConfig().getTaskQueue().getName(),
                    pendingState,
                    pendingAttempt,
                    response.getPendingActivitiesCount(),
                    response.getPendingChildrenCount(),
                    info.getAssignedBuildId(),
                    mostRecentBuildId,
                    versioned,
                    searchBuildIds,
                    OldVersionAuthority.LEGACY_BUILD_ID_STAMP,
                    explicitOverride,
                    effectiveAssignment,
                    versioningInfo.hasDeploymentTransition(),
                    versioningInfo.hasVersionTransition());
        }

        @Override
        public List<HistoryEvent> loadCompleteHistory(RecoveryRequest request) {
            List<HistoryEvent> completeHistory = new ArrayList<>();
            ByteString token = ByteString.EMPTY;
            long expectedEventId = 1;
            for (int page = 0; page < MAXIMUM_HISTORY_PAGES; page++) {
                GetWorkflowExecutionHistoryResponse response =
                        service.getWorkflowExecutionHistory(
                                GetWorkflowExecutionHistoryRequest.newBuilder()
                                        .setNamespace(request.namespace())
                                        .setExecution(workflowExecution(request))
                                        .setMaximumPageSize(
                                                Math.min(
                                                        request.historyMaxEvents(),
                                                        MAXIMUM_HISTORY_EVENTS))
                                        .setNextPageToken(token)
                                        .setWaitNewEvent(false)
                                        .build());
                for (HistoryEvent event : response.getHistory().getEventsList()) {
                    require(
                            event.getEventId() == expectedEventId,
                            "complete history is not strictly contiguous from event 1");
                    expectedEventId++;
                    completeHistory.add(event);
                    require(
                            completeHistory.size() <= request.historyMaxEvents(),
                            "complete history exceeded its bounded maximum");
                }
                token = response.getNextPageToken();
                if (token.isEmpty()) {
                    return List.copyOf(completeHistory);
                }
            }
            throw new IllegalStateException("complete history exceeded its bounded page scan");
        }

        @Override
        public DeploymentAuthority describeDeployment(String namespace, String deploymentName) {
            DescribeWorkerDeploymentResponse response =
                    service.describeWorkerDeployment(
                            DescribeWorkerDeploymentRequest.newBuilder()
                                    .setNamespace(namespace)
                                    .setDeploymentName(deploymentName)
                                    .build());
            require(
                    response.hasWorkerDeploymentInfo()
                            && response.getWorkerDeploymentInfo().hasRoutingConfig(),
                    "worker deployment routing authority is incomplete");
            WorkerDeploymentInfo info = response.getWorkerDeploymentInfo();
            require(
                    deploymentName.equals(info.getName()),
                    "worker deployment response identity drifted");
            RoutingConfig routing = info.getRoutingConfig();
            String current = routingVersion(routing, true, "current routing version");
            String ramping = routingVersion(routing, false, "ramping routing version");
            List<String> summaries =
                    info.getVersionSummariesList().stream()
                            .map(SdkSession::summaryVersion)
                            .toList();
            require(
                    new HashSet<>(summaries).size() == summaries.size(),
                    "worker deployment version summaries are duplicated");
            return new DeploymentAuthority(
                    info.getName(),
                    current,
                    ramping,
                    routing.getRampingVersionPercentage(),
                    routing.getRevisionNumber(),
                    info.getRoutingConfigUpdateState() == ROUTING_CONFIG_UPDATE_STATE_IN_PROGRESS,
                    summaries);
        }

        @Override
        public VersionAuthority describeVersion(String namespace, PinnedVersion version) {
            DescribeWorkerDeploymentVersionResponse response =
                    service.describeWorkerDeploymentVersion(
                            DescribeWorkerDeploymentVersionRequest.newBuilder()
                                    .setNamespace(namespace)
                                    .setDeploymentVersion(
                                            io.temporal.api.deployment.v1.WorkerDeploymentVersion
                                                    .newBuilder()
                                                    .setDeploymentName(version.deploymentName())
                                                    .setBuildId(version.buildId()))
                                    .setReportTaskQueueStats(false)
                                    .build());
            require(
                    response.hasWorkerDeploymentVersionInfo(),
                    "worker deployment version authority is missing");
            WorkerDeploymentVersionInfo info = response.getWorkerDeploymentVersionInfo();
            PinnedVersion described = versionInfoVersion(info);
            Set<QueueMembership> structuredMembership =
                    membership(
                            info.getTaskQueueInfosList().stream()
                                    .map(
                                            queue ->
                                                    new QueueMembership(
                                                            queue.getName(), queue.getType()))
                                    .toList(),
                            "structured version membership");
            Set<QueueMembership> responseMembership =
                    membership(
                            response.getVersionTaskQueuesList().stream()
                                    .map(
                                            queue ->
                                                    new QueueMembership(
                                                            queue.getName(), queue.getType()))
                                    .toList(),
                            "version response membership");
            require(
                    !structuredMembership.isEmpty() || !responseMembership.isEmpty(),
                    "worker deployment version has no task-queue membership");
            if (!structuredMembership.isEmpty() && !responseMembership.isEmpty()) {
                require(
                        structuredMembership.equals(responseMembership),
                        "worker deployment version membership representations conflict");
            }
            Set<QueueMembership> exact =
                    structuredMembership.isEmpty() ? responseMembership : structuredMembership;
            return new VersionAuthority(described, info.getStatus(), exact);
        }

        @Override
        public RePinOutcome repin(RePinCommand command) {
            UpdateWorkflowExecutionOptionsResponse response =
                    service.updateWorkflowExecutionOptions(updateRequest(command));
            require(
                    response.hasWorkflowExecutionOptions(),
                    "re-pin response has no workflow execution options");
            return new RePinOutcome(
                    parseServerPersistedPinnedOverride(
                            response.getWorkflowExecutionOptions().getVersioningOverride(),
                            "re-pin response override"));
        }

        private static String routingVersion(
                RoutingConfig routing, boolean current, String source) {
            boolean hasStructured =
                    current
                            ? routing.hasCurrentDeploymentVersion()
                            : routing.hasRampingDeploymentVersion();
            String compatible = current ? routing.getCurrentVersion() : routing.getRampingVersion();
            if (!hasStructured) {
                return compatible;
            }
            io.temporal.api.deployment.v1.WorkerDeploymentVersion structured =
                    current
                            ? routing.getCurrentDeploymentVersion()
                            : routing.getRampingDeploymentVersion();
            String canonical = canonicalVersion(structured, source);
            require(
                    compatible.isBlank() || compatible.equals(canonical),
                    source + " representations conflict");
            return canonical;
        }

        private static String summaryVersion(
                WorkerDeploymentInfo.WorkerDeploymentVersionSummary summary) {
            if (summary.hasDeploymentVersion()) {
                String canonical =
                        canonicalVersion(
                                summary.getDeploymentVersion(),
                                "worker deployment version summary");
                require(
                        summary.getVersion().isBlank() || summary.getVersion().equals(canonical),
                        "worker deployment version summary representations conflict");
                return canonical;
            }
            return parsePinnedVersion(summary.getVersion(), "worker deployment version summary")
                    .canonicalVersion();
        }

        private static PinnedVersion versionInfoVersion(WorkerDeploymentVersionInfo info) {
            PinnedVersion structured = null;
            if (info.hasDeploymentVersion()) {
                String canonical =
                        canonicalVersion(
                                info.getDeploymentVersion(),
                                "worker deployment version description");
                structured =
                        parsePinnedVersion(canonical, "worker deployment version description");
            }
            String compatible = info.getVersion();
            PinnedVersion parsedCompatible =
                    compatible.isBlank()
                            ? null
                            : parsePinnedVersion(compatible, "worker deployment version");
            if (structured != null && parsedCompatible != null) {
                require(
                        structured.equals(parsedCompatible),
                        "worker deployment version representations conflict");
            }
            PinnedVersion resolved = structured != null ? structured : parsedCompatible;
            require(resolved != null, "worker deployment version identity is missing");
            require(
                    info.getDeploymentName().isBlank()
                            || info.getDeploymentName().equals(resolved.deploymentName()),
                    "worker deployment version deploymentName conflicts");
            return resolved;
        }

        private static Set<QueueMembership> membership(
                List<QueueMembership> values, String source) {
            for (QueueMembership value : values) {
                requireText(value.taskQueue(), source + " taskQueue", 255);
                require(
                        value.type() == TASK_QUEUE_TYPE_WORKFLOW
                                || value.type() == TASK_QUEUE_TYPE_ACTIVITY,
                        source + " contains an unsupported task-queue type");
            }
            Set<QueueMembership> unique = Set.copyOf(values);
            require(unique.size() == values.size(), source + " contains duplicates");
            return unique;
        }

        private static WorkflowExecution workflowExecution(RecoveryRequest request) {
            return WorkflowExecution.newBuilder()
                    .setWorkflowId(request.workflowId())
                    .setRunId(request.runId())
                    .build();
        }

        @Override
        public void close() {
            serviceStubs.shutdown();
            if (!serviceStubs.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                serviceStubs.shutdownNow();
            }
        }
    }
}
