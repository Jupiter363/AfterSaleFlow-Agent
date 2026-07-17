package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseCommandRepository extends JpaRepository<CaseCommandEntity, String> {

    Optional<CaseCommandEntity> findByTenantSurrogateAndCommandId(
            String tenantSurrogate, String commandId);

    Optional<CaseCommandEntity> findFirstByCaseIdOrderByCaseCommandSequenceDesc(String caseId);
}
