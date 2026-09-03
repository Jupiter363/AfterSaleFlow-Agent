package com.example.dispute.room.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisCaseEventWakeupPublisherTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void publishesOnlyCaseCursorMetadataOnTheDedicatedChannel() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisCaseEventWakeupPublisher publisher =
                new RedisCaseEventWakeupPublisher(redis, objectMapper);

        try {
            publisher.publish(wakeup(9));

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(redis, timeout(2_000))
                    .convertAndSend(eq(RedisCaseEventWakeupPublisher.CHANNEL), payload.capture());
            JsonNode json = objectMapper.readTree(payload.getValue());
            Set<String> fieldNames = new HashSet<>();
            json.fieldNames().forEachRemaining(fieldNames::add);
            assertThat(fieldNames)
                    .containsExactlyInAnyOrder(
                            "schema_version", "case_id", "durable_sequence");
            assertThat(json.path("durable_sequence").asLong()).isEqualTo(9);
        } finally {
            publisher.close();
        }
    }

    @Test
    void redisFailureNeverEscapesTheBestEffortPublisher() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CountDownLatch attempted = new CountDownLatch(1);
        when(redis.convertAndSend(eq(RedisCaseEventWakeupPublisher.CHANNEL), any(String.class)))
                .thenAnswer(
                        ignored -> {
                            attempted.countDown();
                            throw new RedisConnectionFailureException("redis unavailable");
                        });
        RedisCaseEventWakeupPublisher publisher =
                new RedisCaseEventWakeupPublisher(redis, objectMapper);

        try {
            assertThatCode(() -> publisher.publish(wakeup(10))).doesNotThrowAnyException();
            assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            publisher.close();
        }

        assertThatCode(() -> publisher.publish(wakeup(11)))
                .as("a stopped advisory publisher cannot fail a formal commit path")
                .doesNotThrowAnyException();
    }

    private static CaseEventWakeup wakeup(long sequence) {
        return new CaseEventWakeup(CaseEventWakeup.SCHEMA_VERSION, "CASE_1", sequence);
    }
}
