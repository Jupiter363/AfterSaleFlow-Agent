package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "evidence_item")
public class EvidenceItemEntity extends AbstractEntity {
    protected EvidenceItemEntity() {}
}
