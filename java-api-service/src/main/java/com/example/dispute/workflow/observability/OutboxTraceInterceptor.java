package com.example.dispute.workflow.observability;

import com.example.dispute.common.trace.W3cTraceContext;
import com.example.dispute.config.AppProperties;
import com.example.dispute.workflow.infrastructure.outbox.ClaimedCaseCommandDelivery;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class OutboxTraceInterceptor {

    private final Tracer tracer;
    private final boolean enabled;

    @Autowired
    public OutboxTraceInterceptor(AppProperties properties, Tracer tracer) {
        this(tracer, properties.temporal().observability().tracingEnabled());
    }

    OutboxTraceInterceptor(Tracer tracer, boolean enabled) {
        this.tracer = tracer;
        this.enabled = enabled;
    }

    public static OutboxTraceInterceptor disabled() {
        return new OutboxTraceInterceptor(
                OpenTelemetry.noop().getTracer("com.example.dispute.temporal-control"),
                false);
    }

    public <T> T trace(
            ClaimedCaseCommandDelivery delivery, Supplier<T> action) {
        Context parent = parent(delivery.command().traceparent());
        if (!enabled) {
            try (Scope ignored = parent.makeCurrent()) {
                return action.get();
            }
        }
        Span span =
                tracer.spanBuilder("temporal.outbox.deliver")
                        .setParent(parent)
                        .setSpanKind(SpanKind.PRODUCER)
                        .setAttribute("messaging.system", "temporal")
                        .setAttribute("temporal.workflow.type", delivery.workflowType())
                        .setAttribute("temporal.task_queue", delivery.taskQueue())
                        .setAttribute(
                                "temporal.delivery.kind", delivery.deliveryKind().name())
                        .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            T result = action.get();
            if (result instanceof DeliveryTraceResult deliveryResult) {
                span.setAttribute(
                        "temporal.delivery.outcome", deliveryResult.outcome().name());
                if (deliveryResult.errorCode() != null) {
                    span.setAttribute(
                            "temporal.delivery.error_code", deliveryResult.errorCode());
                }
                span.setStatus(
                        deliveryResult.outcome() == DeliveryOutcome.DELIVERED
                                        || deliveryResult.outcome()
                                                == DeliveryOutcome.STALE_LEASE
                                ? StatusCode.OK
                                : StatusCode.ERROR);
            } else {
                span.setStatus(StatusCode.OK);
            }
            return result;
        } catch (RuntimeException | Error failure) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR);
            throw failure;
        } finally {
            span.end();
        }
    }

    private static Context parent(String traceparent) {
        try {
            return W3cTraceContext.extract(traceparent, null);
        } catch (IllegalArgumentException invalidTraceContext) {
            return Context.root();
        }
    }

    public record DeliveryTraceResult(DeliveryOutcome outcome, String errorCode) {
        public DeliveryTraceResult {
            if (outcome == null) {
                throw new IllegalArgumentException("delivery outcome must not be null");
            }
        }

        public static DeliveryTraceResult success(DeliveryOutcome outcome) {
            return new DeliveryTraceResult(outcome, null);
        }

        public static DeliveryTraceResult failure(
                DeliveryOutcome outcome, String errorCode) {
            return new DeliveryTraceResult(outcome, errorCode);
        }
    }

    public enum DeliveryOutcome {
        DELIVERED,
        RETRY_SCHEDULED,
        DEAD_LETTERED,
        STALE_LEASE
    }
}
