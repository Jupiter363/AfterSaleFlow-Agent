package com.example.dispute.agentstream.infrastructure.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.agent-run-v2.enabled", havingValue = "true")
public class AgentRunStreamWakeupSubscriptionConfiguration {

    /**
     * Owns one long-lived Redis Pub/Sub connection for this API node.
     *
     * <p>The previous direct {@code RedisConnection.subscribe(...)} invocation is non-blocking for
     * Lettuce. Its worker immediately reached {@code finally} and closed the connection, silently
     * degrading live SSE delivery to the PostgreSQL heartbeat fallback. Spring's listener container
     * retains the subscription connection, reconnects it after a Redis connection failure, and
     * closes it during application shutdown.
     */
    @Bean(destroyMethod = "destroy")
    RedisMessageListenerContainer agentRunStreamWakeupSubscription(
            RedisConnectionFactory connectionFactory,
            RedisAgentRunStreamWakeupSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Keep Redis advisory. PostgreSQL cursor replay remains the authoritative fallback while
        // the container performs periodic reconnects in the background.
        container.setRecoveryInterval(1_000L);
        container.addMessageListener(
                subscriber, new ChannelTopic(RedisAgentRunStreamWakeupPublisher.CHANNEL));
        return container;
    }
}
