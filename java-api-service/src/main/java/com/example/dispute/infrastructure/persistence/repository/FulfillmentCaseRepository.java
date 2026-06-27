package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.FulfillmentCaseEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FulfillmentCaseRepository extends JpaRepository<FulfillmentCaseEntity, String> {
    Optional<FulfillmentCaseEntity> findByCreationIdempotencyKey(String idempotencyKey);
}
