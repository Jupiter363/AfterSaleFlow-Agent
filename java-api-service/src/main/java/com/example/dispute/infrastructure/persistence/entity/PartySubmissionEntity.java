package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "party_submission")
public class PartySubmissionEntity extends AbstractEntity {
    protected PartySubmissionEntity() {}
}
