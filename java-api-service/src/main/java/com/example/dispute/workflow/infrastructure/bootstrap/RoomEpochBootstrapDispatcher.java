package com.example.dispute.workflow.infrastructure.bootstrap;

import com.example.dispute.workflow.config.RoomEpochBootstrapProperties;
import com.example.dispute.workflow.contract.v1.ProvisionRoomEpochReceipt;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class RoomEpochBootstrapDispatcher {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RoomEpochBootstrapDispatcher.class);
    private static final int MAX_ERROR_DETAIL_LENGTH = 4096;

    private final RoomEpochBootstrapStore store;
    private final RoomEpochProvisioningGateway gateway;
    private final RoomEpochBootstrapProperties properties;
    private final Clock clock;
    private final ExecutorService deliveryExecutor;

    @Autowired
    public RoomEpochBootstrapDispatcher(
            RoomEpochBootstrapStore store,
            RoomEpochProvisioningGateway gateway,
            RoomEpochBootstrapProperties properties,
            Clock clock) {
        this(store, gateway, properties, clock, Executors.newVirtualThreadPerTaskExecutor());
    }

    RoomEpochBootstrapDispatcher(
            RoomEpochBootstrapStore store,
            RoomEpochProvisioningGateway gateway,
            RoomEpochBootstrapProperties properties,
            Clock clock,
            ExecutorService deliveryExecutor) {
        this.store = store;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
        this.deliveryExecutor = deliveryExecutor;
    }

    public boolean dispatchNow(String outboxId) {
        Optional<ClaimedRoomEpochBootstrap> claimed =
                store.claimById(outboxId, now(), properties.leaseDuration());
        if (claimed.isEmpty()) {
            return false;
        }
        dispatchClaimed(claimed.orElseThrow());
        return true;
    }

    public int dispatchAvailable() {
        int limit = Math.min(properties.batchSize(), properties.concurrency());
        List<Future<?>> deliveries = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            Optional<ClaimedRoomEpochBootstrap> claimed =
                    store.claimNext(now(), properties.leaseDuration());
            if (claimed.isEmpty()) {
                break;
            }
            ClaimedRoomEpochBootstrap delivery = claimed.orElseThrow();
            deliveries.add(
                    deliveryExecutor.submit(() -> dispatchSafely(delivery)));
        }
        awaitDeliveries(deliveries);
        return deliveries.size();
    }

    private void dispatchSafely(ClaimedRoomEpochBootstrap delivery) {
        try {
            dispatchClaimed(delivery);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Room epoch bootstrap persistence failed: outbox_id={} update_id={} exception_type={} message={}",
                    delivery.outboxId(),
                    delivery.updateId(),
                    exception.getClass().getName(),
                    exception.getMessage(),
                    exception);
        }
    }

    private void dispatchClaimed(ClaimedRoomEpochBootstrap delivery) {
        OffsetDateTime startedAt = now();
        if (!store.beginProvisioning(delivery, startedAt)) {
            logStaleLease(delivery, "begin");
            return;
        }
        ProvisionRoomEpochReceipt receipt;
        try {
            receipt = gateway.provision(delivery.toGatewayRequest());
        } catch (RoomEpochProvisioningException failure) {
            handleFailure(delivery, failure);
            return;
        } catch (IllegalArgumentException failure) {
            handleFailure(
                    delivery,
                    RoomEpochProvisioningException.permanent(
                            "ROOM_EPOCH_PAYLOAD_CONFLICT", detail(failure), failure));
            return;
        }
        if (!store.finalizeProvisioning(delivery, receipt, now())) {
            logStaleLease(delivery, "finalize");
        }
    }

    private void handleFailure(
            ClaimedRoomEpochBootstrap delivery,
            RoomEpochProvisioningException failure) {
        OffsetDateTime failedAt = now();
        String errorDetail = truncate(detail(failure));
        boolean persisted;
        if (failure.retryable()) {
            persisted =
                    store.markRetry(
                            delivery,
                            failure.errorCode(),
                            errorDetail,
                            failedAt.plus(backoff(delivery.attemptCount())),
                            failedAt);
        } else {
            persisted =
                    store.deadLetter(
                            delivery,
                            failure.errorCode(),
                            errorDetail,
                            failedAt);
        }
        if (!persisted) {
            logStaleLease(delivery, failure.retryable() ? "retry" : "dead-letter");
        }
    }

    private Duration backoff(int attemptCount) {
        int exponent = Math.min(30, Math.max(0, attemptCount - 1));
        long multiplier = 1L << exponent;
        try {
            Duration candidate = properties.baseBackoff().multipliedBy(multiplier);
            return candidate.compareTo(properties.maxBackoff()) > 0
                    ? properties.maxBackoff()
                    : candidate;
        } catch (ArithmeticException exception) {
            return properties.maxBackoff();
        }
    }

    private static void awaitDeliveries(List<Future<?>> deliveries) {
        boolean interrupted = false;
        for (Future<?> delivery : deliveries) {
            try {
                delivery.get();
            } catch (InterruptedException exception) {
                interrupted = true;
                delivery.cancel(true);
            } catch (ExecutionException exception) {
                // dispatchSafely contains per-delivery failures.
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static String detail(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String truncate(String value) {
        return value.length() <= MAX_ERROR_DETAIL_LENGTH
                ? value
                : value.substring(0, MAX_ERROR_DETAIL_LENGTH);
    }

    private static void logStaleLease(
            ClaimedRoomEpochBootstrap delivery, String outcome) {
        LOGGER.info(
                "Ignored stale room epoch bootstrap completion: outbox_id={} update_id={} outcome={}",
                delivery.outboxId(),
                delivery.updateId(),
                outcome);
    }

    @PreDestroy
    public void closeExecutor() {
        deliveryExecutor.shutdownNow();
    }
}
