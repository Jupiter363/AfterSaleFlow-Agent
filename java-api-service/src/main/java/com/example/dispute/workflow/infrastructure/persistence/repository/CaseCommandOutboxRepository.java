package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandOutboxEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseCommandOutboxRepository
        extends JpaRepository<CaseCommandOutboxEntity, String> {

    Optional<CaseCommandOutboxEntity> findByCaseCommandId(String caseCommandId);
}
