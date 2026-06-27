package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "action_record")
public class ActionRecordEntity extends AbstractEntity {
    protected ActionRecordEntity() {}
}
