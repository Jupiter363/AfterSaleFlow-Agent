package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.infrastructure.persistence.entity.DomainOperationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomainOperationRepository extends JpaRepository<DomainOperationEntity, String> {

    Optional<DomainOperationEntity> findByTenantSurrogateAndOperationKey(
            String tenantSurrogate, String operationKey);

    @Query(
            value =
                    "select pg_advisory_xact_lock(hashtextextended(concat(:tenantSurrogate, ':', :operationKey), 0))",
            nativeQuery = true)
    void lockTenantOperationKey(
            @Param("tenantSurrogate") String tenantSurrogate,
            @Param("operationKey") String operationKey);
}
