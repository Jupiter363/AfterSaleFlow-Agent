package com.example.dispute.evidence.infrastructure.persistence.repository;

import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceVerificationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceVerificationRepository
        extends JpaRepository<EvidenceVerificationEntity, String> {
    Optional<EvidenceVerificationEntity> findTopByEvidenceIdOrderByVerificationVersionDesc(
            String evidenceId);
}
