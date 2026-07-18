package com.example.dispute.workflow.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.workflow.config.RoomEpochBootstrapProperties;
import com.example.dispute.workflow.infrastructure.bootstrap.ClaimedRoomEpochBootstrap;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochBootstrapDispatcher;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochBootstrapStore;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochProvisioningException;
import com.example.dispute.workflow.infrastructure.bootstrap.RoomEpochProvisioningGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoomEpochBootstrapDispatcherTest {

    private static final Instant NOW = RoomEpochProvisioningFixtures.REQUESTED_AT;
    private static final OffsetDateTime NOW_OFFSET =
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock private RoomEpochBootstrapStore store;
    @Mock private RoomEpochProvisioningGateway gateway;

    private RoomEpochBootstrapDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher =
                new RoomEpochBootstrapDispatcher(
                        store,
                        gateway,
                        properties(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        dispatcher.closeExecutor();
    }

    @Test
    void unknownOutcomeStillRetriesAfterManyAttempts() {
        var delivery = delivery("CASE_1", "EPOCH_1", 999, "lease-1");
        when(store.claimById("REBOOT_EPOCH_1", NOW_OFFSET, Duration.ofMinutes(2)))
                .thenReturn(Optional.of(delivery));
        when(store.beginProvisioning(delivery, NOW_OFFSET)).thenReturn(true);
        when(gateway.provision(delivery.toGatewayRequest()))
                .thenThrow(
                        RoomEpochProvisioningException.retryable(
                                "TEMPORAL_UNAVAILABLE", "outcome unknown", null));
        when(store.markRetry(
                        eq(delivery),
                        eq("TEMPORAL_UNAVAILABLE"),
                        any(),
                        eq(NOW_OFFSET.plusMinutes(5)),
                        eq(NOW_OFFSET)))
                .thenReturn(true);

        assertThat(dispatcher.dispatchNow("REBOOT_EPOCH_1")).isTrue();

        verify(store)
                .markRetry(
                        eq(delivery),
                        eq("TEMPORAL_UNAVAILABLE"),
                        any(),
                        eq(NOW_OFFSET.plusMinutes(5)),
                        eq(NOW_OFFSET));
        verify(store, never()).deadLetter(any(), any(), any(), any());
    }

    @Test
    void onlyDefinitiveConflictDeadLettersTheEpoch() {
        var delivery = delivery("CASE_1", "EPOCH_1", 1, "lease-1");
        when(store.claimById("REBOOT_EPOCH_1", NOW_OFFSET, Duration.ofMinutes(2)))
                .thenReturn(Optional.of(delivery));
        when(store.beginProvisioning(delivery, NOW_OFFSET)).thenReturn(true);
        when(gateway.provision(delivery.toGatewayRequest()))
                .thenThrow(
                        RoomEpochProvisioningException.permanent(
                                "ROOM_EPOCH_FENCE_CONFLICT", "stale fence", null));
        when(store.deadLetter(
                        eq(delivery),
                        eq("ROOM_EPOCH_FENCE_CONFLICT"),
                        any(),
                        eq(NOW_OFFSET)))
                .thenReturn(true);

        dispatcher.dispatchNow("REBOOT_EPOCH_1");

        verify(store)
                .deadLetter(
                        eq(delivery),
                        eq("ROOM_EPOCH_FENCE_CONFLICT"),
                        any(),
                        eq(NOW_OFFSET));
        verify(store, never()).markRetry(any(), any(), any(), any(), any());
    }

    @Test
    void slowFirstProvisioningDoesNotBlockTheNextClaimedRoom() throws Exception {
        var first = delivery("CASE_1", "EPOCH_1", 1, "lease-1");
        var second = delivery("CASE_2", "EPOCH_2", 1, "lease-2");
        when(store.claimNext(NOW_OFFSET, Duration.ofMinutes(2)))
                .thenReturn(Optional.of(first), Optional.of(second));
        when(store.beginProvisioning(any(), eq(NOW_OFFSET))).thenReturn(true);
        when(store.finalizeProvisioning(any(), any(), eq(NOW_OFFSET))).thenReturn(true);
        CountDownLatch secondStarted = new CountDownLatch(1);
        when(gateway.provision(any()))
                .thenAnswer(
                        invocation -> {
                            RoomEpochProvisioningGateway.ProvisioningRequest request =
                                    invocation.getArgument(0);
                            if (request.command().caseId().equals("CASE_1")) {
                                assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
                            } else {
                                secondStarted.countDown();
                            }
                            return RoomEpochProvisioningFixtures.receipt(request.command());
                        });

        assertThat(dispatcher.dispatchAvailable()).isEqualTo(2);

        verify(gateway, times(2)).provision(any());
        ArgumentCaptor<OffsetDateTime> finalizedAt =
                ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(store, times(2))
                .finalizeProvisioning(any(), any(), finalizedAt.capture());
        assertThat(finalizedAt.getAllValues())
                .allMatch(timestamp -> timestamp.isBefore(first.leaseExpiresAt()));
    }

    private static ClaimedRoomEpochBootstrap delivery(
            String caseId, String epochId, int attempt, String lease) {
        var command = RoomEpochProvisioningFixtures.command(epochId, caseId);
        return new ClaimedRoomEpochBootstrap(
                "REBOOT_" + epochId,
                epochId,
                "CaseProcessWorkflow",
                "case-control",
                command.updateId(),
                command.payloadSha256(),
                command,
                attempt,
                lease,
                NOW_OFFSET.plusMinutes(2));
    }

    private static RoomEpochBootstrapProperties properties() {
        return new RoomEpochBootstrapProperties(
                true,
                2,
                2,
                Duration.ofMinutes(2),
                Duration.ofSeconds(90),
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                Duration.ofSeconds(5));
    }
}
