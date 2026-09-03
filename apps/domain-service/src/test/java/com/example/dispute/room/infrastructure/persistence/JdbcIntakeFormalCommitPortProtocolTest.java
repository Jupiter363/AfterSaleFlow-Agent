package com.example.dispute.room.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures;
import org.junit.jupiter.api.Test;

class JdbcIntakeFormalCommitPortProtocolTest {

    @Test
    void selectsRunProtocolFromTheExactCommandExecutionProfile() {
        assertThat(JdbcIntakeFormalCommitPort.requiredRunProtocol(
                        AgentRunPersistenceFixtures.parallelIntakeRequest().command()))
                .isEqualTo("agent-stream.v4");
        assertThat(JdbcIntakeFormalCommitPort.requiredRunProtocol(
                        AgentRunPersistenceFixtures.requestV3(1, "ATTEMPT_V3_PROTOCOL").command()))
                .isEqualTo("agent-stream.v3");
    }
}
