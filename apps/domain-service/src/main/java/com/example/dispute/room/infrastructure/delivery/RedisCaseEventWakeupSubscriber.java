package com.example.dispute.room.infrastructure.delivery;

import com.example.dispute.room.application.CaseEventService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/** Converts Redis hints into authoritative PostgreSQL catch-up on this servlet SSE node. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class RedisCaseEventWakeupSubscriber implements MessageListener {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedisCaseEventWakeupSubscriber.class);
    private static final long WARNING_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final ObjectMapper objectMapper;
    private final CaseEventService eventService;
    private final AtomicLong nextWarning = new AtomicLong();

    public RedisCaseEventWakeupSubscriber(ObjectMapper objectMapper, CaseEventService eventService) {
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
            CaseEventWakeup hint = objectMapper.readValue(encoded, CaseEventWakeup.class);
            // durableSequence is diagnostic only. Each subscriber replays from its own cursor,
            // so stale, duplicated, or reordered Redis hints can never skip PostgreSQL rows.
            eventService.wakeUp(hint.caseId());
        } catch (JsonProcessingException | RuntimeException failure) {
            logIgnoredHint(failure);
        }
    }

    private void logIgnoredHint(Exception failure) {
        long now = System.nanoTime();
        long next = nextWarning.get();
        if (now >= next && nextWarning.compareAndSet(next, now + WARNING_INTERVAL_NANOS)) {
            LOGGER.warn(
                    "Ignoring case event wakeup; PostgreSQL replay remains authoritative: failure_type={}",
                    failure.getClass().getName());
        }
    }
}
