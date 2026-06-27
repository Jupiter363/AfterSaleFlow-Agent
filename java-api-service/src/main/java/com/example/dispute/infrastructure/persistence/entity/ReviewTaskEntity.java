package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "review_task")
public class ReviewTaskEntity extends AbstractEntity {
    protected ReviewTaskEntity() {}
}
