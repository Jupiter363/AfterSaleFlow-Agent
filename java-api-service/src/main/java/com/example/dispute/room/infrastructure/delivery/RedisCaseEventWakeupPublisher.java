package com.example.dispute.room.infrastructure.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Publishes only case cursor hints; event payloads remain exclusively in PostgreSQL. */
@Component
public final class RedisCaseEventWakeupPublisher implements CaseEventWakeupPublisher, AutoCloseable {

    public static final String CHANNEL = "dispute:case-event:v1:wakeup";
    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedisCaseEventWakeupPublisher.class);
    private static final long FAILURE_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor deliveryExecutor;
    private final AtomicLong nextFailureLog = new AtomicLong();

    @Autowired
    public RedisCaseEventWakeupPublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(redis, objectMapper, newDeliveryExecutor());
    }

    RedisCaseEventWakeupPublisher(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            ThreadPoolExecutor deliveryExecutor) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.deliveryExecutor =
                Objects.requireNonNull(deliveryExecutor, "deliveryExecutor must not be null");
    }

    @Override
    public void publish(CaseEventWakeup wakeup) {
        if (wakeup == null) {
            logDroppedHint(new IllegalArgumentException("wakeup must not be null"));
            return;
        }
        try {
            deliveryExecutor.execute(() -> deliver(wakeup));
        } catch (RuntimeException failure) {
            logDroppedHint(failure);
        }
    }

    private void deliver(CaseEventWakeup wakeup) {
        try {
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(wakeup));
        } catch (JsonProcessingException | RuntimeException failure) {
            logDroppedHint(failure);
        }
    }

    private void logDroppedHint(Exception failure) {
        long now = System.nanoTime();
        long next = nextFailureLog.get();
        if (now >= next && nextFailureLog.compareAndSet(next, now + FAILURE_LOG_INTERVAL_NANOS)) {
            LOGGER.warn(
                    "Redis case event wakeup dropped; PostgreSQL replay remains authoritative",
                    failure);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        deliveryExecutor.shutdownNow();
    }

    private static ThreadPoolExecutor newDeliveryExecutor() {
        ThreadFactory threadFactory =
                runnable -> {
                    Thread thread = new Thread(runnable, "case-event-wakeup-publisher");
                    thread.setDaemon(true);
                    return thread;
                };
        // Hints are advisory, so under Redis backpressure retain the newest durable cursors.
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256),
                threadFactory,
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }
}
