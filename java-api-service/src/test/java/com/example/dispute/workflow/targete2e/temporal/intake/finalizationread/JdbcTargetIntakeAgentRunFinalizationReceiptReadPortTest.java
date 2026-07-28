package com.example.dispute.workflow.targete2e.temporal.intake.finalizationread;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.temporal.room.intake.IntakeAgentRunFinalizationReceiptReadPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

class JdbcTargetIntakeAgentRunFinalizationReceiptReadPortTest {

    @Test
    void isAnExplicitFrameworkFreeReadPort() {
        assertThat(IntakeAgentRunFinalizationReceiptReadPort.class)
                .isAssignableFrom(JdbcTargetIntakeAgentRunFinalizationReceiptReadPort.class);
    }

    @Test
    void rejectsMissingPersistenceDependenciesAtAssembly() {
        assertThatThrownBy(() -> new JdbcTargetIntakeAgentRunFinalizationReceiptReadPort(
                (NamedParameterJdbcOperations) null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("jdbc");
    }
}
