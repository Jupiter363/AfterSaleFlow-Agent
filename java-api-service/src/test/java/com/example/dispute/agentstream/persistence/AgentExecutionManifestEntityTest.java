package com.example.dispute.agentstream.persistence;

import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.MANIFEST_HASH;
import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.RESULT_HASH;
import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.RUN_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dispute.agentstream.application.AgentExecutionManifestStore.ManifestCommit;
import com.example.dispute.agentstream.infrastructure.persistence.JpaAgentExecutionManifestStore;
import com.example.dispute.infrastructure.persistence.entity.AgentRunAttemptEntity;
import com.example.dispute.infrastructure.persistence.entity.AgentRunEntity;
import com.example.dispute.infrastructure.persistence.repository.AgentRunAttemptRepository;
import com.example.dispute.infrastructure.persistence.repository.AgentRunRepository;
import com.example.dispute.workflow.contract.v1.AgentRunFinalizationReceipt.CommitStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.AgentRunAttemptStatus;
import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.persistence.entity.AgentExecutionManifestEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ManifestTerminalStatus;
import com.example.dispute.workflow.infrastructure.persistence.repository.AgentExecutionManifestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;

class AgentExecutionManifestEntityTest {

    @Test
    void mapsAFormalManifestWithoutCreatingMutableProvenance() {
        AgentExecutionManifestEntity entity =
                AgentExecutionManifestEntity.formal(
                        AgentRunPersistenceFixtures.manifest("ATTEMPT_V2_1"),
                        RoomType.EVIDENCE,
                        "s3://manifests/MANIFEST_V2_PERSISTENCE.json",
                        MANIFEST_HASH,
                        "[{\"artifact_id\":\"SNAP_INPUT\"}]");

        assertThat(AgentExecutionManifestEntity.class).hasAnnotation(Immutable.class);
        assertThat(entity.getId()).isEqualTo("MANIFEST_V2_PERSISTENCE");
        assertThat(entity.getTenantSurrogate()).isEqualTo("tenant-persistence");
        assertThat(entity.getCaseId()).isEqualTo("CASE_V2_PERSISTENCE");
        assertThat(entity.getLogicalAgentRunId()).isEqualTo("RUN_V2_PERSISTENCE");
        assertThat(entity.getAttemptId()).isEqualTo("ATTEMPT_V2_1");
        assertThat(entity.getManifestSha256()).isEqualTo(MANIFEST_HASH);
        assertThat(entity.getOutputSnapshotId()).isEqualTo("SNAP_OUTPUT");
        assertThat(entity.getOutputSha256()).isEqualTo(RESULT_HASH);
        assertThat(entity.getTerminalStatus()).isEqualTo(ManifestTerminalStatus.COMPLETED);
    }

    @Test
    void rejectsAnUnboundManifestHash() {
        assertThatThrownBy(
                        () ->
                                AgentExecutionManifestEntity.formal(
                                        AgentRunPersistenceFixtures.manifest("ATTEMPT_V2_1"),
                                        RoomType.EVIDENCE,
                                        "s3://manifests/MANIFEST_V2_PERSISTENCE.json",
                                        "not-a-hash",
                                        "[]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manifestHash");
    }

    @Test
    void storeCommitsOneHashBoundManifestAndReplaysTheSameReceipt() {
        AgentRunEntity run = AgentRunEntity.logicalV3(AgentRunPersistenceFixtures.logicalRunV3());
        run.markV3AttemptStarted();
        run.markV3AttemptFailed(
                AgentRunAttemptStatus.FAILED,
                true,
                AgentRunPersistenceFixtures.STARTED_AT.plusSeconds(1));
        run.markV3AttemptStarted();
        var allocation = AgentRunPersistenceFixtures.allocation(2, "ATTEMPT_V2_MANIFEST");
        AgentRunAttemptEntity attempt =
                AgentRunAttemptEntity.start(
                        RUN_ID,
                        allocation,
                        "ATTEMPT_V2_1",
                        false,
                        0,
                        AgentRunPersistenceFixtures.STARTED_AT);
        attempt.recordResultReady(
                AgentRunPersistenceFixtures.result(2, "ATTEMPT_V2_MANIFEST"),
                "{\"result_hash\":\"" + RESULT_HASH + "\"}");
        run.markV3ResultReady(
                "ATTEMPT_V2_MANIFEST",
                RESULT_HASH,
                AgentRunPersistenceFixtures.COMPLETED_AT);

        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentRunAttemptRepository attemptRepository = mock(AgentRunAttemptRepository.class);
        AgentExecutionManifestRepository manifestRepository =
                mock(AgentExecutionManifestRepository.class);
        JpaAgentExecutionManifestStore store =
                new JpaAgentExecutionManifestStore(
                        runRepository,
                        attemptRepository,
                        manifestRepository,
                        new ObjectMapper().findAndRegisterModules());
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));
        when(attemptRepository.findByIdForUpdate("ATTEMPT_V2_MANIFEST"))
                .thenReturn(Optional.of(attempt));
        when(attemptRepository.findById("ATTEMPT_V2_MANIFEST"))
                .thenReturn(Optional.of(attempt));
        when(manifestRepository.findByTenantSurrogateAndCaseIdAndLogicalAgentRunId(
                        "tenant-persistence",
                        AgentRunPersistenceFixtures.CASE_ID,
                        RUN_ID))
                .thenReturn(Optional.empty());
        AtomicReference<AgentExecutionManifestEntity> persisted = new AtomicReference<>();
        when(manifestRepository.saveAndFlush(any(AgentExecutionManifestEntity.class)))
                .thenAnswer(
                        invocation -> {
                            AgentExecutionManifestEntity entity = invocation.getArgument(0);
                            persisted.set(entity);
                            return entity;
                        });

        ManifestCommit invalidProvenance =
                new ManifestCommit(
                        AgentRunPersistenceFixtures.manifestV3(
                                "ATTEMPT_V2_MANIFEST",
                                allocation.binding().commandRequestHash(),
                                "e".repeat(64)),
                        RoomType.EVIDENCE,
                        "s3://manifests/MANIFEST_V2_PERSISTENCE.json",
                        MANIFEST_HASH,
                        RESULT_HASH,
                        3);
        assertThatThrownBy(() -> store.append(invalidProvenance))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("responseHash");
        verify(manifestRepository, never()).saveAndFlush(any());

        var manifest = AgentRunPersistenceFixtures.manifestV3(
                "ATTEMPT_V2_MANIFEST", allocation.binding().commandRequestHash(), RESULT_HASH);
        ManifestCommit commit =
                new ManifestCommit(
                        manifest,
                        RoomType.EVIDENCE,
                        "s3://manifests/MANIFEST_V2_PERSISTENCE.json",
                        MANIFEST_HASH,
                        RESULT_HASH,
                        3);
        var receipt = store.append(commit);
        assertThat(receipt.commitStatus()).isEqualTo(CommitStatus.COMMITTED);
        assertThat(run.getFinalizationStatus()).isEqualTo("COMMITTED");
        assertThat(attempt.getAttemptStatus().name()).isEqualTo("COMPLETED");

        when(manifestRepository.findByTenantSurrogateAndCaseIdAndLogicalAgentRunId(
                        "tenant-persistence",
                        AgentRunPersistenceFixtures.CASE_ID,
                        RUN_ID))
                .thenReturn(Optional.of(persisted.get()));
        assertThat(store.append(commit).commitStatus()).isEqualTo(CommitStatus.ALREADY_COMMITTED);
        assertThatThrownBy(
                        () ->
                                store.append(
                                        new ManifestCommit(
                                                commit.manifest(),
                                                commit.roomType(),
                                                commit.manifestUri(),
                                                commit.manifestHash(),
                                                commit.finalResultHash(),
                                                2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finalStreamSequenceNo");
        assertThatThrownBy(
                        () ->
                                store.append(
                                        new ManifestCommit(
                                                commit.manifest(),
                                                commit.roomType(),
                                                commit.manifestUri(),
                                                "d".repeat(64),
                                                commit.finalResultHash(),
                                                commit.finalStreamSequenceNo())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifestHash");
        verify(manifestRepository, times(1)).saveAndFlush(any());
    }
}
