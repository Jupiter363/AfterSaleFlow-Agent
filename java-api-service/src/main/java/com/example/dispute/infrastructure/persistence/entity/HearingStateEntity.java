package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "hearing_state")
public class HearingStateEntity extends AbstractEntity {
    protected HearingStateEntity() {}
}
