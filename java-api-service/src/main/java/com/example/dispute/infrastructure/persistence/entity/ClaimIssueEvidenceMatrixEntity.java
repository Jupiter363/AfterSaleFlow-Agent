package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "claim_issue_evidence_matrix")
public class ClaimIssueEvidenceMatrixEntity extends AbstractEntity {
    protected ClaimIssueEvidenceMatrixEntity() {}
}
