package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "evaluation_trace")
public class EvaluationTraceEntity extends AbstractEntity {
    protected EvaluationTraceEntity() {}
}
