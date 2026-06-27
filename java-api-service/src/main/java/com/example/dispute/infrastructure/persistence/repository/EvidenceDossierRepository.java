package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.EvidenceDossierEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceDossierRepository extends JpaRepository<EvidenceDossierEntity, String> {
    Optional<EvidenceDossierEntity> findByCaseId(String caseId);
}
