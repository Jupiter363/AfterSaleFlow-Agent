package com.example.dispute.agentstream.persistence;

import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.COMPLETED_AT;
import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.RESULT_HASH;
import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.RUN_ID;
import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.STARTED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
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

    @Test
    void mergesHeartbeatDimensionsMonotonicallyAcrossClockSkewAndReordering() {
        AgentRunAttemptEntity attempt =
                AgentRunAttemptEntity.start(
                        RUN_ID,
                        AgentRunPersistenceFixtures.request(1, "ATTEMPT_V2_HEARTBEAT"),
                        STARTED_AT);

        attempt.recordHeartbeat(
                new AgentRunAttemptHeartbeat(
                        AgentRunAttemptHeartbeat.SCHEMA_VERSION,
                        RUN_ID,
                        "ATTEMPT_V2_HEARTBEAT",
                        1,
                        5,
                        false,
                        false,
                        STARTED_AT.plusSeconds(5)));
        attempt.recordHeartbeat(
                new AgentRunAttemptHeartbeat(
                        AgentRunAttemptHeartbeat.SCHEMA_VERSION,
                        RUN_ID,
                        "ATTEMPT_V2_HEARTBEAT",
                        1,
                        6,
                        true,
                        false,
                        STARTED_AT.plusSeconds(4)));
        attempt.recordHeartbeat(
                new AgentRunAttemptHeartbeat(
                        AgentRunAttemptHeartbeat.SCHEMA_VERSION,
                        RUN_ID,
                        "ATTEMPT_V2_HEARTBEAT",
                        1,
                        4,
                        false,
                        true,
                        STARTED_AT.plusSeconds(6)));

        assertThat(attempt.getLastSequenceNo()).isEqualTo(6);
        assertThat(attempt.isPublicOutputEmitted()).isTrue();
        assertThat(attempt.isFinalFrameObserved()).isTrue();
        assertThat(attempt.getLastHeartbeatAt().toInstant()).isEqualTo(STARTED_AT.plusSeconds(6));
    }

    @Test
    void formalCommitRejectsManifestProvenanceAndSequenceDrift() {
        AgentRunAttemptEntity attempt =
                AgentRunAttemptEntity.start(
                        RUN_ID,
                        AgentRunPersistenceFixtures.request(1, "ATTEMPT_V2_MANIFEST"),
                        STARTED_AT);
        attempt.recordResultReady(
                AgentRunPersistenceFixtures.result(1, "ATTEMPT_V2_MANIFEST"),
                "{\"result_hash\":\"" + RESULT_HASH + "\"}");

        assertThatThrownBy(
                        () ->
                                attempt.markCommitted(
                                        AgentRunPersistenceFixtures.manifestWithModelHashes(
                                                "ATTEMPT_V2_MANIFEST",
                                                "f".repeat(64),
                                                RESULT_HASH),
                                        3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requestHash");
        assertThatThrownBy(
                        () ->
                                attempt.markCommitted(
                                        AgentRunPersistenceFixtures.manifest(
                                                "ATTEMPT_V2_MANIFEST"),
                                        2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finalStreamSequenceNo");

        attempt.markCommitted(
                AgentRunPersistenceFixtures.manifest("ATTEMPT_V2_MANIFEST"), 3);
        attempt.markCommitted(
                AgentRunPersistenceFixtures.manifest("ATTEMPT_V2_MANIFEST"), 3);
        assertThat(attempt.getAttemptStatus()).isEqualTo(AgentRunAttemptStatus.COMPLETED);
        assertThat(attempt.getProvider()).isEqualTo("provider-v2");
        assertThat(attempt.getModelVersion()).isEqualTo("model-v2");
    }

    @Test
    void resultReadyRejectsResolvedExecutionMetadataOutsideTheAuthorizedRequest() {
        AgentRunAttemptEntity attempt =
                AgentRunAttemptEntity.start(
                        RUN_ID,
                        AgentRunPersistenceFixtures.request(1, "ATTEMPT_V2_METADATA"),
                        STARTED_AT);

        assertThatThrownBy(
                        () ->
                                attempt.recordResultReady(
                                        AgentRunPersistenceFixtures.resultWithExecutionMetadata(
                                                1,
                                                "ATTEMPT_V2_METADATA",
                                                "unapproved-model-profile",
                                                "room-graph-result.v1",
                                                "policy-v2",
                                                "guardrail-v2"),
                                        "{\"result_hash\":\"" + RESULT_HASH + "\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("modelProfileId");
        assertThat(attempt.getAttemptStatus()).isEqualTo(AgentRunAttemptStatus.RUNNING);
        assertThat(attempt.getResultHash()).isNull();
    }
}
