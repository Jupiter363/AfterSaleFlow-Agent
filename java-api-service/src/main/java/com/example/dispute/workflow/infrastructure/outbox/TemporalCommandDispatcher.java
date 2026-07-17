package com.example.dispute.workflow.infrastructure.outbox;

import com.example.dispute.workflow.config.CommandOutboxProperties;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.DeliveryKind;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TemporalCommandDispatcher {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TemporalCommandDispatcher.class);
    private static final int MAX_ERROR_DETAIL_LENGTH = 4096;

    private final CaseCommandOutboxStore outboxStore;
    private final TemporalUpdateGateway temporalGateway;
    private final CommandOutboxProperties properties;
    private final Clock clock;

    public TemporalCommandDispatcher(
            CaseCommandOutboxStore outboxStore,
            TemporalUpdateGateway temporalGateway,
            CommandOutboxProperties properties,
            Clock clock) {
        this.outboxStore = outboxStore;
        this.temporalGateway = temporalGateway;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean dispatchNow(String outboxId) {
        return outboxStore
                .claimById(outboxId, now(), properties.leaseDuration())
                .map(
                        delivery -> {
                            dispatchClaimed(delivery);
                            return true;
                        })
                .orElse(false);
    }

    public int dispatchAvailable() {
        List<ClaimedCaseCommandDelivery> claimed =
                outboxStore.claimBatch(
                        now(), properties.leaseDuration(), properties.batchSize());
        claimed.forEach(
                delivery -> {
                    try {
                        dispatchClaimed(delivery);
                    } catch (RuntimeException exception) {
                        LOGGER.warn(
                                "Temporal command delivery persistence failed: outbox_id={} update_id={} exception_type={} message={}",
                                delivery.outboxId(),
                                delivery.updateId(),
                                exception.getClass().getName(),
                                exception.getMessage(),
                                exception);
                    }
                });
        return claimed.size();
    }

    private void dispatchClaimed(ClaimedCaseCommandDelivery delivery) {
        if (delivery.deliveryKind() != DeliveryKind.UPDATE_WITH_START) {
            handleFailure(
                    delivery,
                    TemporalUpdateDeliveryException.permanent(
                            "TEMPORAL_DELIVERY_KIND_INVALID",
                            "unsupported command outbox delivery kind",
                            null));
            return;
        }
        TemporalUpdateGateway.DeliveryReceipt receipt;
        try {
            receipt = temporalGateway.deliver(delivery.toGatewayRequest());
        } catch (TemporalUpdateDeliveryException exception) {
            handleFailure(delivery, exception);
            return;
        } catch (IllegalArgumentException exception) {
            handleFailure(
                    delivery,
                    TemporalUpdateDeliveryException.permanent(
                            "TEMPORAL_REQUEST_INVALID",
                            exceptionDetail(exception),
                            exception));
            return;
        } catch (RuntimeException exception) {
            handleFailure(
                    delivery,
                    TemporalUpdateDeliveryException.retryable(
                            "TEMPORAL_DISPATCH_UNEXPECTED",
                            exceptionDetail(exception),
                            exception));
            return;
        }
        boolean marked =
                outboxStore.markDelivered(delivery, receipt.temporalRunId(), now());
        if (!marked) {
            logStaleLease(delivery, "delivered");
        }
    }

    private void handleFailure(
            ClaimedCaseCommandDelivery delivery,
            TemporalUpdateDeliveryException failure) {
        OffsetDateTime failedAt = now();
        String detail = truncate(exceptionDetail(failure));
        boolean marked;
        if (failure.retryable() && delivery.attemptCount() < properties.maxAttempts()) {
            OffsetDateTime availableAt =
                    failedAt.plus(backoff(delivery.attemptCount()));
            marked =
                    outboxStore.markRetry(
                            delivery,
                            failure.errorCode(),
                            detail,
                            availableAt,
                            failedAt);
        } else {
            String errorCode =
                    failure.retryable()
                            ? "TEMPORAL_DELIVERY_EXHAUSTED"
                            : failure.errorCode();
            marked =
                    outboxStore.markDeadLetter(
                            delivery, errorCode, detail, failedAt);
        }
        if (!marked) {
            logStaleLease(delivery, "failed");
        }
    }

    private Duration backoff(int attemptCount) {
        int exponent = Math.min(30, Math.max(0, attemptCount - 1));
        long multiplier = 1L << exponent;
        Duration candidate;
        try {
            candidate = properties.baseBackoff().multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            return properties.maxBackoff();
        }
        return candidate.compareTo(properties.maxBackoff()) > 0
                ? properties.maxBackoff()
                : candidate;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static String exceptionDetail(Throwable exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String truncate(String detail) {
        return detail.length() <= MAX_ERROR_DETAIL_LENGTH
                ? detail
                : detail.substring(0, MAX_ERROR_DETAIL_LENGTH);
    }

    private static void logStaleLease(
            ClaimedCaseCommandDelivery delivery, String outcome) {
        LOGGER.info(
                "Ignored stale Temporal command delivery completion: outbox_id={} update_id={} outcome={}",
                delivery.outboxId(),
                delivery.updateId(),
                outcome);
    }
}
