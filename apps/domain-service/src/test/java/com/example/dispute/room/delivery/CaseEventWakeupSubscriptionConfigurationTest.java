package com.example.dispute.room.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.room.application.CaseEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class CaseEventWakeupSubscriptionConfigurationTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final CaseEventService eventService = mock(CaseEventService.class);

    @Test
    void configurationAndSubscriberAreRestrictedToServletApplications() {
        assertThat(
                        CaseEventWakeupSubscriptionConfiguration.class
                                .getAnnotation(ConditionalOnWebApplication.class)
                                .type())
                .isEqualTo(ConditionalOnWebApplication.Type.SERVLET);
        assertThat(
                        RedisCaseEventWakeupSubscriber.class
                                .getAnnotation(ConditionalOnWebApplication.class)
                                .type())
                .isEqualTo(ConditionalOnWebApplication.Type.SERVLET);
    }

    @Test
    void retainsSubscriptionForwardsHintsAndClosesOnStop() throws Exception {
        LettuceConnectionFactory connectionFactory = mock(LettuceConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        AtomicReference<MessageListener> registeredListener = new AtomicReference<>();
        byte[] expectedChannel =
                RedisCaseEventWakeupPublisher.CHANNEL.getBytes(StandardCharsets.UTF_8);
        when(connectionFactory.getConnection()).thenReturn(connection);
        doAnswer(
                        invocation -> {
                            MessageListener listener = invocation.getArgument(0);
                            byte[] channel = invocation.getArgument(1);
                            registeredListener.set(listener);
                            assertThat(channel).isEqualTo(expectedChannel);
                            ((SubscriptionListener) listener).onChannelSubscribed(channel, 1L);
                            return null;
                        })
                .when(connection)
                .subscribe(any(MessageListener.class), any(byte[][].class));
        RedisCaseEventWakeupSubscriber subscriber =
                new RedisCaseEventWakeupSubscriber(objectMapper, eventService);
        RedisMessageListenerContainer container =
                new CaseEventWakeupSubscriptionConfiguration()
                        .caseEventWakeupSubscription(connectionFactory, subscriber);

        try {
            container.afterPropertiesSet();
            container.start();

            assertThat(container.isRunning()).isTrue();
            assertThat(registeredListener.get()).isNotNull();
            verify(connection, never()).close();

            String encoded =
                    objectMapper.writeValueAsString(
                            new CaseEventWakeup(
                                    CaseEventWakeup.SCHEMA_VERSION, "CASE_1", 7));
            registeredListener
                    .get()
                    .onMessage(
                            new DefaultMessage(
                                    expectedChannel, encoded.getBytes(StandardCharsets.UTF_8)),
                            null);

            verify(eventService, timeout(1_000)).wakeUp("CASE_1");
            container.stop();

            assertThat(container.isRunning()).isFalse();
            verify(connection).close();
        } finally {
            container.destroy();
        }
    }
}
