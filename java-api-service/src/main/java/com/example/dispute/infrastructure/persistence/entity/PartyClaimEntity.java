package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "party_claim")
public class PartyClaimEntity extends AbstractEntity {
    protected PartyClaimEntity() {}
}
