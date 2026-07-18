package com.example.dispute.agentstream.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.example.dispute.agentstream.infrastructure.delivery.AgentRunStreamWakeup;
import com.example.dispute.agentstream.infrastructure.delivery.RedisAgentRunStreamWakeupPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisAgentRunStreamWakeupPublisherTest {

    @Test
    void publishesOnlyAttemptCursorMetadata() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RedisAgentRunStreamWakeupPublisher publisher =
                new RedisAgentRunStreamWakeupPublisher(redis, objectMapper);

        try {
            publisher.publish(
                    new AgentRunStreamWakeup(
                            AgentRunStreamWakeup.SCHEMA_VERSION, "RUN_1", "ATTEMPT_1", 9));

            ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
            verify(redis, timeout(2_000))
                    .convertAndSend(
                            org.mockito.ArgumentMatchers.eq(
                                    RedisAgentRunStreamWakeupPublisher.CHANNEL),
                            payload.capture());
            JsonNode json = objectMapper.readTree(payload.getValue());
            Set<String> fieldNames = new HashSet<>();
            json.fieldNames().forEachRemaining(fieldNames::add);
            assertThat(fieldNames)
                    .isEqualTo(
                            Set.of(
                                    "schema_version",
                                    "run_id",
                                    "attempt_id",
                                    "durable_high_watermark"));
            assertThat(json.path("durable_high_watermark").asLong()).isEqualTo(9);
        } finally {
            publisher.close();
        }
    }
}
