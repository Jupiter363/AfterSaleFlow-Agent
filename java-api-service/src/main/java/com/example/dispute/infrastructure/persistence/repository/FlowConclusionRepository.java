package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.FlowConclusionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowConclusionRepository
        extends JpaRepository<FlowConclusionEntity, String> {

    Optional<FlowConclusionEntity> findByCaseId(String caseId);
}
