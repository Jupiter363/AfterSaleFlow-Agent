package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.infrastructure.persistence.entity.CaseCommandEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseCommandRepository extends JpaRepository<CaseCommandEntity, String> {

    Optional<CaseCommandEntity> findByTenantSurrogateAndCommandId(
            String tenantSurrogate, String commandId);

    Optional<CaseCommandEntity> findFirstByCaseIdOrderByCaseCommandSequenceDesc(String caseId);

    @Query(
            value =
                    "select pg_advisory_xact_lock(hashtextextended(concat(:tenantSurrogate, ':', :commandId), 0))",
            nativeQuery = true)
    void lockTenantCommandId(
            @Param("tenantSurrogate") String tenantSurrogate,
            @Param("commandId") String commandId);
}
