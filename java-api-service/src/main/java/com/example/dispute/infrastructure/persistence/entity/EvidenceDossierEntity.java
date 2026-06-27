package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "evidence_dossier")
public class EvidenceDossierEntity extends AbstractEntity {
    protected EvidenceDossierEntity() {}
}
