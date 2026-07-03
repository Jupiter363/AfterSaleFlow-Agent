package com.example.dispute.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class CommonConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
