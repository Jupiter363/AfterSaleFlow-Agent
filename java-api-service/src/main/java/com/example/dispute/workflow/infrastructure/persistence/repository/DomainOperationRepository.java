package com.example.dispute.workflow.infrastructure.persistence.repository;

import com.example.dispute.workflow.infrastructure.persistence.entity.DomainOperationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainOperationRepository extends JpaRepository<DomainOperationEntity, String> {

    Optional<DomainOperationEntity> findByTenantSurrogateAndOperationKey(
            String tenantSurrogate, String operationKey);
}
