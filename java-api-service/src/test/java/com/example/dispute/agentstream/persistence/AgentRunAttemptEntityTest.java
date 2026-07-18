package com.example.dispute.agentstream.persistence;

import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.COMPLETED_AT;
import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.RESULT_HASH;
import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.RUN_ID;
import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.STARTED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import org.junit.jupiter.api.Test;

class AgentRunAttemptEntityTest {

    @Test
    void retainsExecutionMetadataProgressUsageAndResultAcrossReplay() {
        var request = AgentRunPersistenceFixtures.request(1, "ATTEMPT_V2_1");
        AgentRunAttemptEntity attempt =
                AgentRunAttemptEntity.start(RUN_ID, request, STARTED_AT);

        attempt.recordHeartbeat(
                AgentRunPersistenceFixtures.heartbeat(1, "ATTEMPT_V2_1", 2));
        attempt.recordResultReady(
                AgentRunPersistenceFixtures.result(1, "ATTEMPT_V2_1"),
                "{\"result_hash\":\"" + RESULT_HASH + "\"}");
        attempt.recordResultReady(
                AgentRunPersistenceFixtures.result(1, "ATTEMPT_V2_1"),
                "{\"result_hash\":\"" + RESULT_HASH + "\"}");

        assertThat(attempt.getAttemptStatus()).isEqualTo(AgentRunAttemptStatus.RESULT_READY);
        assertThat(attempt.getExecutorKind().name()).isEqualTo("TEMPORAL_ACTIVITY");
        assertThat(attempt.getGraphKey()).isEqualTo("evidence.graph");
        assertThat(attempt.getGraphVersion()).isEqualTo("graph-v2");
        assertThat(attempt.getCheckpointSchemaVersion()).isEqualTo("checkpoint-v2");
        assertThat(attempt.getCheckpointId()).isEqualTo("checkpoint-42");
        assertThat(attempt.getModelProfileId()).isEqualTo("model-profile-v2");
        assertThat(attempt.getPromptVersion()).isEqualTo("prompt-v2");
        assertThat(attempt.getPolicyVersion()).isEqualTo("policy-v2");
        assertThat(attempt.getGuardrailVersion()).isEqualTo("guardrail-v2");
        assertThat(attempt.getInputTokens()).isEqualTo(100);
        assertThat(attempt.getOutputTokens()).isEqualTo(20);
        assertThat(attempt.getTotalTokens()).isEqualTo(120);
        assertThat(attempt.getLatencyMs()).isEqualTo(3000);
        assertThat(attempt.getResultHash()).isEqualTo(RESULT_HASH);
        assertThat(attempt.getLastSequenceNo()).isEqualTo(3);
        assertThat(attempt.isPublicOutputEmitted()).isTrue();
        assertThat(attempt.isFinalFrameObserved()).isTrue();
        assertThat(attempt.getCompletedAt().toInstant()).isEqualTo(COMPLETED_AT);
    }

    @Test
    void terminalFailureReplayIsIdempotentAndConflictsFailClosed() {
        AgentRunAttemptEntity attempt =
                AgentRunAttemptEntity.start(
                        RUN_ID,
                        AgentRunPersistenceFixtures.request(1, "ATTEMPT_V2_FAILURE"),
                        STARTED_AT);

        attempt.recordFailure(
                AgentRunAttemptStatus.FAILED,
                "PROVIDER_TIMEOUT",
                true,
                COMPLETED_AT);
        attempt.recordFailure(
                AgentRunAttemptStatus.FAILED,
                "PROVIDER_TIMEOUT",
                true,
                COMPLETED_AT.plusSeconds(5));

        assertThat(attempt.getAttemptStatus()).isEqualTo(AgentRunAttemptStatus.FAILED);
        assertThat(attempt.getErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(attempt.getErrorRetryable()).isTrue();
        assertThatThrownBy(
                        () ->
                                attempt.recordFailure(
                                        AgentRunAttemptStatus.FAILED,
                                        "DIFFERENT_FAILURE",
                                        true,
                                        COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("errorCode");
    }
}
