package com.example.dispute.agentstream.infrastructure.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Publishes only cursor hints. Event payloads remain exclusively in PostgreSQL. */
@Component
public class RedisAgentRunStreamWakeupPublisher
        implements AgentRunStreamWakeupPublisher, AutoCloseable {

    public static final String CHANNEL = "dispute:agent-stream:v2:wakeup";
    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedisAgentRunStreamWakeupPublisher.class);
    private static final long FAILURE_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor deliveryExecutor;
    private final AtomicLong nextFailureLog = new AtomicLong();

    @Autowired
    public RedisAgentRunStreamWakeupPublisher(
            StringRedisTemplate redis, ObjectMapper objectMapper) {
        this(redis, objectMapper, newDeliveryExecutor());
    }

    RedisAgentRunStreamWakeupPublisher(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            ThreadPoolExecutor deliveryExecutor) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.deliveryExecutor =
                Objects.requireNonNull(deliveryExecutor, "deliveryExecutor must not be null");
    }

    @Override
    public void publish(AgentRunStreamWakeup wakeup) {
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

    private void deliver(AgentRunStreamWakeup wakeup) {
        try {
            String encoded = objectMapper.writeValueAsString(wakeup);
            redis.convertAndSend(CHANNEL, encoded);
        } catch (JsonProcessingException | RuntimeException failure) {
            logDroppedHint(failure);
        }
    }

    private void logDroppedHint(Exception failure) {
        long now = System.nanoTime();
        long next = nextFailureLog.get();
        if (now >= next && nextFailureLog.compareAndSet(next, now + FAILURE_LOG_INTERVAL_NANOS)) {
            LOGGER.warn(
                    "Redis agent stream wakeup dropped; PostgreSQL replay remains authoritative",
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
                    Thread thread = new Thread(runnable, "agent-stream-wakeup-publisher");
                    thread.setDaemon(true);
                    return thread;
                };
        // Wakeups are hints, so under Redis backpressure retain the newest high-watermarks.
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
