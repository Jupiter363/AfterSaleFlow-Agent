package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.config.ActorRole;
import com.example.dispute.hearing.infrastructure.persistence.entity.SettlementConfirmationEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementConfirmationRepository
        extends JpaRepository<SettlementConfirmationEntity, String> {
    Optional<SettlementConfirmationEntity> findByCaseIdAndIdempotencyKey(
            String caseId, String idempotencyKey);
    Optional<SettlementConfirmationEntity> findByProposalIdAndParticipantRole(
            String proposalId, ActorRole participantRole);
    List<SettlementConfirmationEntity>
            findAllByProposalIdAndConfirmationStatus(
                    String proposalId, String confirmationStatus);
    long countByProposalIdAndConfirmationStatus(
            String proposalId, String confirmationStatus);
}
