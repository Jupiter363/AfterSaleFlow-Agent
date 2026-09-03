package com.example.dispute.workflow.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RecoveryPropertiesTest {

    @Test
    void domainEventRecoveryRejectsUnboundedScans() {
        assertThatThrownBy(
                        () ->
                                new CaseDomainEventRecoveryProperties(
                                        true,
                                        1_001,
                                        64,
                                        Duration.ofMinutes(5),
                                        Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflowBatchSize");
    }

    @Test
    void projectionReconciliationRejectsUnboundedScans() {
        assertThatThrownBy(
                        () ->
                                new ProcessProjectionReconciliationProperties(
                                        true,
                                        1_001,
                                        Duration.ofMinutes(5),
                                        Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }
}
