package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.RemedyPlanEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RemedyPlanRepository extends JpaRepository<RemedyPlanEntity, String> {
    Optional<RemedyPlanEntity> findFirstByCaseIdOrderByPlanVersionDesc(String caseId);
    Optional<RemedyPlanEntity> findByCaseIdAndPlanVersion(String caseId, int planVersion);
}
