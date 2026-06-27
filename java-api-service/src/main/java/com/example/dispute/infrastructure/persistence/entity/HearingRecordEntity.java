package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "hearing_record")
public class HearingRecordEntity extends AbstractEntity {
    protected HearingRecordEntity() {}
}
