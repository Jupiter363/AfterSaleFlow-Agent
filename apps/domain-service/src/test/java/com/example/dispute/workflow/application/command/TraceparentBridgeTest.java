package com.example.dispute.workflow.application.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;

class TraceparentBridgeTest {

    @Test
    void persistsTheCurrentHttpSpanInsteadOfTheRemoteCallerSpan() {
        SpanContext serverSpan =
                SpanContext.create(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "bbbbbbbbbbbbbbbb",
                        TraceFlags.getSampled(),
                        TraceState.getDefault());
        String resolved;
        try (var ignored = Context.root().with(Span.wrap(serverSpan)).makeCurrent()) {
            resolved =
                    TraceparentBridge.resolve(
                            "00-11111111111111111111111111111111-2222222222222222-01",
                            "TRACE_http",
                            "REQ_http");
        }

        assertThat(resolved)
                .isEqualTo(
                        "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
    }

    @Test
    void preservesAValidIncomingTraceparentWhenNoHttpSpanExists() {
        assertThat(
                        TraceparentBridge.resolve(
                                "00-11111111111111111111111111111111-2222222222222222-01",
                                "TRACE_http",
                                "REQ_http"))
                .isEqualTo(
                        "00-11111111111111111111111111111111-2222222222222222-01");
    }
}
