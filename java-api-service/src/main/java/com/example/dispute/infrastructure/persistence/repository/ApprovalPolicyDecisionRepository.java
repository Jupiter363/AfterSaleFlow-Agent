package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.ApprovalPolicyDecisionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalPolicyDecisionRepository
        extends JpaRepository<ApprovalPolicyDecisionEntity, String> {

    Optional<ApprovalPolicyDecisionEntity>
            findFirstByCaseIdAndPlanIdOrderByCreatedAtDesc(
                    String caseId, String planId);
}
