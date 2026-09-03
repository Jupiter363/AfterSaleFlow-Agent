/*
 * 所属模块：PostgreSQL 事实模型。
 * 文件职责：声明规则在 PostgreSQL 中的查询与写入契约。
 * 业务链路：核心入口/契约为 「findActive」；映射案件全链路实体并提供 Spring Data 仓储查询。
 * 关键边界：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
 */
package com.example.dispute.infrastructure.persistence.repository;

import com.example.dispute.infrastructure.persistence.entity.PolicyRuleEntity;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 所属模块：【PostgreSQL 事实模型 / 仓储接口层】类型「PolicyRuleRepository」。
// 类型职责：声明规则在 PostgreSQL 中的查询与写入契约；本类型显式提供 「findActive」。
// 协作关系：主要由 「CaseFulfillmentDisputeActivitiesImpl.buildAgentRequest」、「PolicyApplicationService.findActive」、「RouterApplicationService.route」、「RouterApplicationServiceTest.highRiskCaseGoesToHearingWithoutCreatingAnExecutionConclusion」 使用。
// 边界意义：实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
// Java 语法：interface 只定义能力契约，调用方依赖接口而不是具体适配器。
public interface PolicyRuleRepository extends JpaRepository<PolicyRuleEntity, String> {

    // 所属模块：【PostgreSQL 事实模型 / 仓储接口层】「PolicyRuleRepository.findActive(String,OffsetDateTime)」。
    // 具体功能：「PolicyRuleRepository.findActive(String,OffsetDateTime)」：声明按当前访问规则的 Spring Data 查询，由框架根据方法签名生成 SQL，并以「List<PolicyRuleEntity>」返回。
    // 上游调用：「PolicyRuleRepository.findActive(String,OffsetDateTime)」的上游调用点包括 「PolicyApplicationService.findActive」、「RouterApplicationService.route」、「CaseFulfillmentDisputeActivitiesImpl.buildAgentRequest」、「RouterApplicationServiceTest.regularFlowCreatesAConclusionForRemedyPlanningAndHumanReview」。
    // 下游影响：「PolicyRuleRepository.findActive(String,OffsetDateTime)」的下游由 接口实现 接管，并把返回值交还当前模块调用方。
    // 系统意义：「PolicyRuleRepository.findActive(String,OffsetDateTime)」直接影响 PostgreSQL 事实投影；实体记录是 API 查询投影和审计依据，写入必须服从上层事务与状态机
    // Java 语法：接口方法以分号结束，只声明契约；运行时执行实现类中的同签名方法。
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
