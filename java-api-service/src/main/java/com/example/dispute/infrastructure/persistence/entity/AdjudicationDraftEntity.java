package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "adjudication_draft")
public class AdjudicationDraftEntity extends AbstractEntity {
    protected AdjudicationDraftEntity() {}
}
