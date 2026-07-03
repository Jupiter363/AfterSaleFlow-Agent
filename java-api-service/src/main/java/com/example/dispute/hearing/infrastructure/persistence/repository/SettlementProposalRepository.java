package com.example.dispute.hearing.infrastructure.persistence.repository;

import com.example.dispute.hearing.infrastructure.persistence.entity.SettlementProposalEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementProposalRepository
        extends JpaRepository<SettlementProposalEntity, String> {
    Optional<SettlementProposalEntity> findTopByCaseIdOrderByProposalVersionDesc(
            String caseId);
    Optional<SettlementProposalEntity> findByCaseIdAndProposalVersion(
            String caseId, int proposalVersion);
    List<SettlementProposalEntity> findAllByCaseIdOrderByProposalVersionDesc(
            String caseId);
}
