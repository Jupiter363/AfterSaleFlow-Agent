package com.example.dispute.evidence.infrastructure.persistence.repository;

import com.example.dispute.evidence.infrastructure.persistence.entity.EvidencePartyCompletionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidencePartyCompletionRepository
        extends JpaRepository<EvidencePartyCompletionEntity, String> {
    Optional<EvidencePartyCompletionEntity> findByCaseIdAndIdempotencyKey(
            String caseId, String idempotencyKey);
    long countByCaseIdAndDossierVersionAndCompletionStatus(
            String caseId, int dossierVersion, String completionStatus);
}
