package com.example.dispute.workflow.recovery;

import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_CONTROL_TASK_QUEUE;
import static com.example.dispute.workflow.contract.v1.CaseProcessWorkflowProtocol.CASE_WORKFLOW_TYPE;
import static io.temporal.api.enums.v1.PendingWorkflowTaskState.PENDING_WORKFLOW_TASK_STATE_SCHEDULED;
import static io.temporal.api.enums.v1.TaskQueueKind.TASK_QUEUE_KIND_NORMAL;
import static io.temporal.api.enums.v1.WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING;

import com.example.dispute.workflow.temporal.caseprocess.CaseProcessWorkflowImpl;
import com.example.dispute.workflow.observability.TemporalTraceContextPropagator;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryReverseRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryReverseResponse;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone, workflow-only recovery bridge for an exact legacy CaseProcess execution.
 *
 * <p>This entrypoint deliberately does not start Spring, register activities, create workers for
 * any queue other than {@code case-control}, or infer workflow authority from case data. It is
 * suitable only for a bounded, operator-supplied recovery of one already-running execution.
 */
public final class LegacyCaseProcessWorkflowRecoveryMain {

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
    static final Duration MINIMUM_RUNTIME = Duration.ofSeconds(1);
    static final Duration MAXIMUM_RUNTIME = Duration.ofHours(8);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int VISIBILITY_PAGE_SIZE = 1000;
    private static final int MAXIMUM_VISIBILITY_PAGES = 100;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern LEGACY_CONTROL_BUILD_ID =
            Pattern.compile("[A-Za-z0-9._-]+-([0-9a-f]{64})-control");
    private static final Set<String> REQUIRED_ARGUMENTS =
            Set.of(
                    "address",
                    "namespace",
                    "workflow-id",
                    "run-id",
                    "legacy-build-id",
                    "retained-classes",
                    "max-runtime");

    private LegacyCaseProcessWorkflowRecoveryMain() {}

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
                RecoveryPlan plan =
                        prepare(
                                request,
                                session.authority(),
                                LegacyCaseProcessWorkflowRecoveryMain::loadedWorkflowClassBytes);
                try (SdkWorkflowOnlyRuntime runtime = session.workflowOnlyRuntime(plan)) {
                    Thread shutdownHook =
                            new Thread(runtime::close, "legacy-case-process-recovery-shutdown");
                    Runtime.getRuntime().addShutdownHook(shutdownHook);
                    try {
                        runBridge(plan, runtime);
                    } finally {
                        removeShutdownHook(shutdownHook);
                    }
                }
            }
            System.out.println("Legacy CaseProcess workflow recovery bridge completed its bounded run.");
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("Legacy CaseProcess workflow recovery bridge was interrupted.");
            return 130;
        } catch (RuntimeException | IOException failure) {
            System.err.println("Legacy CaseProcess workflow recovery bridge failed closed.");
            return 2;
        }
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
        List<OpenExecutionAuthority> buildExecutions =
                List.copyOf(authority.openExecutionsBoundTo(request.namespace(), request.legacyBuildId()));
        require(
                buildExecutions.size() == 1,
                "legacy build ID does not bind exactly one open execution");
        OpenExecutionAuthority openExecution = buildExecutions.getFirst();
        require(
                openExecution.workflowId().equals(request.workflowId())
                        && openExecution.runId().equals(request.runId())
                        && openExecution.workflowType().equals(CASE_WORKFLOW_TYPE)
                        && openExecution.taskQueue().equals(CASE_CONTROL_TASK_QUEUE)
                        && openExecution.status() == WorkflowStatus.RUNNING
                        && isBuildBound(openExecution, request.legacyBuildId()),
                "the sole legacy-build execution is not the requested CaseProcess run");

        return new RecoveryPlan(
                request.address(),
                request.namespace(),
                request.workflowId(),
                request.runId(),
                request.legacyBuildId(),
                retainedRoot,
                retainedBinding,
                retainedWorkflowHash,
                request.maxRuntime());
    }

    static void runBridge(RecoveryPlan plan, WorkflowOnlyRuntime runtime)
            throws InterruptedException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(runtime, "runtime");
        runtime.registerWorkflowImplementationTypes(CaseProcessWorkflowImpl.class);
        runtime.start();
        runtime.await(plan.maxRuntime());
    }

    private static void validateRequest(RecoveryRequest request) {
        requireText(request.address(), "address");
        requireText(request.namespace(), "namespace");
        requireText(request.workflowId(), "workflowId");
        requireText(request.runId(), "runId");
        requireText(request.legacyBuildId(), "legacyBuildId");
        require(request.retainedClasses() != null, "retainedClasses is required");
        try {
            require(
                    UUID.fromString(request.runId()).toString().equals(request.runId()),
                    "runId must be a canonical UUID");
        } catch (IllegalArgumentException invalidRunId) {
            throw new IllegalStateException("runId must be a canonical UUID", invalidRunId);
        }
        require(
                !request.maxRuntime().isNegative()
                        && request.maxRuntime().compareTo(MINIMUM_RUNTIME) >= 0
                        && request.maxRuntime().compareTo(MAXIMUM_RUNTIME) <= 0,
                "maxRuntime is outside the bounded recovery window");
        buildWorktreeBinding(request.legacyBuildId());
    }

    private static void validateExecution(
            RecoveryRequest request, ExecutionAuthority execution) {
        require(execution != null, "workflow execution authority is missing");
        require(
                execution.workflowId().equals(request.workflowId())
                        && execution.runId().equals(request.runId()),
                "workflow execution identity drifted");
        require(
                execution.workflowType().equals(CASE_WORKFLOW_TYPE),
                "workflow type is not CaseProcessWorkflow");
        require(
                execution.status() == WorkflowStatus.RUNNING,
                "workflow execution is not running");
        require(
                execution.taskQueue().equals(CASE_CONTROL_TASK_QUEUE)
                        && execution.pendingTaskQueue().equals(CASE_CONTROL_TASK_QUEUE),
                "workflow task queue is not case-control");
        require(
                execution.assignedBuildId().equals(request.legacyBuildId())
                        && execution.versioned()
                        && execution.mostRecentBuildId().equals(request.legacyBuildId()),
                "workflow build assignment drifted");
        require(
                execution.pendingState() == PendingTaskState.SCHEDULED
                        && execution.pendingTaskKind() == PendingTaskKind.NORMAL
                        && execution.pendingAttempt() > 0,
                "workflow does not have a scheduled normal workflow task");
    }

    private static boolean isBuildBound(
            OpenExecutionAuthority execution, String legacyBuildId) {
        return execution.assignedBuildId().equals(legacyBuildId)
                || (execution.versioned() && execution.mostRecentBuildId().equals(legacyBuildId));
    }

    static List<OpenExecutionAuthority> mergeExactBuildVisibilityRows(
            List<WorkflowExecutionInfo> assignedRows,
            List<WorkflowExecutionInfo> versionedRows,
            String legacyBuildId) {
        LinkedHashMap<String, OpenExecutionAuthority> merged = new LinkedHashMap<>();
        mergeExactBuildVisibilityRows(merged, assignedRows, legacyBuildId, true);
        mergeExactBuildVisibilityRows(merged, versionedRows, legacyBuildId, false);
        return List.copyOf(merged.values());
    }

    private static void mergeExactBuildVisibilityRows(
            Map<String, OpenExecutionAuthority> merged,
            List<WorkflowExecutionInfo> rows,
            String legacyBuildId,
            boolean assignedQuery) {
        for (WorkflowExecutionInfo info : rows) {
            require(info.hasExecution() && info.hasType(), "visibility authority is incomplete");
            if (assignedQuery && !info.getAssignedBuildId().isBlank()) {
                require(
                        info.getAssignedBuildId().equals(legacyBuildId),
                        "assigned-build visibility row drifted");
            }
            if (!assignedQuery
                    && info.hasMostRecentWorkerVersionStamp()
                    && info.getMostRecentWorkerVersionStamp().getUseVersioning()) {
                require(
                        info.getMostRecentWorkerVersionStamp().getBuildId().equals(legacyBuildId),
                        "versioned-build visibility row drifted");
            }
            String key =
                    info.getExecution().getWorkflowId()
                            + '\u0000'
                            + info.getExecution().getRunId();
            OpenExecutionAuthority previous = merged.get(key);
            boolean versioned = !assignedQuery || (previous != null && previous.versioned());
            merged.put(
                    key,
                    new OpenExecutionAuthority(
                            info.getExecution().getWorkflowId(),
                            info.getExecution().getRunId(),
                            info.getType().getName(),
                            info.getStatus() == WORKFLOW_EXECUTION_STATUS_RUNNING
                                    ? WorkflowStatus.RUNNING
                                    : WorkflowStatus.OTHER,
                            info.getTaskQueue(),
                            assignedQuery
                                    ? legacyBuildId
                                    : previous == null ? "" : previous.assignedBuildId(),
                            versioned ? legacyBuildId : "",
                            versioned));
        }
    }

    private static String buildWorktreeBinding(String buildId) {
        Matcher matcher = LEGACY_CONTROL_BUILD_ID.matcher(buildId);
        require(matcher.matches(), "legacy control build ID shape is invalid");
        return matcher.group(1);
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireText(String value, String name) {
        require(value != null && !value.isBlank(), name + " is required");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void removeShutdownHook(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException shutdownInProgress) {
            // The hook is already responsible for cleanup.
        }
    }

    record RecoveryRequest(
            String address,
            String namespace,
            String workflowId,
            String runId,
            String legacyBuildId,
            Path retainedClasses,
            Duration maxRuntime) {

        static RecoveryRequest parse(String[] args) {
            Objects.requireNonNull(args, "args");
            Map<String, String> values = new HashMap<>();
            for (String argument : args) {
                require(argument != null && argument.startsWith("--"), "argument shape is invalid");
                int separator = argument.indexOf('=');
                require(separator > 2 && separator < argument.length() - 1, "argument shape is invalid");
                String key = argument.substring(2, separator);
                String value = argument.substring(separator + 1);
                require(REQUIRED_ARGUMENTS.contains(key), "unknown recovery argument");
                require(values.putIfAbsent(key, value) == null, "duplicate recovery argument");
            }
            require(values.keySet().equals(REQUIRED_ARGUMENTS), "required recovery arguments are missing");
            try {
                return new RecoveryRequest(
                        values.get("address"),
                        values.get("namespace"),
                        values.get("workflow-id"),
                        values.get("run-id"),
                        values.get("legacy-build-id"),
                        Path.of(values.get("retained-classes")),
                        Duration.parse(values.get("max-runtime")));
            } catch (RuntimeException invalidArgument) {
                throw new IllegalStateException("recovery arguments are invalid", invalidArgument);
            }
        }
    }

    record RecoveryPlan(
            String address,
            String namespace,
            String workflowId,
            String runId,
            String legacyBuildId,
            Path retainedClasses,
            String retainedWorktreeBinding,
            String workflowClassSha256,
            Duration maxRuntime) {}

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
            PendingTaskKind pendingTaskKind,
            String pendingTaskQueue,
            int pendingAttempt) {}

    record OpenExecutionAuthority(
            String workflowId,
            String runId,
            String workflowType,
            WorkflowStatus status,
            String taskQueue,
            String assignedBuildId,
            String mostRecentBuildId,
            boolean versioned) {}

    enum WorkflowStatus {
        RUNNING,
        OTHER
    }

    enum PendingTaskState {
        SCHEDULED,
        OTHER
    }

    enum PendingTaskKind {
        NORMAL,
        OTHER
    }

    @FunctionalInterface
    interface ClassBytesSource {
        byte[] read() throws IOException;
    }

    interface TemporalAuthority {
        ExecutionAuthority describe(RecoveryRequest request);

        List<OpenExecutionAuthority> openExecutionsBoundTo(
                String namespace, String legacyBuildId);
    }

    interface WorkflowOnlyRuntime extends AutoCloseable {
        void registerWorkflowImplementationTypes(Class<?>... workflowImplementationTypes);

        void registerActivitiesImplementations(Object... activityImplementations);

        void start();

        void await(Duration duration) throws InterruptedException;

        @Override
        void close();
    }

    private static final class SdkSession implements AutoCloseable {
        private final WorkflowServiceStubs serviceStubs;
        private final WorkflowClient workflowClient;
        private final SdkTemporalAuthority authority;

        private SdkSession(
                WorkflowServiceStubs serviceStubs,
                WorkflowClient workflowClient,
                SdkTemporalAuthority authority) {
            this.serviceStubs = serviceStubs;
            this.workflowClient = workflowClient;
            this.authority = authority;
        }

        static SdkSession open(RecoveryRequest request) {
            WorkflowServiceStubsOptions serviceOptions =
                    WorkflowServiceStubsOptions.newBuilder()
                            .setTarget(request.address())
                            .setHealthCheckTimeout(CONNECT_TIMEOUT)
                            .setSystemInfoTimeout(CONNECT_TIMEOUT)
                            .build();
            WorkflowServiceStubs serviceStubs =
                    WorkflowServiceStubs.newServiceStubs(serviceOptions);
            try {
                serviceStubs.connect(CONNECT_TIMEOUT);
                WorkflowClient workflowClient =
                        WorkflowClient.newInstance(
                                serviceStubs,
                                WorkflowClientOptions.newBuilder()
                                        .setNamespace(request.namespace())
                                        .setDataConverter(DefaultDataConverter.newDefaultInstance())
                                        .setContextPropagators(
                                                List.of(new TemporalTraceContextPropagator()))
                                        .build());
                return new SdkSession(
                        serviceStubs,
                        workflowClient,
                        new SdkTemporalAuthority(serviceStubs.blockingStub()));
            } catch (RuntimeException failure) {
                serviceStubs.shutdownNow();
                throw failure;
            }
        }

        TemporalAuthority authority() {
            return authority;
        }

        SdkWorkflowOnlyRuntime workflowOnlyRuntime(RecoveryPlan plan) {
            return new SdkWorkflowOnlyRuntime(workflowClient, plan);
        }

        @Override
        public void close() {
            serviceStubs.shutdown();
            if (!serviceStubs.awaitTermination(5, TimeUnit.SECONDS)) {
                serviceStubs.shutdownNow();
            }
        }
    }

    private static final class SdkTemporalAuthority implements TemporalAuthority {
        private final WorkflowServiceBlockingStub service;

        private SdkTemporalAuthority(WorkflowServiceBlockingStub service) {
            this.service = service;
        }

        @Override
        public ExecutionAuthority describe(RecoveryRequest request) {
            WorkflowExecution execution =
                    WorkflowExecution.newBuilder()
                            .setWorkflowId(request.workflowId())
                            .setRunId(request.runId())
                            .build();
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
            var scheduledTaskQueue = latestScheduledTaskQueue(request, execution);
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
                    scheduledTaskQueue.kind() == TASK_QUEUE_KIND_NORMAL
                            ? PendingTaskKind.NORMAL
                            : PendingTaskKind.OTHER,
                    scheduledTaskQueue.name(),
                    pending.getAttempt());
        }

        @Override
        public List<OpenExecutionAuthority> openExecutionsBoundTo(
                String namespace, String legacyBuildId) {
            List<WorkflowExecutionInfo> assigned =
                    visibilityRows(
                            namespace,
                            "ExecutionStatus = \"Running\" AND BuildIds = \"assigned:"
                                    + legacyBuildId
                                    + "\"");
            List<WorkflowExecutionInfo> versioned =
                    visibilityRows(
                            namespace,
                            "ExecutionStatus = \"Running\" AND BuildIds = \"versioned:"
                                    + legacyBuildId
                                    + "\"");
            return mergeExactBuildVisibilityRows(assigned, versioned, legacyBuildId);
        }

        private List<WorkflowExecutionInfo> visibilityRows(String namespace, String query) {
            List<WorkflowExecutionInfo> rows = new ArrayList<>();
            ByteString nextPageToken = ByteString.EMPTY;
            for (int page = 0; page < MAXIMUM_VISIBILITY_PAGES; page++) {
                ListWorkflowExecutionsResponse response =
                        service.listWorkflowExecutions(
                                ListWorkflowExecutionsRequest.newBuilder()
                                        .setNamespace(namespace)
                                        .setPageSize(VISIBILITY_PAGE_SIZE)
                                        .setNextPageToken(nextPageToken)
                                        .setQuery(query)
                                        .build());
                rows.addAll(response.getExecutionsList());
                nextPageToken = response.getNextPageToken();
                if (nextPageToken.isEmpty()) {
                    return List.copyOf(rows);
                }
            }
            throw new IllegalStateException("open-execution visibility exceeded its bounded scan");
        }

        private ScheduledTaskQueue latestScheduledTaskQueue(
                RecoveryRequest request, WorkflowExecution execution) {
            GetWorkflowExecutionHistoryReverseResponse history =
                    service.getWorkflowExecutionHistoryReverse(
                            GetWorkflowExecutionHistoryReverseRequest.newBuilder()
                                    .setNamespace(request.namespace())
                                    .setExecution(execution)
                                    .setMaximumPageSize(100)
                                    .build());
            for (HistoryEvent event : history.getHistory().getEventsList()) {
                if (event.hasWorkflowTaskScheduledEventAttributes()) {
                    var taskQueue = event.getWorkflowTaskScheduledEventAttributes().getTaskQueue();
                    return new ScheduledTaskQueue(taskQueue.getName(), taskQueue.getKind());
                }
            }
            throw new IllegalStateException("scheduled workflow-task authority is missing");
        }
    }

    private record ScheduledTaskQueue(
            String name, io.temporal.api.enums.v1.TaskQueueKind kind) {}

    private static final class SdkWorkflowOnlyRuntime implements WorkflowOnlyRuntime {
        private final WorkerFactory factory;
        private final Worker worker;
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean closing = new AtomicBoolean();

        @SuppressWarnings("deprecation")
        private SdkWorkflowOnlyRuntime(WorkflowClient workflowClient, RecoveryPlan plan) {
            factory =
                    WorkerFactory.newInstance(
                            workflowClient,
                            WorkerFactoryOptions.newBuilder()
                                    .setEnableLoggingInReplay(false)
                                    .build());
            WorkerOptions options =
                    WorkerOptions.newBuilder()
                            .setBuildId(plan.legacyBuildId())
                            .setUseBuildIdForVersioning(true)
                            .setMaxConcurrentWorkflowTaskExecutionSize(1)
                            .setMaxConcurrentWorkflowTaskPollers(1)
                            .build();
            worker = factory.newWorker(CASE_CONTROL_TASK_QUEUE, options);
        }

        @Override
        public void registerWorkflowImplementationTypes(Class<?>... workflowImplementationTypes) {
            worker.registerWorkflowImplementationTypes(workflowImplementationTypes);
        }

        @Override
        public void registerActivitiesImplementations(Object... activityImplementations) {
            throw new IllegalStateException("activity registration is forbidden for workflow recovery");
        }

        @Override
        public void start() {
            factory.start();
        }

        @Override
        public void await(Duration duration) throws InterruptedException {
            closed.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            if (!closing.compareAndSet(false, true)) {
                return;
            }
            closed.countDown();
            factory.shutdown();
            factory.awaitTermination(5, TimeUnit.SECONDS);
            if (!factory.isTerminated()) {
                factory.shutdownNow();
            }
        }
    }
}
