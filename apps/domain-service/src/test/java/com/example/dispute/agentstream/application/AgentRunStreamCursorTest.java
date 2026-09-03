package com.example.dispute.agentstream.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunProtocol;
import org.junit.jupiter.api.Test;

class AgentRunStreamCursorTest {

    @Test
    void v4CursorRoundTripsWithItsOwnProtocolPrefix() {
        AgentRunStreamCursor cursor =
                new AgentRunStreamCursor(AgentRunProtocol.V4, "ATTEMPT_V4_1", 7);

        assertThat(cursor.wireValue()).isEqualTo("v4:ATTEMPT_V4_1:7");
        assertThat(AgentRunStreamCursor.parse(cursor.wireValue(), AgentRunProtocol.V4))
                .isEqualTo(cursor);
    }

    @Test
    void v4RejectsLegacyAttemptPrefixes() {
        assertThatThrownBy(
                        () -> AgentRunStreamCursor.parse(
                                "v3:ATTEMPT_V4_1:7", AgentRunProtocol.V4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bind an attemptId");
    }

    @Test
    void v4NonInitialSequenceRequiresAnAttempt() {
        assertThatThrownBy(() -> new AgentRunStreamCursor(AgentRunProtocol.V4, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires an attemptId");
    }
}
