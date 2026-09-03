package com.example.dispute.workflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
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
import com.example.dispute.workflow.infrastructure.outbox.CaseCommandOutboxStore.ExpirationResolution;
import com.example.dispute.workflow.infrastructure.outbox.CaseCommandOutboxStore.PermanentFailureResolution;
import com.example.dispute.workflow.infrastructure.outbox.ClaimedCaseCommandDelivery;
import com.example.dispute.workflow.infrastructure.outbox.TemporalCommandDispatcher;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateDeliveryException;
import com.example.dispute.workflow.infrastructure.outbox.TemporalUpdateGateway;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.DeliveryKind;
import com.example.dispute.workflow.observability.OutboxTraceInterceptor;
import com.example.dispute.workflow.observability.OutboxTraceInterceptor.DeliveryOutcome;
import com.example.dispute.workflow.observability.OutboxTraceInterceptor.DeliveryTraceResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TemporalCommandDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private static final OffsetDateTime NOW_OFFSET =
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock private CaseCommandOutboxStore outboxStore;
    @Mock private TemporalUpdateGateway temporalGateway;
    @Mock private OutboxTraceInterceptor traceInterceptor;

    private TemporalCommandDispatcher dispatcher;
    private CommandOutboxProperties properties;
    private List<DeliveryTraceResult> tracedResults;

    @BeforeEach
    void setUp() {
        tracedResults = new ArrayList<>();
        lenient()
                .when(traceInterceptor.trace(any(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<DeliveryTraceResult> action = invocation.getArgument(1);
                            DeliveryTraceResult result = action.get();
                            tracedResults.add(result);
                            return result;
                        });
        properties =
                new CommandOutboxProperties(
                        false,
                        10,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5));
        dispatcher =
                new TemporalCommandDispatcher(
                        outboxStore,
                        temporalGateway,
                        properties,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        traceInterceptor);
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
    void permanentFailuresDeadLetterWhileRetryableFailuresIgnoreAttemptCeiling() {
        var permanent = delivery(1, "lease-permanent");
        var beyondAttemptCeiling = delivery(3, "lease-retry");
        when(outboxStore.claimById(
                        eq("COUT_1"), eq(NOW_OFFSET), eq(Duration.ofMinutes(1))))
                .thenReturn(Optional.of(permanent), Optional.of(beyondAttemptCeiling));
        when(temporalGateway.deliver(any()))
                .thenThrow(
                        TemporalUpdateDeliveryException.permanent(
                                "TEMPORAL_INVALID_ARGUMENT", "invalid", null))
                .thenThrow(
                        TemporalUpdateDeliveryException.retryable(
                                "TEMPORAL_UNAVAILABLE", "unavailable", null));
        when(outboxStore.resolvePermanentFailure(
                        permanent,
                        "TEMPORAL_INVALID_ARGUMENT",
                        "TemporalUpdateDeliveryException: invalid",
                        NOW_OFFSET))
                .thenReturn(PermanentFailureResolution.DEAD_LETTERED);
        when(outboxStore.markRetry(
                        beyondAttemptCeiling,
                        "TEMPORAL_UNAVAILABLE",
                        "TemporalUpdateDeliveryException: unavailable",
                        NOW_OFFSET.plusSeconds(8),
                        NOW_OFFSET))
                .thenReturn(true);

        dispatcher.dispatchNow("COUT_1");
        dispatcher.dispatchNow("COUT_1");

        verify(outboxStore)
                .resolvePermanentFailure(
                        permanent,
                        "TEMPORAL_INVALID_ARGUMENT",
                        "TemporalUpdateDeliveryException: invalid",
                        NOW_OFFSET);
        verify(outboxStore)
                .markRetry(
                        beyondAttemptCeiling,
                        "TEMPORAL_UNAVAILABLE",
                        "TemporalUpdateDeliveryException: unavailable",
                        NOW_OFFSET.plusSeconds(8),
                        NOW_OFFSET);
        assertThat(tracedResults)
                .extracting(DeliveryTraceResult::outcome)
                .containsExactly(
                        DeliveryOutcome.DEAD_LETTERED,
                        DeliveryOutcome.RETRY_SCHEDULED);
    }

    @Test
    void terminalCommandPermanentFailureIsTracedAsReconciled() {
        var delivery = delivery(2, "lease-reconciled");
        when(outboxStore.claimById(
                        "COUT_1", NOW_OFFSET, Duration.ofMinutes(1)))
                .thenReturn(Optional.of(delivery));
        when(temporalGateway.deliver(delivery.toGatewayRequest()))
                .thenThrow(
                        TemporalUpdateDeliveryException.permanent(
                                "TEMPORAL_UPDATE_REJECTED", "late rejection", null));
        when(outboxStore.resolvePermanentFailure(
                        delivery,
                        "TEMPORAL_UPDATE_REJECTED",
                        "TemporalUpdateDeliveryException: late rejection",
                        NOW_OFFSET))
                .thenReturn(PermanentFailureResolution.RECONCILED);

        assertThat(dispatcher.dispatchNow("COUT_1")).isTrue();

        assertThat(tracedResults)
                .containsExactly(
                        DeliveryTraceResult.success(DeliveryOutcome.RECONCILED));
    }

    @Test
    void permanentFailureAfterLeaseExpiryIsTracedAsStaleLease() {
        var delivery = delivery(2, "lease-stale");
        when(outboxStore.claimById(
                        "COUT_1", NOW_OFFSET, Duration.ofMinutes(1)))
                .thenReturn(Optional.of(delivery));
        when(temporalGateway.deliver(delivery.toGatewayRequest()))
                .thenThrow(
                        TemporalUpdateDeliveryException.permanent(
                                "TEMPORAL_UPDATE_REJECTED", "late rejection", null));
        when(outboxStore.resolvePermanentFailure(
                        delivery,
                        "TEMPORAL_UPDATE_REJECTED",
                        "TemporalUpdateDeliveryException: late rejection",
                        NOW_OFFSET))
                .thenReturn(PermanentFailureResolution.STALE_LEASE);

        assertThat(dispatcher.dispatchNow("COUT_1")).isTrue();

        assertThat(tracedResults)
                .containsExactly(
                        DeliveryTraceResult.failure(
                                DeliveryOutcome.STALE_LEASE,
                                "TEMPORAL_UPDATE_REJECTED"));
    }

    @Test
    void aDeliveryRecoversAfterTemporalWasUnavailableForFiveMinutes() {
        OffsetDateTime recoveredAt = NOW_OFFSET.plusMinutes(5);
        dispatcher =
                new TemporalCommandDispatcher(
                        outboxStore,
                        temporalGateway,
                        new CommandOutboxProperties(
                                false,
                                10,
                                Duration.ofMinutes(1),
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(5)),
                        Clock.fixed(recoveredAt.toInstant(), ZoneOffset.UTC));
        var recovered = delivery(20, "lease-after-outage", recoveredAt.plusMinutes(1));
        when(outboxStore.claimById(
                        "COUT_1", recoveredAt, Duration.ofMinutes(1)))
                .thenReturn(Optional.of(recovered));
        when(temporalGateway.deliver(recovered.toGatewayRequest()))
                .thenReturn(new TemporalUpdateGateway.DeliveryReceipt("run-recovered"));
        when(outboxStore.markDelivered(recovered, "run-recovered", recoveredAt))
                .thenReturn(true);

        assertThat(dispatcher.dispatchNow("COUT_1")).isTrue();

        verify(outboxStore).markDelivered(recovered, "run-recovered", recoveredAt);
        verify(outboxStore, never())
                .resolvePermanentFailure(any(), any(), any(), any());
    }

    @Test
    void anExpiredCommandIsPersistedOnlyAfterTemporalDefinitivelyRejectsIt() {
        OffsetDateTime deadline = NOW_OFFSET.plusHours(1);
        dispatcher =
                new TemporalCommandDispatcher(
                        outboxStore,
                        temporalGateway,
                        new CommandOutboxProperties(
                                false,
                                10,
                                Duration.ofMinutes(1),
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(5)),
                        Clock.fixed(deadline.toInstant(), ZoneOffset.UTC));
        var expired = delivery(7, "lease-expired", deadline.plusMinutes(1));
        when(outboxStore.claimById(
                        "COUT_1", deadline, Duration.ofMinutes(1)))
                .thenReturn(Optional.of(expired));
        when(temporalGateway.deliver(expired.toGatewayRequest()))
                .thenThrow(
                        TemporalUpdateDeliveryException.permanent(
                                "COMMAND_DEADLINE_EXPIRED",
                                "workflow rejected a newly late command",
                                null));
        when(outboxStore.markExpired(
                        eq(expired),
                        eq("COMMAND_DEADLINE_EXPIRED"),
                        any(),
                        eq(deadline)))
                .thenReturn(ExpirationResolution.EXPIRED);

        assertThat(dispatcher.dispatchNow("COUT_1")).isTrue();

        verify(temporalGateway).deliver(expired.toGatewayRequest());
        verify(outboxStore)
                .markExpired(
                        eq(expired),
                        eq("COMMAND_DEADLINE_EXPIRED"),
                        any(),
                        eq(deadline));
    }

    @Test
    void aGenericPermanentUpdateRejectionAfterDeadlinePreservesItsActualReason() {
        OffsetDateTime afterDeadline = NOW_OFFSET.plusHours(1).plusSeconds(1);
        dispatcher =
                new TemporalCommandDispatcher(
                        outboxStore,
                        temporalGateway,
                        properties,
                        Clock.fixed(afterDeadline.toInstant(), ZoneOffset.UTC));
        var rejected = delivery(7, "lease-rejected", afterDeadline.plusMinutes(1));
        when(outboxStore.claimById(
                        "COUT_1", afterDeadline, Duration.ofMinutes(1)))
                .thenReturn(Optional.of(rejected));
        when(temporalGateway.deliver(rejected.toGatewayRequest()))
                .thenThrow(
                        TemporalUpdateDeliveryException.permanent(
                                "TEMPORAL_UPDATE_REJECTED",
                                "validator rejected the update",
                                null));
        when(outboxStore.resolvePermanentFailure(
                        rejected,
                        "TEMPORAL_UPDATE_REJECTED",
                        "TemporalUpdateDeliveryException: validator rejected the update",
                        afterDeadline))
                .thenReturn(PermanentFailureResolution.DEAD_LETTERED);

        assertThat(dispatcher.dispatchNow("COUT_1")).isTrue();

        verify(outboxStore)
                .resolvePermanentFailure(
                        rejected,
                        "TEMPORAL_UPDATE_REJECTED",
                        "TemporalUpdateDeliveryException: validator rejected the update",
                        afterDeadline);
        verify(outboxStore, never()).markExpired(any(), any(), any(), any());
    }

    @Test
    void aGenericPermanentUpdateRejectionBeforeDeadlineRemainsADeadLetter() {
        OffsetDateTime beforeDeadline = NOW_OFFSET.plusMinutes(30);
        dispatcher =
                new TemporalCommandDispatcher(
                        outboxStore,
                        temporalGateway,
                        properties,
                        Clock.fixed(beforeDeadline.toInstant(), ZoneOffset.UTC));
        var rejected = delivery(2, "lease-rejected", beforeDeadline.plusMinutes(1));
        when(outboxStore.claimById(
                        "COUT_1", beforeDeadline, Duration.ofMinutes(1)))
                .thenReturn(Optional.of(rejected));
        when(temporalGateway.deliver(rejected.toGatewayRequest()))
                .thenThrow(
                        TemporalUpdateDeliveryException.permanent(
                                "TEMPORAL_UPDATE_REJECTED",
                                "validator rejected the update",
                                null));
        when(outboxStore.resolvePermanentFailure(
                        rejected,
                        "TEMPORAL_UPDATE_REJECTED",
                        "TemporalUpdateDeliveryException: validator rejected the update",
                        beforeDeadline))
                .thenReturn(PermanentFailureResolution.DEAD_LETTERED);

        assertThat(dispatcher.dispatchNow("COUT_1")).isTrue();

        verify(outboxStore)
                .resolvePermanentFailure(
                        rejected,
                        "TEMPORAL_UPDATE_REJECTED",
                        "TemporalUpdateDeliveryException: validator rejected the update",
                        beforeDeadline);
        verify(outboxStore, never()).markExpired(any(), any(), any(), any());
    }

    @Test
    void aRetryableFailureAfterTheDeadlineKeepsTheSameUpdatePendingForDisambiguation() {
        OffsetDateTime afterDeadline = NOW_OFFSET.plusHours(2);
        dispatcher =
                new TemporalCommandDispatcher(
                        outboxStore,
                        temporalGateway,
                        new CommandOutboxProperties(
                                false,
                                10,
                                Duration.ofMinutes(1),
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(5)),
                        Clock.fixed(afterDeadline.toInstant(), ZoneOffset.UTC));
        var uncertain = delivery(7, "lease-uncertain", afterDeadline.plusMinutes(1));
        when(outboxStore.claimById(
                        "COUT_1", afterDeadline, Duration.ofMinutes(1)))
                .thenReturn(Optional.of(uncertain));
        when(temporalGateway.deliver(uncertain.toGatewayRequest()))
                .thenThrow(
                        TemporalUpdateDeliveryException.retryable(
                                "TEMPORAL_UNAVAILABLE", "receipt is uncertain", null));
        when(outboxStore.markRetry(
                        eq(uncertain),
                        eq("TEMPORAL_UNAVAILABLE"),
                        any(),
                        eq(afterDeadline.plusSeconds(10)),
                        eq(afterDeadline)))
                .thenReturn(true);

        assertThat(dispatcher.dispatchNow("COUT_1")).isTrue();

        verify(outboxStore)
                .markRetry(
                        eq(uncertain),
                        eq("TEMPORAL_UNAVAILABLE"),
                        any(),
                        eq(afterDeadline.plusSeconds(10)),
                        eq(afterDeadline));
        verify(outboxStore, never()).markExpired(any(), any(), any(), any());
    }

    @Test
    void batchDispatchClaimsEachLeaseImmediatelyBeforeItsDelivery() {
        var first = delivery(1, "lease-first");
        var second = delivery(1, "lease-second");
        when(outboxStore.claimNext(NOW_OFFSET, Duration.ofMinutes(1)))
                .thenReturn(Optional.of(first), Optional.of(second), Optional.empty());
        when(temporalGateway.deliver(any()))
                .thenReturn(
                        new TemporalUpdateGateway.DeliveryReceipt("run-first"),
                        new TemporalUpdateGateway.DeliveryReceipt("run-second"));
        when(outboxStore.markDelivered(first, "run-first", NOW_OFFSET))
                .thenReturn(true);
        when(outboxStore.markDelivered(second, "run-second", NOW_OFFSET))
                .thenReturn(true);

        assertThat(dispatcher.dispatchAvailable()).isEqualTo(2);

        InOrder order = inOrder(outboxStore, temporalGateway);
        order.verify(outboxStore).claimNext(NOW_OFFSET, Duration.ofMinutes(1));
        order.verify(temporalGateway).deliver(first.toGatewayRequest());
        order.verify(outboxStore).markDelivered(first, "run-first", NOW_OFFSET);
        order.verify(outboxStore).claimNext(NOW_OFFSET, Duration.ofMinutes(1));
        order.verify(temporalGateway).deliver(second.toGatewayRequest());
        order.verify(outboxStore).markDelivered(second, "run-second", NOW_OFFSET);
        order.verify(outboxStore).claimNext(NOW_OFFSET, Duration.ofMinutes(1));
        verify(outboxStore, never()).claimBatch(any(), any(), anyInt());
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
        return delivery(attemptCount, leaseToken, NOW_OFFSET.plusMinutes(1));
    }

    private static ClaimedCaseCommandDelivery delivery(
            int attemptCount, String leaseToken, OffsetDateTime leaseExpiresAt) {
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
                leaseExpiresAt);
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
