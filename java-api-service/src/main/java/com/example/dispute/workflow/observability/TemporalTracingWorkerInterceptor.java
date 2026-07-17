package com.example.dispute.workflow.observability;

import com.example.dispute.config.AppProperties;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.workflow.Workflow;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class TemporalTracingWorkerInterceptor extends WorkerInterceptorBase {

    private final Tracer tracer;
    private final boolean enabled;

    @Autowired
    public TemporalTracingWorkerInterceptor(AppProperties properties, Tracer tracer) {
        this(tracer, properties.temporal().observability().tracingEnabled());
    }

    TemporalTracingWorkerInterceptor(Tracer tracer, boolean enabled) {
        this.tracer = tracer;
        this.enabled = enabled;
    }

    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(
            WorkflowInboundCallsInterceptor next) {
        return new WorkflowCalls(next, tracer, enabled);
    }

    @Override
    public ActivityInboundCallsInterceptor interceptActivity(
            ActivityInboundCallsInterceptor next) {
        return new ActivityCalls(next, tracer, enabled);
    }

    private static final class WorkflowCalls extends WorkflowInboundCallsInterceptorBase {

        private final Tracer tracer;
        private final boolean enabled;

        private WorkflowCalls(
                WorkflowInboundCallsInterceptor next, Tracer tracer, boolean enabled) {
            super(next);
            this.tracer = tracer;
            this.enabled = enabled;
        }

        @Override
        public WorkflowOutput execute(WorkflowInput input) {
            Context parent = parent(input.getHeader());
            try {
                if (!enabled || Workflow.isReplaying()) {
                    return inContext(parent, () -> super.execute(input));
                }
                var info = Workflow.getInfo();
                return traced(
                        parent,
                        "temporal.workflow.run",
                        info.getWorkflowType(),
                        info.getTaskQueue(),
                        () -> super.execute(input));
            } finally {
                TemporalTraceContextPropagator.clear();
            }
        }

        @Override
        public UpdateOutput executeUpdate(UpdateInput input) {
            Context parent = parent(input.getHeader());
            if (!enabled || Workflow.isReplaying()) {
                return inContext(parent, () -> super.executeUpdate(input));
            }
            var info = Workflow.getInfo();
            return traced(
                    parent,
                    "temporal.workflow.update",
                    info.getWorkflowType(),
                    info.getTaskQueue(),
                    () -> super.executeUpdate(input));
        }

        @Override
        public void handleSignal(SignalInput input) {
            Context parent = parent(input.getHeader());
            if (!enabled || Workflow.isReplaying()) {
                inContext(
                        parent,
                        () -> {
                            super.handleSignal(input);
                            return null;
                        });
                return;
            }
            var info = Workflow.getInfo();
            traced(
                    parent,
                    "temporal.workflow.signal",
                    info.getWorkflowType(),
                    info.getTaskQueue(),
                    () -> {
                        super.handleSignal(input);
                        return null;
                    });
        }

        private Context parent(io.temporal.common.interceptors.Header header) {
            Context extracted = TemporalTraceContextPropagator.fromHeader(header);
            return Span.fromContext(extracted).getSpanContext().isValid()
                    ? extracted
                    : TemporalTraceContextPropagator.currentPropagatedContext();
        }

        private <T> T traced(
                Context parent,
                String spanName,
                String workflowType,
                String taskQueue,
                Supplier<T> action) {
            Span span =
                    tracer.spanBuilder(spanName)
                            .setParent(parent)
                            .setSpanKind(SpanKind.CONSUMER)
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

    private static final class ActivityCalls extends ActivityInboundCallsInterceptorBase {

        private final Tracer tracer;
        private final boolean enabled;
        private ActivityExecutionContext executionContext;

        private ActivityCalls(
                ActivityInboundCallsInterceptor next, Tracer tracer, boolean enabled) {
            super(next);
            this.tracer = tracer;
            this.enabled = enabled;
        }

        @Override
        public void init(ActivityExecutionContext context) {
            this.executionContext = context;
            super.init(context);
        }

        @Override
        public ActivityOutput execute(ActivityInput input) {
            Context parent = TemporalTraceContextPropagator.fromHeader(input.getHeader());
            if (!Span.fromContext(parent).getSpanContext().isValid()) {
                parent = TemporalTraceContextPropagator.currentPropagatedContext();
            }
            try {
                if (!enabled) {
                    return inContext(parent, () -> super.execute(input));
                }
                var info = executionContext.getInfo();
                Span span =
                        tracer.spanBuilder("temporal.activity.execute")
                                .setParent(parent)
                                .setSpanKind(SpanKind.CONSUMER)
                                .setAttribute("temporal.workflow.type", info.getWorkflowType())
                                .setAttribute("temporal.activity.type", info.getActivityType())
                                .setAttribute(
                                        "temporal.task_queue", info.getActivityTaskQueue())
                                .startSpan();
                try (Scope ignored = span.makeCurrent()) {
                    ActivityOutput output = super.execute(input);
                    span.setStatus(StatusCode.OK);
                    return output;
                } catch (RuntimeException | Error failure) {
                    span.recordException(failure);
                    span.setStatus(StatusCode.ERROR);
                    throw failure;
                } finally {
                    span.end();
                }
            } finally {
                TemporalTraceContextPropagator.clear();
            }
        }
    }

    private static <T> T inContext(Context context, Supplier<T> action) {
        try (Scope ignored = context.makeCurrent()) {
            return action.get();
        }
    }
}
