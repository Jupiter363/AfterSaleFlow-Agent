package com.example.dispute.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
public class CommonConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(
            name = "dispute.scheduling.enabled",
            havingValue = "true",
            matchIfMissing = true)
    static class SchedulingConfiguration {}
}
