package com.example.dispute.workflow.recovery;

import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.DOMAIN_EVENT_SIGNAL;
import static io.temporal.api.enums.v1.PendingWorkflowTaskState.PENDING_WORKFLOW_TASK_STATE_SCHEDULED;
import static io.temporal.api.enums.v1.ResetReapplyType.RESET_REAPPLY_TYPE_SIGNAL;
import static io.temporal.api.enums.v1.TaskQueueKind.TASK_QUEUE_KIND_NORMAL;
import static io.temporal.api.enums.v1.TaskQueueKind.TASK_QUEUE_KIND_STICKY;
import static io.temporal.api.enums.v1.TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_START;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;

import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.ResetReapplyType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse;
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
 * Standalone, target-only reset operator for one exact legacy CaseProcess execution.
 *
 * <p>The operator has two explicit phases. {@code PREPARE} validates immutable class material,
 * current execution authority, and a bounded complete history without mutating Temporal. The
 * complete history is required to prove that the server's reset base contains no pending child
 * workflow. {@code APPLY} requires the hashes emitted by PREPARE, repeats the complete read-side
 * validation, and issues one idempotent ResetWorkflowExecution request. No history payload is
 * printed.
 */
public final class ExactCaseProcessWorkflowResetRecoveryMain {

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
    static final String NO_TIMER = "NONE";
    static final String RESET_IDENTITY = "exact-case-process-reset-recovery.v1";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAXIMUM_HISTORY_EVENTS = 1000;
    private static final int MAXIMUM_HISTORY_PAGES = 100;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SAFE_TIMER_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern LEGACY_CONTROL_BUILD_ID =
            Pattern.compile("[A-Za-z0-9._-]+-([0-9a-f]{64})-control");
    private static final Set<String> COMMON_ARGUMENTS =
            Set.of(
                    "address",
                    "namespace",
                    "workflow-id",
                    "run-id",
                    "legacy-build-id",
                    "retained-classes",
                    "reset-workflow-task-finish-event-id",
                    "expected-signal-name",
                    "expected-timer-id",
                    "request-id",
                    "reason",
                    "history-max-events",
                    "mode");
    private static final Set<String> APPLY_ARGUMENTS =
            Set.of("expected-history-suffix-sha256", "expected-authority-sha256");
    private static final Set<String> ALL_ARGUMENTS = allArguments();

    private ExactCaseProcessWorkflowResetRecoveryMain() {}

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
                                ExactCaseProcessWorkflowResetRecoveryMain::loadedWorkflowClassBytes,
                                session);
                printSafe(result);
            }
            return 0;
        } catch (RuntimeException | IOException failure) {
            System.err.println("Exact CaseProcess reset recovery failed closed.");
            return 2;
        }
    }

    static OperationResult operate(
            RecoveryRequest request,
            TemporalAuthority authority,
            ClassBytesSource currentWorkflowClass,
            ResetExecutor resetExecutor)
            throws IOException {
        Objects.requireNonNull(resetExecutor, "resetExecutor");
        RecoveryPlan prepared = prepare(request, authority, currentWorkflowClass);
        if (request.mode() == Mode.PREPARE) {
            return OperationResult.prepared(prepared);
        }

        require(
                prepared.historySuffixSha256().equals(request.expectedHistorySuffixSha256()),
                "history suffix changed after PREPARE");
        require(
                prepared.authoritySha256().equals(request.expectedAuthoritySha256()),
                "reset authority changed after PREPARE");

        // Narrow the read-to-reset window. The server reset API has no history-version CAS, so a
        // second complete read is mandatory immediately before the single mutating RPC.
        RecoveryPlan revalidated = prepare(request, authority, currentWorkflowClass);
        require(prepared.equals(revalidated), "reset authority drifted during APPLY revalidation");

        ResetOutcome outcome = resetExecutor.reset(prepared.resetCommand());
        require(outcome != null, "reset response is missing");
        requireCanonicalUuid(outcome.newRunId(), "new reset runId");
        require(
                !prepared.request().runId().equals(outcome.newRunId()),
                "reset response did not create a new run");
        return OperationResult.applied(prepared, outcome.newRunId());
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
        String retainedWorkflowHash = sha256(retainedWorkflowBytes);
        require(
                retainedWorkflowHash.equals(sha256(currentWorkflowBytes)),
                "retained and current CaseProcess workflow classes differ");

        ExecutionAuthority execution = authority.describe(request);
        validateExecution(request, execution);
        List<HistoryEvent> completeHistory = List.copyOf(authority.loadCompleteHistory(request));
        HistoryValidation history = validateHistory(request, execution, completeHistory);
        require(
                execution.pendingChildren() == 0,
                "workflow currently has pending child workflows");

        ResetCommand command =
                new ResetCommand(
                        request.namespace(),
                        request.workflowId(),
                        request.runId(),
                        request.resetWorkflowTaskFinishEventId(),
                        request.requestId(),
                        request.reason(),
                        RESET_REAPPLY_TYPE_SIGNAL,
                        RESET_IDENTITY);
        String authoritySha256 =
                authoritySha256(
                        request,
                        retainedRoot,
                        retainedBinding,
                        retainedWorkflowHash,
                        execution,
                        history);
        return new RecoveryPlan(
                request,
                retainedRoot,
                retainedBinding,
                retainedWorkflowHash,
                execution,
                history.completeEventCount(),
                history.lastEventId(),
                history.resetBaseSha256(),
                history.suffixEventCount(),
                history.historySuffixSha256(),
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
        require(
                request.resetWorkflowTaskFinishEventId() > 0,
                "reset workflow-task finish event ID must be positive");
        require(
                DOMAIN_EVENT_SIGNAL.equals(request.expectedSignalName()),
                "expected signal name is not the CaseProcess domain-event protocol signal");
        require(
                NO_TIMER.equals(request.expectedTimerId())
                        || SAFE_TIMER_ID.matcher(request.expectedTimerId()).matches(),
                "expected timer ID is invalid");
        requireCanonicalUuid(request.requestId(), "requestId");
        requireText(request.reason(), "reason", 256);
        require(
                request.reason().chars().noneMatch(Character::isISOControl),
                "reason contains control characters");
        require(
                request.historyMaxEvents() >= 2
                        && request.historyMaxEvents() <= MAXIMUM_HISTORY_EVENTS,
                "history maximum is outside the bounded range");
        Objects.requireNonNull(request.mode(), "mode");
        buildWorktreeBinding(request.legacyBuildId());
        if (request.mode() == Mode.PREPARE) {
            require(
                    request.expectedHistorySuffixSha256() == null
                            && request.expectedAuthoritySha256() == null,
                    "PREPARE must not carry APPLY hashes");
        } else {
            requireSha256(request.expectedHistorySuffixSha256(), "expected history suffix hash");
            requireSha256(request.expectedAuthoritySha256(), "expected authority hash");
        }
    }

    private static void validateExecution(
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
        require(execution.assignedBuildId().isBlank(), "workflow assigned build ID is not blank");
        require(
                execution.versioned()
                        && request.legacyBuildId().equals(execution.mostRecentBuildId()),
                "workflow versioned build authority drifted");
        require(
                execution.pendingState() == PendingTaskState.SCHEDULED
                        && execution.pendingAttempt() > 0,
                "workflow does not have a scheduled workflow task");
        require(execution.pendingActivities() == 0, "workflow has pending activities");
    }

    private static HistoryValidation validateHistory(
            RecoveryRequest request,
            ExecutionAuthority execution,
            List<HistoryEvent> completeHistory) {
        require(completeHistory != null, "complete history is missing");
        require(
                completeHistory.size() >= 2
                        && completeHistory.size() <= request.historyMaxEvents(),
                "complete history size is invalid");
        require(
                completeHistory.getFirst().getEventId() == 1
                        && completeHistory
                                .getFirst()
                                .hasWorkflowExecutionStartedEventAttributes(),
                "complete history does not start at workflow execution event 1");

        int resetIndex = -1;
        long expectedEventId = 1;
        for (int index = 0; index < completeHistory.size(); index++) {
            HistoryEvent event = completeHistory.get(index);
            require(event.getEventId() == expectedEventId, "complete history is not contiguous");
            if (event.getEventId() == request.resetWorkflowTaskFinishEventId()) {
                resetIndex = index;
            }
            expectedEventId++;
        }
        require(resetIndex >= 1, "reset workflow-task finish event is absent from complete history");
        HistoryEvent resetEvent = completeHistory.get(resetIndex);
        require(
                resetEvent.hasWorkflowTaskCompletedEventAttributes(),
                "reset boundary is not the exact workflow-task completion event");

        List<HistoryEvent> resetBase = completeHistory.subList(0, resetIndex);
        validateNoPendingChildAtResetBase(resetBase);
        List<HistoryEvent> historySuffix = completeHistory.subList(resetIndex, completeHistory.size());
        require(historySuffix.size() >= 2, "history suffix is incomplete");

        int signalCount = 0;
        HistoryEvent timerStarted = null;
        HistoryEvent timerFired = null;
        List<HistoryEvent> workflowTaskEvents = new ArrayList<>();
        for (HistoryEvent event : historySuffix) {
            if (event == resetEvent) {
                continue;
            }
            if (event.hasWorkflowExecutionSignaledEventAttributes()) {
                signalCount++;
                require(
                        request.expectedSignalName()
                                .equals(
                                        event.getWorkflowExecutionSignaledEventAttributes()
                                                .getSignalName()),
                        "history contains an unexpected signal");
            } else if (event.hasTimerStartedEventAttributes()) {
                require(!NO_TIMER.equals(request.expectedTimerId()), "history contains a timer");
                require(timerStarted == null, "history contains multiple timer starts");
                require(
                        request.expectedTimerId()
                                        .equals(event.getTimerStartedEventAttributes().getTimerId())
                                && event.getTimerStartedEventAttributes()
                                                .getWorkflowTaskCompletedEventId()
                                        == request.resetWorkflowTaskFinishEventId(),
                        "timer start does not bind the reset boundary");
                timerStarted = event;
            } else if (event.hasTimerFiredEventAttributes()) {
                require(!NO_TIMER.equals(request.expectedTimerId()), "history contains a timer");
                require(timerFired == null, "history contains multiple timer fires");
                require(
                        request.expectedTimerId()
                                .equals(event.getTimerFiredEventAttributes().getTimerId()),
                        "timer fire identity drifted");
                timerFired = event;
            } else if (event.hasWorkflowTaskScheduledEventAttributes()
                    || event.hasWorkflowTaskTimedOutEventAttributes()) {
                workflowTaskEvents.add(event);
            } else {
                throw new IllegalStateException("history contains an event outside the reset suffix");
            }
        }

        require(signalCount == 1, "history must contain exactly one expected signal");
        validateTimer(request, timerStarted, timerFired);
        validateWorkflowTaskSuffix(execution, workflowTaskEvents);
        String resetBaseHash = historySha256(resetBase);
        String suffixHash = historySha256(historySuffix);
        return new HistoryValidation(
                completeHistory.size(),
                completeHistory.getLast().getEventId(),
                resetBaseHash,
                historySuffix.size(),
                suffixHash);
    }

    private static void validateNoPendingChildAtResetBase(List<HistoryEvent> resetBase) {
        Map<Long, Long> pendingChildren = new HashMap<>();
        for (HistoryEvent event : resetBase) {
            if (event.hasStartChildWorkflowExecutionInitiatedEventAttributes()) {
                require(
                        pendingChildren.putIfAbsent(event.getEventId(), 0L) == null,
                        "child workflow initiation is duplicated");
                continue;
            }
            if (event.hasStartChildWorkflowExecutionFailedEventAttributes()) {
                long initiatedEventId =
                        event.getStartChildWorkflowExecutionFailedEventAttributes()
                                .getInitiatedEventId();
                require(
                        pendingChildren.remove(initiatedEventId) != null,
                        "child workflow start failure has no initiation authority");
                continue;
            }
            if (event.hasChildWorkflowExecutionStartedEventAttributes()) {
                long initiatedEventId =
                        event.getChildWorkflowExecutionStartedEventAttributes()
                                .getInitiatedEventId();
                Long priorStartedEventId = pendingChildren.get(initiatedEventId);
                require(
                        priorStartedEventId != null && priorStartedEventId == 0,
                        "child workflow start has no unique initiation authority");
                pendingChildren.put(initiatedEventId, event.getEventId());
                continue;
            }
            ChildTerminalAuthority terminal = childTerminalAuthority(event);
            if (terminal != null) {
                Long startedEventId = pendingChildren.remove(terminal.initiatedEventId());
                require(
                        startedEventId != null
                                && startedEventId > 0
                                && startedEventId == terminal.startedEventId(),
                        "child workflow terminal event has no exact start authority");
            }
        }
        require(
                pendingChildren.isEmpty(),
                "TARGET_INELIGIBLE_PENDING_CHILD: reset base retains pending child workflow");
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

    private static void validateTimer(
            RecoveryRequest request, HistoryEvent timerStarted, HistoryEvent timerFired) {
        if (NO_TIMER.equals(request.expectedTimerId())) {
            require(timerStarted == null && timerFired == null, "unexpected timer authority");
            return;
        }
        require(timerStarted != null && timerFired != null, "expected timer pair is incomplete");
        require(
                timerFired.getEventId() > timerStarted.getEventId()
                        && timerFired.getTimerFiredEventAttributes().getStartedEventId()
                                == timerStarted.getEventId(),
                "timer fire does not bind the expected timer start");
    }

    private static void validateWorkflowTaskSuffix(
            ExecutionAuthority execution, List<HistoryEvent> workflowTaskEvents) {
        require(
                workflowTaskEvents.size() == 1 || workflowTaskEvents.size() == 3,
                "workflow-task suffix is not a normal schedule or sticky-timeout recovery");
        int normalIndex = 0;
        if (workflowTaskEvents.size() == 3) {
            HistoryEvent sticky = workflowTaskEvents.get(0);
            HistoryEvent timedOut = workflowTaskEvents.get(1);
            require(
                    sticky.hasWorkflowTaskScheduledEventAttributes()
                            && sticky.getWorkflowTaskScheduledEventAttributes()
                                            .getTaskQueue()
                                            .getKind()
                                    == TASK_QUEUE_KIND_STICKY,
                    "workflow-task suffix does not start with a sticky schedule");
            require(
                    timedOut.hasWorkflowTaskTimedOutEventAttributes()
                            && timedOut.getWorkflowTaskTimedOutEventAttributes().getTimeoutType()
                                    == TIMEOUT_TYPE_SCHEDULE_TO_START
                            && timedOut.getWorkflowTaskTimedOutEventAttributes()
                                            .getScheduledEventId()
                                    == sticky.getEventId()
                            && timedOut.getWorkflowTaskTimedOutEventAttributes().getStartedEventId()
                                    == 0,
                    "sticky workflow task did not time out before start");
            normalIndex = 2;
        }
        HistoryEvent normal = workflowTaskEvents.get(normalIndex);
        require(
                normal.hasWorkflowTaskScheduledEventAttributes(),
                "history has no final normal workflow-task schedule");
        var scheduled = normal.getWorkflowTaskScheduledEventAttributes();
        require(
                scheduled.hasTaskQueue()
                        && scheduled.getTaskQueue().getKind() == TASK_QUEUE_KIND_NORMAL
                        && CASE_CONTROL_TASK_QUEUE.equals(scheduled.getTaskQueue().getName())
                        && scheduled.getAttempt() == execution.pendingAttempt(),
                "normal workflow-task schedule does not match pending authority");
    }

    private static String authoritySha256(
            RecoveryRequest request,
            Path retainedRoot,
            String retainedBinding,
            String workflowClassSha256,
            ExecutionAuthority execution,
            HistoryValidation history) {
        return canonicalSha256(
                "exact-case-process-reset-authority.v1",
                request.address(),
                request.namespace(),
                request.workflowId(),
                request.runId(),
                request.legacyBuildId(),
                retainedRoot.toString(),
                retainedBinding,
                workflowClassSha256,
                Long.toString(request.resetWorkflowTaskFinishEventId()),
                request.expectedSignalName(),
                request.expectedTimerId(),
                request.requestId(),
                sha256(request.reason().getBytes(StandardCharsets.UTF_8)),
                Integer.toString(request.historyMaxEvents()),
                execution.workflowType(),
                execution.status().name(),
                execution.taskQueue(),
                execution.assignedBuildId(),
                execution.mostRecentBuildId(),
                Boolean.toString(execution.versioned()),
                execution.pendingState().name(),
                Integer.toString(execution.pendingAttempt()),
                Integer.toString(execution.pendingActivities()),
                Integer.toString(execution.pendingChildren()),
                Integer.toString(history.completeEventCount()),
                Long.toString(history.lastEventId()),
                history.resetBaseSha256(),
                Integer.toString(history.suffixEventCount()),
                history.historySuffixSha256());
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
                byte[] bytes = Objects.requireNonNull(value, "canonical authority value")
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
                CaseProcessWorkflowImpl.class.getResourceAsStream(
                        "CaseProcessWorkflowImpl.class")) {
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
            require(UUID.fromString(value).toString().equals(value), field + " must be a canonical UUID");
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
        if (result.mode() == Mode.PREPARE) {
            System.out.println(
                    "Exact CaseProcess reset PREPARED"
                            + " workflowId="
                            + result.workflowId()
                            + " runId="
                            + result.sourceRunId()
                            + " resetWorkflowTaskFinishEventId="
                            + result.resetWorkflowTaskFinishEventId()
                            + " resetBaseSha256="
                            + result.resetBaseSha256()
                            + " historySuffixSha256="
                            + result.historySuffixSha256()
                            + " authoritySha256="
                            + result.authoritySha256());
            return;
        }
        System.out.println(
                "Exact CaseProcess reset APPLIED"
                        + " workflowId="
                        + result.workflowId()
                        + " sourceRunId="
                        + result.sourceRunId()
                        + " newRunId="
                        + result.newRunId());
    }

    enum Mode {
        PREPARE,
        APPLY
    }

    enum WorkflowStatus {
        RUNNING,
        OTHER
    }

    enum PendingTaskState {
        SCHEDULED,
        OTHER
    }

    record RecoveryRequest(
            String address,
            String namespace,
            String workflowId,
            String runId,
            String legacyBuildId,
            Path retainedClasses,
            long resetWorkflowTaskFinishEventId,
            String expectedSignalName,
            String expectedTimerId,
            String requestId,
            String reason,
            int historyMaxEvents,
            Mode mode,
            String expectedHistorySuffixSha256,
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
            require(values.keySet().containsAll(COMMON_ARGUMENTS), "required recovery arguments are missing");
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
                        Long.parseLong(values.get("reset-workflow-task-finish-event-id")),
                        values.get("expected-signal-name"),
                        values.get("expected-timer-id"),
                        values.get("request-id"),
                        values.get("reason"),
                        Integer.parseInt(values.get("history-max-events")),
                        mode,
                        values.get("expected-history-suffix-sha256"),
                        values.get("expected-authority-sha256"));
            } catch (RuntimeException invalidArgument) {
                throw new IllegalStateException("recovery arguments are invalid", invalidArgument);
            }
        }

        RecoveryRequest forApply(String historySuffixSha256, String authoritySha256) {
            return new RecoveryRequest(
                    address,
                    namespace,
                    workflowId,
                    runId,
                    legacyBuildId,
                    retainedClasses,
                    resetWorkflowTaskFinishEventId,
                    expectedSignalName,
                    expectedTimerId,
                    requestId,
                    reason,
                    historyMaxEvents,
                    Mode.APPLY,
                    historySuffixSha256,
                    authoritySha256);
        }
    }

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
            int pendingChildren) {}

    record RecoveryPlan(
            RecoveryRequest request,
            Path retainedClasses,
            String retainedWorktreeBinding,
            String workflowClassSha256,
            ExecutionAuthority execution,
            int historyEventCount,
            long historyLastEventId,
            String resetBaseSha256,
            int historySuffixEventCount,
            String historySuffixSha256,
            String authoritySha256,
            ResetCommand resetCommand) {}

    record ResetCommand(
            String namespace,
            String workflowId,
            String runId,
            long workflowTaskFinishEventId,
            String requestId,
            String reason,
            ResetReapplyType resetReapplyType,
            String identity) {}

    record ResetOutcome(String newRunId) {}

    record OperationResult(
            Mode mode,
            String workflowId,
            String sourceRunId,
            long resetWorkflowTaskFinishEventId,
            String resetBaseSha256,
            String historySuffixSha256,
            String authoritySha256,
            String newRunId) {

        static OperationResult prepared(RecoveryPlan plan) {
            return new OperationResult(
                    Mode.PREPARE,
                    plan.request().workflowId(),
                    plan.request().runId(),
                    plan.request().resetWorkflowTaskFinishEventId(),
                    plan.resetBaseSha256(),
                    plan.historySuffixSha256(),
                    plan.authoritySha256(),
                    null);
        }

        static OperationResult applied(RecoveryPlan plan, String newRunId) {
            return new OperationResult(
                    Mode.APPLY,
                    plan.request().workflowId(),
                    plan.request().runId(),
                    plan.request().resetWorkflowTaskFinishEventId(),
                    plan.resetBaseSha256(),
                    plan.historySuffixSha256(),
                    plan.authoritySha256(),
                    newRunId);
        }
    }

    private record HistoryValidation(
            int completeEventCount,
            long lastEventId,
            String resetBaseSha256,
            int suffixEventCount,
            String historySuffixSha256) {}

    private record ChildTerminalAuthority(long initiatedEventId, long startedEventId) {}

    @FunctionalInterface
    interface ClassBytesSource {
        byte[] read() throws IOException;
    }

    interface TemporalAuthority {
        ExecutionAuthority describe(RecoveryRequest request);

        List<HistoryEvent> loadCompleteHistory(RecoveryRequest request);
    }

    @FunctionalInterface
    interface ResetExecutor {
        ResetOutcome reset(ResetCommand command);
    }

    private static final class SdkSession
            implements AutoCloseable, TemporalAuthority, ResetExecutor {
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
        public ExecutionAuthority describe(RecoveryRequest request) {
            WorkflowExecution execution = workflowExecution(request);
            DescribeWorkflowExecutionResponse response =
                    service.describeWorkflowExecution(
                            DescribeWorkflowExecutionRequest.newBuilder()
                                    .setNamespace(request.namespace())
                                    .setExecution(execution)
                                    .build());
            require(
                    response.hasExecutionConfig()
                            && response.hasWorkflowExecutionInfo()
                            && response.hasPendingWorkflowTask(),
                    "workflow description authority is incomplete");
            WorkflowExecutionInfo info = response.getWorkflowExecutionInfo();
            var pending = response.getPendingWorkflowTask();
            boolean versioned =
                    info.hasMostRecentWorkerVersionStamp()
                            && info.getMostRecentWorkerVersionStamp().getUseVersioning();
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
                    pending.getState() == PENDING_WORKFLOW_TASK_STATE_SCHEDULED
                            ? PendingTaskState.SCHEDULED
                            : PendingTaskState.OTHER,
                    pending.getAttempt(),
                    response.getPendingActivitiesCount(),
                    response.getPendingChildrenCount());
        }

        @Override
        public List<HistoryEvent> loadCompleteHistory(RecoveryRequest request) {
            WorkflowExecution execution = workflowExecution(request);
            List<HistoryEvent> completeHistory = new ArrayList<>();
            ByteString token = ByteString.EMPTY;
            long expectedEventId = 1;
            for (int page = 0; page < MAXIMUM_HISTORY_PAGES; page++) {
                GetWorkflowExecutionHistoryResponse response =
                        service.getWorkflowExecutionHistory(
                                GetWorkflowExecutionHistoryRequest.newBuilder()
                                        .setNamespace(request.namespace())
                                        .setExecution(execution)
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
        public ResetOutcome reset(ResetCommand command) {
            ResetWorkflowExecutionRequest request =
                    ResetWorkflowExecutionRequest.newBuilder()
                            .setNamespace(command.namespace())
                            .setWorkflowExecution(
                                    WorkflowExecution.newBuilder()
                                            .setWorkflowId(command.workflowId())
                                            .setRunId(command.runId()))
                            .setReason(command.reason())
                            .setWorkflowTaskFinishEventId(command.workflowTaskFinishEventId())
                            .setRequestId(command.requestId())
                            .setResetReapplyType(command.resetReapplyType())
                            .setIdentity(command.identity())
                            .build();
            ResetWorkflowExecutionResponse response = service.resetWorkflowExecution(request);
            return new ResetOutcome(response.getRunId());
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
