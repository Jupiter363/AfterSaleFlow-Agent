package com.example.dispute.workflow.observability;

import com.example.dispute.config.AppProperties;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class TemporalTracingClientInterceptor extends WorkflowClientInterceptorBase {

    private final Tracer tracer;
    private final boolean enabled;

    @Autowired
    public TemporalTracingClientInterceptor(AppProperties properties, Tracer tracer) {
        this(tracer, properties.temporal().observability().tracingEnabled());
    }

    TemporalTracingClientInterceptor(Tracer tracer, boolean enabled) {
        this.tracer = tracer;
        this.enabled = enabled;
    }

    @Override
    public WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
            WorkflowClientCallsInterceptor next) {
        return new Calls(next, tracer, enabled);
    }

    private static final class Calls extends WorkflowClientCallsInterceptorBase {

        private final Tracer tracer;
        private final boolean enabled;

        private Calls(
                WorkflowClientCallsInterceptor next, Tracer tracer, boolean enabled) {
            super(next);
            this.tracer = tracer;
            this.enabled = enabled;
        }

        @Override
        public WorkflowStartOutput start(WorkflowStartInput input) {
            return traced(
                    "start",
                    input.getWorkflowType(),
                    input.getOptions().getTaskQueue(),
                    () -> super.start(input));
        }

        @Override
        public WorkflowSignalWithStartOutput signalWithStart(
                WorkflowSignalWithStartInput input) {
            WorkflowStartInput start = input.getWorkflowStartInput();
            return traced(
                    "signal_with_start",
                    start.getWorkflowType(),
                    start.getOptions().getTaskQueue(),
                    () -> super.signalWithStart(input));
        }

        @Override
        public WorkflowSignalOutput signal(WorkflowSignalInput input) {
            return traced(
                    "signal",
                    "UNKNOWN",
                    "UNKNOWN",
                    () -> super.signal(input));
        }

        @Override
        public <R> WorkflowUpdateWithStartOutput<R> updateWithStart(
                WorkflowUpdateWithStartInput<R> input) {
            WorkflowStartInput start = input.getWorkflowStartInput();
            return traced(
                    "update_with_start",
                    start.getWorkflowType(),
                    start.getOptions().getTaskQueue(),
                    () -> super.updateWithStart(input));
        }

        @Override
        public <R> WorkflowUpdateHandle<R> startUpdate(StartUpdateInput<R> input) {
            return traced(
                    "start_update",
                    input.getWorkflowType().orElse("UNKNOWN"),
                    "UNKNOWN",
                    () -> super.startUpdate(input));
        }

        private <T> T traced(
                String operation,
                String workflowType,
                String taskQueue,
                Supplier<T> action) {
            if (!enabled) {
                return action.get();
            }
            Span span =
                    tracer.spanBuilder("temporal.client." + operation)
                            .setSpanKind(SpanKind.PRODUCER)
                            .setAttribute("temporal.operation", operation)
                            .setAttribute("temporal.workflow.type", workflowType)
                            .setAttribute("temporal.task_queue", taskQueue)
                            .startSpan();
            try (Scope ignored = span.makeCurrent()) {
                T result = action.get();
                span.setStatus(StatusCode.OK);
                return result;
            } catch (RuntimeException | Error failure) {
                span.recordException(failure);
                span.setStatus(StatusCode.ERROR);
                throw failure;
            } finally {
                span.end();
            }
        }
    }
}
