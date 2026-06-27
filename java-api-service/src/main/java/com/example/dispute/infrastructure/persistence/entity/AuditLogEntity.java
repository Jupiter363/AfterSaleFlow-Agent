package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity extends AbstractEntity {
    protected AuditLogEntity() {}
}
