package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.EvidenceItemEntity;
import com.example.dispute.evidence.domain.EvidenceSubmissionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceItemRepository extends JpaRepository<EvidenceItemEntity, String> {
    Optional<EvidenceItemEntity>
            findFirstByCaseIdAndFileHashAndSourceTypeAndDeletedAtIsNullOrderByCreatedAtDesc(
            String caseId, String fileHash, String sourceType);

    List<EvidenceItemEntity>
            findAllByCaseIdAndDeletedAtIsNullOrderByOccurredAtAscCreatedAtAsc(String caseId);

    long countByCaseIdAndSubmittedByRoleAndSubmissionStatusAndDeletedAtIsNull(
            String caseId, String submittedByRole, EvidenceSubmissionStatus submissionStatus);
}
