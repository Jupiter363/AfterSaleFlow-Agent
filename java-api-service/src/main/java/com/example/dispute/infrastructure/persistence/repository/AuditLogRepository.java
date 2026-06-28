package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.AuditLogEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, String> {
    long countByCaseId(String caseId);

    List<AuditLogEntity> findAllByCaseIdOrderByCreatedAtDesc(String caseId);
}
