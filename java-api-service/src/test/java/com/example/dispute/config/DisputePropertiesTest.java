package com.example.dispute.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DisputePropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsAllRoomTimingAndDemoSettings() {
        contextRunner
                .withPropertyValues(
                        "dispute.evidence-window=PT2H",
                        "dispute.hearing-window=PT3H",
                        "dispute.max-hearing-rounds=3",
                        "dispute.sse-heartbeat=PT15S",
                        "dispute.seed-demo-disputes=true")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            DisputeProperties properties =
                                    context.getBean(DisputeProperties.class);
                            assertThat(properties.evidenceWindow())
                                    .isEqualTo(Duration.ofHours(2));
                            assertThat(properties.hearingWindow())
                                    .isEqualTo(Duration.ofHours(3));
                            assertThat(properties.maxHearingRounds()).isEqualTo(3);
                            assertThat(properties.sseHeartbeat())
                                    .isEqualTo(Duration.ofSeconds(15));
                            assertThat(properties.seedDemoDisputes()).isTrue();
                        });
    }

    @Test
    void rejectsNonPositiveDurationsAndRoundsOutsideOneToFive() {
        contextRunner
                .withPropertyValues(
                        "dispute.evidence-window=PT0S",
                        "dispute.hearing-window=PT3H",
                        "dispute.max-hearing-rounds=6",
                        "dispute.sse-heartbeat=PT15S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DisputeProperties.class)
    static class PropertiesConfiguration {}
}
