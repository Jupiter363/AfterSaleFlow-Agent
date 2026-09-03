package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.hearing.infrastructure.persistence.entity.HearingTrialDossierEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HearingTrialDossierRepository
        extends JpaRepository<HearingTrialDossierEntity, String> {
    Optional<HearingTrialDossierEntity> findByCaseId(String caseId);

    Optional<HearingTrialDossierEntity> findByFlowInstanceId(String flowInstanceId);
}
