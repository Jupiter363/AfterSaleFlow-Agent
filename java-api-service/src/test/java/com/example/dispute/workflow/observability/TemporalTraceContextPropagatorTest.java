package com.example.dispute.workflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.common.trace.W3cTraceContext;
import com.google.protobuf.ByteString;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.temporal.api.common.v1.Payload;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TemporalTraceContextPropagatorTest {

    private final TemporalTraceContextPropagator propagator =
            new TemporalTraceContextPropagator();

    @AfterEach
    void clearThreadContext() {
        TemporalTraceContextPropagator.clear();
    }

    @Test
    void roundTripsTraceparentAndTracestateThroughTemporalPayloadHeaders() {
        Context context =
                W3cTraceContext.extract(
                        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                        "vendor=value");

        var serialized = propagator.serializeContext(context);
        Context restored = (Context) propagator.deserializeContext(serialized);

        assertThat(serialized).containsKeys("traceparent", "tracestate");
        assertThat(Span.fromContext(restored).getSpanContext().getTraceId())
                .isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(Span.fromContext(restored).getSpanContext().getTraceState().get("vendor"))
                .isEqualTo("value");
    }

    @Test
    void usesCurrentSpanThenFallsBackToTheWorkerPropagatedContext() {
        Context workerParent =
                Context.root()
                        .with(
                                Span.wrap(
                                        SpanContext.createFromRemoteParent(
                                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                                "bbbbbbbbbbbbbbbb",
                                                TraceFlags.getSampled(),
                                                TraceState.getDefault())));
        propagator.setCurrentContext(workerParent);
        assertThat(
                        Span.fromContext((Context) propagator.getCurrentContext())
                                .getSpanContext()
                                .getTraceId())
                .isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        Context current =
                Context.root()
                        .with(
                                Span.wrap(
                                        SpanContext.create(
                                                "cccccccccccccccccccccccccccccccc",
                                                "dddddddddddddddd",
                                                TraceFlags.getSampled(),
                                                TraceState.getDefault())));
        try (var ignored = current.makeCurrent()) {
            assertThat(
                            Span.fromContext((Context) propagator.getCurrentContext())
                                    .getSpanContext()
                                    .getTraceId())
                    .isEqualTo("cccccccccccccccccccccccccccccccc");
        }
    }

    @Test
    void malformedTracingMetadataDegradesToRootContext() {
        Payload malformed =
                Payload.newBuilder()
                        .setData(ByteString.copyFromUtf8("invalid-traceparent"))
                        .build();

        Context restored =
                (Context)
                        propagator.deserializeContext(
                                Map.of(W3cTraceContext.TRACEPARENT, malformed));

        assertThat(Span.fromContext(restored).getSpanContext().isValid()).isFalse();
    }
}
