package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "remedy_plan")
public class RemedyPlanEntity extends AbstractEntity {
    protected RemedyPlanEntity() {}
}
