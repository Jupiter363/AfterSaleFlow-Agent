package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.hearing.domain.HearingArtifactType;
import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowArtifactEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HearingFlowArtifactRepository
        extends JpaRepository<HearingFlowArtifactEntity, String> {
    Optional<HearingFlowArtifactEntity> findByCaseIdAndArtifactType(
            String caseId, HearingArtifactType artifactType);

    Optional<HearingFlowArtifactEntity> findByFlowInstanceIdAndArtifactType(
            String flowInstanceId, HearingArtifactType artifactType);

    List<HearingFlowArtifactEntity> findTop50ByArtifactTypeOrderByCreatedAtDesc(
            HearingArtifactType artifactType);
}
