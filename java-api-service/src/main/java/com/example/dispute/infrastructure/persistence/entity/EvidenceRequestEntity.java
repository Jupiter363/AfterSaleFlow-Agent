package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "evidence_request")
public class EvidenceRequestEntity extends AbstractEntity {
    protected EvidenceRequestEntity() {}
}
