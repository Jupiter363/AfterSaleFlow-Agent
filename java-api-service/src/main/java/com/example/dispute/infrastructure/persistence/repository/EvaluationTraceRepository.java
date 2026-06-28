package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.EvaluationTraceEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvaluationTraceRepository
        extends JpaRepository<EvaluationTraceEntity, String> {

    Optional<EvaluationTraceEntity>
            findFirstByCaseIdOrderByEvaluationVersionDesc(String caseId);

    List<EvaluationTraceEntity> findAllByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select trace from EvaluationTraceEntity trace "
                    + "where trace.id = :traceId")
    Optional<EvaluationTraceEntity> findByIdForUpdate(
            @Param("traceId") String traceId);
}
