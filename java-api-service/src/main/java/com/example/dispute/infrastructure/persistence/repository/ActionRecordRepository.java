package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.ActionRecordEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActionRecordRepository extends JpaRepository<ActionRecordEntity, String> {

    Optional<ActionRecordEntity> findByIdempotencyKey(String idempotencyKey);

    List<ActionRecordEntity> findAllByCaseIdOrderByCreatedAtAsc(String caseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select action from ActionRecordEntity action "
                    + "where action.idempotencyKey = :idempotencyKey")
    Optional<ActionRecordEntity> findByIdempotencyKeyForUpdate(
            @Param("idempotencyKey") String idempotencyKey);
}
