package com.example.dispute.agentstream.infrastructure.delivery;

import com.example.dispute.agentstream.application.AgentRunStreamEventService;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/** Relays trusted transient Redis events into this servlet node's in-memory SSE subscribers. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "app.agent-run-v2.enabled", havingValue = "true")
public final class RedisAgentRunTransientStreamSubscriber implements MessageListener {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedisAgentRunTransientStreamSubscriber.class);
    private static final long WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final ObjectMapper objectMapper;
    private final AgentRunStreamEventService eventService;
    private final AtomicLong nextWarning = new AtomicLong();

    public RedisAgentRunTransientStreamSubscriber(
            ObjectMapper objectMapper, AgentRunStreamEventService eventService) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.eventService = Objects.requireNonNull(eventService, "eventService");
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        byte[] body = message.getBody();
        if (body.length > RedisAgentRunTransientStreamPublisher.MAX_EVENT_BYTES) {
            logIgnoredEvent(new IllegalArgumentException("transient event exceeds byte budget"));
            return;
        }
        accept(new String(body, StandardCharsets.UTF_8));
    }

    void accept(String encoded) {
        try {
            AgentStreamEvent event = objectMapper.readValue(encoded, AgentStreamEvent.class);
            eventService.publish(event);
        } catch (JsonProcessingException | RuntimeException failure) {
            logIgnoredEvent(failure);
        }
    }

    private void logIgnoredEvent(Exception failure) {
        long now = System.nanoTime();
        long next = nextWarning.get();
        if (now >= next && nextWarning.compareAndSet(next, now + WARNING_INTERVAL_NANOS)) {
            LOGGER.warn(
                    "Ignoring transient AgentRun relay event: failure_type={}",
                    failure.getClass().getName());
        }
    }
}
