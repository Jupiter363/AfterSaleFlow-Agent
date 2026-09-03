package com.example.dispute.workflow.bootstrap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.config.RoomEpochBootstrapProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RoomEpochBootstrapPropertiesTest {

    @Test
    void completionTimeoutMustFitInsideTheLease() {
        assertThatThrownBy(
                        () ->
                                new RoomEpochBootstrapProperties(
                                        true,
                                        32,
                                        16,
                                        Duration.ofSeconds(30),
                                        Duration.ofSeconds(30),
                                        Duration.ofSeconds(1),
                                        Duration.ofMinutes(5),
                                        Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorter than leaseDuration");
    }

    @Test
    void concurrencyCannotExceedTheClaimBatch() {
        assertThatThrownBy(
                        () ->
                                new RoomEpochBootstrapProperties(
                                        true,
                                        2,
                                        3,
                                        Duration.ofMinutes(2),
                                        Duration.ofSeconds(90),
                                        Duration.ofSeconds(1),
                                        Duration.ofMinutes(5),
                                        Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("concurrency");
    }
}
