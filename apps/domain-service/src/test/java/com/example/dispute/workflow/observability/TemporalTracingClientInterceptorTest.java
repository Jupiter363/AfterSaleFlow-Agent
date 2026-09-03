package com.example.dispute.workflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dispute.common.trace.W3cTraceContext;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.StartUpdateInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowStartInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowUpdateWithStartInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowUpdateWithStartOutput;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TemporalTracingClientInterceptorTest {

    @Test
    void updateWithStartCreatesAChildSpanThatIsCurrentDuringSdkAdmission() {
        var exporter = new CapturingExporter();
        try (SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()) {
            WorkflowClientCallsInterceptor next =
                    mock(WorkflowClientCallsInterceptor.class);
            var interceptor =
                    new TemporalTracingClientInterceptor(
                            provider.get("temporal-client-test"), true);
            WorkflowClientCallsInterceptor calls =
                    interceptor.workflowClientCallsInterceptor(next);
            WorkflowStartInput start =
                    new WorkflowStartInput(
                            "case-process:tenant:CASE_1",
                            "CaseProcessWorkflow",
                            Header.empty(),
                            new Object[0],
                            WorkflowOptions.newBuilder()
                                    .setWorkflowId("case-process:tenant:CASE_1")
                                    .setTaskQueue("case-control")
                                    .build());
            @SuppressWarnings("unchecked")
            StartUpdateInput<Void> update = mock(StartUpdateInput.class);
            WorkflowUpdateWithStartInput<Void> input =
                    new WorkflowUpdateWithStartInput<>(start, update);
            @SuppressWarnings("unchecked")
            WorkflowUpdateWithStartOutput<Void> output =
                    mock(WorkflowUpdateWithStartOutput.class);
            AtomicReference<SpanContext> activeInSdk = new AtomicReference<>();
            when(next.updateWithStart(input))
                    .thenAnswer(
                            ignored -> {
                                activeInSdk.set(Span.current().getSpanContext());
                                return output;
                            });
            Context parent =
                    W3cTraceContext.extract(
                            "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                            null);

            WorkflowUpdateWithStartOutput<Void> result;
            try (var ignored = parent.makeCurrent()) {
                result = calls.updateWithStart(input);
            }

            assertThat(result).isSameAs(output);
            assertThat(activeInSdk.get().isValid()).isTrue();
            assertThat(exporter.spans).hasSize(1);
            SpanData span = exporter.spans.getFirst();
            assertThat(span.getName()).isEqualTo("temporal.client.update_with_start");
            assertThat(span.getTraceId()).isEqualTo("0123456789abcdef0123456789abcdef");
            assertThat(span.getParentSpanContext().getSpanId())
                    .isEqualTo("0123456789abcdef");
            assertThat(activeInSdk.get().getSpanId()).isEqualTo(span.getSpanId());
            assertThat(
                            span.getAttributes()
                                    .get(AttributeKey.stringKey("temporal.task_queue")))
                    .isEqualTo("case-control");
        }
    }

    private static final class CapturingExporter implements SpanExporter {
        private final List<SpanData> spans = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
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
