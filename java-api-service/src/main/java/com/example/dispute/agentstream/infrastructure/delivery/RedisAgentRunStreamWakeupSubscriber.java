package com.example.dispute.agentstream.infrastructure.delivery;

import com.example.dispute.agentstream.application.AgentRunStreamEventService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/** Converts Redis cursor hints into authoritative PostgreSQL catch-up on this SSE node. */
@Component
@ConditionalOnProperty(name = "app.agent-run-v2.enabled", havingValue = "true")
public final class RedisAgentRunStreamWakeupSubscriber implements MessageListener {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedisAgentRunStreamWakeupSubscriber.class);
    private static final long WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final ObjectMapper objectMapper;
    private final AgentRunStreamEventService eventService;
    private final AtomicLong nextWarning = new AtomicLong();

    public RedisAgentRunStreamWakeupSubscriber(
            ObjectMapper objectMapper, AgentRunStreamEventService eventService) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.eventService = Objects.requireNonNull(eventService, "eventService");
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            accept(new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            logIgnoredHint(failure);
        }
    }

    void accept(String encoded) {
        try {
            AgentRunStreamWakeup hint =
                    objectMapper.readValue(encoded, AgentRunStreamWakeup.class);
            // attemptId and high-watermark are diagnostics only. The service replays from each
            // subscriber's PostgreSQL-backed cursor, so stale or reordered hints cannot skip data.
            eventService.wakeUp(hint.runId());
        } catch (JsonProcessingException | RuntimeException failure) {
            logIgnoredHint(failure);
        }
    }

    private void logIgnoredHint(Exception failure) {
        long now = System.nanoTime();
        long next = nextWarning.get();
        if (now >= next
                && nextWarning.compareAndSet(next, now + WARNING_INTERVAL_NANOS)) {
            LOGGER.warn(
                    "Ignoring AgentRun stream wakeup; PostgreSQL replay remains authoritative: failure_type={}",
                    failure.getClass().getName());
        }
    }
}
