package com.example.dispute.agentstream.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunStreamEventService;
import com.example.dispute.workflow.contract.v1.AgentStreamEvent;
import com.example.dispute.workflow.contract.v1.ContractTypes.Audience;
import com.example.dispute.workflow.contract.v1.ContractTypes.StreamEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisAgentRunTransientStreamRelayTest {

    private final ObjectMapper objectMapper =
            JsonMapper.builder().findAndAddModules().build();

    @Test
    void relaysEachProviderDeltaAcrossRedisIntoTheServletSseNode() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AgentRunStreamEventService eventService = mock(AgentRunStreamEventService.class);
        RedisAgentRunTransientStreamSubscriber subscriber =
                new RedisAgentRunTransientStreamSubscriber(objectMapper, eventService);
        when(redis.convertAndSend(
                        eq(RedisAgentRunTransientStreamPublisher.CHANNEL), anyString()))
                .thenAnswer(
                        invocation -> {
                            subscriber.accept(invocation.getArgument(1, String.class));
                            return 1L;
                        });
        RedisAgentRunTransientStreamPublisher publisher =
                new RedisAgentRunTransientStreamPublisher(redis, objectMapper);
        AgentStreamEvent delta = delta();

        publisher.publish(delta);

        ArgumentCaptor<AgentStreamEvent> relayed =
                ArgumentCaptor.forClass(AgentStreamEvent.class);
        verify(eventService).publish(relayed.capture());
        assertThat(objectMapper.writeValueAsString(relayed.getValue()))
                .isEqualTo(objectMapper.writeValueAsString(delta));
        assertThat(
                        RedisAgentRunTransientStreamSubscriber.class
                                .getAnnotation(ConditionalOnWebApplication.class)
                                .type())
                .isEqualTo(ConditionalOnWebApplication.Type.SERVLET);
    }

    private static AgentStreamEvent delta() {
        return new AgentStreamEvent(
                "agent-stream.v3",
                "target-evidence-run:relay-test",
                "target-evidence-run:relay-test:1",
                2,
                StreamEventType.PUBLIC_TEXT_DELTA,
                Audience.USER,
                Instant.parse("2026-08-19T00:00:00Z"),
                new AgentStreamEvent.Payload(
                        null,
                        null,
                        "欢迎",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "EFRM_0123456789ABCDEF01234567",
                        1,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
    }
}
