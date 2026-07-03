package com.example.dispute.evidence.infrastructure.persistence.repository;

import com.example.dispute.evidence.infrastructure.persistence.entity.EvidencePartyCompletionEntity;
import com.example.dispute.config.ActorRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidencePartyCompletionRepository
        extends JpaRepository<EvidencePartyCompletionEntity, String> {
    Optional<EvidencePartyCompletionEntity> findByCaseIdAndIdempotencyKey(
            String caseId, String idempotencyKey);
    Optional<EvidencePartyCompletionEntity>
            findByCaseIdAndDossierVersionAndParticipantRole(
                    String caseId, int dossierVersion, ActorRole participantRole);
    List<EvidencePartyCompletionEntity>
            findAllByCaseIdAndDossierVersionAndCompletionStatus(
                    String caseId, int dossierVersion, String completionStatus);
    Optional<EvidencePartyCompletionEntity>
            findTopByCaseIdOrderByDossierVersionDesc(String caseId);
    long countByCaseIdAndDossierVersionAndCompletionStatus(
            String caseId, int dossierVersion, String completionStatus);
}
