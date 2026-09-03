package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.infrastructure.persistence.entity.ProcessReconciliationIssueEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessReconciliationIssueRepository
        extends JpaRepository<ProcessReconciliationIssueEntity, String> {

    Optional<ProcessReconciliationIssueEntity> findByTenantSurrogateAndIssueKey(
            String tenantSurrogate, String issueKey);

    @Query(
            value =
                    "select pg_advisory_xact_lock(hashtextextended(concat(:tenantSurrogate, ':', :issueKey), 0))",
            nativeQuery = true)
    void lockTenantIssueKey(
            @Param("tenantSurrogate") String tenantSurrogate,
            @Param("issueKey") String issueKey);
}
