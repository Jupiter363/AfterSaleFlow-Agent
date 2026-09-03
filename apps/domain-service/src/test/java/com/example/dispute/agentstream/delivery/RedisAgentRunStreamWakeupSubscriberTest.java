package com.example.dispute.agentstream.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentRunStreamEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class RedisAgentRunStreamWakeupSubscriberTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final AgentRunStreamEventService eventService =
            Mockito.mock(AgentRunStreamEventService.class);

    @Test
    void validHintTriggersDatabaseCatchUpWithoutCarryingEventPayload() throws Exception {
        RedisAgentRunStreamWakeupSubscriber subscriber =
                new RedisAgentRunStreamWakeupSubscriber(objectMapper, eventService);
        String encoded = objectMapper.writeValueAsString(new AgentRunStreamWakeup(
                AgentRunStreamWakeup.SCHEMA_VERSION,
                "AGENT_RUN_1",
                "ATTEMPT_1",
                7));

        subscriber.accept(encoded);

        verify(eventService).wakeUp("AGENT_RUN_1");
    }

    @Test
    void malformedHintIsIgnored() throws Exception {
        RedisAgentRunStreamWakeupSubscriber subscriber =
                new RedisAgentRunStreamWakeupSubscriber(objectMapper, eventService);

        subscriber.accept("{\"schema_version\":\"wrong\"}");

        verify(eventService, never()).wakeUp(Mockito.anyString());
    }

    @Test
    void lifecycleStartupDoesNotFailWhenRedisIsInitiallyUnavailable() {
        RedisConnectionFactory connectionFactory =
                Mockito.mock(RedisConnectionFactory.class);
        when(connectionFactory.getConnection())
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        RedisAgentRunStreamWakeupSubscriber subscriber =
                new RedisAgentRunStreamWakeupSubscriber(objectMapper, eventService);
        SmartLifecycle lifecycle = new AgentRunStreamWakeupSubscriptionConfiguration()
                .agentRunStreamWakeupSubscription(connectionFactory, subscriber);

        try {
            assertThatCode(lifecycle::start).doesNotThrowAnyException();
            assertThat(lifecycle.isRunning()).isTrue();
            verify(connectionFactory, timeout(1_000).atLeastOnce()).getConnection();
        } finally {
            lifecycle.stop();
        }
    }

    @Test
    void lifecycleStopClosesAConnectionThatArrivesDuringShutdown() throws Exception {
        RedisConnectionFactory connectionFactory =
                Mockito.mock(RedisConnectionFactory.class);
        RedisConnection connection = Mockito.mock(RedisConnection.class);
        CountDownLatch connectionRequested = new CountDownLatch(1);
        CountDownLatch releaseConnection = new CountDownLatch(1);
        when(connectionFactory.getConnection()).thenAnswer(ignored -> {
            connectionRequested.countDown();
            awaitUninterruptibly(releaseConnection);
            return connection;
        });
        RedisAgentRunStreamWakeupSubscriber subscriber =
                new RedisAgentRunStreamWakeupSubscriber(objectMapper, eventService);
        SmartLifecycle lifecycle = new AgentRunStreamWakeupSubscriptionConfiguration()
                .agentRunStreamWakeupSubscription(connectionFactory, subscriber);

        try {
            lifecycle.start();
            assertThat(connectionRequested.await(1, TimeUnit.SECONDS)).isTrue();

            lifecycle.stop();
            releaseConnection.countDown();

            verify(connection, timeout(1_000)).close();
            verify(connection, never())
                    .subscribe(Mockito.any(), Mockito.any(byte[][].class));
        } finally {
            releaseConnection.countDown();
            lifecycle.stop();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
