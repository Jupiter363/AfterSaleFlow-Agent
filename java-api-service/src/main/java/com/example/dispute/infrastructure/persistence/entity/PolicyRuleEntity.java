package com.example.dispute.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "policy_rule")
public class PolicyRuleEntity extends AbstractEntity {
    protected PolicyRuleEntity() {}
}
