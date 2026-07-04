package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.HearingStateEntity;
import com.example.dispute.domain.model.HearingStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HearingStateRepository extends JpaRepository<HearingStateEntity, String> {
    Optional<HearingStateEntity> findByCaseId(String caseId);
    Optional<HearingStateEntity> findByWorkflowId(String workflowId);
    List<HearingStateEntity> findAllByHearingStatusOrderByCompletedAtAsc(
            HearingStatus hearingStatus);
}
