package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HearingStateRepository extends JpaRepository<HearingStateEntity, String> {
    Optional<HearingStateEntity> findByCaseId(String caseId);
    Optional<HearingStateEntity> findByWorkflowId(String workflowId);
}
