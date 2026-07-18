package com.example.dispute.agentstream.persistence;

import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.MANIFEST_HASH;
import static com.example.dispute.agentstream.persistence.AgentRunPersistenceFixtures.RESULT_HASH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dispute.workflow.contract.v1.ContractTypes.RoomType;
import com.example.dispute.workflow.infrastructure.persistence.entity.AgentExecutionManifestEntity;
import com.example.dispute.workflow.infrastructure.persistence.entity.WorkflowPersistenceTypes.ManifestTerminalStatus;
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
}
