package com.example.dispute.common.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;

class W3cTraceContextTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String SPAN_ID = "0123456789abcdef";

    @Test
    void extractsAndInjectsTheStandardW3cCarrier() {
        Context extracted =
                W3cTraceContext.extract(
                        "00-" + TRACE_ID + "-" + SPAN_ID + "-01",
                        "vendor=value");

        var carrier = W3cTraceContext.inject(extracted);

        assertThat(carrier)
                .containsEntry(
                        W3cTraceContext.TRACEPARENT,
                        "00-" + TRACE_ID + "-" + SPAN_ID + "-01")
                .containsEntry(W3cTraceContext.TRACESTATE, "vendor=value");
        assertThat(Span.fromContext(extracted).getSpanContext().isRemote()).isTrue();
    }

    @Test
    void exposesTheCurrentSpanAsTraceparent() {
        SpanContext spanContext =
                SpanContext.create(
                        TRACE_ID,
                        SPAN_ID,
                        TraceFlags.getSampled(),
                        TraceState.getDefault());
        try (var ignored = Context.root().with(Span.wrap(spanContext)).makeCurrent()) {
            assertThat(W3cTraceContext.currentTraceparent())
                    .contains("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
        }
    }

    @Test
    void rejectsInvalidOrAllZeroTraceContext() {
        assertThatThrownBy(
                        () ->
                                W3cTraceContext.extract(
                                        "00-00000000000000000000000000000000-"
                                                + "0000000000000000-01",
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
