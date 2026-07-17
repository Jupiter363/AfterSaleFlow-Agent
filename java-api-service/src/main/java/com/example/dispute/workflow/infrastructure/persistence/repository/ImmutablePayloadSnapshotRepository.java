package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.infrastructure.persistence.entity.ImmutablePayloadSnapshotEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImmutablePayloadSnapshotRepository
        extends JpaRepository<ImmutablePayloadSnapshotEntity, String> {

    Optional<ImmutablePayloadSnapshotEntity> findByTenantSurrogateAndCaseIdAndContentSha256(
            String tenantSurrogate, String caseId, String contentSha256);

    Optional<ImmutablePayloadSnapshotEntity> findByTenantSurrogateAndSourceTypeAndSourceId(
            String tenantSurrogate, String sourceType, String sourceId);
}
