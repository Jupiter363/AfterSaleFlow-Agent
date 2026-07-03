package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceDossierRepository extends JpaRepository<EvidenceDossierEntity, String> {
    Optional<EvidenceDossierEntity> findTopByCaseIdOrderByDossierVersionDesc(String caseId);

    Optional<EvidenceDossierEntity> findByCaseIdAndDossierVersion(
            String caseId, int dossierVersion);

    default Optional<EvidenceDossierEntity> findByCaseId(String caseId) {
        return findTopByCaseIdOrderByDossierVersionDesc(caseId);
    }
}
