package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.HearingRecordEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HearingRecordRepository extends JpaRepository<HearingRecordEntity, String> {
    boolean existsByWorkflowIdAndNodeNameAndRoundNoAndRecordType(
            String workflowId, String nodeName, int roundNo, String recordType);
    Optional<HearingRecordEntity> findTopByCaseIdAndNodeNameAndRoundNoAndRecordTypeOrderByCreatedAtDesc(
            String caseId, String nodeName, int roundNo, String recordType);
    List<HearingRecordEntity> findAllByCaseIdOrderByCreatedAtAsc(String caseId);
}
