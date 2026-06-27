package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "issue")
public class IssueEntity extends AbstractEntity {
    protected IssueEntity() {}
}
