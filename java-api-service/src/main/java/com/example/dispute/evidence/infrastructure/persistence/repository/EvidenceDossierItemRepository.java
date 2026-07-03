package com.example.dispute.evidence.infrastructure.persistence.repository;

import com.example.dispute.evidence.infrastructure.persistence.entity.EvidenceDossierItemEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceDossierItemRepository
        extends JpaRepository<EvidenceDossierItemEntity, String> {
    List<EvidenceDossierItemEntity> findAllByDossierIdOrderBySequenceNo(String dossierId);
}
