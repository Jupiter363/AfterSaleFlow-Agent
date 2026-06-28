package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.ApprovalRecordEntity;
import com.example.dispute.domain.model.ApprovalDecisionType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecordEntity, String> {
    Optional<ApprovalRecordEntity> findByApprovalHash(String approvalHash);
    List<ApprovalRecordEntity> findAllByCaseIdOrderByCreatedAtAsc(String caseId);
    Optional<ApprovalRecordEntity>
            findFirstByCaseIdAndDecisionTypeInOrderByCreatedAtDesc(
                    String caseId, Collection<ApprovalDecisionType> decisionTypes);
}
