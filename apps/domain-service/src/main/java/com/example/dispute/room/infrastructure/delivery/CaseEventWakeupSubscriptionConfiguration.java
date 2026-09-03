package com.example.dispute.room.infrastructure.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** Owns the long-lived cross-process case SSE wakeup subscription on API servlet nodes. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CaseEventWakeupSubscriptionConfiguration {

    @Bean(destroyMethod = "destroy")
    RedisMessageListenerContainer caseEventWakeupSubscription(
            RedisConnectionFactory connectionFactory, RedisCaseEventWakeupSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Redis is advisory. Keep reconnecting while PostgreSQL cursor replay remains authoritative.
        container.setRecoveryInterval(1_000L);
        container.addMessageListener(
                subscriber, new ChannelTopic(RedisCaseEventWakeupPublisher.CHANNEL));
        return container;
    }
}
