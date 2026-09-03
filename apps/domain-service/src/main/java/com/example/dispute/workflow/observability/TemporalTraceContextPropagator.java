package com.example.dispute.workflow.observability;

import com.example.dispute.common.trace.W3cTraceContext;
import com.google.protobuf.ByteString;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.interceptors.Header;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class TemporalTraceContextPropagator implements ContextPropagator {

    private static final String NAME = "w3c-trace-context-v1";
    private static final String ENCODING = "binary/plain";
    private static final ThreadLocal<Context> PROPAGATED = new ThreadLocal<>();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Map<String, Payload> serializeContext(Object context) {
        if (!(context instanceof Context otelContext)
                || !Span.fromContext(otelContext).getSpanContext().isValid()) {
            return Map.of();
        }
        Map<String, Payload> payloads = new LinkedHashMap<>();
        W3cTraceContext.inject(otelContext)
                .forEach((key, value) -> payloads.put(key, textPayload(value)));
        return Map.copyOf(payloads);
    }

    @Override
    public Object deserializeContext(Map<String, Payload> context) {
        return extract(context);
    }

    @Override
    public Object getCurrentContext() {
        Context current = Context.current();
        if (Span.fromContext(current).getSpanContext().isValid()) {
            return current;
        }
        Context propagated = PROPAGATED.get();
        return propagated == null ? Context.root() : propagated;
    }

    @Override
    public void setCurrentContext(Object context) {
        if (context instanceof Context otelContext
                && Span.fromContext(otelContext).getSpanContext().isValid()) {
            PROPAGATED.set(otelContext);
        } else {
            PROPAGATED.remove();
        }
    }

    static Context fromHeader(Header header) {
        return header == null ? Context.root() : extract(header.getValues());
    }

    static Context currentPropagatedContext() {
        Context current = PROPAGATED.get();
        return current == null ? Context.root() : current;
    }

    static void clear() {
        PROPAGATED.remove();
    }

    private static Context extract(Map<String, Payload> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return Context.root();
        }
        Payload traceparent = payloads.get(W3cTraceContext.TRACEPARENT);
        if (traceparent == null) {
            return Context.root();
        }
        Payload tracestate = payloads.get(W3cTraceContext.TRACESTATE);
        try {
            return W3cTraceContext.extract(
                    text(traceparent), tracestate == null ? null : text(tracestate));
        } catch (IllegalArgumentException invalidTraceContext) {
            // Observability metadata must never make a durable business command fail.
            return Context.root();
        }
    }

    private static Payload textPayload(String value) {
        return Payload.newBuilder()
                .putMetadata("encoding", ByteString.copyFromUtf8(ENCODING))
                .setData(ByteString.copyFrom(value, StandardCharsets.UTF_8))
                .build();
    }

    private static String text(Payload payload) {
        return payload.getData().toString(StandardCharsets.UTF_8);
    }
}
