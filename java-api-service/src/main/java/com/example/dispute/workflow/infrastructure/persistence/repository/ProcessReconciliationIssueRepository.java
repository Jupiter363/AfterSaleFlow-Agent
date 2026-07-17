package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.infrastructure.persistence.entity.ProcessReconciliationIssueEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessReconciliationIssueRepository
        extends JpaRepository<ProcessReconciliationIssueEntity, String> {

    Optional<ProcessReconciliationIssueEntity> findByTenantSurrogateAndIssueKey(
            String tenantSurrogate, String issueKey);
}
