package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FulfillmentCaseRepository
        extends JpaRepository<FulfillmentCaseEntity, String>,
                JpaSpecificationExecutor<FulfillmentCaseEntity> {
    Optional<FulfillmentCaseEntity> findByCreationIdempotencyKey(String idempotencyKey);

    Optional<FulfillmentCaseEntity> findBySourceSystemAndExternalCaseRef(
            String sourceSystem, String externalCaseRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select disputeCase from FulfillmentCaseEntity disputeCase where disputeCase.id = :id")
    Optional<FulfillmentCaseEntity> findByIdForUpdate(@Param("id") String id);
}
