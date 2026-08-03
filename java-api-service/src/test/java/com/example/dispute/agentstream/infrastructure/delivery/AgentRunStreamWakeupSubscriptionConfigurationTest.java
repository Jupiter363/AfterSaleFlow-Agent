package com.example.dispute.agentstream.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunStreamEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class AgentRunStreamWakeupSubscriptionConfigurationTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final AgentRunStreamEventService eventService = mock(AgentRunStreamEventService.class);

    @Test
    void retainsTheSubscriptionForwardsWakeupsAndClosesItOnStop() throws Exception {
        LettuceConnectionFactory connectionFactory = mock(LettuceConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        AtomicReference<MessageListener> registeredListener = new AtomicReference<>();
        byte[] expectedChannel =
                RedisAgentRunStreamWakeupPublisher.CHANNEL.getBytes(StandardCharsets.UTF_8);
        when(connectionFactory.getConnection()).thenReturn(connection);
        doAnswer(invocation -> {
            MessageListener listener = invocation.getArgument(0);
            byte[] channel = invocation.getArgument(1);
            registeredListener.set(listener);
            assertThat(channel).isEqualTo(expectedChannel);
            ((SubscriptionListener) listener).onChannelSubscribed(channel, 1L);
            return null;
        }).when(connection).subscribe(any(MessageListener.class), any(byte[][].class));

        RedisAgentRunStreamWakeupSubscriber subscriber =
                new RedisAgentRunStreamWakeupSubscriber(objectMapper, eventService);
        RedisMessageListenerContainer container =
                new AgentRunStreamWakeupSubscriptionConfiguration()
                        .agentRunStreamWakeupSubscription(connectionFactory, subscriber);

        try {
            container.afterPropertiesSet();
            container.start();

            assertThat(container.isRunning()).isTrue();
            assertThat(registeredListener.get()).isNotNull();
            verify(connection, never()).close();

            String encoded = objectMapper.writeValueAsString(new AgentRunStreamWakeup(
                    AgentRunStreamWakeup.SCHEMA_VERSION,
                    "AGENT_RUN_STREAM_1",
                    "ATTEMPT_STREAM_1",
                    9));
            registeredListener.get().onMessage(
                    new DefaultMessage(expectedChannel, encoded.getBytes(StandardCharsets.UTF_8)), null);

            verify(eventService, timeout(1_000)).wakeUp("AGENT_RUN_STREAM_1");

            container.stop();

            assertThat(container.isRunning()).isFalse();
            verify(connection).close();
        } finally {
            container.destroy();
        }
    }
}
