package com.example.dispute.workflow.recovery;

import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.DOMAIN_EVENT_SIGNAL;
import static io.temporal.api.enums.v1.EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED;
import static io.temporal.api.enums.v1.PendingWorkflowTaskState.PENDING_WORKFLOW_TASK_STATE_SCHEDULED;
import static io.temporal.api.enums.v1.TaskQueueKind.TASK_QUEUE_KIND_NORMAL;
import static io.temporal.api.enums.v1.VersioningBehavior.VERSIONING_BEHAVIOR_PINNED;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;

import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.FieldMask;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflow.v1.PendingChildExecutionInfo;
import io.temporal.api.workflow.v1.VersioningOverride;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflow.v1.WorkflowExecutionOptions;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionOptionsRequest;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionOptionsResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Target-only, fail-closed CaseProcess recovery by an execution-scoped Worker Deployment pin.
 *
 * <p>{@code PREPARE} reads and hashes all routing and history authority without mutation. {@code
 * APPLY} requires that hash, repeats the complete read, and updates only the exact execution's
 * {@code versioning_override}. It never changes task-queue Build-ID compatibility rules and never
 * starts a worker.
 */
public final class ExactCaseProcessWorkflowPinRecoveryMain {

    static final Path WORKTREE_MARKER =
            Path.of("META-INF", "after-sale-flow", "compiled-worktree.sha256");
    static final Path CASE_PROCESS_WORKFLOW_CLASS =
            Path.of(
                    "com",
                    "example",
                    "dispute",
                    "workflow",
                    "temporal",
                    "caseprocess",
                    "CaseProcessWorkflowImpl.class");
    static final String UPDATE_MASK_PATH = "versioning_override";
    static final String UPDATE_IDENTITY = "exact-case-process-pin-recovery.v1";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final ServerVersion MINIMUM_SERVER_VERSION = new ServerVersion(1, 27, 4);
    private static final int MAXIMUM_HISTORY_EVENTS = 5000;
    private static final int MAXIMUM_HISTORY_PAGES = 100;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern LEGACY_CONTROL_BUILD_ID =
            Pattern.compile("[A-Za-z0-9._-]+-([0-9a-f]{64})-control");
    private static final Pattern SERVER_VERSION =
            Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$");
    private static final Set<String> COMMON_ARGUMENTS =
            Set.of(
                    "address",
                    "namespace",
                    "workflow-id",
                    "run-id",
                    "legacy-build-id",
                    "retained-classes",
                    "recovery-deployment-name",
                    "recovery-build-id",
                    "recovery-pinned-version",
                    "pending-workflow-task-scheduled-event-id",
                    "pending-workflow-task-attempt",
                    "pending-child-workflow-id",
                    "pending-child-run-id",
                    "pending-child-workflow-type",
                    "pending-child-initiated-event-id",
                    "expected-last-event-id",
                    "expected-last-signal-event-id",
                    "expected-signal-name",
                    "history-max-events",
                    "mode");
    private static final Set<String> APPLY_ARGUMENTS = Set.of("expected-authority-sha256");
    private static final Set<String> ALL_ARGUMENTS = allArguments();

    private ExactCaseProcessWorkflowPinRecoveryMain() {}

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
                                ExactCaseProcessWorkflowPinRecoveryMain::loadedWorkflowClassBytes,
                                session);
                printSafe(result);
            }
            return 0;
        } catch (RuntimeException | IOException failure) {
            System.err.println("Exact CaseProcess pin recovery failed closed.");
            return 2;
        }
    }

    static OperationResult operate(
            RecoveryRequest request,
            TemporalAuthority authority,
            ClassBytesSource currentWorkflowClass,
            PinExecutor pinExecutor)
            throws IOException {
        Objects.requireNonNull(pinExecutor, "pinExecutor");
        RecoveryPlan prepared = prepare(request, authority, currentWorkflowClass);
        if (request.mode() == Mode.PREPARE) {
            return OperationResult.prepared(prepared);
        }
        require(
                prepared.authoritySha256().equals(request.expectedAuthoritySha256()),
                "pin authority changed after PREPARE");
        if (prepared.alreadyPinned()) {
            return OperationResult.alreadyPinned(prepared);
        }

        RecoveryPlan revalidated = prepare(request, authority, currentWorkflowClass);
        require(
                revalidated.authoritySha256().equals(request.expectedAuthoritySha256()),
                "pin authority drifted during APPLY revalidation");
        if (revalidated.alreadyPinned()) {
            return OperationResult.alreadyPinned(revalidated);
        }
        require(prepared.equals(revalidated), "pin plan drifted during APPLY revalidation");

        PinOutcome outcome = pinExecutor.pin(revalidated.pinCommand());
        require(outcome != null, "pin response is missing");
        require(
                revalidated.targetVersion().equals(outcome.version()),
                "pin response does not contain the exact target version");

        ExecutionAuthority after = authority.describe(request);
        validatePostPinExecution(request, after, revalidated.targetVersion());
        return OperationResult.pinned(revalidated);
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

        Path retainedRoot = request.retainedClasses().toRealPath();
        require(Files.isDirectory(retainedRoot), "retained classes root is not a directory");
        String retainedBinding =
                Files.readString(retainedRoot.resolve(WORKTREE_MARKER), StandardCharsets.US_ASCII)
                        .strip();
        require(SHA256.matcher(retainedBinding).matches(), "retained worktree marker is invalid");
        require(
                retainedBinding.equals(buildWorktreeBinding(request.legacyBuildId())),
                "legacy build ID does not bind the retained worktree marker");

        byte[] retainedWorkflowBytes =
                Files.readAllBytes(retainedRoot.resolve(CASE_PROCESS_WORKFLOW_CLASS));
        byte[] currentWorkflowBytes = currentWorkflowClass.read();
        require(
                retainedWorkflowBytes.length > 0 && currentWorkflowBytes.length > 0,
                "CaseProcess workflow class bytes are empty");
        String workflowClassSha256 = sha256(retainedWorkflowBytes);
        require(
                workflowClassSha256.equals(sha256(currentWorkflowBytes)),
                "retained and current CaseProcess workflow classes differ");

        String serverVersion = authority.serverVersion();
        requireServerVersion(serverVersion);
        PinnedVersion targetVersion = targetVersion(request);
        ExecutionAuthority execution = authority.describe(request);
        boolean alreadyPinned = validateExecution(request, execution, targetVersion);
        List<HistoryEvent> completeHistory = List.copyOf(authority.loadCompleteHistory(request));
        HistoryAuthority history =
                validateHistory(
                        request, execution, completeHistory, alreadyPinned, targetVersion);

        PinCommand command =
                new PinCommand(
                        request.namespace(),
                        request.workflowId(),
                        request.runId(),
                        targetVersion,
                        UPDATE_MASK_PATH,
                        UPDATE_IDENTITY);
        String authoritySha256 =
                authoritySha256(
                        request,
                        retainedRoot,
                        retainedBinding,
                        workflowClassSha256,
                        serverVersion,
                        execution,
                        history,
                        targetVersion);
        return new RecoveryPlan(
                request,
                retainedRoot,
                retainedBinding,
                workflowClassSha256,
                serverVersion,
                execution,
                history,
                targetVersion,
                alreadyPinned,
                authoritySha256,
                command);
    }

    private static void validateRequest(RecoveryRequest request) {
        requireText(request.address(), "address", 512);
        requireText(request.namespace(), "namespace", 255);
        requireText(request.workflowId(), "workflowId", 255);
        requireCanonicalUuid(request.runId(), "runId");
        requireText(request.legacyBuildId(), "legacyBuildId", 512);
        require(request.retainedClasses() != null, "retainedClasses is required");
        requireText(request.recoveryDeploymentName(), "recoveryDeploymentName", 255);
        requireText(request.recoveryBuildId(), "recoveryBuildId", 255);
        requireText(request.recoveryPinnedVersion(), "recoveryPinnedVersion", 512);
        require(
                request.pendingWorkflowTaskScheduledEventId() > 0,
                "pending workflow-task scheduled event ID must be positive");
        require(
                request.pendingWorkflowTaskAttempt() > 0,
                "pending workflow-task attempt must be positive");
        requireText(request.pendingChildWorkflowId(), "pendingChildWorkflowId", 255);
        requireCanonicalUuid(request.pendingChildRunId(), "pendingChildRunId");
        requireText(request.pendingChildWorkflowType(), "pendingChildWorkflowType", 255);
        require(
                request.pendingChildInitiatedEventId() > 0,
                "pending child initiated event ID must be positive");
        require(request.expectedLastEventId() > 0, "expected last event ID must be positive");
        require(
                request.pendingWorkflowTaskScheduledEventId() <= request.expectedLastEventId(),
                "pending workflow-task schedule is after the complete history tail");
        require(
                request.expectedLastSignalEventId() > 0
                        && request.expectedLastSignalEventId() <= request.expectedLastEventId(),
                "expected last signal event ID is invalid");
        require(
                DOMAIN_EVENT_SIGNAL.equals(request.expectedSignalName()),
                "expected signal name is not the CaseProcess domain-event protocol signal");
        require(
                request.expectedLastEventId() < MAXIMUM_HISTORY_EVENTS
                        && request.historyMaxEvents() >= request.expectedLastEventId() + 1
                        && request.historyMaxEvents() <= MAXIMUM_HISTORY_EVENTS,
                "history maximum is outside the bounded range");
        Objects.requireNonNull(request.mode(), "mode");
        buildWorktreeBinding(request.legacyBuildId());
        targetVersion(request);
        if (request.mode() == Mode.PREPARE) {
            require(
                    request.expectedAuthoritySha256() == null,
                    "PREPARE must not carry an APPLY authority hash");
        } else {
            requireSha256(request.expectedAuthoritySha256(), "expected authority hash");
        }
    }

    private static boolean validateExecution(
            RecoveryRequest request,
            ExecutionAuthority execution,
            PinnedVersion targetVersion) {
        validateFixedExecutionIdentity(request, execution);
        require(execution.assignedBuildId().isBlank(), "workflow assigned build ID is not blank");
        require(
                execution.versioned()
                        && request.legacyBuildId().equals(execution.mostRecentBuildId()),
                "workflow versioned legacy build authority drifted");
        require(
                execution.pendingState() == PendingTaskState.SCHEDULED
                        && execution.pendingAttempt() == request.pendingWorkflowTaskAttempt(),
                "workflow pending task state or attempt drifted");
        require(execution.pendingActivities() == 0, "workflow has pending activities");
        require(
                execution.pendingChildren().size() == 1,
                "workflow does not have exactly one pending child");
        require(
                expectedPendingChild(request).equals(execution.pendingChildren().getFirst()),
                "pending child workflow authority drifted");
        require(
                !execution.deploymentTransition() && !execution.versionTransition(),
                "workflow has an active deployment version transition");
        if (execution.currentOverride().kind() == OverrideKind.ABSENT) {
            return false;
        }
        require(
                execution.currentOverride().kind() == OverrideKind.PINNED
                        && targetVersion.deploymentName()
                                .equals(execution.currentOverride().deploymentName())
                        && targetVersion.buildId().equals(execution.currentOverride().buildId()),
                "workflow has a conflicting versioning override");
        return true;
    }

    private static void validateFixedExecutionIdentity(
            RecoveryRequest request, ExecutionAuthority execution) {
        require(execution != null, "workflow execution authority is missing");
        require(
                request.workflowId().equals(execution.workflowId())
                        && request.runId().equals(execution.runId()),
                "workflow execution identity drifted");
        require(
                CASE_WORKFLOW_TYPE.equals(execution.workflowType()),
                "workflow type is not CaseProcessWorkflow");
        require(execution.status() == WorkflowStatus.RUNNING, "workflow execution is not running");
        require(
                CASE_CONTROL_TASK_QUEUE.equals(execution.taskQueue()),
                "workflow task queue is not case-control");
    }

    private static void validatePostPinExecution(
            RecoveryRequest request,
            ExecutionAuthority execution,
            PinnedVersion targetVersion) {
        validateFixedExecutionIdentity(request, execution);
        require(
                execution.currentOverride().kind() == OverrideKind.PINNED
                        && targetVersion.deploymentName()
                                .equals(execution.currentOverride().deploymentName())
                        && targetVersion.buildId().equals(execution.currentOverride().buildId()),
                "workflow did not retain the exact pinned override after update");
    }

    private static HistoryAuthority validateHistory(
            RecoveryRequest request,
            ExecutionAuthority execution,
            List<HistoryEvent> completeHistory,
            boolean alreadyPinned,
            PinnedVersion targetVersion) {
        require(completeHistory != null, "complete history is missing");
        int prePinEventCount = Math.toIntExact(request.expectedLastEventId());
        int expectedCompleteEventCount = prePinEventCount + (alreadyPinned ? 1 : 0);
        require(
                !completeHistory.isEmpty()
                        && completeHistory.size() == expectedCompleteEventCount
                        && completeHistory.size() <= request.historyMaxEvents(),
                "complete history size is invalid");
        require(
                completeHistory.getFirst().getEventId() == 1
                        && completeHistory
                                .getFirst()
                                .hasWorkflowExecutionStartedEventAttributes(),
                "complete history does not start at workflow execution event 1");

        long expectedEventId = 1;
        for (HistoryEvent event : completeHistory) {
            require(event.getEventId() == expectedEventId, "complete history is not contiguous");
            expectedEventId++;
        }
        if (alreadyPinned) {
            validatePostPinHistoryEvent(completeHistory.getLast(), request, targetVersion);
        }

        List<HistoryEvent> prePinHistory = completeHistory.subList(0, prePinEventCount);
        long lastSignalEventId = 0;
        String lastSignalName = "";
        HistoryEvent lastWorkflowTaskScheduled = null;
        Map<Long, ChildHistoryState> pendingChildren = new HashMap<>();
        for (HistoryEvent event : prePinHistory) {
            if (event.hasWorkflowExecutionSignaledEventAttributes()) {
                lastSignalEventId = event.getEventId();
                lastSignalName =
                        event.getWorkflowExecutionSignaledEventAttributes().getSignalName();
            }
            if (event.hasWorkflowTaskScheduledEventAttributes()) {
                lastWorkflowTaskScheduled = event;
            }
            applyChildHistoryEvent(event, pendingChildren);
        }

        HistoryEvent last = prePinHistory.getLast();
        require(
                last.getEventId() == request.expectedLastEventId(),
                "complete history last event drifted");
        require(
                lastSignalEventId == request.expectedLastSignalEventId()
                        && request.expectedSignalName().equals(lastSignalName),
                "complete history last signal authority drifted");
        require(
                lastWorkflowTaskScheduled != null
                        && lastWorkflowTaskScheduled.getEventId()
                                == request.pendingWorkflowTaskScheduledEventId(),
                "complete history last workflow-task schedule authority drifted");
        var scheduled = lastWorkflowTaskScheduled.getWorkflowTaskScheduledEventAttributes();
        require(
                scheduled.hasTaskQueue()
                        && scheduled.getTaskQueue().getKind() == TASK_QUEUE_KIND_NORMAL
                        && CASE_CONTROL_TASK_QUEUE.equals(scheduled.getTaskQueue().getName())
                        && scheduled.getAttempt() == request.pendingWorkflowTaskAttempt()
                        && scheduled.getAttempt() == execution.pendingAttempt(),
                "pending workflow-task history authority drifted");
        require(
                pendingChildren.size() == 1,
                "complete history does not contain exactly one active pending child");
        ChildHistoryState child = pendingChildren.values().iterator().next();
        require(child.startedEventId() > 0, "pending child has not reached STARTED authority");
        require(
                child.asPendingAuthority().equals(expectedPendingChild(request))
                        && child.asPendingAuthority().equals(execution.pendingChildren().getFirst()),
                "history pending child does not match current execution authority");
        return new HistoryAuthority(
                prePinHistory.size(),
                last.getEventId(),
                lastSignalEventId,
                lastWorkflowTaskScheduled.getEventId(),
                historySha256(prePinHistory),
                child.asPendingAuthority());
    }

    private static void validatePostPinHistoryEvent(
            HistoryEvent event, RecoveryRequest request, PinnedVersion targetVersion) {
        require(
                event.getEventId() == request.expectedLastEventId() + 1
                        && event.getEventType()
                                == EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED
                        && event.hasWorkflowExecutionOptionsUpdatedEventAttributes()
                        && event.getWorkerMayIgnore(),
                "post-pin history suffix is not the exact ignorable options update");
        var attributes = event.getWorkflowExecutionOptionsUpdatedEventAttributes();
        require(
                attributes.hasVersioningOverride()
                        && !attributes.getUnsetVersioningOverride()
                        && attributes.getAttachedRequestId().isBlank()
                        && attributes.getAttachedCompletionCallbacksCount() == 0
                        && attributes.getIdentity().isBlank()
                        && !attributes.hasPriority(),
                "post-pin options update contains unsupported authority");
        require(
                targetVersion.equals(
                        requirePinnedVersion(
                                attributes.getVersioningOverride(),
                                "post-pin history override")),
                "post-pin history override does not match the exact target version");
    }

    private static void applyChildHistoryEvent(
            HistoryEvent event, Map<Long, ChildHistoryState> pendingChildren) {
        if (event.hasStartChildWorkflowExecutionInitiatedEventAttributes()) {
            var attributes = event.getStartChildWorkflowExecutionInitiatedEventAttributes();
            require(
                    attributes.hasWorkflowType()
                            && !attributes.getWorkflowId().isBlank()
                            && !attributes.getWorkflowType().getName().isBlank(),
                    "child workflow initiation authority is incomplete");
            ChildHistoryState state =
                    new ChildHistoryState(
                            attributes.getWorkflowId(),
                            "",
                            attributes.getWorkflowType().getName(),
                            event.getEventId(),
                            0);
            require(
                    pendingChildren.putIfAbsent(event.getEventId(), state) == null,
                    "child workflow initiation is duplicated");
            return;
        }
        if (event.hasStartChildWorkflowExecutionFailedEventAttributes()) {
            long initiatedEventId =
                    event.getStartChildWorkflowExecutionFailedEventAttributes()
                            .getInitiatedEventId();
            require(
                    pendingChildren.remove(initiatedEventId) != null,
                    "child workflow start failure has no initiation authority");
            return;
        }
        if (event.hasChildWorkflowExecutionStartedEventAttributes()) {
            var attributes = event.getChildWorkflowExecutionStartedEventAttributes();
            ChildHistoryState initiated = pendingChildren.get(attributes.getInitiatedEventId());
            require(
                    initiated != null
                            && initiated.startedEventId() == 0
                            && attributes.hasWorkflowExecution()
                            && attributes.hasWorkflowType()
                            && initiated.workflowId()
                                    .equals(attributes.getWorkflowExecution().getWorkflowId())
                            && initiated.workflowType()
                                    .equals(attributes.getWorkflowType().getName()),
                    "child workflow start has no exact initiation authority");
            requireCanonicalUuid(
                    attributes.getWorkflowExecution().getRunId(), "child history runId");
            pendingChildren.put(
                    attributes.getInitiatedEventId(),
                    new ChildHistoryState(
                            initiated.workflowId(),
                            attributes.getWorkflowExecution().getRunId(),
                            initiated.workflowType(),
                            initiated.initiatedEventId(),
                            event.getEventId()));
            return;
        }
        ChildTerminalAuthority terminal = childTerminalAuthority(event);
        if (terminal != null) {
            ChildHistoryState started = pendingChildren.remove(terminal.initiatedEventId());
            require(
                    started != null
                            && started.startedEventId() > 0
                            && started.startedEventId() == terminal.startedEventId(),
                    "child workflow terminal event has no exact start authority");
        }
    }

    private static ChildTerminalAuthority childTerminalAuthority(HistoryEvent event) {
        if (event.hasChildWorkflowExecutionCompletedEventAttributes()) {
            var attributes = event.getChildWorkflowExecutionCompletedEventAttributes();
            return new ChildTerminalAuthority(
                    attributes.getInitiatedEventId(), attributes.getStartedEventId());
        }
        if (event.hasChildWorkflowExecutionFailedEventAttributes()) {
            var attributes = event.getChildWorkflowExecutionFailedEventAttributes();
            return new ChildTerminalAuthority(
                    attributes.getInitiatedEventId(), attributes.getStartedEventId());
        }
        if (event.hasChildWorkflowExecutionCanceledEventAttributes()) {
            var attributes = event.getChildWorkflowExecutionCanceledEventAttributes();
            return new ChildTerminalAuthority(
                    attributes.getInitiatedEventId(), attributes.getStartedEventId());
        }
        if (event.hasChildWorkflowExecutionTimedOutEventAttributes()) {
            var attributes = event.getChildWorkflowExecutionTimedOutEventAttributes();
            return new ChildTerminalAuthority(
                    attributes.getInitiatedEventId(), attributes.getStartedEventId());
        }
        if (event.hasChildWorkflowExecutionTerminatedEventAttributes()) {
            var attributes = event.getChildWorkflowExecutionTerminatedEventAttributes();
            return new ChildTerminalAuthority(
                    attributes.getInitiatedEventId(), attributes.getStartedEventId());
        }
        return null;
    }

    private static String authoritySha256(
            RecoveryRequest request,
            Path retainedRoot,
            String retainedBinding,
            String workflowClassSha256,
            String serverVersion,
            ExecutionAuthority execution,
            HistoryAuthority history,
            PinnedVersion targetVersion) {
        PendingChildAuthority child = execution.pendingChildren().getFirst();
        return canonicalSha256(
                "exact-case-process-pin-authority.v1",
                request.address(),
                request.namespace(),
                request.workflowId(),
                request.runId(),
                request.legacyBuildId(),
                retainedRoot.toString(),
                retainedBinding,
                workflowClassSha256,
                serverVersion,
                targetVersion.deploymentName(),
                targetVersion.buildId(),
                targetVersion.canonicalVersion(),
                execution.workflowType(),
                execution.status().name(),
                execution.taskQueue(),
                execution.assignedBuildId(),
                execution.mostRecentBuildId(),
                Boolean.toString(execution.versioned()),
                execution.pendingState().name(),
                Integer.toString(execution.pendingAttempt()),
                Integer.toString(execution.pendingActivities()),
                child.workflowId(),
                child.runId(),
                child.workflowType(),
                Long.toString(child.initiatedEventId()),
                Long.toString(history.lastEventId()),
                Long.toString(history.lastSignalEventId()),
                Long.toString(history.pendingWorkflowTaskScheduledEventId()),
                Integer.toString(history.eventCount()),
                history.historySha256(),
                UPDATE_MASK_PATH,
                UPDATE_IDENTITY);
    }

    private static PinnedVersion targetVersion(RecoveryRequest request) {
        String canonical;
        try {
            canonical =
                    new io.temporal.common.WorkerDeploymentVersion(
                                    request.recoveryDeploymentName(), request.recoveryBuildId())
                            .toCanonicalString();
        } catch (RuntimeException invalidVersion) {
            throw new IllegalStateException("recovery Worker Deployment Version is invalid", invalidVersion);
        }
        require(
                canonical.equals(request.recoveryPinnedVersion()),
                "recovery pinned version does not match deployment name and build ID");
        return new PinnedVersion(
                request.recoveryDeploymentName(), request.recoveryBuildId(), canonical);
    }

    private static PendingChildAuthority expectedPendingChild(RecoveryRequest request) {
        return new PendingChildAuthority(
                request.pendingChildWorkflowId(),
                request.pendingChildRunId(),
                request.pendingChildWorkflowType(),
                request.pendingChildInitiatedEventId());
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

    static CurrentOverride currentOverride(WorkflowExecutionInfo info) {
        if (!info.hasVersioningInfo()
                || !info.getVersioningInfo().hasVersioningOverride()) {
            return CurrentOverride.absent();
        }
        PinnedVersion version =
                requirePinnedVersion(
                        info.getVersioningInfo().getVersioningOverride(),
                        "workflow description override");
        return CurrentOverride.pinned(version.deploymentName(), version.buildId());
    }

    static PinnedVersion requirePinnedVersion(
            WorkflowExecutionOptions options, String source) {
        require(options != null && options.hasVersioningOverride(), source + " has no override");
        return requirePinnedVersion(options.getVersioningOverride(), source);
    }

    private static PinnedVersion requirePinnedVersion(
            VersioningOverride override, String source) {
        require(override != null, source + " is missing");
        require(
                override.getBehavior() == VERSIONING_BEHAVIOR_PINNED,
                source + " does not have top-level PINNED behavior");
        require(!override.hasPinned(), source + " contains a legacy nested pinned override");
        String canonical = override.getPinnedVersion();
        requireText(canonical, source + " pinnedVersion", 512);
        require(canonical.equals(canonical.strip()), source + " pinnedVersion is noncanonical");
        io.temporal.common.WorkerDeploymentVersion version;
        try {
            version = io.temporal.common.WorkerDeploymentVersion.fromCanonicalString(canonical);
        } catch (IllegalArgumentException invalidVersion) {
            throw new IllegalStateException(source + " pinnedVersion is invalid", invalidVersion);
        }
        requireText(version.getDeploymentName(), source + " deploymentName", 255);
        requireText(version.getBuildId(), source + " buildId", 255);
        require(
                canonical.equals(version.toCanonicalString()),
                source + " pinnedVersion is noncanonical");
        return new PinnedVersion(
                version.getDeploymentName(), version.getBuildId(), canonical);
    }

    private static VersioningOverride versioningOverride(PinnedVersion version) {
        return VersioningOverride.newBuilder()
                .setBehavior(VERSIONING_BEHAVIOR_PINNED)
                .setPinnedVersion(version.canonicalVersion())
                .build();
    }

    static UpdateWorkflowExecutionOptionsRequest updateRequest(PinCommand command) {
        Objects.requireNonNull(command, "command");
        VersioningOverride override = versioningOverride(command.targetVersion());
        return UpdateWorkflowExecutionOptionsRequest.newBuilder()
                .setNamespace(command.namespace())
                .setWorkflowExecution(
                        WorkflowExecution.newBuilder()
                                .setWorkflowId(command.workflowId())
                                .setRunId(command.runId()))
                .setWorkflowExecutionOptions(
                        WorkflowExecutionOptions.newBuilder().setVersioningOverride(override))
                .setUpdateMask(FieldMask.newBuilder().addPaths(command.updateMaskPath()))
                .setIdentity(command.identity())
                .build();
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

    private static String canonicalSha256(String... values) {
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
                CaseProcessWorkflowImpl.class.getResourceAsStream("CaseProcessWorkflowImpl.class")) {
            if (input == null) {
                throw new IOException("loaded CaseProcess workflow class resource is missing");
            }
            return input.readAllBytes();
        }
    }

    private static String buildWorktreeBinding(String buildId) {
        Matcher matcher = LEGACY_CONTROL_BUILD_ID.matcher(buildId);
        require(matcher.matches(), "legacy control build ID shape is invalid");
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
                "Exact CaseProcess pin recovery"
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
        PINNED,
        ALREADY_PINNED
    }

    enum WorkflowStatus {
        RUNNING,
        OTHER
    }

    enum PendingTaskState {
        SCHEDULED,
        OTHER
    }

    enum OverrideKind {
        ABSENT,
        PINNED,
        OTHER
    }

    record RecoveryRequest(
            String address,
            String namespace,
            String workflowId,
            String runId,
            String legacyBuildId,
            Path retainedClasses,
            String recoveryDeploymentName,
            String recoveryBuildId,
            String recoveryPinnedVersion,
            long pendingWorkflowTaskScheduledEventId,
            int pendingWorkflowTaskAttempt,
            String pendingChildWorkflowId,
            String pendingChildRunId,
            String pendingChildWorkflowType,
            long pendingChildInitiatedEventId,
            long expectedLastEventId,
            long expectedLastSignalEventId,
            String expectedSignalName,
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
                        values.get("legacy-build-id"),
                        Path.of(values.get("retained-classes")),
                        values.get("recovery-deployment-name"),
                        values.get("recovery-build-id"),
                        values.get("recovery-pinned-version"),
                        Long.parseLong(values.get("pending-workflow-task-scheduled-event-id")),
                        Integer.parseInt(values.get("pending-workflow-task-attempt")),
                        values.get("pending-child-workflow-id"),
                        values.get("pending-child-run-id"),
                        values.get("pending-child-workflow-type"),
                        Long.parseLong(values.get("pending-child-initiated-event-id")),
                        Long.parseLong(values.get("expected-last-event-id")),
                        Long.parseLong(values.get("expected-last-signal-event-id")),
                        values.get("expected-signal-name"),
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
                    legacyBuildId,
                    retainedClasses,
                    recoveryDeploymentName,
                    recoveryBuildId,
                    recoveryPinnedVersion,
                    pendingWorkflowTaskScheduledEventId,
                    pendingWorkflowTaskAttempt,
                    pendingChildWorkflowId,
                    pendingChildRunId,
                    pendingChildWorkflowType,
                    pendingChildInitiatedEventId,
                    expectedLastEventId,
                    expectedLastSignalEventId,
                    expectedSignalName,
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

    record CurrentOverride(OverrideKind kind, String deploymentName, String buildId) {
        static CurrentOverride absent() {
            return new CurrentOverride(OverrideKind.ABSENT, "", "");
        }

        static CurrentOverride pinned(String deploymentName, String buildId) {
            return new CurrentOverride(OverrideKind.PINNED, deploymentName, buildId);
        }

        static CurrentOverride other() {
            return new CurrentOverride(OverrideKind.OTHER, "", "");
        }
    }

    record PendingChildAuthority(
            String workflowId, String runId, String workflowType, long initiatedEventId) {}

    record ExecutionAuthority(
            String workflowId,
            String runId,
            String workflowType,
            WorkflowStatus status,
            String taskQueue,
            String assignedBuildId,
            String mostRecentBuildId,
            boolean versioned,
            PendingTaskState pendingState,
            int pendingAttempt,
            int pendingActivities,
            List<PendingChildAuthority> pendingChildren,
            CurrentOverride currentOverride,
            boolean deploymentTransition,
            boolean versionTransition) {
        ExecutionAuthority {
            pendingChildren = List.copyOf(pendingChildren);
        }
    }

    record HistoryAuthority(
            int eventCount,
            long lastEventId,
            long lastSignalEventId,
            long pendingWorkflowTaskScheduledEventId,
            String historySha256,
            PendingChildAuthority pendingChild) {}

    record RecoveryPlan(
            RecoveryRequest request,
            Path retainedClasses,
            String retainedWorktreeBinding,
            String workflowClassSha256,
            String serverVersion,
            ExecutionAuthority execution,
            HistoryAuthority history,
            PinnedVersion targetVersion,
            boolean alreadyPinned,
            String authoritySha256,
            PinCommand pinCommand) {}

    record PinCommand(
            String namespace,
            String workflowId,
            String runId,
            PinnedVersion targetVersion,
            String updateMaskPath,
            String identity) {}

    record PinOutcome(PinnedVersion version) {}

    record OperationResult(
            Disposition disposition,
            String workflowId,
            String runId,
            String pinnedVersion,
            String authoritySha256) {
        static OperationResult prepared(RecoveryPlan plan) {
            return from(plan, Disposition.PREPARED);
        }

        static OperationResult pinned(RecoveryPlan plan) {
            return from(plan, Disposition.PINNED);
        }

        static OperationResult alreadyPinned(RecoveryPlan plan) {
            return from(plan, Disposition.ALREADY_PINNED);
        }

        private static OperationResult from(RecoveryPlan plan, Disposition disposition) {
            return new OperationResult(
                    disposition,
                    plan.request().workflowId(),
                    plan.request().runId(),
                    plan.targetVersion().canonicalVersion(),
                    plan.authoritySha256());
        }
    }

    private record ChildHistoryState(
            String workflowId,
            String runId,
            String workflowType,
            long initiatedEventId,
            long startedEventId) {
        PendingChildAuthority asPendingAuthority() {
            return new PendingChildAuthority(
                    workflowId, runId, workflowType, initiatedEventId);
        }
    }

    private record ChildTerminalAuthority(long initiatedEventId, long startedEventId) {}

    @FunctionalInterface
    interface ClassBytesSource {
        byte[] read() throws IOException;
    }

    interface TemporalAuthority {
        String serverVersion();

        ExecutionAuthority describe(RecoveryRequest request);

        List<HistoryEvent> loadCompleteHistory(RecoveryRequest request);
    }

    @FunctionalInterface
    interface PinExecutor {
        PinOutcome pin(PinCommand command);
    }

    private static final class SdkSession implements AutoCloseable, TemporalAuthority, PinExecutor {
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
        public ExecutionAuthority describe(RecoveryRequest request) {
            DescribeWorkflowExecutionResponse response =
                    service.describeWorkflowExecution(
                            DescribeWorkflowExecutionRequest.newBuilder()
                                    .setNamespace(request.namespace())
                                    .setExecution(workflowExecution(request))
                                    .build());
            require(
                    response.hasExecutionConfig()
                            && response.hasWorkflowExecutionInfo()
                            && response.hasPendingWorkflowTask(),
                    "workflow description authority is incomplete");
            WorkflowExecutionInfo info = response.getWorkflowExecutionInfo();
            boolean versioned =
                    info.hasMostRecentWorkerVersionStamp()
                            && info.getMostRecentWorkerVersionStamp().getUseVersioning();
            List<PendingChildAuthority> children =
                    response.getPendingChildrenList().stream()
                            .map(SdkSession::pendingChildAuthority)
                            .toList();
            boolean deploymentTransition =
                    info.hasVersioningInfo()
                            && info.getVersioningInfo().hasDeploymentTransition();
            boolean versionTransition =
                    info.hasVersioningInfo() && info.getVersioningInfo().hasVersionTransition();
            return new ExecutionAuthority(
                    info.getExecution().getWorkflowId(),
                    info.getExecution().getRunId(),
                    info.getType().getName(),
                    info.getStatus() == WORKFLOW_EXECUTION_STATUS_RUNNING
                            ? WorkflowStatus.RUNNING
                            : WorkflowStatus.OTHER,
                    response.getExecutionConfig().getTaskQueue().getName(),
                    info.getAssignedBuildId(),
                    versioned ? info.getMostRecentWorkerVersionStamp().getBuildId() : "",
                    versioned,
                    response.getPendingWorkflowTask().getState()
                                    == PENDING_WORKFLOW_TASK_STATE_SCHEDULED
                            ? PendingTaskState.SCHEDULED
                            : PendingTaskState.OTHER,
                    response.getPendingWorkflowTask().getAttempt(),
                    response.getPendingActivitiesCount(),
                    children,
                    currentOverride(info),
                    deploymentTransition,
                    versionTransition);
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
        public PinOutcome pin(PinCommand command) {
            UpdateWorkflowExecutionOptionsRequest request = updateRequest(command);
            UpdateWorkflowExecutionOptionsResponse response =
                    service.updateWorkflowExecutionOptions(request);
            require(
                    response.hasWorkflowExecutionOptions(),
                    "pin response has no workflow execution options");
            return new PinOutcome(
                    requirePinnedVersion(response.getWorkflowExecutionOptions(), "pin response"));
        }

        private static PendingChildAuthority pendingChildAuthority(PendingChildExecutionInfo child) {
            requireCanonicalUuid(child.getRunId(), "pending child runId");
            require(
                    !child.getWorkflowId().isBlank()
                            && !child.getWorkflowTypeName().isBlank()
                            && child.getInitiatedId() > 0,
                    "pending child describe authority is incomplete");
            return new PendingChildAuthority(
                    child.getWorkflowId(),
                    child.getRunId(),
                    child.getWorkflowTypeName(),
                    child.getInitiatedId());
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
