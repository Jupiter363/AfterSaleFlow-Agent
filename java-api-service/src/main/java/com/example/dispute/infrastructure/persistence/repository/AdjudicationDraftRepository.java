package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.AdjudicationDraftEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdjudicationDraftRepository
        extends JpaRepository<AdjudicationDraftEntity, String> {
    Optional<AdjudicationDraftEntity> findByCaseIdAndDraftVersion(
            String caseId, int draftVersion);
    Optional<AdjudicationDraftEntity> findFirstByCaseIdOrderByDraftVersionDesc(
            String caseId);
}
