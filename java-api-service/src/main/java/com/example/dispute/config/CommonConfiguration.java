package com.example.dispute.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
