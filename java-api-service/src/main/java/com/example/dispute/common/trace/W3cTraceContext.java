package com.example.dispute.common.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class W3cTraceContext {

    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";

    private static final W3CTraceContextPropagator PROPAGATOR =
            W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<Map<String, String>> GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    return carrier.get(key);
                }
            };
    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;

    private W3cTraceContext() {}

    public static Context extract(String traceparent, String tracestate) {
        if (traceparent == null || traceparent.isBlank()) {
            throw new IllegalArgumentException("traceparent must not be blank");
        }
        Map<String, String> carrier = new HashMap<>();
        carrier.put(TRACEPARENT, traceparent);
        if (tracestate != null && !tracestate.isBlank()) {
            carrier.put(TRACESTATE, tracestate);
        }
        Context extracted = PROPAGATOR.extract(Context.root(), carrier, GETTER);
        if (!Span.fromContext(extracted).getSpanContext().isValid()) {
            throw new IllegalArgumentException("traceparent is invalid");
        }
        return extracted;
    }

    public static Map<String, String> inject(Context context) {
        Map<String, String> carrier = new HashMap<>();
        PROPAGATOR.inject(context == null ? Context.root() : context, carrier, SETTER);
        return Map.copyOf(carrier);
    }

    public static Optional<String> currentTraceparent() {
        SpanContext spanContext = Span.current().getSpanContext();
        return spanContext.isValid() ? Optional.of(format(spanContext)) : Optional.empty();
    }

    public static String format(SpanContext spanContext) {
        if (spanContext == null || !spanContext.isValid()) {
            throw new IllegalArgumentException("span context must be valid");
        }
        return "00-"
                + spanContext.getTraceId()
                + "-"
                + spanContext.getSpanId()
                + "-"
                + spanContext.getTraceFlags().asHex();
    }
}
