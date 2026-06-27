package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.ClaimIssueEvidenceMatrixEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimIssueEvidenceMatrixRepository
        extends JpaRepository<ClaimIssueEvidenceMatrixEntity, String> {}
