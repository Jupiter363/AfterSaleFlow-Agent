package com.example.dispute.agentstream.persistence;

import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.COMPLETED_AT;
import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.RESULT_HASH;
import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.RUN_ID;
import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.STARTED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.workflow.contract.v1.AgentExecutionManifest;
import com.example.dispute.workflow.contract.v1.AgentRunAttemptHeartbeat;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunRecoveryAction;
import com.example.dispute.workflow.contract.v1.ExecuteAgentRunResult;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRunAttemptEntityTest {

    @Test
    void initializesProgressAtTheJavaOwnedPreludeHighWatermark() {
        AgentRunAttemptEntity attempt =
                AgentRunAttemptEntity.start(
                        RUN_ID,
                        AgentRunPersistenceFixtures.allocation(2, "ATTEMPT_V2_RESET"),
                        "ATTEMPT_V2_1",
                        true,
                        1,
                        STARTED_AT);

        assertThat(attempt.getLastSequenceNo()).isEqualTo(1);
        assertThat(attempt.isPublicOutputEmitted()).isFalse();
        assertThat(attempt.isFinalFrameObserved()).isFalse();
    }

    @Test
    void retainsExecutionMetadataProgressUsageAndResultAcrossReplay() {
        AgentRunAttemptEntity attempt =
                AgentRunAttemptEntity.start(
                        RUN_ID,
                        AgentRunPersistenceFixtures.allocation(1, "ATTEMPT_V2_1"),
                        null,
                        false,
                        0,
                        STARTED_AT);

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
        assertThat(attempt.getLineageSchemaVersion())
                .isEqualTo("agent-run-attempt-lineage.v1");
        assertThat(attempt.getCommandId()).isEqualTo("command-persistence-1");
        assertThat(attempt.getCommandRequestHash()).matches("[0-9a-f]{64}");
        assertThat(attempt.getLogicalInputHash()).matches("[0-9a-f]{64}");
        assertThat(attempt.getCommandJson()).contains("command-persistence-1");
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
                        AgentRunPersistenceFixtures.allocation(1, "ATTEMPT_V2_FAILURE"),
                        null,
                        false,
                        0,
                        STARTED_AT);

        attempt.recordFailure(
                AgentRunAttemptStatus.FAILED,
                "PROVIDER_TIMEOUT",
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                COMPLETED_AT);
        attempt.recordFailure(
                AgentRunAttemptStatus.FAILED,
                "PROVIDER_TIMEOUT",
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                COMPLETED_AT.plusSeconds(5));

        assertThat(attempt.getAttemptStatus()).isEqualTo(AgentRunAttemptStatus.FAILED);
        assertThat(attempt.getErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(attempt.getErrorRetryable()).isTrue();
        assertThat(attempt.getTerminationCode()).isEqualTo("CREATE_NEXT_ATTEMPT");
        assertThatThrownBy(() -> attempt.recordFailure(
                        AgentRunAttemptStatus.FAILED,
                        "PROVIDER_TIMEOUT",
                        AgentRunRecoveryAction.RETRY_SAME_COMMAND,
                        COMPLETED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Activity-local");
        assertThatThrownBy(
                        () ->
                                attempt.recordFailure(
                                        AgentRunAttemptStatus.FAILED,
                                        "DIFFERENT_FAILURE",
                                        AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                                        COMPLETED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("errorCode");
    }

    @Test
    void recoveryTerminalErrorAdvancesOnlyTheExactPublicCursor() {
        AgentRunAttemptEntity attempt = AgentRunAttemptEntity.start(
                RUN_ID,
                AgentRunPersistenceFixtures.allocation(1, "ATTEMPT_V2_RECOVERY_ERROR"),
                null,
                false,
                0,
                STARTED_AT);
        attempt.recordHeartbeat(
                AgentRunPersistenceFixtures.heartbeat(
                        1, "ATTEMPT_V2_RECOVERY_ERROR", 2));
        attempt.recordFailure(
                AgentRunAttemptStatus.ABORTED,
                "PROVIDER_TIMEOUT",
                AgentRunRecoveryAction.CREATE_NEXT_ATTEMPT,
                COMPLETED_AT);

        attempt.advanceRecoveryTerminalErrorSequence(3L);

        assertThat(attempt.getLastSequenceNo()).isEqualTo(3L);
        assertThat(attempt.getAttemptStatus()).isEqualTo(AgentRunAttemptStatus.ABORTED);
        assertThat(attempt.getErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(attempt.getErrorRetryable()).isTrue();
        assertThat(attempt.getTerminationCode()).isEqualTo("CREATE_NEXT_ATTEMPT");
        assertThat(attempt.getCompletedAt().toInstant()).isEqualTo(COMPLETED_AT);
        assertThatThrownBy(() -> attempt.advanceRecoveryTerminalErrorSequence(5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact next public sequence");
    }

    @Test
    void failLogicalRunStoresOnlyTheExactNextTerminalResult() {
        String attemptId = "ATTEMPT_V2_ACTIVITY_TERMINAL";
        AgentRunAttemptEntity attempt = AgentRunAttemptEntity.start(
                RUN_ID,
                AgentRunPersistenceFixtures.allocation(1, attemptId),
                null,
                false,
                0,
                STARTED_AT);
        attempt.recordHeartbeat(AgentRunPersistenceFixtures.heartbeat(1, attemptId, 2));
        ExecuteAgentRunResult source = failureResult(
                attemptId, 2, AgentRunRecoveryAction.FAIL_LOGICAL_RUN);
        ExecuteAgentRunResult terminal = failureResult(
                attemptId, 3, AgentRunRecoveryAction.FAIL_LOGICAL_RUN);

        attempt.recordFailureResultWithTerminal(
                AgentRunAttemptStatus.ABORTED,
                source,
                terminal,
                "{\"last_sequence_no\":3}");

        assertThat(attempt.getAttemptStatus()).isEqualTo(AgentRunAttemptStatus.ABORTED);
        assertThat(attempt.getLastSequenceNo()).isEqualTo(3);
        assertThat(attempt.getErrorCode()).isEqualTo("GRAPH_GATEWAY_NOT_READY");
        assertThat(attempt.getErrorRetryable()).isFalse();
        assertThat(attempt.getTerminationCode()).isEqualTo("FAIL_LOGICAL_RUN");
        assertThat(attempt.getResultJson()).isEqualTo("{\"last_sequence_no\":3}");
        attempt.requireDurableFailureResult(terminal);
        assertThatThrownBy(() -> attempt.recordFailureResultWithTerminal(
                        AgentRunAttemptStatus.ABORTED,
                        source,
                        terminal,
                        "{\"last_sequence_no\":3}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
        assertThatThrownBy(() -> attempt.recordFailureResultWithTerminal(
                        AgentRunAttemptStatus.ABORTED,
                        source,
                        failureResult(attemptId, 4, AgentRunRecoveryAction.FAIL_LOGICAL_RUN),
                        "{\"last_sequence_no\":4}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mergesHeartbeatDimensionsMonotonicallyAcrossClockSkewAndReordering() {
        AgentRunAttemptEntity attempt =
                AgentRunAttemptEntity.start(
                        RUN_ID,
                        AgentRunPersistenceFixtures.allocation(1, "ATTEMPT_V2_HEARTBEAT"),
                        null,
                        false,
                        0,
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
                        AgentRunPersistenceFixtures.allocation(1, "ATTEMPT_V2_MANIFEST"),
                        null,
                        false,
                        0,
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
    void formalCommitRequiresSeparateGraphEnvelopeAndOutputSchemaContracts() {
        String attemptId = "ATTEMPT_V2_SCHEMA_CONTRACTS";
        AgentRunAttemptEntity attempt =
                AgentRunAttemptEntity.start(
                        RUN_ID,
                        AgentRunPersistenceFixtures.allocation(1, attemptId),
                        null,
                        false,
                        0,
                        STARTED_AT);
        attempt.recordResultReady(
                AgentRunPersistenceFixtures.result(1, attemptId),
                "{\"result_hash\":\"" + RESULT_HASH + "\"}");
        AgentExecutionManifest manifest = AgentRunPersistenceFixtures.manifest(attemptId);

        Map<String, String> missingOutputSchema = new HashMap<>(manifest.contractVersions());
        missingOutputSchema.remove("output_schema");
        assertThatThrownBy(
                        () ->
                                attempt.markCommitted(
                                        withContractVersions(manifest, missingOutputSchema), 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outputSchemaVersion");

        Map<String, String> wrongGraphEnvelope = new HashMap<>(manifest.contractVersions());
        wrongGraphEnvelope.put("graph_result", "intake-turn-proposal.v2");
        assertThatThrownBy(
                        () ->
                                attempt.markCommitted(
                                        withContractVersions(manifest, wrongGraphEnvelope), 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("graphResultSchemaVersion");
    }

    @Test
    void resultReadyRejectsResolvedExecutionMetadataOutsideTheAuthorizedRequest() {
        AgentRunAttemptEntity attempt =
                AgentRunAttemptEntity.start(
                        RUN_ID,
                        AgentRunPersistenceFixtures.allocation(1, "ATTEMPT_V2_METADATA"),
                        null,
                        false,
                        0,
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

    private static AgentExecutionManifest withContractVersions(
            AgentExecutionManifest source, Map<String, String> contractVersions) {
        return new AgentExecutionManifest(
                source.schemaVersion(),
                source.manifestId(),
                source.tenantSurrogate(),
                source.caseId(),
                source.roomEpoch(),
                source.processRevision(),
                source.fencingToken(),
                source.workflow(),
                source.agentRun(),
                source.graph(),
                source.model(),
                contractVersions,
                source.policyVersion(),
                source.guardrailVersion(),
                source.toolVersions(),
                source.inputs(),
                source.output(),
                source.usage(),
                source.traceparent(),
                source.finalizedAt());
    }

    private static ExecuteAgentRunResult failureResult(
            String attemptId,
            long lastSequenceNo,
            AgentRunRecoveryAction recoveryAction) {
        return new ExecuteAgentRunResult(
                ExecuteAgentRunResult.SCHEMA_VERSION,
                RUN_ID,
                RUN_ID,
                attemptId,
                1,
                ExecuteAgentRunResult.Outcome.FAILED,
                null,
                null,
                lastSequenceNo,
                true,
                "GRAPH_GATEWAY_NOT_READY",
                false,
                recoveryAction,
                COMPLETED_AT);
    }
}
