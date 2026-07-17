package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.infrastructure.persistence.entity.CaseProcessProjectionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseProcessProjectionRepository
        extends JpaRepository<CaseProcessProjectionEntity, String> {

    Optional<CaseProcessProjectionEntity> findByTemporalWorkflowId(String temporalWorkflowId);
}
