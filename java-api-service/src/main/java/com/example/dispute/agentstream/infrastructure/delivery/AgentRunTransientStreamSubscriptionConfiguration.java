package com.example.dispute.agentstream.infrastructure.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Owns the cross-process transient AgentRun subscription on servlet API nodes. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "app.agent-run-v2.enabled", havingValue = "true")
public class AgentRunTransientStreamSubscriptionConfiguration {

    @Bean(destroyMethod = "destroy")
    RedisMessageListenerContainer agentRunTransientStreamSubscription(
            RedisConnectionFactory connectionFactory,
            RedisAgentRunTransientStreamSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setRecoveryInterval(1_000L);
        container.addMessageListener(
                subscriber,
                new ChannelTopic(RedisAgentRunTransientStreamPublisher.CHANNEL));
        return container;
    }
}
