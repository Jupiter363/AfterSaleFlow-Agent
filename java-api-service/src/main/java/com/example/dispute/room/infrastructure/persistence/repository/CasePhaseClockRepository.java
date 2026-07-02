package com.example.dispute.room.infrastructure.persistence.repository;

import com.example.dispute.room.domain.PhaseClockType;
import com.example.dispute.room.infrastructure.persistence.entity.CasePhaseClockEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CasePhaseClockRepository
        extends JpaRepository<CasePhaseClockEntity, String> {

    Optional<CasePhaseClockEntity> findByCaseIdAndClockType(
            String caseId, PhaseClockType clockType);
}
