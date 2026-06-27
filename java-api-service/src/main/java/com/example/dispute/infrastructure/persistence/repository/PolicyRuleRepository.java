package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.PolicyRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRuleRepository extends JpaRepository<PolicyRuleEntity, String> {}
