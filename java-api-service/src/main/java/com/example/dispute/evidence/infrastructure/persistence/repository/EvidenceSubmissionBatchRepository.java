package com.example.dispute.evidence.infrastructure.persistence.repository;

import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceSubmissionBatchEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceSubmissionBatchRepository
        extends JpaRepository<EvidenceSubmissionBatchEntity, String> {
    Optional<EvidenceSubmissionBatchEntity> findByCaseIdAndIdempotencyKey(
            String caseId, String idempotencyKey);
}
