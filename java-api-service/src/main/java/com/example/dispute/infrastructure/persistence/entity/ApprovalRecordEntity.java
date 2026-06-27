package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "approval_record")
public class ApprovalRecordEntity extends AbstractEntity {
    protected ApprovalRecordEntity() {}
}
