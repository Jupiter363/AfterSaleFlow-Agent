package com.example.dispute.workflow.shadow.evidence;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Process-local, fail-closed admission policy for synthetic Evidence graph work.
 *
 * <p>The single fair FIFO protects global, tenant, and room counters atomically. It deliberately
 * does not claim cross-replica GRAPH-016 coordination; a distributed lease remains a separate
 * integration obligation.
 */
public final class EvidenceBulkheadPolicy implements AutoCloseable {

    public static final int MAX_ROOM_CONCURRENCY = 8;
    public static final int MAX_IDENTIFIER_LENGTH = 128;
    public static final Duration MAX_ACQUIRE_TIMEOUT = Duration.ofSeconds(30);
    private static final String METRIC_COMPONENT = "evidence_bulkhead";

    private final Limits limits;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
    private final Map<String, Integer> tenantInFlight = new HashMap<>();
    private final Map<RoomKey, Integer> roomInFlight = new HashMap<>();
    private final Map<String, Integer> tenantQueued = new HashMap<>();
    private int globalInFlight;
    private boolean closed;

    public EvidenceBulkheadPolicy(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
    }

    public Lease acquire(AdmissionKey key) throws InterruptedException {
        return acquire(key, limits.defaultAcquireTimeout());
    }

    public Lease acquire(AdmissionKey key, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(key, "key must not be null");
        long remainingNanos = validatedTimeout(timeout).toNanos();
        lock.lockInterruptibly();
        Waiter waiter = null;
        try {
            requireOpen();
            if (waiters.isEmpty() && canAdmit(key)) {
                return admit(key);
            }
            if (remainingNanos == 0) {
                throw rejected(RejectReason.TIMED_OUT);
            }
            waiter = enqueue(key);
            while (true) {
                if (closed) {
                    remove(waiter);
                    throw rejected(RejectReason.CLOSED);
                }
                if (waiters.peekFirst() == waiter && canAdmit(key)) {
                    remove(waiter);
                    Lease lease = admit(key);
                    signalNext();
                    return lease;
                }
                if (remainingNanos <= 0) {
                    remove(waiter);
                    signalNext();
                    throw rejected(RejectReason.TIMED_OUT);
                }
                try {
                    remainingNanos = waiter.ready().awaitNanos(remainingNanos);
                } catch (InterruptedException cancelled) {
                    remove(waiter);
                    signalNext();
                    throw cancelled;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(
                    globalInFlight,
                    waiters.size(),
                    tenantInFlight.size(),
                    roomInFlight.size(),
                    closed);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            waiters.forEach(waiter -> waiter.ready().signal());
        } finally {
            lock.unlock();
        }
    }

    private Waiter enqueue(AdmissionKey key) {
        if (waiters.size() >= limits.globalQueueCapacity()) {
            throw rejected(RejectReason.GLOBAL_QUEUE_FULL);
        }
        int queuedForTenant = tenantQueued.getOrDefault(key.tenantId(), 0);
        if (queuedForTenant >= limits.tenantQueueCapacity()) {
            throw rejected(RejectReason.TENANT_QUEUE_FULL);
        }
        Waiter waiter = new Waiter(key, lock.newCondition());
        waiters.addLast(waiter);
        tenantQueued.put(key.tenantId(), queuedForTenant + 1);
        return waiter;
    }

    private void remove(Waiter waiter) {
        if (!waiters.remove(waiter)) {
            return;
        }
        decrementOrRemove(tenantQueued, waiter.key().tenantId());
    }

    private boolean canAdmit(AdmissionKey key) {
        return globalInFlight < limits.globalConcurrency()
                && tenantInFlight.getOrDefault(key.tenantId(), 0)
                        < limits.tenantConcurrency()
                && roomInFlight.getOrDefault(RoomKey.from(key), 0)
                        < limits.roomConcurrency();
    }

    private Lease admit(AdmissionKey key) {
        globalInFlight++;
        tenantInFlight.merge(key.tenantId(), 1, Integer::sum);
        roomInFlight.merge(RoomKey.from(key), 1, Integer::sum);
        return new Lease(this, key);
    }

    private void release(AdmissionKey key) {
        lock.lock();
        try {
            if (globalInFlight <= 0) {
                throw new IllegalStateException("Evidence bulkhead permit counters are corrupt");
            }
            globalInFlight--;
            decrementOrRemove(tenantInFlight, key.tenantId());
            decrementOrRemove(roomInFlight, RoomKey.from(key));
            signalNext();
        } finally {
            lock.unlock();
        }
    }

    private void signalNext() {
        Waiter next = waiters.peekFirst();
        if (next != null) {
            next.ready().signal();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw rejected(RejectReason.CLOSED);
        }
    }

    private static Duration validatedTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.compareTo(MAX_ACQUIRE_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "timeout must be between zero and " + MAX_ACQUIRE_TIMEOUT);
        }
        return timeout;
    }

    private static BulkheadRejectedException rejected(RejectReason reason) {
        return new BulkheadRejectedException(reason);
    }

    private static <K> void decrementOrRemove(Map<K, Integer> counters, K key) {
        Integer current = counters.get(key);
        if (current == null || current <= 0) {
            throw new IllegalStateException("Evidence bulkhead permit counters are corrupt");
        }
        if (current == 1) {
            counters.remove(key);
        } else {
            counters.put(key, current - 1);
        }
    }

    public record Limits(
            int roomConcurrency,
            int tenantConcurrency,
            int globalConcurrency,
            int tenantQueueCapacity,
            int globalQueueCapacity,
            Duration defaultAcquireTimeout) {

        public Limits {
            if (roomConcurrency < 1 || roomConcurrency > MAX_ROOM_CONCURRENCY) {
                throw new IllegalArgumentException("roomConcurrency must be between 1 and 8");
            }
            if (tenantConcurrency < roomConcurrency) {
                throw new IllegalArgumentException(
                        "tenantConcurrency must be at least roomConcurrency");
            }
            if (globalConcurrency < tenantConcurrency) {
                throw new IllegalArgumentException(
                        "globalConcurrency must be at least tenantConcurrency");
            }
            if (tenantQueueCapacity < 1) {
                throw new IllegalArgumentException("tenantQueueCapacity must be positive");
            }
            if (globalQueueCapacity < tenantQueueCapacity) {
                throw new IllegalArgumentException(
                        "globalQueueCapacity must be at least tenantQueueCapacity");
            }
            validatedTimeout(defaultAcquireTimeout);
            if (defaultAcquireTimeout.isZero()) {
                throw new IllegalArgumentException("defaultAcquireTimeout must be positive");
            }
        }

        public static Limits defaults() {
            return new Limits(8, 32, 256, 32, 1024, Duration.ofSeconds(5));
        }
    }

    public record AdmissionKey(String tenantId, String roomId) {

        public AdmissionKey {
            tenantId = requireIdentifier(tenantId, "tenantId");
            roomId = requireIdentifier(roomId, "roomId");
        }

        private static String requireIdentifier(String value, String field) {
            if (value == null
                    || value.isBlank()
                    || !value.equals(value.trim())
                    || value.length() > MAX_IDENTIFIER_LENGTH) {
                throw new IllegalArgumentException(
                        field + " must be trimmed, non-blank, and at most 128 characters");
            }
            return value;
        }
    }

    public static final class Lease implements AutoCloseable {

        private final EvidenceBulkheadPolicy owner;
        private final AdmissionKey key;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(EvidenceBulkheadPolicy owner, AdmissionKey key) {
            this.owner = owner;
            this.key = key;
        }

        public MetricLabels admittedMetricLabels() {
            return MetricLabels.of(MetricOutcome.ADMITTED, MetricScope.GLOBAL);
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                owner.release(key);
            }
        }
    }

    public enum RejectReason {
        GLOBAL_QUEUE_FULL(MetricOutcome.REJECTED, MetricScope.GLOBAL),
        TENANT_QUEUE_FULL(MetricOutcome.REJECTED, MetricScope.TENANT),
        TIMED_OUT(MetricOutcome.TIMED_OUT, MetricScope.GLOBAL),
        CLOSED(MetricOutcome.REJECTED, MetricScope.GLOBAL);

        private final MetricOutcome outcome;
        private final MetricScope scope;

        RejectReason(MetricOutcome outcome, MetricScope scope) {
            this.outcome = outcome;
            this.scope = scope;
        }

        public MetricLabels metricLabels() {
            return MetricLabels.of(outcome, scope);
        }
    }

    public enum MetricOutcome {
        ADMITTED("admitted"),
        REJECTED("rejected"),
        TIMED_OUT("timed_out"),
        CANCELLED("cancelled"),
        RELEASED("released");

        private final String label;

        MetricOutcome(String label) {
            this.label = label;
        }
    }

    public enum MetricScope {
        ROOM("room"),
        TENANT("tenant"),
        GLOBAL("global");

        private final String label;

        MetricScope(String label) {
            this.label = label;
        }
    }

    /** Metric labels are a closed enum product and never contain tenant or room identifiers. */
    public record MetricLabels(String component, String outcome, String scope) {

        private static MetricLabels of(MetricOutcome outcome, MetricScope scope) {
            return new MetricLabels(METRIC_COMPONENT, outcome.label, scope.label);
        }

        public Map<String, String> asMap() {
            return Map.of("component", component, "outcome", outcome, "scope", scope);
        }
    }

    public static MetricLabels cancellationMetricLabels() {
        return MetricLabels.of(MetricOutcome.CANCELLED, MetricScope.GLOBAL);
    }

    public static MetricLabels releaseMetricLabels() {
        return MetricLabels.of(MetricOutcome.RELEASED, MetricScope.GLOBAL);
    }

    public static final class BulkheadRejectedException extends RuntimeException {

        private final RejectReason reason;

        private BulkheadRejectedException(RejectReason reason) {
            super("Evidence bulkhead admission rejected: " + reason.name());
            this.reason = reason;
        }

        public RejectReason reason() {
            return reason;
        }

        public MetricLabels metricLabels() {
            return reason.metricLabels();
        }
    }

    public record Snapshot(
            int globalInFlight,
            int globalQueued,
            int activeTenantCount,
            int activeRoomCount,
            boolean closed) {}

    private record RoomKey(String tenantId, String roomId) {

        private static RoomKey from(AdmissionKey key) {
            return new RoomKey(key.tenantId(), key.roomId());
        }
    }

    private record Waiter(AdmissionKey key, Condition ready) {}
}
