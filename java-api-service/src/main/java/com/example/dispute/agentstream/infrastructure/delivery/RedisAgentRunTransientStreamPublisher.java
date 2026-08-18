package com.example.dispute.agentstream.infrastructure.delivery;

import com.example.dispute.agentstream.application.AgentRunTransientStreamPublisher;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Publishes transient v3 frame bytes across worker/API process boundaries without persistence. */
@Primary
@Component
@ConditionalOnProperty(name = "app.agent-run-v2.enabled", havingValue = "true")
public final class RedisAgentRunTransientStreamPublisher
        implements AgentRunTransientStreamPublisher {

    public static final String CHANNEL = "dispute:agent-stream:v3:transient";
    static final int MAX_EVENT_BYTES = 64 * 1024;
    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedisAgentRunTransientStreamPublisher.class);
    private static final long WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AtomicLong nextWarning = new AtomicLong();

    public RedisAgentRunTransientStreamPublisher(
            StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void publish(AgentStreamEvent event) {
        AgentRunTransientStreamPublisher.requireTransientV3(event);
        String encoded;
        try {
            encoded = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("cannot encode transient AgentRun event", failure);
        }
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("transient AgentRun event exceeds its byte budget");
        }
        try {
            redis.convertAndSend(CHANNEL, encoded);
        } catch (RuntimeException failure) {
            logDroppedEvent(failure);
        }
    }

    private void logDroppedEvent(RuntimeException failure) {
        long now = System.nanoTime();
        long next = nextWarning.get();
        if (now >= next && nextWarning.compareAndSet(next, now + WARNING_INTERVAL_NANOS)) {
            LOGGER.warn(
                    "Redis transient AgentRun event dropped; frame snapshot remains authoritative: failure_type={}",
                    failure.getClass().getName());
        }
    }
}
