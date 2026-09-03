package com.example.dispute.workflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.outbox.ClaimedCaseCommandDelivery;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.DeliveryKind;
import com.example.dispute.workflow.observability.OutboxTraceInterceptor.DeliveryOutcome;
import com.example.dispute.workflow.observability.OutboxTraceInterceptor.DeliveryTraceResult;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutboxTraceInterceptorTest {

    @Test
    void restoresThePersistedParentAndEmitsOnlyOperationalDimensions() {
        var exporter = new CapturingExporter();
        try (SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()) {
            var interceptor =
                    new OutboxTraceInterceptor(
                            provider.get("outbox-test"), true);
            var delivery = delivery();

            DeliveryTraceResult result =
                    interceptor.trace(
                            delivery,
                            () -> DeliveryTraceResult.success(DeliveryOutcome.DELIVERED));

            assertThat(result.outcome()).isEqualTo(DeliveryOutcome.DELIVERED);
            assertThat(exporter.spans).hasSize(1);
            SpanData span = exporter.spans.getFirst();
            assertThat(span.getTraceId()).isEqualTo("0123456789abcdef0123456789abcdef");
            assertThat(span.getParentSpanContext().getSpanId())
                    .isEqualTo("0123456789abcdef");
            assertThat(span.getAttributes().get(AttributeKey.stringKey("temporal.task_queue")))
                    .isEqualTo("case-control");
            assertThat(
                            span.getAttributes()
                                    .get(AttributeKey.stringKey("temporal.delivery.outcome")))
                    .isEqualTo("DELIVERED");
            assertThat(span.getAttributes().asMap().keySet())
                    .extracting(AttributeKey::getKey)
                    .noneMatch(
                            key ->
                                    key.contains("case_id")
                                            || key.contains("command_id")
                                            || key.contains("payload")
                                            || key.contains("traceparent"));
        }
    }

    @Test
    void disabledExportStillMakesThePersistedParentCurrentForTemporalPropagation() {
        var interceptor = OutboxTraceInterceptor.disabled();

        String traceId =
                interceptor.trace(
                        delivery(),
                        () -> Span.current().getSpanContext().getTraceId());

        assertThat(traceId).isEqualTo("0123456789abcdef0123456789abcdef");
    }

    private static ClaimedCaseCommandDelivery delivery() {
        Instant now = Instant.parse("2026-07-17T08:00:00Z");
        CaseCommandRef command =
                new CaseCommandRef(
                        "case-command-ref.v1",
                        "command-trace",
                        "tenant-trace",
                        "CASE_TRACE",
                        1,
                        CommandType.EVIDENCE_SUBMIT,
                        RoomType.EVIDENCE,
                        1,
                        new ActorRef("actor-trace", ActorRole.USER, List.of("case:write")),
                        new PayloadRef(
                                "evidence-command.v1",
                                "urn:payload:trace",
                                "a".repeat(64),
                                42),
                        0,
                        now,
                        now.plusSeconds(60),
                        "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                        "b".repeat(64));
        return new ClaimedCaseCommandDelivery(
                "COUT_TRACE",
                "CMD_TRACE",
                DeliveryKind.UPDATE_WITH_START,
                "case-process:tenant-trace:CASE_TRACE",
                "CaseProcessWorkflow",
                "case-control",
                "command-trace",
                command,
                1,
                "lease-trace",
                OffsetDateTime.parse("2026-07-17T08:01:00Z"));
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
