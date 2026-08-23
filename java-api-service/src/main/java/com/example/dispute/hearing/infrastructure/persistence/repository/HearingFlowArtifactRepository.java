package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowArtifactEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HearingFlowArtifactRepository
        extends JpaRepository<HearingFlowArtifactEntity, String> {
    Optional<HearingFlowArtifactEntity> findByCaseIdAndArtifactType(
            String caseId, HearingArtifactType artifactType);

    Optional<HearingFlowArtifactEntity> findByFlowInstanceIdAndArtifactType(
            String flowInstanceId, HearingArtifactType artifactType);

    List<HearingFlowArtifactEntity> findTop50ByArtifactTypeOrderByCreatedAtDesc(
            HearingArtifactType artifactType);

    /**
     * Legacy recovery must never compete with the Temporal Hearing handoff writer.  The projection
     * and epoch joins are the persisted ownership authority; artifact shape alone is not.
     */
    @Query(
            value =
                    """
                    select artifact.*
                      from hearing_flow_artifact artifact
                      join hearing_temporal_projection projection
                        on projection.flow_instance_id = artifact.flow_instance_id
                       and projection.case_id = artifact.case_id
                       and projection.writer_mode = 'LEGACY'
                      join case_room_epoch epoch
                        on epoch.id = projection.epoch_id
                       and epoch.case_id = projection.case_id
                       and epoch.tenant_surrogate = projection.tenant_surrogate
                       and epoch.room_type = 'HEARING'
                       and epoch.room_epoch = projection.hearing_epoch
                       and epoch.fencing_token = projection.fencing_token
                       and epoch.writer_mode = 'LEGACY'
                     where artifact.artifact_type = 'ADJUDICATION_DRAFT'
                     order by artifact.created_at desc
                     limit 50
                    """,
            nativeQuery = true)
    List<HearingFlowArtifactEntity> findTop50LegacyAdjudicationDrafts();
}
