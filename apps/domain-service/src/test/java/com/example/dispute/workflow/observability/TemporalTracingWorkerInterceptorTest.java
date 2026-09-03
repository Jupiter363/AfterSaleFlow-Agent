package com.example.dispute.workflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.common.trace.W3cTraceContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor.QueryInput;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor.QueryOutput;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor.SignalInput;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor.UpdateInput;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor.UpdateOutput;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.SignalMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class TemporalTracingWorkerInterceptorTest {

    private static final String TASK_QUEUE = "trace-worker-test";
    private static final String PROPAGATED_TRACE_ID =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @AfterEach
    void clearPropagatedContext() {
        TemporalTraceContextPropagator.clear();
    }

    @Test
    void propagatesClientWorkflowAndActivityAsOneParentedTrace() {
        var exporter = new CapturingExporter();
        try (SdkTracerProvider provider =
                        SdkTracerProvider.builder()
                                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                                .build();
                TestWorkflowEnvironment environment =
                        environment(provider)) {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(TraceWorkflowImpl.class);
            worker.registerActivitiesImplementations(new TraceActivitiesImpl());
            environment.start();
            TraceWorkflow workflow =
                    environment.getWorkflowClient()
                            .newWorkflowStub(
                                    TraceWorkflow.class,
                                    WorkflowOptions.newBuilder()
                                            .setWorkflowId("trace-workflow-test")
                                            .setTaskQueue(TASK_QUEUE)
                                            .build());

            String result;
            try (var ignored =
                    W3cTraceContext.extract(
                                    "00-0123456789abcdef0123456789abcdef-"
                                            + "0123456789abcdef-01",
                                    null)
                            .makeCurrent()) {
                result = workflow.run("payload-ref");
            }

            assertThat(result).isEqualTo("processed:payload-ref");
            SpanData client = exporter.single("temporal.client.start");
            SpanData workflowSpan = exporter.single("temporal.workflow.activate");
            SpanData activity = exporter.single("temporal.activity.execute");
            assertThat(client.getTraceId())
                    .isEqualTo("0123456789abcdef0123456789abcdef");
            assertThat(workflowSpan.getParentSpanContext().getSpanId())
                    .isEqualTo(client.getSpanId());
            assertThat(activity.getParentSpanContext().getSpanId())
                    .isEqualTo(workflowSpan.getSpanId());
            assertThat(activity.getTraceId()).isEqualTo(client.getTraceId());
        }
    }

    @Test
    void exportsTheWorkflowActivationBeforeALongRunningWorkflowCompletes() {
        var exporter = new CapturingExporter();
        try (SdkTracerProvider provider =
                        SdkTracerProvider.builder()
                                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                                .build();
                TestWorkflowEnvironment environment = environment(provider)) {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(WaitingWorkflowImpl.class);
            environment.start();
            WaitingWorkflow workflow =
                    environment.getWorkflowClient()
                            .newWorkflowStub(
                                    WaitingWorkflow.class,
                                    WorkflowOptions.newBuilder()
                                            .setWorkflowId("trace-waiting-workflow-test")
                                            .setTaskQueue(TASK_QUEUE)
                                            .build());

            WorkflowClient.start(workflow::run);

            SpanData activation =
                    exporter.awaitSingle("temporal.workflow.activate");
            assertThat(activation.hasEnded()).isTrue();
            workflow.finish();
            io.temporal.client.WorkflowStub.fromTyped(workflow)
                    .getResult(Void.class);
        }
    }

    @Test
    void clearsPropagatedContextAfterEveryUpdateExecutionPath() {
        for (InboundPath path : inboundPaths()) {
            WorkflowInboundCallsInterceptor next =
                    mock(WorkflowInboundCallsInterceptor.class);
            UpdateInput input = new UpdateInput("update", Header.empty(), new Object[0]);
            if (path.fails()) {
                when(next.executeUpdate(input))
                        .thenThrow(new IllegalStateException(path.name()));
            } else {
                when(next.executeUpdate(input)).thenReturn(new UpdateOutput("done"));
            }

            WorkflowInboundCallsInterceptor interceptor =
                    workflowInterceptor(next, path.enabled());

            assertInboundContextCleared(
                    path,
                    () -> interceptor.executeUpdate(input));
        }
    }

    @Test
    void clearsPropagatedContextAfterEverySignalExecutionPath() {
        for (InboundPath path : inboundPaths()) {
            WorkflowInboundCallsInterceptor next =
                    mock(WorkflowInboundCallsInterceptor.class);
            SignalInput input =
                    new SignalInput("signal", new Object[0], 1L, Header.empty());
            if (path.fails()) {
                doThrow(new IllegalStateException(path.name()))
                        .when(next)
                        .handleSignal(input);
            }

            WorkflowInboundCallsInterceptor interceptor =
                    workflowInterceptor(next, path.enabled());

            assertInboundContextCleared(
                    path,
                    () -> interceptor.handleSignal(input));
        }
    }

    @Test
    void serialUpdateHandlersDoNotInheritAContextAfterSuccessOrFailure() {
        try (SdkTracerProvider provider = SdkTracerProvider.builder().build();
                MockedStatic<Workflow> workflow = mockStatic(Workflow.class)) {
            WorkflowInboundCallsInterceptor next =
                    mock(WorkflowInboundCallsInterceptor.class);
            when(next.executeUpdate(any(UpdateInput.class)))
                    .thenAnswer(
                            invocation -> {
                                UpdateInput input = invocation.getArgument(0);
                                if (input.getUpdateName().equals("failure")) {
                                    throw new IllegalStateException("rejected");
                                }
                                return new UpdateOutput(
                                        Span.current()
                                                .getSpanContext()
                                                .getTraceId());
                            });
            WorkflowInboundCallsInterceptor interceptor =
                    tracedWorkflowInterceptor(next, provider, workflow);
            UpdateInput success =
                    new UpdateInput("success", Header.empty(), new Object[0]);
            UpdateInput failure =
                    new UpdateInput("failure", Header.empty(), new Object[0]);
            UpdateInput withoutTrace =
                    new UpdateInput("without-trace", Header.empty(), new Object[0]);

            seedPropagatedContext(PROPAGATED_TRACE_ID);
            assertThat(interceptor.executeUpdate(success).getResult())
                    .isEqualTo(PROPAGATED_TRACE_ID);
            assertPropagatedContextCleared("serial update success");

            String failureTraceId = "cccccccccccccccccccccccccccccccc";
            seedPropagatedContext(failureTraceId);
            assertThatThrownBy(() -> interceptor.executeUpdate(failure))
                    .isInstanceOf(IllegalStateException.class);
            assertPropagatedContextCleared("serial update failure");

            String unparentedTraceId =
                    (String) interceptor.executeUpdate(withoutTrace).getResult();
            assertThat(unparentedTraceId)
                    .isNotEqualTo(PROPAGATED_TRACE_ID)
                    .isNotEqualTo(failureTraceId)
                    .isNotEqualTo("00000000000000000000000000000000");
            assertPropagatedContextCleared("serial update without trace");
        }
    }

    @Test
    void serialSignalHandlersDoNotInheritAContextAfterSuccessOrFailure() {
        try (SdkTracerProvider provider = SdkTracerProvider.builder().build();
                MockedStatic<Workflow> workflow = mockStatic(Workflow.class)) {
            WorkflowInboundCallsInterceptor next =
                    mock(WorkflowInboundCallsInterceptor.class);
            List<String> observedTraceIds = new ArrayList<>();
            org.mockito.Mockito.doAnswer(
                            invocation -> {
                                SignalInput input = invocation.getArgument(0);
                                observedTraceIds.add(
                                        Span.current()
                                                .getSpanContext()
                                                .getTraceId());
                                if (input.getSignalName().equals("failure")) {
                                    throw new IllegalStateException("rejected");
                                }
                                return null;
                            })
                    .when(next)
                    .handleSignal(any(SignalInput.class));
            WorkflowInboundCallsInterceptor interceptor =
                    tracedWorkflowInterceptor(next, provider, workflow);
            SignalInput success =
                    new SignalInput("success", new Object[0], 1L, Header.empty());
            SignalInput failure =
                    new SignalInput("failure", new Object[0], 2L, Header.empty());
            SignalInput withoutTrace =
                    new SignalInput("without-trace", new Object[0], 3L, Header.empty());

            seedPropagatedContext(PROPAGATED_TRACE_ID);
            interceptor.handleSignal(success);
            assertPropagatedContextCleared("serial signal success");

            String failureTraceId = "cccccccccccccccccccccccccccccccc";
            seedPropagatedContext(failureTraceId);
            assertThatThrownBy(() -> interceptor.handleSignal(failure))
                    .isInstanceOf(IllegalStateException.class);
            assertPropagatedContextCleared("serial signal failure");

            interceptor.handleSignal(withoutTrace);
            assertThat(observedTraceIds).hasSize(3);
            assertThat(observedTraceIds.subList(0, 2))
                    .containsExactly(PROPAGATED_TRACE_ID, failureTraceId);
            assertThat(observedTraceIds.get(2))
                    .isNotEqualTo(PROPAGATED_TRACE_ID)
                    .isNotEqualTo(failureTraceId)
                    .isNotEqualTo("00000000000000000000000000000000");
            assertPropagatedContextCleared("serial signal without trace");
        }
    }

    @Test
    void retainsAnEmptyHeaderFallbackFromSuccessfulValidationThroughUpdateExecution() {
        try (SdkTracerProvider provider = SdkTracerProvider.builder().build();
                MockedStatic<Workflow> workflow = mockStatic(Workflow.class)) {
            WorkflowInboundCallsInterceptor next =
                    mock(WorkflowInboundCallsInterceptor.class);
            UpdateInput input =
                    new UpdateInput("validate", Header.empty(), new Object[0]);
            when(next.executeUpdate(input))
                    .thenAnswer(
                            ignored ->
                                    new UpdateOutput(
                                            Span.current()
                                                    .getSpanContext()
                                                    .getTraceId()));
            var info = mock(io.temporal.workflow.WorkflowInfo.class);
            when(info.getWorkflowType()).thenReturn("CleanupWorkflow");
            when(info.getTaskQueue()).thenReturn(TASK_QUEUE);
            workflow.when(Workflow::isReplaying).thenReturn(false);
            workflow.when(Workflow::getInfo).thenReturn(info);
            WorkflowInboundCallsInterceptor interceptor =
                    new TemporalTracingWorkerInterceptor(
                                    provider.get("update-continuity-test"), true)
                            .interceptWorkflow(next);
            seedPropagatedContext();

            interceptor.validateUpdate(input);

            assertThat(
                            Span.fromContext(
                                            TemporalTraceContextPropagator
                                                    .currentPropagatedContext())
                                    .getSpanContext()
                                    .getTraceId())
                    .isEqualTo(PROPAGATED_TRACE_ID);
            UpdateOutput output = interceptor.executeUpdate(input);
            assertThat(output.getResult()).isEqualTo(PROPAGATED_TRACE_ID);
            assertPropagatedContextCleared("successful validator and executor");
        }
    }

    @Test
    void clearsPropagatedContextWhenUpdateValidationRejects() {
        for (boolean enabled : List.of(false, true)) {
            WorkflowInboundCallsInterceptor next =
                    mock(WorkflowInboundCallsInterceptor.class);
            UpdateInput input =
                    new UpdateInput("validate", Header.empty(), new Object[0]);
            doThrow(new IllegalStateException("rejected"))
                    .when(next)
                    .validateUpdate(input);
            WorkflowInboundCallsInterceptor interceptor =
                    workflowInterceptor(next, enabled);
            seedPropagatedContext();

            assertThatThrownBy(() -> interceptor.validateUpdate(input))
                    .isInstanceOf(IllegalStateException.class);
            assertPropagatedContextCleared("rejected validator, enabled=" + enabled);
        }
    }

    @Test
    void clearsPropagatedContextAfterEveryQueryExecutionPath() {
        for (InboundPath path : inboundPaths()) {
            WorkflowInboundCallsInterceptor next =
                    mock(WorkflowInboundCallsInterceptor.class);
            QueryInput input = new QueryInput("query", Header.empty(), new Object[0]);
            if (path.fails()) {
                when(next.handleQuery(input))
                        .thenThrow(new IllegalStateException(path.name()));
            } else {
                when(next.handleQuery(input)).thenReturn(new QueryOutput("answer"));
            }

            WorkflowInboundCallsInterceptor interceptor =
                    workflowInterceptor(next, path.enabled());

            assertInboundContextCleared(
                    path,
                    () -> interceptor.handleQuery(input));
        }
    }

    @Test
    void clearsPropagatedContextWhenWorkflowChildThreadsCompleteOrFail() {
        for (boolean enabled : List.of(false, true)) {
            for (boolean callback : List.of(false, true)) {
                for (boolean fails : List.of(false, true)) {
                    WorkflowInboundCallsInterceptor next =
                            mock(WorkflowInboundCallsInterceptor.class);
                    WorkflowInboundCallsInterceptor interceptor =
                            workflowInterceptor(next, enabled);
                    String name = callback ? "callback" : "workflow-method";
                    Object thread = new Object();
                    if (callback) {
                        when(next.newCallbackThread(any(Runnable.class), eq(name)))
                                .thenReturn(thread);
                    } else {
                        when(next.newWorkflowMethodThread(any(Runnable.class), eq(name)))
                                .thenReturn(thread);
                    }
                    Runnable child =
                            () -> {
                                if (fails) {
                                    throw new IllegalStateException(name);
                                }
                            };

                    Object actual =
                            callback
                                    ? interceptor.newCallbackThread(child, name)
                                    : interceptor.newWorkflowMethodThread(child, name);
                    ArgumentCaptor<Runnable> guarded =
                            ArgumentCaptor.forClass(Runnable.class);
                    if (callback) {
                        verify(next).newCallbackThread(guarded.capture(), eq(name));
                    } else {
                        verify(next).newWorkflowMethodThread(guarded.capture(), eq(name));
                    }

                    assertThat(actual).isSameAs(thread);
                    seedPropagatedContext();
                    if (fails) {
                        assertThatThrownBy(guarded.getValue()::run)
                                .isInstanceOf(IllegalStateException.class);
                    } else {
                        guarded.getValue().run();
                    }
                    assertPropagatedContextCleared(
                            "enabled=" + enabled + ", callback=" + callback + ", fails=" + fails);
                }
            }
        }
    }

    private static WorkflowInboundCallsInterceptor workflowInterceptor(
            WorkflowInboundCallsInterceptor next, boolean enabled) {
        return new TemporalTracingWorkerInterceptor(
                        OpenTelemetry.noop().getTracer("worker-cleanup-test"), enabled)
                .interceptWorkflow(next);
    }

    private static WorkflowInboundCallsInterceptor tracedWorkflowInterceptor(
            WorkflowInboundCallsInterceptor next,
            SdkTracerProvider provider,
            MockedStatic<Workflow> workflow) {
        var info = mock(io.temporal.workflow.WorkflowInfo.class);
        when(info.getWorkflowType()).thenReturn("CleanupWorkflow");
        when(info.getTaskQueue()).thenReturn(TASK_QUEUE);
        workflow.when(Workflow::isReplaying).thenReturn(false);
        workflow.when(Workflow::getInfo).thenReturn(info);
        return new TemporalTracingWorkerInterceptor(
                        provider.get("serial-handler-cleanup-test"), true)
                .interceptWorkflow(next);
    }

    private static void assertInboundContextCleared(
            InboundPath path, Runnable invocation) {
        try (MockedStatic<Workflow> workflow = mockStatic(Workflow.class)) {
            workflow.when(Workflow::isReplaying).thenReturn(path.replaying());
            var info = mock(io.temporal.workflow.WorkflowInfo.class);
            when(info.getWorkflowType()).thenReturn("CleanupWorkflow");
            when(info.getTaskQueue()).thenReturn(TASK_QUEUE);
            workflow.when(Workflow::getInfo).thenReturn(info);
            seedPropagatedContext();

            if (path.fails()) {
                assertThatThrownBy(invocation::run)
                        .as(path.name())
                        .isInstanceOf(IllegalStateException.class);
            } else {
                invocation.run();
            }

            assertPropagatedContextCleared(path.name());
        } finally {
            TemporalTraceContextPropagator.clear();
        }
    }

    private static void seedPropagatedContext() {
        seedPropagatedContext(PROPAGATED_TRACE_ID);
    }

    private static void seedPropagatedContext(String traceId) {
        new TemporalTraceContextPropagator()
                .setCurrentContext(
                        W3cTraceContext.extract(
                                "00-"
                                        + traceId
                                        + "-bbbbbbbbbbbbbbbb-01",
                                null));
        assertThat(
                        Span.fromContext(
                                        TemporalTraceContextPropagator
                                                .currentPropagatedContext())
                                .getSpanContext()
                                .getTraceId())
                .isEqualTo(traceId);
    }

    private static void assertPropagatedContextCleared(String path) {
        assertThat(
                        Span.fromContext(
                                        TemporalTraceContextPropagator
                                                .currentPropagatedContext())
                                .getSpanContext()
                                .isValid())
                .as(path)
                .isFalse();
    }

    private static List<InboundPath> inboundPaths() {
        return List.of(
                new InboundPath("enabled success", true, false, false),
                new InboundPath("enabled failure", true, false, true),
                new InboundPath("disabled success", false, false, false),
                new InboundPath("disabled failure", false, false, true),
                new InboundPath("replay success", true, true, false),
                new InboundPath("replay failure", true, true, true));
    }

    private record InboundPath(
            String name, boolean enabled, boolean replaying, boolean fails) {}

    private static TestWorkflowEnvironment environment(SdkTracerProvider provider) {
        var contextPropagator = new TemporalTraceContextPropagator();
        var clientInterceptor =
                new TemporalTracingClientInterceptor(provider.get("client-test"), true);
        var workerInterceptor =
                new TemporalTracingWorkerInterceptor(provider.get("worker-test"), true);
        WorkflowClientOptions clientOptions =
                WorkflowClientOptions.newBuilder()
                        .setContextPropagators(List.of(contextPropagator))
                        .setInterceptors(clientInterceptor)
                        .build();
        WorkerFactoryOptions workerOptions =
                WorkerFactoryOptions.newBuilder()
                        .setWorkerInterceptors(workerInterceptor)
                        .setEnableLoggingInReplay(false)
                        .build();
        return TestWorkflowEnvironment.newInstance(
                TestEnvironmentOptions.newBuilder()
                        .setWorkflowClientOptions(clientOptions)
                        .setWorkerFactoryOptions(workerOptions)
                        .setUseTimeskipping(true)
                        .build());
    }

    @WorkflowInterface
    public interface TraceWorkflow {
        @WorkflowMethod
        String run(String payloadReference);
    }

    @ActivityInterface
    public interface TraceActivities {
        String process(String payloadReference);
    }

    public static final class TraceWorkflowImpl implements TraceWorkflow {
        private final TraceActivities activities =
                Workflow.newActivityStub(
                        TraceActivities.class,
                        ActivityOptions.newBuilder()
                                .setStartToCloseTimeout(Duration.ofSeconds(5))
                                .build());

        @Override
        public String run(String payloadReference) {
            return activities.process(payloadReference);
        }
    }

    public static final class TraceActivitiesImpl implements TraceActivities {
        @Override
        public String process(String payloadReference) {
            return "processed:" + payloadReference;
        }
    }

    @WorkflowInterface
    public interface WaitingWorkflow {
        @WorkflowMethod
        void run();

        @SignalMethod
        void finish();
    }

    public static final class WaitingWorkflowImpl implements WaitingWorkflow {
        private boolean finished;

        @Override
        public void run() {
            Workflow.await(() -> finished);
        }

        @Override
        public void finish() {
            finished = true;
        }
    }

    private static final class CapturingExporter implements SpanExporter {
        private final List<SpanData> spans = new ArrayList<>();

        private SpanData single(String name) {
            return spans.stream()
                    .filter(span -> span.getName().equals(name))
                    .collect(
                            java.util.stream.Collectors.collectingAndThen(
                                    java.util.stream.Collectors.toList(),
                                    matching -> {
                                        assertThat(matching).hasSize(1);
                                        return matching.getFirst();
                                    }));
        }

        private SpanData awaitSingle(String name) {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                synchronized (this) {
                    List<SpanData> matching =
                            spans.stream()
                                    .filter(span -> span.getName().equals(name))
                                    .toList();
                    if (matching.size() == 1) {
                        return matching.getFirst();
                    }
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("test interrupted", exception);
                }
            }
            throw new AssertionError("span was not exported: " + name);
        }

        @Override
        public synchronized CompletableResultCode export(Collection<SpanData> spans) {
            this.spans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
