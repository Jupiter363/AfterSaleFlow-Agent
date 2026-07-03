package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.hearing.infrastructure.persistence.entity.HearingRoundEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HearingRoundRepository extends JpaRepository<HearingRoundEntity, String> {
    Optional<HearingRoundEntity> findTopByCaseIdOrderByRoundNoDesc(String caseId);
    Optional<HearingRoundEntity> findByCaseIdAndRoundNo(String caseId, int roundNo);
    List<HearingRoundEntity> findAllByCaseIdOrderByRoundNoAsc(String caseId);
}
