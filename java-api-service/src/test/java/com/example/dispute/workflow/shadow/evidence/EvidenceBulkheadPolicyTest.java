package com.example.dispute.workflow.shadow.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.shadow.evidence.EvidenceBulkheadPolicy.AdmissionKey;
import com.example.dispute.workflow.shadow.evidence.EvidenceBulkheadPolicy.BulkheadRejectedException;
import com.example.dispute.workflow.shadow.evidence.EvidenceBulkheadPolicy.Lease;
import com.example.dispute.workflow.shadow.evidence.EvidenceBulkheadPolicy.Limits;
import com.example.dispute.workflow.shadow.evidence.EvidenceBulkheadPolicy.RejectReason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class EvidenceBulkheadPolicyTest {

    @Test
    void enforcesRoomCeilingAndBoundedConfiguration() throws Exception {
        assertThatThrownBy(() -> new Limits(9, 9, 9, 1, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("roomConcurrency");
        assertThatThrownBy(() -> new Limits(2, 1, 2, 1, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantConcurrency");
        assertThatThrownBy(() -> new Limits(1, 2, 1, 1, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("globalConcurrency");
        assertThatThrownBy(() -> new Limits(1, 1, 1, 2, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("globalQueueCapacity");
        assertThatThrownBy(() -> new Limits(1, 1, 1, 1, 1, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");

        try (EvidenceBulkheadPolicy policy = policy(2, 4, 4, 2, 4);
                Lease first = policy.acquire(key("tenant-a", "room-a"));
                Lease second = policy.acquire(key("tenant-a", "room-a"))) {
            assertThatThrownBy(
                            () -> policy.acquire(key("tenant-a", "room-a"), Duration.ZERO))
                    .isInstanceOfSatisfying(
                            BulkheadRejectedException.class,
                            rejected -> assertThat(rejected.reason())
                                    .isEqualTo(RejectReason.TIMED_OUT));
            assertThat(policy.snapshot().globalInFlight()).isEqualTo(2);
        }
    }

    @Test
    void boundsGlobalAndTenantQueuesWithClosedReasonLabels() throws Exception {
        try (EvidenceBulkheadPolicy globalPolicy = policy(1, 1, 1, 1, 1);
                Lease held = globalPolicy.acquire(key("tenant-a", "room-held"));
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Lease> queued = executor.submit(() -> globalPolicy.acquire(
                    key("tenant-b", "room-queued"), Duration.ofSeconds(2)));
            await(() -> globalPolicy.snapshot().globalQueued() == 1);

            assertThatThrownBy(() -> globalPolicy.acquire(
                            key("tenant-c", "room-overflow"), Duration.ofSeconds(1)))
                    .isInstanceOfSatisfying(
                            BulkheadRejectedException.class,
                            rejected -> {
                                assertThat(rejected.reason())
                                        .isEqualTo(RejectReason.GLOBAL_QUEUE_FULL);
                                assertThat(rejected.metricLabels().asMap())
                                        .containsExactlyInAnyOrderEntriesOf(
                                                java.util.Map.of(
                                                        "component", "evidence_bulkhead",
                                                        "outcome", "rejected",
                                                        "scope", "global"));
                            });
            held.close();
            queued.get().close();
        }

        try (EvidenceBulkheadPolicy tenantPolicy = policy(1, 1, 2, 1, 2);
                Lease held = tenantPolicy.acquire(key("tenant-a", "room-held"));
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Lease> queued = executor.submit(() -> tenantPolicy.acquire(
                    key("tenant-a", "room-queued"), Duration.ofSeconds(2)));
            await(() -> tenantPolicy.snapshot().globalQueued() == 1);

            assertThatThrownBy(() -> tenantPolicy.acquire(
                            key("tenant-a", "room-overflow"), Duration.ofSeconds(1)))
                    .isInstanceOfSatisfying(
                            BulkheadRejectedException.class,
                            rejected -> {
                                assertThat(rejected.reason())
                                        .isEqualTo(RejectReason.TENANT_QUEUE_FULL);
                                assertThat(rejected.metricLabels().scope())
                                        .isEqualTo("tenant");
                            });
            held.close();
            queued.get().close();
        }
    }

    @Test
    void admitsQueuedRequestsInGlobalFifoOrder() throws Exception {
        List<String> admitted = Collections.synchronizedList(new ArrayList<>());
        try (EvidenceBulkheadPolicy policy = policy(1, 1, 1, 4, 4);
                Lease held = policy.acquire(key("tenant-root", "room-root"));
                ExecutorService executor = Executors.newFixedThreadPool(3)) {
            List<Future<Void>> futures = new ArrayList<>();
            for (int index = 1; index <= 3; index++) {
                String id = Integer.toString(index);
                futures.add(executor.submit(() -> {
                    try (Lease ignored = policy.acquire(
                            key("tenant-" + id, "room-" + id), Duration.ofSeconds(2))) {
                        admitted.add(id);
                    }
                    return null;
                }));
                int expectedQueued = index;
                await(() -> policy.snapshot().globalQueued() == expectedQueued);
            }

            held.close();
            for (Future<Void> future : futures) {
                future.get();
            }
            assertThat(admitted).containsExactly("1", "2", "3");
            assertThat(policy.snapshot().globalInFlight()).isZero();
        }
    }

    @Test
    void timeoutAndInterruptedCancellationRemoveQueueEntries() throws Exception {
        try (EvidenceBulkheadPolicy policy = policy(1, 1, 1, 2, 2);
                Lease held = policy.acquire(key("tenant-a", "room-held"));
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> policy.acquire(
                            key("tenant-b", "room-timeout"), Duration.ofMillis(25)))
                    .isInstanceOfSatisfying(
                            BulkheadRejectedException.class,
                            rejected -> assertThat(rejected.reason())
                                    .isEqualTo(RejectReason.TIMED_OUT));
            assertThat(policy.snapshot().globalQueued()).isZero();

            Future<Lease> cancelled = executor.submit(() -> policy.acquire(
                    key("tenant-c", "room-cancelled"), Duration.ofSeconds(2)));
            await(() -> policy.snapshot().globalQueued() == 1);
            assertThat(cancelled.cancel(true)).isTrue();
            assertThatThrownBy(cancelled::get).isInstanceOf(CancellationException.class);
            await(() -> policy.snapshot().globalQueued() == 0);
            assertThat(EvidenceBulkheadPolicy.cancellationMetricLabels().asMap())
                    .doesNotContainValue("tenant-c")
                    .doesNotContainValue("room-cancelled")
                    .containsEntry("outcome", "cancelled");
        }
    }

    @Test
    void leaseReleaseIsIdempotentAndMetricsNeverUseRoomOrTenantIds() throws Exception {
        try (EvidenceBulkheadPolicy policy = policy(1, 1, 1, 1, 1)) {
            Lease lease = policy.acquire(key("sensitive-tenant", "sensitive-room"));
            assertThat(lease.admittedMetricLabels().asMap())
                    .doesNotContainValue("sensitive-tenant")
                    .doesNotContainValue("sensitive-room")
                    .containsEntry("outcome", "admitted");
            lease.close();
            lease.close();

            assertThat(policy.snapshot().globalInFlight()).isZero();
            assertThat(policy.snapshot().activeTenantCount()).isZero();
            assertThat(policy.snapshot().activeRoomCount()).isZero();
            assertThat(EvidenceBulkheadPolicy.releaseMetricLabels().asMap())
                    .containsEntry("outcome", "released");
        }
    }

    private static EvidenceBulkheadPolicy policy(
            int room, int tenant, int global, int tenantQueue, int globalQueue) {
        return new EvidenceBulkheadPolicy(new Limits(
                room,
                tenant,
                global,
                tenantQueue,
                globalQueue,
                Duration.ofSeconds(2)));
    }

    private static AdmissionKey key(String tenant, String room) {
        return new AdmissionKey(tenant, room);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
