package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.DeliberationReportEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliberationReportRepository
        extends JpaRepository<DeliberationReportEntity, String> {
    Optional<DeliberationReportEntity> findFirstByCaseIdOrderByReportVersionDesc(
            String caseId);
}
