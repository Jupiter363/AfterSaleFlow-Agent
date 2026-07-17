package com.example.dispute.workflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.common.trace.W3cTraceContext;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class TemporalTracingWorkerInterceptorTest {

    private static final String TASK_QUEUE = "trace-worker-test";

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
            SpanData workflowSpan = exporter.single("temporal.workflow.run");
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
