package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.hearing.infrastructure.persistence.entity.HearingFlowInstanceEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HearingFlowInstanceRepository
        extends JpaRepository<HearingFlowInstanceEntity, String> {
    Optional<HearingFlowInstanceEntity> findByCaseId(String caseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select flow from HearingFlowInstanceEntity flow where flow.caseId = :caseId")
    Optional<HearingFlowInstanceEntity> findByCaseIdForUpdate(@Param("caseId") String caseId);
}
