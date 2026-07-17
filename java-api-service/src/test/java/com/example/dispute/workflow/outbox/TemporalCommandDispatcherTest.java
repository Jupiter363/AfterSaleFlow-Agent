package com.example.dispute.workflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.config.CommandOutboxProperties;
import com.example.dispute.workflow.contract.v1.CaseCommandRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.ActorRole;
import com.example.dispute.workflow.contract.v1.ContractTypes.CommandType;
import com.example.dispute.workflow.contract.v1.ContractTypes.PayloadRef;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.outbox.CaseCommandOutboxStore;
import com.example.dispute.workflow.infrastructure.outbox.ClaimedCaseCommandDelivery;
import com.example.dispute.workflow.infrastructure.outbox.TemporalCommandDispatcher;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateDeliveryException;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateGateway;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.DeliveryKind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TemporalCommandDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private static final OffsetDateTime NOW_OFFSET =
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock private CaseCommandOutboxStore outboxStore;
    @Mock private TemporalUpdateGateway temporalGateway;

    private TemporalCommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        var properties =
                new CommandOutboxProperties(
                        false,
                        10,
                        Duration.ofMinutes(1),
                        3,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5));
        dispatcher =
                new TemporalCommandDispatcher(
                        outboxStore,
                        temporalGateway,
                        properties,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void marksTheOutboxAndCommandAfterTemporalAdmission() {
        var delivery = delivery(1, "lease-1");
        when(outboxStore.claimById(
                        "COUT_1", NOW_OFFSET, Duration.ofMinutes(1)))
                .thenReturn(Optional.of(delivery));
        when(temporalGateway.deliver(delivery.toGatewayRequest()))
                .thenReturn(new TemporalUpdateGateway.DeliveryReceipt("run-1"));
        when(outboxStore.markDelivered(delivery, "run-1", NOW_OFFSET))
                .thenReturn(true);

        assertThat(dispatcher.dispatchNow("COUT_1")).isTrue();

        verify(outboxStore).markDelivered(delivery, "run-1", NOW_OFFSET);
        verify(outboxStore, never())
                .markRetry(any(), any(), any(), any(), any());
    }

    @Test
    void temporalUnavailabilityLeavesTheDurableRowForBackoffRetry() {
        var delivery = delivery(1, "lease-1");
        when(outboxStore.claimById(
                        "COUT_1", NOW_OFFSET, Duration.ofMinutes(1)))
                .thenReturn(Optional.of(delivery));
        when(temporalGateway.deliver(delivery.toGatewayRequest()))
                .thenThrow(
                        TemporalUpdateDeliveryException.retryable(
                                "TEMPORAL_UNAVAILABLE", "unavailable", null));
        when(outboxStore.markRetry(
                        delivery,
                        "TEMPORAL_UNAVAILABLE",
                        "TemporalUpdateDeliveryException: unavailable",
                        NOW_OFFSET.plusSeconds(2),
                        NOW_OFFSET))
                .thenReturn(true);

        dispatcher.dispatchNow("COUT_1");

        verify(outboxStore)
                .markRetry(
                        delivery,
                        "TEMPORAL_UNAVAILABLE",
                        "TemporalUpdateDeliveryException: unavailable",
                        NOW_OFFSET.plusSeconds(2),
                        NOW_OFFSET);
    }

    @Test
    void permanentAndExhaustedFailuresDeadLetterDeterministically() {
        var permanent = delivery(1, "lease-permanent");
        var exhausted = delivery(3, "lease-exhausted");
        when(outboxStore.claimById(
                        eq("COUT_1"), eq(NOW_OFFSET), eq(Duration.ofMinutes(1))))
                .thenReturn(Optional.of(permanent), Optional.of(exhausted));
        when(temporalGateway.deliver(any()))
                .thenThrow(
                        TemporalUpdateDeliveryException.permanent(
                                "TEMPORAL_INVALID_ARGUMENT", "invalid", null))
                .thenThrow(
                        TemporalUpdateDeliveryException.retryable(
                                "TEMPORAL_UNAVAILABLE", "unavailable", null));
        when(outboxStore.markDeadLetter(
                        permanent,
                        "TEMPORAL_INVALID_ARGUMENT",
                        "TemporalUpdateDeliveryException: invalid",
                        NOW_OFFSET))
                .thenReturn(true);
        when(outboxStore.markDeadLetter(
                        exhausted,
                        "TEMPORAL_DELIVERY_EXHAUSTED",
                        "TemporalUpdateDeliveryException: unavailable",
                        NOW_OFFSET))
                .thenReturn(true);

        dispatcher.dispatchNow("COUT_1");
        dispatcher.dispatchNow("COUT_1");

        verify(outboxStore)
                .markDeadLetter(
                        permanent,
                        "TEMPORAL_INVALID_ARGUMENT",
                        "TemporalUpdateDeliveryException: invalid",
                        NOW_OFFSET);
        verify(outboxStore)
                .markDeadLetter(
                        exhausted,
                        "TEMPORAL_DELIVERY_EXHAUSTED",
                        "TemporalUpdateDeliveryException: unavailable",
                        NOW_OFFSET);
    }

    @Test
    void retriesTheSameTemporalUpdateIdAfterAdmissionMarkingCrashes() {
        var firstClaim = delivery(1, "lease-before-crash");
        var reclaimed = delivery(2, "lease-after-reclaim");
        when(outboxStore.claimById(
                        eq("COUT_1"), eq(NOW_OFFSET), eq(Duration.ofMinutes(1))))
                .thenReturn(Optional.of(firstClaim), Optional.of(reclaimed));
        when(temporalGateway.deliver(any()))
                .thenReturn(new TemporalUpdateGateway.DeliveryReceipt("run-1"));
        when(outboxStore.markDelivered(firstClaim, "run-1", NOW_OFFSET))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(outboxStore.markDelivered(reclaimed, "run-1", NOW_OFFSET))
                .thenReturn(true);

        assertThatThrownBy(() -> dispatcher.dispatchNow("COUT_1"))
                .isInstanceOf(IllegalStateException.class);
        dispatcher.dispatchNow("COUT_1");

        var requests =
                ArgumentCaptor.forClass(
                        TemporalUpdateGateway.UpdateWithStartRequest.class);
        verify(temporalGateway, org.mockito.Mockito.times(2))
                .deliver(requests.capture());
        assertThat(requests.getAllValues())
                .extracting(TemporalUpdateGateway.UpdateWithStartRequest::updateId)
                .containsExactly("command-1", "command-1");
    }

    private static ClaimedCaseCommandDelivery delivery(
            int attemptCount, String leaseToken) {
        return new ClaimedCaseCommandDelivery(
                "COUT_1",
                "CMD_1",
                DeliveryKind.UPDATE_WITH_START,
                "case-process:tenant:CASE_1",
                "CaseProcessWorkflow",
                "case-control",
                "command-1",
                command(),
                attemptCount,
                leaseToken,
                NOW_OFFSET.plusMinutes(1));
    }

    private static CaseCommandRef command() {
        return new CaseCommandRef(
                "case-command-ref.v1",
                "command-1",
                "tenant",
                "CASE_1",
                1,
                CommandType.EVIDENCE_SUBMIT,
                RoomType.EVIDENCE,
                0,
                new ActorRef("user-1", ActorRole.USER, List.of("case:command")),
                new PayloadRef(
                        "evidence-command.v1",
                        "urn:test:command-1",
                        "a".repeat(64),
                        10),
                0,
                NOW,
                NOW.plusSeconds(3600),
                "00-11111111111111111111111111111111-2222222222222222-01",
                "b".repeat(64));
    }
}
