package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.PolicyRuleEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PolicyRuleRepository extends JpaRepository<PolicyRuleEntity, String> {

    @Query(
            """
            select policy from PolicyRuleEntity policy
            where policy.ruleStatus = 'ACTIVE'
              and policy.deletedAt is null
              and policy.effectiveFrom <= :at
              and (policy.effectiveTo is null or policy.effectiveTo > :at)
              and (:scope is null or policy.ruleScope = :scope)
            order by policy.priority desc, policy.ruleVersion desc
            """)
    List<PolicyRuleEntity> findActive(
            @Param("scope") String scope, @Param("at") OffsetDateTime at);
}
